package com.bob.recordinghelper.llm

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A [TextModel] backed by an online chat API: OpenAI-style `/chat/completions` or Anthropic-style `/v1/messages`.
 * Non-streaming; one request per call. Only the text the caller passes in leaves the device.
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
                JSONObject().put("model", model).put("temperature", 0.2).put("max_tokens", budget).put("messages", JSONArray().apply {
                    if (system.isNotBlank()) put(JSONObject().put("role", "system").put("content", system))
                    put(JSONObject().put("role", "user").put("content", user))
                }),
                mapOf("Authorization" to "Bearer $apiKey"))
            LlmApi.ANTHROPIC -> Triple("$base/v1/messages",
                JSONObject().put("model", model).put("max_tokens", budget).put("system", system)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user))),
                mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"))
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20_000; readTimeout = 120_000; doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        current = conn
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (cancelled) throw InterruptedException("已取消")
            if (code !in 200..299) throw IOException("HTTP $code ${describeError(text)}")
            val o = JSONObject(text)
            val out = when (api) {
                LlmApi.OPENAI -> o.getJSONArray("choices").getJSONObject(0).let { c ->
                    val content = c.getJSONObject("message").optString("content")
                    if (content.isBlank() && c.optString("finish_reason") == "length") throw IOException("模型输出被截断：推理占满了输出预算，请换一个模型或稍后重试")
                    content
                }
                LlmApi.ANTHROPIC -> o.getJSONArray("content").let { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
                }
            }
            o.optJSONObject("usage")?.let { u ->
                val input = u.optInt("prompt_tokens", u.optInt("input_tokens"))
                val output = u.optInt("completion_tokens", u.optInt("output_tokens"))
                onUsage("$model · 输入 $input · 输出 $output tokens")
            }
            onText(out)
            return out
        } finally { conn.disconnect(); current = null }
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
