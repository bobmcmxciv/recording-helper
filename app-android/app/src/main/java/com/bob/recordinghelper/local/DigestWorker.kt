package com.bob.recordinghelper.local

import android.content.Context
import androidx.work.*
import com.bob.recordinghelper.LiveAsr
import com.bob.recordinghelper.LocalAsr
import com.bob.recordinghelper.ModelGate
import com.bob.recordinghelper.Progress
import com.bob.recordinghelper.llm.LlmEngine
import com.bob.recordinghelper.llm.LlmSettings
import com.bob.recordinghelper.llm.TextModel
import org.json.JSONObject

/** Builds the period overview on the todo board with whichever model is active. Result is cached per period key. */
class DigestWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    @Volatile private var activeModel: TextModel? = null
    @Volatile private var cancelledByUser = false

    override fun doWork(): Result {
        val ctx = applicationContext
        val key = inputData.getString("key") ?: return Result.failure()
        val label = inputData.getString("label") ?: key
        val lines = inputData.getStringArray("lines")?.toList() ?: return Result.failure()
        val digestId = "digest:$key"
        val taskTitle = "时段总结 · $label"
        val remote = LlmSettings.remoteModel(ctx)
        currentWorker = this
        val result = try {
            if (remote != null) {
                if (LlmSettings.wifiOnly(ctx) && !LlmSettings.onUnmeteredNetwork(ctx)) error("当前不是 Wi-Fi，已按设置暂不发送")
                activeModel = remote
                val modelLabel = "在线模型 ${LlmSettings.model(ctx)}"
                val connMsg = "正在连接 $modelLabel，准备生成「$label」时段总结…"
                AnalysisState.onPhase(digestId, connMsg, title = taskTitle, model = modelLabel)
                LiveAsr.llmBegin(taskTitle, modelLabel, "生成时段总结")
                LiveAsr.llmChunk(1, 1, connMsg)
                Progress.notifyOngoing(ctx, taskTitle, connMsg, 0, 0, force = true)
                var headerPending = true
                val r = LocalAnalysis.digest(
                    model = remote,
                    period = label,
                    lines = lines,
                    onToken = { piece ->
                        val isHeader = headerPending
                        AnalysisState.onToken(digestId, piece, updatePhase = !isHeader)
                        LiveAsr.llmToken(piece)
                        if (!isHeader) {
                            Progress.notifyOngoing(ctx, taskTitle, AnalysisState.snapshot().phase, 0, 0)
                        }
                    },
                    onPrefill = { promptTokens ->
                        headerPending = false
                        val prefillMsg = "正在发送「$label」时段资料并等待云端首包（约 $promptTokens tokens）…"
                        AnalysisState.onPrefill(digestId, 1, 1, prefillMsg, title = taskTitle, model = modelLabel)
                        LiveAsr.llmPrefill(1, 1, prefillMsg)
                        Progress.notifyOngoing(ctx, taskTitle, prefillMsg, 0, 0, force = true)
                    },
                    stopped = { isStopped || cancelledByUser }
                )
                val usage = LlmSettings.lastUsage(ctx)
                val stats = if (usage.isNotBlank()) "已完成 · $usage" else "已完成"
                LiveAsr.llmFinish(stats, r.optString("overview"))
                r.put("source", "在线整理").put("model", modelLabel).put("stats", stats)
            } else {
                val file = LlmEngine.modelIn(LocalAsr.llmDir(ctx)) ?: error("没有可用的整理方式")
                if (MicState.busy || VoiceRelationState.busy) error("正在录音或分析，稍后再试")
                val modelLabel = "本地大模型 ${file.nameWithoutExtension}"
                val sizeStr = Progress.human(file.length())
                val waitMsg = "正在等待模型空闲（准备生成「$label」时段总结）…"
                AnalysisState.onPhase(digestId, waitMsg, title = taskTitle, model = modelLabel)
                LiveAsr.llmBegin(taskTitle, modelLabel, "生成时段总结")
                LiveAsr.llmChunk(1, 1, waitMsg)
                Progress.notifyOngoing(ctx, taskTitle, waitMsg, 0, 0, force = true)
                ModelGate.hold {
                    if (isStopped || cancelledByUser) throw InterruptedException("已取消")
                    val loadMsg = "正在加载 $modelLabel（$sizeStr · CPU 4 核准备中）…"
                    AnalysisState.onPhase(digestId, loadMsg, title = taskTitle, model = modelLabel)
                    LiveAsr.llmChunk(1, 1, loadMsg)
                    Progress.notifyOngoing(ctx, taskTitle, loadMsg, 0, 0, force = true)
                    LlmEngine.load(file).use { model ->
                        activeModel = model
                        var headerPending = true
                        val r = LocalAnalysis.digest(
                            model = model,
                            period = label,
                            lines = lines,
                            onToken = { piece ->
                                val isHeader = headerPending
                                AnalysisState.onToken(digestId, piece, updatePhase = !isHeader)
                                LiveAsr.llmToken(piece)
                                if (!isHeader) {
                                    Progress.notifyOngoing(ctx, taskTitle, AnalysisState.snapshot().phase, 0, 0)
                                }
                            },
                            onPrefill = { promptTokens ->
                                headerPending = false
                                val prefillMsg = "正在预填充阅读「$label」时段资料（约 $promptTokens tokens · CPU 4 核计算中）…"
                                AnalysisState.onPrefill(digestId, 1, 1, prefillMsg, title = taskTitle, model = modelLabel)
                                LiveAsr.llmPrefill(1, 1, prefillMsg)
                                Progress.notifyOngoing(ctx, taskTitle, prefillMsg, 0, 0, force = true)
                            },
                            stopped = { isStopped || cancelledByUser }
                        )
                        val st = runCatching { model.lastStats() }.getOrNull()
                        val stats = if (st != null && st.generatedTokens > 0) {
                            val tokPerSec = if (st.generateMs > 0) st.generatedTokens * 1000.0 / st.generateMs else 0.0
                            "已完成 · 输入 ${st.promptTokens} / 输出 ${st.generatedTokens} tokens · %.1f tok/s".format(tokPerSec)
                        } else "已完成"
                        LiveAsr.llmFinish(stats, r.optString("overview"))
                        r.put("source", "本地整理").put("model", modelLabel).put("stats", stats)
                    }
                }
            }
        } catch (e: Exception) {
            val msg = if (e is InterruptedException || cancelledByUser || isStopped) "已取消时段总结" else (e.message ?: "生成失败")
            LiveAsr.llmFinish(msg)
            AnalysisState.onJobFinished(digestId)
            if (!cancelledByUser && !isStopped && e !is InterruptedException) {
                save(ctx, key, JSONObject().put("error", msg).put("at", System.currentTimeMillis()))
            }
            return Result.success()
        } finally {
            activeModel = null
            if (currentWorker === this) currentWorker = null
        }
        AnalysisState.onJobFinished(digestId)
        save(ctx, key, result.put("at", System.currentTimeMillis()).put("count", lines.size))
        return Result.success()
    }

    override fun onStopped() {
        cancelledByUser = true
        activeModel?.cancel()
    }

    companion object {
        @Volatile private var currentWorker: DigestWorker? = null

        fun cached(ctx: Context, key: String): JSONObject? = LocalSettings.prefs(ctx).getString("digest:$key", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        fun clear(ctx: Context, key: String) { LocalSettings.prefs(ctx).edit().remove("digest:$key").apply() }
        private fun save(ctx: Context, key: String, value: JSONObject) { LocalSettings.prefs(ctx).edit().putString("digest:$key", value.toString()).apply() }

        fun enqueue(ctx: Context, key: String, label: String, lines: List<String>): java.util.UUID {
            cancelledByUserAndReset()
            val request = OneTimeWorkRequestBuilder<DigestWorker>()
                .setInputData(workDataOf("key" to key, "label" to label, "lines" to lines.take(80).toTypedArray()))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("digest", ExistingWorkPolicy.REPLACE, request)
            return request.id
        }

        private fun cancelledByUserAndReset() {
            currentWorker?.let {
                it.cancelledByUser = true
                it.activeModel?.cancel()
            }
        }

        fun cancel(ctx: Context) {
            val app = ctx.applicationContext
            cancelledByUserAndReset()
            WorkManager.getInstance(app).cancelUniqueWork("digest")
            val snap = AnalysisState.snapshot()
            if (snap.activeId?.startsWith("digest:") == true) {
                AnalysisState.onJobFinished(snap.activeId)
            }
            if (LiveAsr.snapshot().llmActive) {
                LiveAsr.llmFinish("已取消时段总结")
            }
        }
    }
}
