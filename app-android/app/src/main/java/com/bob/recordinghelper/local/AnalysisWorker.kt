package com.bob.recordinghelper.local

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.*
import com.bob.recordinghelper.*
import com.bob.recordinghelper.llm.*

class AnalysisWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    @Volatile private var engine: LlmEngine? = null
    @Volatile private var polisher: Polisher? = null
    override fun doWork(): Result {
        val ctx = applicationContext
        if (MicState.busy || VoiceRelationState.busy) return Result.success()
        // Give queued speech transcription priority over the optional, much heavier LLM.
        if (!inputData.getBoolean("now", false) && Prefs.autoScan(ctx) && RecordingQueue.of(ctx).pending() > 0) return Result.success()
        val db = LocalStore.of(ctx)
        val file = LlmEngine.modelIn(LocalAsr.llmDir(ctx)) ?: return Result.success()
        val jobs = db.all("recording").filter { it.optString("status") in listOf("pending", "analyzing") && it.optString("ownerDevice") == LocalSettings.device(ctx) }
        if (jobs.isEmpty()) return Result.success()
        val n = Progress.ongoing(ctx, "本地 AI 整理", "录音与文字均留在手机", 0, 0, true)
        try { setForegroundAsync(if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(220, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(220, n)).get() } catch (_: Exception) {}
        KeepAwake.hold(ctx, "本地 AI 整理") { ModelGate.hold {
            if (MicState.busy || VoiceRelationState.busy || isStopped) return@hold
            try {
                LlmEngine.load(file).use { model ->
                    engine = model
                    for (job in jobs) {
                        if (isStopped || MicState.busy || VoiceRelationState.busy || (!inputData.getBoolean("now", false) && Prefs.autoScan(ctx) && RecordingQueue.of(ctx).pending() > 0)) break
                        val id = job.getString("id")
                        if (db.get(id)?.optString("status") !in listOf("pending", "analyzing")) continue
                        db.status(id, "analyzing")
                        try {
                            val text = job.getString("text")
                            // Preserve raw text; polishing is optional and never replaces the evidence.
                            val polished = if (Prefs.polishMode(ctx) == Prefs.POLISH_LOCAL) {
                                Polisher(model).also { polisher = it }.polish(text).text
                            } else text
                            polisher = null
                            val result = LocalAnalysis.analyze(model, text, job.optString("occurredAt")) { isStopped }
                            if (isStopped) throw InterruptedException()
                            db.analyzed(id, polished, result)
                        } catch (_: InterruptedException) { db.status(id, "pending"); break
                        } catch (e: Exception) { db.status(id, "error", e.message ?: "分析失败") }
                    }
                    engine = null
                }
            } catch (e: Exception) { jobs.forEach { db.status(it.getString("id"), "error", e.message ?: "模型加载失败") } }
        } }
        SyncWorker.schedule(ctx)
        return Result.success()
    }
    override fun onStopped() { polisher?.cancel(); engine?.cancel() }
    companion object {
        fun schedule(ctx: Context, now: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
                .setInputData(workDataOf("now" to now))
                .setConstraints(Constraints.Builder().setRequiresCharging(!now).build()).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(if (now) "local-analysis-now" else "local-analysis", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
