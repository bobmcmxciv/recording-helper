package com.bob.recordinghelper.llm

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A [TextModel] backed by an online chat API: OpenAI-style `/chat/completions` or Anthropic-style `/v1/messages`.
 * Streams Server-Sent Events (`stream: true`) token-by-token in real time, with automatic fallback to single-JSON response.
 * Only the text the caller passes in leaves the device.
 */
class RemoteTextModel(
    private val api: LlmApi,
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val onUsage: (String) -> Unit = {},
) : TextModel {
    private val base = baseUrl.trimEnd('/')
    @Volatile private var cancelled = false
    @Volatile private var current: HttpURLConnection? = null

    /** Characters are a safe upper bound for every tokenizer we care about. */
    override fun countTokens(text: String): Int = text.length

    override fun cancel() { cancelled = true; runCatching { current?.disconnect() } }

    override fun generate(prompt: String, maxTokens: Int, onText: (String) -> Unit): String? = chat("", prompt, maxTokens, onText)

    override fun chat(system: String, user: String, maxTokens: Int, onText: (String) -> Unit): String? {
        cancelled = false
        require(base.startsWith("https://")) { "接口地址必须是 HTTPS" }
        // Reasoning models (DeepSeek V4, o-series) spend max_tokens on hidden reasoning first; a tight budget returns empty content.
        val budget = maxOf(maxTokens * 4, 2048)
        val (url, body, headers) = when (api) {
            LlmApi.OPENAI -> Triple("$base/chat/completions",
                JSONObject().put("model", model).put("temperature", 0.2).put("max_tokens", budget).put("stream", true).put("messages", JSONArray().apply {
                    if (system.isNotBlank()) put(JSONObject().put("role", "system").put("content", system))
                    put(JSONObject().put("role", "user").put("content", user))
                }),
                mapOf("Authorization" to "Bearer $apiKey"))
            LlmApi.ANTHROPIC -> Triple("$base/v1/messages",
                JSONObject().put("model", model).put("max_tokens", budget).put("stream", true).put("system", system)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user))),
                mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"))
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20_000; readTimeout = 120_000; doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream, application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        current = conn
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val errText = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (cancelled) throw InterruptedException("已取消")
                throw IOException("HTTP $code ${describeError(errText)}")
            }
            val contentType = conn.contentType?.lowercase().orEmpty()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val firstLine = reader.readLine() ?: ""
                if (cancelled) throw InterruptedException("已取消")
                if (contentType.contains("event-stream") || firstLine.startsWith("data:") || firstLine.startsWith("event:")) {
                    return readSseStream(firstLine, reader, onText)
                }
                val rest = reader.readText()
                val text = if (rest.isEmpty()) firstLine else "$firstLine\n$rest"
                if (cancelled) throw InterruptedException("已取消")
                return parseJsonResponse(text, onText)
            }
        } finally { conn.disconnect(); current = null }
    }

    internal fun readSseStream(firstLine: String, reader: java.io.BufferedReader, onText: (String) -> Unit): String {
        val full = StringBuilder()
        var inThink = false
        var finishReason = ""
        var inputTokens = 0
        var outputTokens = 0

        fun handleLine(rawLine: String) {
            if (cancelled) throw InterruptedException("已取消")
            val line = rawLine.trim()
            if (!line.startsWith("data:")) return
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") return
            val o = runCatching { JSONObject(payload) }.getOrNull() ?: return
            o.optJSONObject("usage")?.let { u ->
                inputTokens = u.optInt("prompt_tokens", u.optInt("input_tokens", inputTokens))
                outputTokens = u.optInt("completion_tokens", u.optInt("output_tokens", outputTokens))
            }
            when (api) {
                LlmApi.OPENAI -> {
                    val choices = o.optJSONArray("choices") ?: return
                    if (choices.length() == 0) return
                    val c = choices.getJSONObject(0)
                    c.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }?.let { finishReason = it }
                    val delta = c.optJSONObject("delta") ?: c.optJSONObject("message") ?: return
                    val reasoning = delta.optString("reasoning_content")
                    if (reasoning.isNotEmpty() && reasoning != "null") {
                        if (!inThink) {
                            inThink = true
                            val openTag = "<think>\n"
                            full.append(openTag)
                            onText(openTag)
                        }
                        full.append(reasoning)
                        onText(reasoning)
                    }
                    val content = delta.optString("content")
                    if (content.isNotEmpty() && content != "null") {
                        if (inThink) {
                            inThink = false
                            val closeTag = "\n</think>\n"
                            full.append(closeTag)
                            onText(closeTag)
                        }
                        full.append(content)
                        onText(content)
                    }
                }
                LlmApi.ANTHROPIC -> {
                    when (o.optString("type")) {
                        "message_start" -> o.optJSONObject("message")?.optJSONObject("usage")?.let { u ->
                            inputTokens = u.optInt("input_tokens", inputTokens)
                        }
                        "message_delta" -> o.optJSONObject("usage")?.let { u ->
                            outputTokens = u.optInt("output_tokens", outputTokens)
                        }
                        "content_block_delta" -> {
                            val delta = o.optJSONObject("delta") ?: return
                            when (delta.optString("type")) {
                                "thinking_delta" -> {
                                    val t = delta.optString("thinking")
                                    if (t.isNotEmpty()) {
                                        if (!inThink) {
                                            inThink = true
                                            val openTag = "<think>\n"
                                            full.append(openTag)
                                            onText(openTag)
                                        }
                                        full.append(t)
                                        onText(t)
                                    }
                                }
                                "text_delta" -> {
                                    val t = delta.optString("text")
                                    if (t.isNotEmpty()) {
                                        if (inThink) {
                                            inThink = false
                                            val closeTag = "\n</think>\n"
                                            full.append(closeTag)
                                            onText(closeTag)
                                        }
                                        full.append(t)
                                        onText(t)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        handleLine(firstLine)
        while (true) {
            val next = reader.readLine() ?: break
            handleLine(next)
        }
        if (inThink) {
            val closeTag = "\n</think>\n"
            full.append(closeTag)
            onText(closeTag)
        }
        val out = full.toString()
        val afterThink = out.substringAfterLast("</think>").trim()
        if (afterThink.isBlank() && finishReason == "length") {
            throw IOException("模型输出被截断：推理占满了输出预算，请换一个模型或稍后重试")
        }
        if (inputTokens > 0 || outputTokens > 0) {
            onUsage("$model · 输入 $inputTokens · 输出 $outputTokens tokens")
        } else if (out.isNotEmpty()) {
            onUsage("$model · 流式输出 ${out.length} 字")
        }
        return out
    }

    internal fun parseJsonResponse(text: String, onText: (String) -> Unit): String {
        val o = JSONObject(text)
        val out = when (api) {
            LlmApi.OPENAI -> o.getJSONArray("choices").getJSONObject(0).let { c ->
                val msg = c.getJSONObject("message")
                val reasoning = msg.optString("reasoning_content").trim()
                val content = msg.optString("content")
                if (content.isBlank() && c.optString("finish_reason") == "length") throw IOException("模型输出被截断：推理占满了输出预算，请换一个模型或稍后重试")
                if (reasoning.isNotBlank() && !content.contains("<think>")) "<think>\n$reasoning\n</think>\n$content" else content
            }
            LlmApi.ANTHROPIC -> o.getJSONArray("content").let { arr ->
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    val block = arr.getJSONObject(i)
                    when (block.optString("type")) {
                        "thinking" -> {
                            val t = block.optString("thinking").trim()
                            if (t.isNotBlank()) sb.append("<think>\n").append(t).append("\n</think>\n")
                        }
                        "text" -> sb.append(block.optString("text"))
                    }
                }
                sb.toString()
            }
        }
        o.optJSONObject("usage")?.let { u ->
            val input = u.optInt("prompt_tokens", u.optInt("input_tokens"))
            val output = u.optInt("completion_tokens", u.optInt("output_tokens"))
            onUsage("$model · 输入 $input · 输出 $output tokens")
        }
        onText(out)
        return out
    }

    private fun describeError(body: String): String = try {
        val o = JSONObject(body)
        (o.optJSONObject("error")?.optString("message") ?: o.optString("message")).take(200)
    } catch (_: Exception) { body.take(200) }

    companion object {
        /** A tiny round trip used by the settings screen. */
        fun test(api: LlmApi, baseUrl: String, apiKey: String, model: String): Result<String> = runCatching {
            val reply = RemoteTextModel(api, baseUrl, apiKey, model).chat("你是连接测试助手。", "请只回复两个字：收到", 20) ?: ""
            reply.trim().take(40).ifBlank { "（空回复）" }
        }
    }
}
