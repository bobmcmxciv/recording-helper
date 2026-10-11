package com.bob.recordinghelper.local

import android.content.Context
import androidx.work.*
import com.bob.recordinghelper.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Explicit opt-in. Only text/todos, never audio, paths, models or voiceprints. */
class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        if (!LocalSettings.sync(ctx)) return Result.success()
        val db = LocalStore.of(ctx)
        try {
            val reply = call("POST", JSONObject().put("device", LocalSettings.device(ctx)).put("changes", JSONArray(db.pending())))
            val accepted = reply.getJSONArray("results")
            for (i in 0 until accepted.length()) {
                val result = accepted.getJSONObject(i)
                db.receive(result.getJSONObject("item"), result.getLong("localVersion"), result.optBoolean("conflict"))
            }
            var cursor = LocalSettings.prefs(ctx).getLong("cursor", 0)
            do {
                if (!LocalSettings.sync(ctx) || isStopped) return Result.success()
                val page = call("GET", null, cursor)
                val items = page.getJSONArray("items")
                for (i in 0 until items.length()) db.receive(items.getJSONObject(i))
                cursor = page.getLong("cursor")
                LocalSettings.prefs(ctx).edit().putLong("cursor", cursor).commit()
            } while (page.getBoolean("more"))
            LocalSettings.prefs(ctx).edit().putString("syncStatus", "同步成功 · " + java.time.LocalTime.now().withNano(0)).apply()
            return if (db.pending().isNotEmpty()) Result.retry() else Result.success()
        } catch (e: Exception) {
            LocalSettings.prefs(ctx).edit().putString("syncStatus", "同步失败，本地数据不受影响：${e.message}").apply()
            return Result.retry()
        }
    }
    private fun call(method: String, body: JSONObject?, cursor: Long = 0): JSONObject {
        val ctx = applicationContext
        check(LocalSettings.sync(ctx)) { "同步已关闭" }
        val url = URL("${Prefs.baseUrl(ctx)}/api/v2/sync?cursor=$cursor")
        require(url.protocol == "https" && url.userInfo == null) { "同步服务器必须使用 HTTPS" }
        require(Prefs.token(ctx).isNotBlank()) { "请填写同步密钥" }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = false
            setRequestProperty("X-Auth-Token", Prefs.token(ctx))
        }
        try {
            if (body != null) {
                val bytes = body.toString().toByteArray()
                conn.doOutput = true; conn.setFixedLengthStreamingMode(bytes.size)
                conn.setRequestProperty("Content-Type", "application/json")
                check(LocalSettings.sync(ctx)) { "同步已关闭" }
                conn.outputStream.use { it.write(bytes) }
            }
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
    }
    companion object {
        fun schedule(ctx: Context) {
            if (!LocalSettings.sync(ctx)) return
            WorkManager.getInstance(ctx).enqueueUniqueWork("local-sync", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
