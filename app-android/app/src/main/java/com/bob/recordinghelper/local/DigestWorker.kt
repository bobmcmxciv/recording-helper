package com.bob.recordinghelper.local

import android.content.Context
import androidx.work.*
import com.bob.recordinghelper.LocalAsr
import com.bob.recordinghelper.ModelGate
import com.bob.recordinghelper.llm.LlmEngine
import com.bob.recordinghelper.llm.LlmSettings
import org.json.JSONObject

/** Builds the period overview on the todo board with whichever model is active. Result is cached per period key. */
class DigestWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val key = inputData.getString("key") ?: return Result.failure()
        val label = inputData.getString("label") ?: key
        val lines = inputData.getStringArray("lines")?.toList() ?: return Result.failure()
        val remote = LlmSettings.remoteModel(ctx)
        val result = try {
            if (remote != null) {
                if (LlmSettings.wifiOnly(ctx) && !LlmSettings.onUnmeteredNetwork(ctx)) error("当前不是 Wi-Fi，已按设置暂不发送")
                LocalAnalysis.digest(remote, label, lines) { isStopped }.put("source", "在线整理")
            } else {
                val file = LlmEngine.modelIn(LocalAsr.llmDir(ctx)) ?: error("没有可用的整理方式")
                if (MicState.busy || VoiceRelationState.busy) error("正在录音或分析，稍后再试")
                ModelGate.hold { LlmEngine.load(file).use { model -> LocalAnalysis.digest(model, label, lines) { isStopped } } }.put("source", "本地整理")
            }
        } catch (e: Exception) {
            save(ctx, key, JSONObject().put("error", e.message ?: "生成失败").put("at", System.currentTimeMillis()))
            return Result.success()
        }
        save(ctx, key, result.put("at", System.currentTimeMillis()).put("count", lines.size))
        return Result.success()
    }

    companion object {
        fun cached(ctx: Context, key: String): JSONObject? = LocalSettings.prefs(ctx).getString("digest:$key", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        fun clear(ctx: Context, key: String) { LocalSettings.prefs(ctx).edit().remove("digest:$key").apply() }
        private fun save(ctx: Context, key: String, value: JSONObject) { LocalSettings.prefs(ctx).edit().putString("digest:$key", value.toString()).apply() }

        fun enqueue(ctx: Context, key: String, label: String, lines: List<String>): java.util.UUID {
            val request = OneTimeWorkRequestBuilder<DigestWorker>()
                .setInputData(workDataOf("key" to key, "label" to label, "lines" to lines.take(80).toTypedArray()))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("digest", ExistingWorkPolicy.REPLACE, request)
            return request.id
        }
    }
}
