package com.bob.recordinghelper.local

import android.content.Context
import android.net.Uri
import androidx.work.*
import com.bob.recordinghelper.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object ModelFiles {
    val slots = listOf("sv.tflite", "tokens.json", "am.mvn", "spk.tflite", "model.gguf")
    fun target(ctx: Context, slot: String): File {
        require(slot in slots)
        return File(if (slot.endsWith("gguf")) LocalAsr.llmDir(ctx) else LocalAsr.modelDir(ctx), slot)
    }
    fun validate(file: File, slot: String) {
        require(file.length() > 0) { "文件为空" }
        val header = ByteArray(8)
        file.inputStream().use { it.read(header) }
        when {
            slot.endsWith("gguf") -> require(String(header, 0, 4, Charsets.US_ASCII) == "GGUF") { "不是 GGUF 模型" }
            slot.endsWith("tflite") -> require(String(header, 4, 4, Charsets.US_ASCII) == "TFL3") { "不是 TFLite 模型" }
            slot == "tokens.json" -> { require(file.length() < 16 * 1024 * 1024); val value = org.json.JSONTokener(file.readText()).nextValue(); require(value is org.json.JSONObject || value is org.json.JSONArray) }
            slot == "am.mvn" -> { require(file.length() < 1024 * 1024); require(file.readText().contains("<AddShift>")) { "不是兼容的 CMVN 文件" } }
        }
    }
}

/** One streamed file per durable worker; .part files are never considered installed models. */
class ModelInstallWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val slot = inputData.getString("slot") ?: return Result.failure()
        val source = inputData.getString("source") ?: return Result.failure()
        val hash = inputData.getString("sha256").orEmpty().lowercase()
        val target = ModelFiles.target(ctx, slot)
        target.parentFile!!.mkdirs()
        val part = File(target.parentFile, "$slot.part")
        var conn: HttpURLConnection? = null
        try {
            val n = Progress.ongoing(ctx, "模型安装", slot, 0, 0, true)
            setForegroundAsync(if (android.os.Build.VERSION.SDK_INT >= 29) ForegroundInfo(240, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(240, n)).get()
            val input = if (source.startsWith("content://")) ctx.contentResolver.openInputStream(Uri.parse(source))!! else {
                require(Regex("[a-f0-9]{64}").matches(hash)) { "网络下载必须填写发布方的 SHA-256" }
                var url = URL(source)
                for (redirect in 0..5) {
                    require(url.protocol == "https" && url.userInfo == null) { "仅允许 HTTPS 下载" }
                    conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 20000; readTimeout = 60000; instanceFollowRedirects = false }
                    val code = conn!!.responseCode
                    if (code in listOf(301,302,303,307,308)) {
                        val next = conn!!.getHeaderField("Location") ?: error("下载跳转缺少地址")
                        conn!!.disconnect(); url = URL(url, next)
                        require(redirect < 5) { "跳转过多" }
                    } else { require(code == 200) { "下载失败 HTTP $code" }; break }
                }
                conn!!.inputStream
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L; var tick = 0L
            input.use { stream -> part.outputStream().use { out ->
                val buffer = ByteArray(65536)
                while (true) {
                    if (isStopped) throw InterruptedException("安装已取消")
                    val count = stream.read(buffer); if (count < 0) break
                    total += count
                    require(total <= 12L * 1024 * 1024 * 1024) { "文件过大" }
                    if (target.parentFile!!.usableSpace < 32L * 1024 * 1024) error("存储空间不足")
                    out.write(buffer, 0, count); digest.update(buffer, 0, count)
                    if (total - tick > 8 * 1024 * 1024) { tick = total; Progress.notifyOngoing(ctx, "模型安装", "$slot · ${Progress.human(total)}", 0, 0) }
                }
                out.fd.sync()
            } }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(hash.isEmpty() || hash == actual) { "SHA-256 校验失败，未替换原模型" }
            ModelFiles.validate(part, slot)
            ModelGate.hold { java.nio.file.Files.move(part.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
            LocalSettings.prefs(ctx).edit().putString("modelStatus", "$slot 已安装 · SHA-256 $actual").apply()
            AnalysisWorker.schedule(ctx); ImportWorker.schedule(ctx)
            if (slot != "model.gguf") {
                for (name in listOf("CPU", "NEURON", "NNAPI", "QNN")) File(ctx.filesDir, "asr-bad-$name").delete()
                RecordingQueue.of(ctx).retryUnavailable()
            }
            AutoImportSettings.requestScan(ctx); RecordingQueueWorker.schedule(ctx)
            return Result.success()
        } catch (e: Exception) {
            LocalSettings.prefs(ctx).edit().putString("modelStatus", "安装失败：${e.message}").apply()
            return Result.failure()
        } finally { conn?.disconnect(); part.delete() }
    }
    companion object {
        fun enqueue(ctx: Context, slot: String, source: String, sha256: String = "") =
            WorkManager.getInstance(ctx).enqueueUniqueWork("model-install", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ModelInstallWorker>().setInputData(workDataOf("slot" to slot, "source" to source, "sha256" to sha256)).build())
    }
}
