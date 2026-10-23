package com.bob.recordinghelper

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.bob.recordinghelper.local.*

/** Discovery only. A durable, bounded queue owns local transcription; audio never leaves the phone. */
class AutoScanWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        if (!Prefs.autoScan(ctx) && !inputData.getBoolean("manual", false)) return Result.success()
        if (!AutoImportSettings.hasAccess(ctx)) {
            status("需要授权录音目录或文件访问权限；已保存的记录不受影响")
            return Result.success()
        }
        val inventory = RecordingSources.collect(ctx) { isStopped }
        if (isStopped) return Result.retry()
        if (Prefs.scanScope(ctx) !in ImportScope.ranges) {
            status("发现 ${inventory.files.size} 条录音 · 尚未选择自动处理范围，仅检查目录，不加入转写队列")
            return Result.success()
        }
        val now = System.currentTimeMillis()
        val cutoff = ScanWindow.cutoff(now, Prefs.scanDays(ctx), Prefs.scanSince(ctx))
        val eligible = inventory.files.filter { ScanWindow.eligible(it.modified, it.bytes, now, cutoff) }
        val queue = RecordingQueue.of(ctx)
        val fresh = eligible.filter { !Prefs.ledgerContains(ctx, it.key) }
        val added = queue.addAll(fresh)
        queue.reconcileAutomatic(ctx, eligible, !inventory.truncated && inventory.errors == 0)
        status("发现 ${inventory.files.size} 条录音 · 范围内 ${eligible.size} 条 · 新入队 $added 条" +
            (if (inventory.errors > 0) " · ${inventory.errors} 处目录无法读取，请检查授权" else "") +
            (if (inventory.truncated) " · 文件过多，请缩小扫描目录" else "") +
            (if (inventory.files.isEmpty()) "；请授权系统录音机实际保存目录" else "") +
            (if (!LocalAsr.modelReady(ctx)) "；等待安装语音模型" else ""))
        if (queue.pending() > 0) RecordingQueueWorker.schedule(ctx)
        return Result.success()
    }
    private fun status(value: String) { LocalSettings.prefs(applicationContext).edit().putString("scanStatus", value).apply() }
    companion object {
        const val MAX_ASR_FAILURES = 3
        val AUDIO_EXTS = setOf("mp3", "m4a", "wav", "amr", "awb", "aac", "ogg", "opus", "flac", "3gp", "3gpp", "wma", "mp4")
    }
}
