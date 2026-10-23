package com.bob.recordinghelper.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.*
import com.bob.recordinghelper.*
import java.io.File
import java.util.UUID

/** Input goes to persistent storage before enqueueing. Cache eviction cannot lose an accepted import. */
object LocalImport {
    fun audio(ctx: Context, uri: Uri) {
        var name = "导入录音.m4a"
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        require(name.substringAfterLast('.', "").lowercase() in AutoScanWorker.AUDIO_EXTS) { "请选择音频文件" }
        val dir = File(ctx.filesDir, "imports").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val f = File(dir, "$id.audio")
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it, 65536) } }
            require(f.length() > 0) { "空文件" }
            File(dir, "$id.json").writeText(org.json.JSONObject().put("title", name).toString())
        } catch (e: Exception) { f.delete(); throw e }
        ImportWorker.schedule(ctx)
    }
    fun text(ctx: Context, title: String, text: String) {
        require(text.isNotBlank())
        LocalStore.of(ctx).transcript(UUID.randomUUID().toString(), title, text, null)
        AnalysisWorker.schedule(ctx)
    }
}

class ImportWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        if (MicState.busy || VoiceRelationState.busy) return Result.success()
        val dir = File(ctx.filesDir, "imports")
        val inputs = dir.listFiles { f -> f.extension == "audio" && File(dir, f.nameWithoutExtension + ".json").exists() } ?: emptyArray()
        if (inputs.isEmpty()) return Result.success()
        if (!LocalAsr.modelReady(ctx)) {
            LocalSettings.prefs(ctx).edit().putString("importStatus", "录音已保存在本机，等待安装语音模型").apply()
            return Result.success()
        }
        var failed = false
        KeepAwake.hold(ctx, "离线导入录音") { ModelGate.hold {
            if (MicState.busy || VoiceRelationState.busy) return@hold
            PhoneAsrBatch(ctx).use { batch ->
            for (f in inputs) {
                if (isStopped || MicState.busy || VoiceRelationState.busy) break
                val meta = File(dir, f.nameWithoutExtension + ".json")
                if (!meta.exists()) continue
                val title = org.json.JSONObject(meta.readText()).getString("title")
                LocalSettings.prefs(ctx).edit().putString("importStatus", "正在离线转写：$title").apply()
                val n = Progress.ongoing(ctx, "离线转写", title, 0, 0, true)
                try { setForegroundAsync(if (android.os.Build.VERSION.SDK_INT >= 29)
                    ForegroundInfo(230, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(230, n)).get() } catch (_: Exception) {}
                var lastNotify = 0L
                when (batch.process(f, f.name, title, null) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastNotify >= 1000L) {
                        lastNotify = now
                        Progress.notifyOngoing(ctx, "离线转写", "$title · $it", 0, 0)
                    }
                }) {
                    PhoneAsrBatch.Outcome.SENT, PhoneAsrBatch.Outcome.SILENT -> { f.delete(); meta.delete() }
                    else -> failed = true
                }
            }
        } } }
        AnalysisWorker.schedule(ctx)
        LocalSettings.prefs(ctx).edit().putString("importStatus", if (failed) "有文件转写失败，原始导入副本保留，点击立即整理可重试" else "导入处理完成").apply()
        return if (failed && runAttemptCount < 2) Result.retry() else Result.success()
    }
    companion object {
        fun schedule(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniqueWork("local-import", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ImportWorker>().build())
    }
}
