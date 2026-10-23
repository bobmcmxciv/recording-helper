package com.bob.recordinghelper.local

import android.content.Context
import android.net.Uri
import androidx.work.*
import com.bob.recordinghelper.*
import java.io.File
import java.util.concurrent.TimeUnit

/** Bounded batches: resume after restart, pause between files, and isolate failed recordings. */
class RecordingQueueWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    @Volatile private var cancelledByUser = false

    override fun doWork(): Result {
        val ctx = applicationContext
        StateMirror.hydrateIfNeeded(ctx)
        if (MicState.busy || VoiceRelationState.busy) return Result.success()
        val queue = RecordingQueue.of(ctx)
        if (queue.pending() == 0) return Result.success()
        if (!LocalAsr.modelReady(ctx)) { status("等待安装语音模型；录音来源已记入队列"); return Result.success() }
        var processed = 0
        var progressed = false
        var engineUnavailable = false
        var yieldedToAnalysis = false
        currentWorker = this
        try {
            KeepAwake.hold(ctx, "自动转写录音") { ModelGate.hold processing@{
                queue.recover()
                if (queue.next(Prefs.autoScan(ctx)) == null || stoppedOrCancelled() || MicState.busy || VoiceRelationState.busy) return@processing
                if (AnalysisState.isUrgent() && AnalysisWorker.canAnalyze(ctx)) {
                    yieldedToAnalysis = true
                    return@processing
                }
                val n = Progress.ongoing(ctx, "自动转写", "正在处理本机录音，可在应用内暂停", 0, 0, true)
                try { setForegroundAsync(if (android.os.Build.VERSION.SDK_INT >= 29)
                    ForegroundInfo(250, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(250, n)).get() } catch (_: Exception) {}
                val started = android.os.SystemClock.elapsedRealtime()
                val primary = android.os.Environment.getExternalStorageDirectory().canonicalPath
                val owner = LocalSettings.device(ctx)
                val savedLookup = LocalStore.of(ctx).savedLookup(primary, owner)
                PhoneAsrBatch(ctx).use { batch ->
                    while (!stoppedOrCancelled() && !MicState.busy && !VoiceRelationState.busy && processed < 50 && android.os.SystemClock.elapsedRealtime() - started < 480_000L) {
                        if (AnalysisState.isUrgent() && AnalysisWorker.canAnalyze(ctx)) {
                            yieldedToAnalysis = true
                            break
                        }
                        val item = queue.claim(Prefs.autoScan(ctx)) ?: break
                        var copy: File? = null
                        try {
                            val existing = savedLookup.find(item) ?: RecordingSources.savedRecording(ctx, item)
                            if (existing != null) {
                                queue.state(item.key, "done")
                                Prefs.ledgerAdd(ctx, item.key)
                                progressed = true
                                continue
                            }
                            val localFile = RecordingSources.resolveLocalFile(item)
                            val audio = if (localFile != null) {
                                localFile
                            } else if (item.source.startsWith("content://")) {
                                val f = File(ctx.cacheDir, "auto-import-${LocalStore.hash(item.key)}.audio")
                                copy = f
                                require(ctx.cacheDir.usableSpace > item.bytes + 32L * 1024 * 1024) { "存储空间不足" }
                                ctx.contentResolver.openInputStream(Uri.parse(item.source))!!.use { input ->
                                    f.outputStream().use { input.copyTo(it, 65536) }
                                }
                                require(f.length() == item.bytes) { "录音发生变化，请重新扫描" }
                                f
                            } else File(item.source).also {
                                require(it.isFile && it.length() == item.bytes &&
                                    (it.lastModified() == item.modified || it.lastModified() / 1000L == item.modified / 1000L)) { "录音已移动或发生变化，请重新扫描" }
                            }
                            status("正在转写：${item.title} · 待处理 ${queue.pending()} 条")
                            var lastNotify = 0L
                            val outcome = batch.process(audio, item.key, item.title,
                                item.modified.takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it).toString() }) {
                                if (stoppedOrCancelled() || !Prefs.autoScan(ctx)) {
                                    throw InterruptedException("自动转写已暂停")
                                }
                                if (AnalysisState.isUrgent() && AnalysisWorker.canAnalyze(ctx)) {
                                    yieldedToAnalysis = true
                                    throw InterruptedException("让出模型优先整理记录")
                                }
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (now - lastNotify >= 1000L) {
                                    lastNotify = now
                                    Progress.notifyOngoing(ctx, "自动转写", "${item.title} · $it", 0, 0)
                                }
                            }
                            when (outcome) {
                                PhoneAsrBatch.Outcome.SENT, PhoneAsrBatch.Outcome.SILENT -> {
                                    queue.state(item.key, "done"); Prefs.ledgerAdd(ctx, item.key)
                                }
                                else -> queue.failure(item.key, "转写未完成（${outcome.name}），原录音保留，可重试")
                            }
                            processed++
                            progressed = true
                            if (outcome == PhoneAsrBatch.Outcome.UNAVAILABLE) { engineUnavailable = true; break }
                        } catch (_: InterruptedException) {
                            queue.recover()
                            break
                        } catch (e: Exception) {
                            val missingLocal = !item.source.startsWith("content://") && !File(item.source).exists()
                            val staleContentUri = item.source.startsWith("content://") && e is SecurityException &&
                                AutoImportSettings.hasFileAccess(ctx) && RecordingSources.resolveLocalFile(item) == null
                            val msg = if (e is SecurityException) "目录授权失效，请重新授权后重试" else "无法读取或转写此录音，请检查原文件后重试"
                            if (missingLocal || staleContentUri) {
                                queue.removeAutomatic(item.key)
                                if (queue.status(item.key) != null) queue.failure(item.key, msg)
                            } else {
                                queue.failure(item.key, msg)
                            }
                            processed++
                            progressed = true
                        } finally { copy?.delete() }
                    }
                }
            } }
        } finally {
            if (currentWorker === this) currentWorker = null
        }
        val counts = queue.counts()
        status("待转写 ${queue.pending()} 条 · 已完成 ${counts.optInt("done")} 条 · 需重试 ${counts.optInt("error")} 条" +
            if (!Prefs.autoScan(ctx)) " · 自动转写已暂停" else "")
        if (yieldedToAnalysis) {
            if (Prefs.autoScan(ctx) && queue.next(true) != null) schedule(ctx, 5, append = true)
            return Result.success()
        }
        if (!stoppedOrCancelled() && !engineUnavailable && queue.next(Prefs.autoScan(ctx)) != null && (processed > 0 || progressed)) schedule(ctx, 1, append = true)
        if (queue.pending() == 0 && Prefs.autoScan(ctx)) AnalysisWorker.schedule(ctx)
        return Result.success()
    }

    private fun stoppedOrCancelled(): Boolean = isStopped || cancelledByUser

    override fun onStopped() {
        cancelledByUser = true
    }

    private fun status(value: String) { LocalSettings.prefs(applicationContext).edit().putString("autoImportStatus", value).apply() }

    companion object {
        @Volatile private var currentWorker: RecordingQueueWorker? = null

        fun cancelActive(ctx: Context) {
            currentWorker?.cancelledByUser = true
            WorkManager.getInstance(ctx).cancelUniqueWork("recording-queue")
            RecordingQueue.of(ctx).recover()
        }

        fun schedule(ctx: Context, delaySeconds: Long = 0, append: Boolean = false) = WorkManager.getInstance(ctx).enqueueUniqueWork(
            "recording-queue",
            if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RecordingQueueWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS).build())
    }
}
