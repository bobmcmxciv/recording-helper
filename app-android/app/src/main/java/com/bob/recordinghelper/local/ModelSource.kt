package com.bob.recordinghelper.local

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A published list of model files the wizard can download: `{"schema":1,"files":[{slot,name,url,mirror,sha256,size,required,note}]}`.
 * The app verifies every download against the listed SHA-256; the manifest URL itself is user-configurable.
 */
object ModelSource {
    const val DEFAULT_MANIFEST = "https://github.com/bobmcmxciv/recording-helper/releases/download/models-v1/models.json"

    data class Entry(val slot: String, val name: String, val url: String, val mirror: String, val sha256: String,
                     val size: Long, val required: Boolean, val note: String)

    fun manifestUrl(ctx: Context): String = LocalSettings.prefs(ctx).getString("modelManifest", DEFAULT_MANIFEST) ?: DEFAULT_MANIFEST
    fun setManifestUrl(ctx: Context, url: String) { LocalSettings.prefs(ctx).edit().putString("modelManifest", url.trim().ifBlank { DEFAULT_MANIFEST }).apply() }

    fun fetch(url: String): List<Entry> {
        require(url.startsWith("https://")) { "清单地址必须是 HTTPS" }
        var current = URL(url)
        var body = ""
        for (hop in 0..5) {
            val conn = (current.openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = false }
            try {
                val code = conn.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    current = URL(current, conn.getHeaderField("Location") ?: error("清单跳转缺少地址"))
                    require(current.protocol == "https") { "清单只允许 HTTPS" }
                    continue
                }
                require(code == 200) { "读取清单失败 HTTP $code" }
                body = conn.inputStream.bufferedReader().use { it.readText() }
                break
            } finally { conn.disconnect() }
        }
        require(body.isNotBlank()) { "清单为空" }
        return parse(body)
    }

    fun parse(body: String): List<Entry> {
        val o = JSONObject(body)
        val files = o.getJSONArray("files")
        return (0 until files.length()).map { i ->
            val f = files.getJSONObject(i)
            val slot = f.getString("slot")
            require(slot in ModelFiles.slots) { "清单包含未知文件：$slot" }
            val url = f.getString("url")
            require(url.startsWith("https://")) { "清单里的下载地址必须是 HTTPS" }
            val sha = f.getString("sha256").lowercase()
            require(Regex("[a-f0-9]{64}").matches(sha)) { "清单里的 SHA-256 无效：$slot" }
            Entry(slot, f.optString("name", slot), url, f.optString("mirror"), sha, f.optLong("size", 0),
                f.optBoolean("required", slot in setOf("sv.tflite", "tokens.json", "am.mvn")), f.optString("note"))
        }
    }

    /** Orders slots so core speech recognition (tokens -> am.mvn -> sv.tflite) becomes ready before optional speaker or 2.4 GB LLM files. */
    fun slotPriority(slot: String): Int = when (slot) {
        "tokens.json" -> 0
        "am.mvn" -> 1
        "sv.tflite" -> 2
        "spk.tflite" -> 3
        "model.gguf" -> 4
        else -> 5
    }

    fun isDomesticLocale(
        tzId: String = java.util.TimeZone.getDefault().id.orEmpty(),
        country: String = java.util.Locale.getDefault().country.orEmpty(),
    ): Boolean = country.equals("CN", ignoreCase = true) ||
        tzId in setOf("Asia/Shanghai", "Asia/Chongqing", "Asia/Harbin", "Asia/Urumqi", "GMT+08:00")

    /**
     * Resolves primary + fallback HTTPS URLs for an entry.
     * Automatically synthesizes hf-mirror <-> huggingface.co fallback when mirror is omitted,
     * and prioritizes domestic mirrors (e.g. hf-mirror.com) when [preferDomestic] is true.
     */
    fun orderedUrls(entry: Entry, preferDomestic: Boolean = isDomesticLocale(), forceMirrorFirst: Boolean = false): Pair<String, String> {
        val rawMirror = entry.mirror.ifBlank {
            when {
                entry.url.startsWith("https://huggingface.co/") -> entry.url.replaceFirst("https://huggingface.co/", "https://hf-mirror.com/")
                entry.url.startsWith("https://hf-mirror.com/") -> entry.url.replaceFirst("https://hf-mirror.com/", "https://huggingface.co/")
                else -> ""
            }
        }
        val candidates = listOf(entry.url, rawMirror).filter { it.startsWith("https://") }.distinct()
        if (candidates.size <= 1) return candidates.firstOrNull().orEmpty() to ""
        if (forceMirrorFirst) return candidates[1] to candidates[0]
        val sorted = if (preferDomestic) {
            candidates.sortedBy { url ->
                when {
                    url.startsWith("https://hf-mirror.com/") -> 0
                    !url.startsWith("https://huggingface.co/") && !url.startsWith("https://github.com/") -> 1
                    else -> 2
                }
            }
        } else candidates
        return sorted[0] to sorted.getOrElse(1) { "" }
    }

    /** Queues the downloads one after another (the installer is a single unique work chain). */
    fun enqueue(ctx: Context, entries: List<Entry>, useMirror: Boolean = false, unmeteredLlm: Boolean = false) {
        val ordered = entries.sortedBy { slotPriority(it.slot) }
        for (e in ordered) {
            val (primary, fallback) = orderedUrls(e, forceMirrorFirst = useMirror && e.mirror.isNotBlank())
            val wifiOnly = unmeteredLlm && e.slot == "model.gguf"
            ModelInstallWorker.enqueue(ctx, e.slot, primary, e.sha256, fallback, unmeteredOnly = wifiOnly)
        }
    }

    /** Returns entries that are not yet installed on disk, ordered so speech ASR models install before the larger LLM. */
    fun missingEntries(ctx: Context, entries: List<Entry>, includeLlm: Boolean = false): List<Entry> {
        return entries
            .filter { includeLlm || it.slot != "model.gguf" }
            .filter { e ->
                if (e.slot == "model.gguf") {
                    com.bob.recordinghelper.llm.LlmEngine.modelIn(com.bob.recordinghelper.LocalAsr.llmDir(ctx)) == null
                } else {
                    val f = ModelFiles.target(ctx, e.slot)
                    !f.isFile || f.length() <= 0L
                }
            }
            .sortedBy { slotPriority(it.slot) }
    }
}
