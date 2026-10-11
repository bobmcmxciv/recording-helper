package com.bob.recordinghelper.local

import android.content.Context
import android.net.Uri
import androidx.work.*
import com.bob.recordinghelper.*
import java.io.File
import java.util.concurrent.TimeUnit

/** Bounded batches: resume after restart, pause between files, and isolate failed recordings. */
class RecordingQueueWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        if (MicState.busy || VoiceRelationState.busy) return Result.success()
        val queue = RecordingQueue.of(ctx)
        if (queue.pending() == 0) return Result.success()
        if (!LocalAsr.modelReady(ctx)) { status("等待安装语音模型；录音来源已记入队列"); return Result.success() }
        var processed = 0
        var engineUnavailable = false
        KeepAwake.hold(ctx, "自动转写录音") { ModelGate.hold processing@{
            queue.recover()
            if (queue.next(Prefs.autoScan(ctx)) == null || isStopped || MicState.busy || VoiceRelationState.busy) return@processing
            val n = Progress.ongoing(ctx, "自动转写", "正在处理本机录音，可在应用内暂停", 0, 0, true)
            try { setForegroundAsync(if (android.os.Build.VERSION.SDK_INT >= 29)
                ForegroundInfo(250, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(250, n)).get() } catch (_: Exception) {}
            val started = android.os.SystemClock.elapsedRealtime()
            PhoneAsrBatch(ctx).use { batch ->
                while (!isStopped && !MicState.busy && !VoiceRelationState.busy && processed < 3 && android.os.SystemClock.elapsedRealtime() - started < 240_000L) {
                    val item = queue.claim(Prefs.autoScan(ctx)) ?: break
                    var copy: File? = null
                    try {
                        val existing = RecordingSources.savedRecording(ctx, item)
                        if (existing != null) { queue.state(item.key, "done"); Prefs.ledgerAdd(ctx, item.key); processed++; continue }
                        val audio = if (item.source.startsWith("content://")) {
                            val f = File(ctx.cacheDir, "auto-import-${LocalStore.hash(item.key)}.audio")
                            copy = f
                            require(ctx.cacheDir.usableSpace > item.bytes + 32L * 1024 * 1024) { "存储空间不足" }
                            ctx.contentResolver.openInputStream(Uri.parse(item.source))!!.use { input ->
                                f.outputStream().use { input.copyTo(it, 65536) }
                            }
                            require(f.length() == item.bytes) { "录音发生变化，请重新扫描" }
                            f
                        } else File(item.source).also {
                            require(it.isFile && it.length() == item.bytes && it.lastModified() == item.modified) { "录音已移动或发生变化，请重新扫描" }
                        }
                        status("正在转写：${item.title} · 待处理 ${queue.pending()} 条")
                        val outcome = batch.process(audio, item.key, item.title,
                            item.modified.takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it).toString() }) {
                            Progress.notifyOngoing(ctx, "自动转写", "${item.title} · $it", 0, 0)
                        }
                        when (outcome) {
                            PhoneAsrBatch.Outcome.SENT, PhoneAsrBatch.Outcome.SILENT -> {
                                queue.state(item.key, "done"); Prefs.ledgerAdd(ctx, item.key)
                            }
                            else -> queue.failure(item.key, "转写未完成（${outcome.name}），原录音保留，可重试")
                        }
                        processed++
                        if (outcome == PhoneAsrBatch.Outcome.UNAVAILABLE) { engineUnavailable = true; break }
                    } catch (e: Exception) {
                        queue.failure(item.key, if (e is SecurityException) "目录授权失效，请重新授权后重试" else "无法读取或转写此录音，请检查原文件后重试")
                        processed++
                    } finally { copy?.delete() }
                }
            }
        } }
        val counts = queue.counts()
        status("待转写 ${queue.pending()} 条 · 已完成 ${counts.optInt("done")} 条 · 需重试 ${counts.optInt("error")} 条" +
            if (!Prefs.autoScan(ctx)) " · 自动转写已暂停" else "")
        if (!isStopped && !engineUnavailable && queue.next(Prefs.autoScan(ctx)) != null && processed > 0) schedule(ctx, 10)
        if (queue.pending() == 0) AnalysisWorker.schedule(ctx)
        return Result.success()
    }
    private fun status(value: String) { LocalSettings.prefs(applicationContext).edit().putString("autoImportStatus", value).apply() }
    companion object {
        fun schedule(ctx: Context, delaySeconds: Long = 0) = WorkManager.getInstance(ctx).enqueueUniqueWork("recording-queue", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<RecordingQueueWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS).build())
    }
}
