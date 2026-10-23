package com.bob.recordinghelper.local

import android.content.Context
import android.net.Uri
import androidx.work.*
import com.bob.recordinghelper.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object ModelFiles {
    val slots = listOf("sv.tflite", "tokens.json", "am.mvn", "spk.tflite", "model.gguf")
    fun label(slot: String?): String = when (slot) {
        "sv.tflite" -> "语音识别模型"
        "tokens.json" -> "语音词表"
        "am.mvn" -> "声学特征"
        "spk.tflite" -> "说话人声纹模型"
        "model.gguf" -> "本地整理大模型"
        else -> slot.orEmpty().ifBlank { "模型文件" }
    }
    fun target(ctx: Context, slot: String): File {
        require(slot in slots)
        return File(if (slot.endsWith("gguf")) LocalAsr.llmDir(ctx) else LocalAsr.modelDir(ctx), slot)
    }
    fun partFile(ctx: Context, slot: String): File = File(target(ctx, slot).parentFile, "$slot.part")
    fun partMetaFile(targetDir: File, slot: String): File = File(targetDir, "$slot.part.sha256")
    fun cachedPartBytes(ctx: Context, slot: String): Long = partFile(ctx, slot).takeIf { it.isFile }?.length() ?: 0L

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(65536)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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

    internal fun preparePartForHash(part: File, meta: File, expectedHash: String) {
        val clean = expectedHash.trim().lowercase()
        if (clean.isEmpty()) return
        val recorded = if (meta.isFile) runCatching { meta.readText().trim().lowercase() }.getOrDefault("") else ""
        if (part.exists() && recorded.isNotEmpty() && recorded != clean) {
            part.delete()
        }
        meta.parentFile?.mkdirs()
        runCatching { meta.writeText(clean) }
    }

    /**
     * Streams a model file to [part] with HTTP Range resume (`206 Partial Content`), automatic failover across
     * [candidates] on network error or low speed, and full-file SHA-256 + header validation upon completion.
     * Interrupted or failed network downloads preserve [part] on disk so subsequent runs resume without data loss.
     */
    internal fun downloadHttpsWithResume(
        slot: String,
        part: File,
        meta: File,
        expectedHash: String,
        candidates: List<String>,
        allowLoopbackHttp: Boolean = false,
        windowMs: Long = 15_000L,
        minBytesPerWindow: Long = 15L * 32 * 1024,
        clockMs: () -> Long = { System.currentTimeMillis() },
        isStopped: () -> Boolean = { false },
        onProgress: (bytes: Long, total: Long, resumed: Boolean) -> Unit = { _, _, _ -> },
    ): String {
        val hash = expectedHash.trim().lowercase()
        require(Regex("[a-f0-9]{64}").matches(hash)) { "网络下载必须填写发布方的 SHA-256" }
        val validCandidates = candidates.filter {
            it.startsWith("https://") || (allowLoopbackHttp && (it.startsWith("http://127.0.0.1:") || it.startsWith("http://localhost:")))
        }.distinct()
        require(validCandidates.isNotEmpty()) { "仅允许 HTTPS 下载" }
        part.parentFile?.mkdirs()
        preparePartForHash(part, meta, hash)

        if (part.isFile && part.length() > 0L) {
            val existingSha = runCatching { sha256(part) }.getOrDefault("")
            if (existingSha == hash) {
                validate(part, slot)
                onProgress(part.length(), part.length(), true)
                return existingSha
            }
        }

        val maxAttempts = maxOf(validCandidates.size * 2, 2)
        var lastErr: Exception? = null
        for (attempt in 0 until maxAttempts) {
            if (isStopped()) throw InterruptedException("安装已取消")
            val candidate = validCandidates[attempt % validCandidates.size]
            var conn: HttpURLConnection? = null
            try {
                val resumeFrom = if (part.isFile) part.length().coerceAtLeast(0L) else 0L
                var url = URL(candidate)
                var code = -1
                for (redirect in 0..5) {
                    val isAllowedHttp = allowLoopbackHttp && url.protocol == "http" && (url.host == "127.0.0.1" || url.host == "localhost")
                    require((url.protocol == "https" || isAllowedHttp) && url.userInfo == null) { "仅允许 HTTPS 下载" }
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 45_000
                        instanceFollowRedirects = false
                        if (resumeFrom > 0L) setRequestProperty("Range", "bytes=$resumeFrom-")
                    }
                    code = conn!!.responseCode
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        val next = conn!!.getHeaderField("Location") ?: error("下载跳转缺少地址")
                        conn!!.disconnect()
                        url = URL(url, next)
                        require(redirect < 5) { "跳转过多" }
                    } else break
                }
                if (code == 416 && resumeFrom > 0L) {
                    conn?.disconnect()
                    conn = null
                    val sha = runCatching { sha256(part) }.getOrDefault("")
                    if (sha == hash) {
                        validate(part, slot)
                        onProgress(part.length(), part.length(), true)
                        return sha
                    }
                    part.delete()
                    continue
                }
                if (code != 200 && code != 206) throw IOException("下载失败 HTTP $code")
                val append = code == 206 && resumeFrom > 0L
                val contentLen = conn!!.contentLengthLong
                val expectedTotal = when {
                    contentLen > 0L && append -> resumeFrom + contentLen
                    contentLen > 0L -> contentLen
                    else -> -1L
                }
                var total = if (append) resumeFrom else 0L
                var tick = total
                var winStart = clockMs()
                var winBytes = 0L
                onProgress(total, expectedTotal, append)
                conn!!.inputStream.use { stream ->
                    FileOutputStream(part, append).use { out ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            if (isStopped()) {
                                runCatching { out.fd.sync() }
                                throw InterruptedException("安装已取消")
                            }
                            val count = stream.read(buffer)
                            if (count < 0) break
                            total += count
                            winBytes += count
                            require(total <= 12L * 1024 * 1024 * 1024) { "文件过大" }
                            val usable = part.parentFile?.usableSpace ?: Long.MAX_VALUE
                            if (usable in 1L..(32L * 1024 * 1024)) error("存储空间不足")
                            out.write(buffer, 0, count)
                            if (total - tick >= 2 * 1024 * 1024) {
                                tick = total
                                onProgress(total, expectedTotal, append)
                            }
                            val now = clockMs()
                            if (windowMs > 0L && now - winStart >= windowMs) {
                                if (winBytes < minBytesPerWindow && validCandidates.size > 1 && attempt + 1 < maxAttempts) {
                                    runCatching { out.fd.sync() }
                                    throw IOException("当前下载源速度过慢，正在切换备用镜像续传")
                                }
                                winStart = now
                                winBytes = 0L
                            }
                        }
                        out.fd.sync()
                    }
                }
                val actual = sha256(part)
                if (hash != actual) {
                    part.delete()
                    meta.delete()
                    throw IllegalArgumentException("SHA-256 校验失败，未替换原模型")
                }
                try {
                    validate(part, slot)
                } catch (e: Exception) {
                    part.delete()
                    meta.delete()
                    throw IllegalArgumentException(e.message ?: "模型格式校验失败", e)
                }
                onProgress(total, total, append)
                return actual
            } catch (e: InterruptedException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                lastErr = e
            } finally {
                conn?.disconnect()
            }
        }
        throw (lastErr ?: IOException("无法连接下载地址"))
    }
}

/** One streamed file per durable worker; .part files are preserved across network drops for HTTP Range resume. */
class ModelInstallWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val slot = inputData.getString("slot") ?: return Result.failure()
        val source = inputData.getString("source") ?: return Result.failure()
        val mirror = inputData.getString("mirror").orEmpty()
        val hash = inputData.getString("sha256").orEmpty().lowercase()
        val target = ModelFiles.target(ctx, slot)
        target.parentFile!!.mkdirs()
        val part = ModelFiles.partFile(ctx, slot)
        val meta = ModelFiles.partMetaFile(target.parentFile!!, slot)
        val isContentUri = source.startsWith("content://")
        try {
            val label = ModelFiles.label(slot)
            val n = Progress.ongoing(ctx, "模型安装", label, 0, 0, true)
            try { setForegroundAsync(if (android.os.Build.VERSION.SDK_INT >= 29) ForegroundInfo(240, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(240, n)).get() } catch (_: Exception) {}
            val actual = if (isContentUri) {
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                var tick = 0L
                setProgressAsync(workDataOf("slot" to slot, "bytes" to 0L, "total" to -1L, "resumed" to false))
                ctx.contentResolver.openInputStream(Uri.parse(source))!!.use { stream ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            if (isStopped) throw InterruptedException("安装已取消")
                            val count = stream.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 12L * 1024 * 1024 * 1024) { "文件过大" }
                            if (target.parentFile!!.usableSpace < 32L * 1024 * 1024) error("存储空间不足")
                            out.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            if (total - tick >= 4 * 1024 * 1024) {
                                tick = total
                                Progress.notifyOngoing(ctx, "模型安装", "$label · ${Progress.human(total)}", 0, 0)
                                setProgressAsync(workDataOf("slot" to slot, "bytes" to total, "total" to -1L, "resumed" to false))
                            }
                        }
                        out.fd.sync()
                    }
                }
                val computed = digest.digest().joinToString("") { "%02x".format(it) }
                if (hash.isNotEmpty() && hash != computed) {
                    part.delete()
                    error("SHA-256 校验失败，未替换原模型")
                }
                ModelFiles.validate(part, slot)
                computed
            } else {
                ModelFiles.downloadHttpsWithResume(
                    slot = slot,
                    part = part,
                    meta = meta,
                    expectedHash = hash,
                    candidates = listOf(source, mirror),
                    isStopped = { isStopped },
                    onProgress = { bytes, expected, resumed ->
                        val resumeTag = if (resumed) "（断点续传）" else ""
                        val progText = if (expected > 0L) {
                            "$label$resumeTag · ${Progress.human(bytes)} / ${Progress.human(expected)}"
                        } else {
                            "$label$resumeTag · ${Progress.human(bytes)}"
                        }
                        Progress.notifyOngoing(ctx, "模型安装", progText, 0, 0)
                        setProgressAsync(workDataOf("slot" to slot, "bytes" to bytes, "total" to expected, "resumed" to resumed))
                    }
                )
            }
            val total = part.length()
            ModelGate.hold {
                java.nio.file.Files.move(
                    part.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            }
            meta.delete()
            setProgressAsync(workDataOf("slot" to slot, "bytes" to total, "total" to total, "done" to true))
            LocalSettings.prefs(ctx).edit().putString("modelStatus", "${ModelFiles.label(slot)} 已安装 · SHA-256 $actual").apply()
            if (slot == "model.gguf") {
                LocalStore.of(ctx).requeueQuickExtracted()
                if (!AnalysisWorker.isPaused(ctx)) {
                    AnalysisWorker.schedule(ctx, now = true)
                }
            } else {
                for (name in listOf("CPU", "NEURON", "NNAPI", "QNN")) File(ctx.filesDir, "asr-bad-$name").delete()
                RecordingQueue.of(ctx).retryUnavailable()
                if (LocalAsr.modelReady(ctx)) {
                    ImportWorker.schedule(ctx)
                    AutoImportSettings.requestScan(ctx)
                    RecordingQueueWorker.schedule(ctx)
                }
                AnalysisWorker.schedule(ctx)
            }
            return Result.success()
        } catch (e: Exception) {
            if (isContentUri) part.delete()
            val cached = if (!isContentUri && part.isFile) part.length() else 0L
            val resumeHint = if (cached > 0L) "（已缓存 ${Progress.human(cached)}，重试自动续传）" else ""
            LocalSettings.prefs(ctx).edit().putString("modelStatus", "安装失败$resumeHint：${e.message}").apply()
            val transientNetwork = !isContentUri && !isStopped && e !is IllegalArgumentException && runAttemptCount < 3
            return if (transientNetwork) Result.retry() else Result.failure()
        }
    }
    companion object {
        fun enqueue(
            ctx: Context,
            slot: String,
            source: String,
            sha256: String = "",
            mirror: String = "",
            unmeteredOnly: Boolean = false,
        ) {
            val req = OneTimeWorkRequestBuilder<ModelInstallWorker>()
                .addTag("slot:$slot")
                .setInputData(workDataOf("slot" to slot, "source" to source, "sha256" to sha256, "mirror" to mirror))
                .apply {
                    if (unmeteredOnly) {
                        setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                    }
                }
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("model-install", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
