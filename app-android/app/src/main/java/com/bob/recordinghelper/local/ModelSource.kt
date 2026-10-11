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

    /** Queues the downloads one after another (the installer is a single unique work chain). */
    fun enqueue(ctx: Context, entries: List<Entry>, useMirror: Boolean = false) {
        for (e in entries) ModelInstallWorker.enqueue(ctx, e.slot, if (useMirror && e.mirror.isNotBlank()) e.mirror else e.url, e.sha256)
    }
}
