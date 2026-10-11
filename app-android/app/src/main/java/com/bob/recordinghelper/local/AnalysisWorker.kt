package com.bob.recordinghelper.local

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.*
import com.bob.recordinghelper.*
import com.bob.recordinghelper.llm.*

/** Turns pending transcripts into notes and todos, with the local model or, after explicit consent, an online one. */
class AnalysisWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    @Volatile private var active: TextModel? = null
    @Volatile private var polisher: Polisher? = null

    override fun doWork(): Result {
        val ctx = applicationContext
        if (MicState.busy || VoiceRelationState.busy) return Result.success()
        val now = inputData.getBoolean("now", false)
        // Give queued speech transcription priority over the optional, much heavier LLM.
        if (!now && Prefs.autoScan(ctx) && RecordingQueue.of(ctx).pending() > 0) return Result.success()
        val db = LocalStore.of(ctx)
        val jobs = db.all("recording").filter { it.optString("status") in listOf("pending", "analyzing") && it.optString("ownerDevice") == LocalSettings.device(ctx) }
        if (jobs.isEmpty()) return Result.success()
        val remote = LlmSettings.remoteModel(ctx)
        if (remote != null) {
            if (LlmSettings.wifiOnly(ctx) && !LlmSettings.onUnmeteredNetwork(ctx)) return Result.retry()
            foreground(ctx, "在线 AI 整理", "只发送转写文字")
            runJobs(ctx, db, jobs, remote, polish = false) { !now && Prefs.autoScan(ctx) && RecordingQueue.of(ctx).pending() > 0 }
        } else {
            val file = LlmEngine.modelIn(LocalAsr.llmDir(ctx)) ?: return Result.success()
            foreground(ctx, "本地 AI 整理", "录音与文字均留在手机")
            KeepAwake.hold(ctx, "本地 AI 整理") { ModelGate.hold {
                if (MicState.busy || VoiceRelationState.busy || isStopped) return@hold
                try {
                    LlmEngine.load(file).use { model ->
                        runJobs(ctx, db, jobs, model, polish = Prefs.polishMode(ctx) == Prefs.POLISH_LOCAL) {
                            MicState.busy || VoiceRelationState.busy || (!now && Prefs.autoScan(ctx) && RecordingQueue.of(ctx).pending() > 0)
                        }
                    }
                } catch (e: Exception) { jobs.forEach { db.status(it.getString("id"), "error", e.message ?: "模型加载失败") } }
            } }
        }
        SyncWorker.schedule(ctx)
        return Result.success()
    }

    private fun runJobs(ctx: Context, db: LocalStore, jobs: List<org.json.JSONObject>, model: TextModel, polish: Boolean, yield: () -> Boolean) {
        active = model
        try {
            for (job in jobs) {
                if (isStopped || yield()) break
                val id = job.getString("id")
                if (db.get(id)?.optString("status") !in listOf("pending", "analyzing")) continue
                db.status(id, "analyzing")
                try {
                    val text = job.getString("text")
                    // Preserve raw text; polishing is optional and never replaces the evidence.
                    val polished = if (polish) Polisher(model).also { polisher = it }.polish(text).text else text
                    polisher = null
                    val result = LocalAnalysis.analyze(model, text, job.optString("occurredAt")) { isStopped }
                    if (isStopped) throw InterruptedException()
                    db.analyzed(id, polished, result)
                } catch (_: InterruptedException) { db.status(id, "pending"); break
                } catch (e: Exception) { db.status(id, "error", e.message ?: "分析失败") }
            }
        } finally { active = null }
    }

    private fun foreground(ctx: Context, title: String, text: String) {
        val n = Progress.ongoing(ctx, title, text, 0, 0, true)
        try { setForegroundAsync(if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(220, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(220, n)).get() } catch (_: Exception) {}
    }

    override fun onStopped() { polisher?.cancel(); active?.cancel() }

    companion object {
        fun schedule(ctx: Context, now: Boolean = false) {
            val remote = LlmSettings.remoteEnabled(ctx)
            val constraints = Constraints.Builder().apply {
                if (remote) setRequiredNetworkType(if (LlmSettings.wifiOnly(ctx)) NetworkType.UNMETERED else NetworkType.CONNECTED)
                else setRequiresCharging(!now)
            }.build()
            val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
                .setInputData(workDataOf("now" to now)).setConstraints(constraints).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(if (now) "local-analysis-now" else "local-analysis", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
