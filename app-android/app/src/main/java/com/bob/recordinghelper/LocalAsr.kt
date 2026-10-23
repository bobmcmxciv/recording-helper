package com.bob.recordinghelper

import android.content.Context
import com.bob.recordinghelper.asr.Frontend
import com.bob.recordinghelper.asr.SenseVoice
import com.bob.recordinghelper.asr.SpeakerEmbedder
import com.bob.recordinghelper.asr.SpeakerTracker
import com.bob.recordinghelper.asr.Transcriber
import java.io.File

/**
 * 手机本地转写。模型放在 Android/data/com.bob.recordinghelper/files/asr/（sv.tflite、tokens.json、am.mvn）。
 * 引擎按芯片排序：高通先试 QNN（Hexagon NPU，需要 snapdragon 版安装包里的 QNN 库），联发科先试 NeuroPilot，
 * 然后系统 NNAPI，最后 CPU。厂商库崩溃会直接带走进程、Java 层接不住，
 * 所以用引擎期间一直留着标记文件，用完再删；下次启动发现标记还在，就查退出原因决定要不要停用该引擎。
 * 一批录音共用一个 [Session]：模型只加载、NPU 只准备一次（每次约 5–8 秒）。
 * 可选：目录里有 spk.tflite（CAM++ 声纹模型）就在 CPU 上区分说话人；有 voiceprints.json（服务器声纹库导出）
 * 就给认得出的说话人标上名字。
 */
object LocalAsr {
    private val MODEL_FILES = listOf("sv.tflite", "tokens.json", "am.mvn")

    /** [initMs]: loading the model and preparing the engine (NPU compile, or its cache); 0 for later files of a session. */
    data class Outcome(val result: Transcriber.Result, val engine: SenseVoice.Engine, val initMs: Long)

    /** A loaded model on one engine, for transcribing a batch of recordings. Not thread-safe; close when done. */
    class Session internal constructor(
        private val frontend: Frontend,
        private val model: SenseVoice,
        private val marker: File,
        private val embedder: SpeakerEmbedder? = null,
        private val library: Map<String, List<FloatArray>> = emptyMap(),
    ) : java.io.Closeable {
        val engine: SenseVoice.Engine get() = model.engine
        private var initPending = model.initMs

        /** Speaker separation available (spk.tflite loaded). */
        val speakersOn: Boolean get() = embedder != null

        /** Where the speaker model runs ("QNN" / "CPU"), "" when off. */
        val speakerDevice: String get() = embedder?.device ?: ""
        val libraryNames: Int get() = library.size

        /** Fixed-window preview for the microphone recorder, using the same frontend as file transcription. */
        fun transcribePcm(pcm: FloatArray): String {
            require(pcm.size <= com.bob.recordinghelper.asr.Segmenter.MAX_SAMPLES)
            if (pcm.size < 3200 || Transcriber.loudest100ms(pcm, pcm.size) < Transcriber.SILENCE_RMS) return ""
            val padded = com.bob.recordinghelper.asr.Segmenter.padded(pcm, pcm.size)
            return model.runFlat(frontend.featuresFlat(padded)).trim()
        }

        fun transcribe(
            audio: File,
            onSegment: (Transcriber.Segment) -> Unit = {},
            onLive: (Transcriber.Live) -> Unit = {},
        ): Outcome {
            val tracker = embedder?.let { SpeakerTracker(it) }
            val r = Transcriber(frontend, model, tracker, library, onLive).transcribe(audio, onSegment)
            return Outcome(r, model.engine, initPending).also { initPending = 0 }
        }

        override fun close() {
            model.close()
            embedder?.close()
            marker.delete()
        }
    }

    fun modelDir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "asr")

    /** Local LLM for polishing transcripts: one *.gguf here (Qwen3.5-4B on phones, 9B on the 24 GB tablet). */
    fun llmDir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "llm")

    fun modelReady(ctx: Context): Boolean = MODEL_FILES.all { File(modelDir(ctx), it).length() > 0 }

    /** Loads the model on the first engine that comes up (see [usableEngines]); throws if none does. */
    fun open(ctx: Context, cpuOnly: Boolean = false, loadSpeakers: Boolean = true): Session {
        val dir = modelDir(ctx)
        val frontend = Frontend.load(File(dir, "am.mvn"))
        var initError: Throwable? = null
        for (engine in (if (cpuOnly) listOf(SenseVoice.Engine.CPU) else usableEngines(ctx))) {
            val marker = File(ctx.filesDir, "asr-trying-${engine.name}")
            marker.writeText(System.currentTimeMillis().toString())
            try {
                val model = SenseVoice(File(dir, "sv.tflite"), File(dir, "tokens.json"), engine,
                    File(ctx.filesDir, "npucache"), ctx.applicationInfo.nativeLibraryDir)
                // speaker model is optional: without it (or if it fails to load) transcription works as before
                val spk = File(dir, "spk.tflite").takeIf { loadSpeakers && it.length() > 0 }?.let {
                    // CPU only: CAM++ on the Qualcomm HTP next to SenseVoice failed at run time (graph execute 1003 /
                    // 1007 on the Mi Pad 8 Pro, even with the two invokes serialised) and took SenseVoice down with it
                    try { SpeakerEmbedder(it) } catch (_: Throwable) { null }
                }
                // Prefer the explicitly enrolled local identities. Combining a legacy alias of the
                // same person with a new self-profile would make the runner-up margin ambiguous.
                val localLibrary = if (spk != null) runCatching { com.bob.recordinghelper.local.VoiceLibrary.compatible(ctx)
                    .mapKeys { "疑似${it.key}" } }.getOrDefault(emptyMap()) else emptyMap()
                val lib = if (localLibrary.isNotEmpty()) localLibrary else if (spk != null)
                    SpeakerEmbedder.loadLibrary(File(dir, "voiceprints.json")).mapKeys { "疑似${it.key}" } else emptyMap()
                return Session(frontend, model, marker, spk, lib)
            } catch (t: Throwable) {
                marker.delete()
                initError = t
            }
        }
        throw IllegalStateException("没有可用的转写引擎：${initError?.message}", initError)
    }

    /** One file with its own session (probe / one-off use). */
    fun transcribe(ctx: Context, audio: File, onSegment: (Transcriber.Segment) -> Unit = {}): Outcome =
        open(ctx).use { it.transcribe(audio, onSegment) }

    fun defaultAccelerated(ctx: Context): Boolean {
        if (File(ctx.filesDir, "npucache").listFiles()?.any { it.name != "model-fingerprint.txt" && it.length() > 0 } == true) return true
        if (android.os.Build.VERSION.SDK_INT >= 31 && SenseVoice.googleNeuronOptions(android.os.Build.SOC_MODEL) != null) return true
        return false
    }

    fun isAccelerated(ctx: Context): Boolean =
        com.bob.recordinghelper.local.LocalSettings.prefs(ctx).getBoolean("asrAcceleration", defaultAccelerated(ctx))

    /**
     * 按芯片排好序的引擎，去掉曾把进程搞崩的。残留的 trying 标记只说明进程死在了引擎里；
     * 被强行停止、被杀后台、低内存回收也会留下它，所以要查系统记录的退出原因，只有崩溃才停用。
     * 停用记的是当时的安装时间，App 一更新就自动重试。
     */
    fun usableEngines(ctx: Context): List<SenseVoice.Engine> {
        val installed = installTime(ctx)
        val accelerated = isAccelerated(ctx)
        return (if (accelerated) engineOrder() else listOf(SenseVoice.Engine.CPU)).filter { e ->
            val trying = File(ctx.filesDir, "asr-trying-${e.name}")
            val bad = File(ctx.filesDir, "asr-bad-${e.name}")
            if (trying.exists()) {
                // A previous APK's marker must not poison the new install. An unrelated Java
                // foreground-service crash must not blacklist a healthy speech engine either.
                if (trying.lastModified() >= installed && diedOfCrash(ctx, trying.lastModified())) bad.writeText(installed.toString())
                trying.delete()
            }
            !(bad.exists() && bad.readText().trim() == installed.toString())
        }
    }

    private fun installTime(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime

    /** 标记之后的第一次进程退出是不是崩溃。Android 11 以下查不到原因，按崩溃处理（宁可退回 CPU）。 */
    private fun diedOfCrash(ctx: Context, since: Long): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 30) return true
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        val exit = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10)
            .filter { it.timestamp >= since }
            .minByOrNull { it.timestamp } ?: return false
        return exit.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
    }

    /** Build.SOC_MANUFACTURER（Android 12+）：高通报 "QTI"，联发科报 "Mediatek"；更老的系统看 Build.HARDWARE。 */
    private fun engineOrder(): List<SenseVoice.Engine> {
        val soc = (if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MANUFACTURER else android.os.Build.HARDWARE)
            .orEmpty().lowercase()
        val npu = when {
            "qti" in soc || "qualcomm" in soc || soc.startsWith("qcom") -> listOf(SenseVoice.Engine.QNN)
            "mediatek" in soc || soc.startsWith("mt") -> listOf(SenseVoice.Engine.NEURON)
            else -> emptyList()
        }
        return npu + SenseVoice.Engine.NNAPI + SenseVoice.Engine.CPU
    }

    fun status(ctx: Context): String = buildString {
        // 这里不碰 NpuBridge：加载厂商库可能崩，只在有崩溃标记保护的 transcribe() 里加载
        append(if (modelReady(ctx)) "模型：已就绪" else "模型：未放入（${modelDir(ctx).absolutePath}）")
        val dir = modelDir(ctx)
        if (File(dir, "spk.tflite").length() > 0) {
            append("\n说话人区分：已开启")
            val lib = File(dir, "voiceprints.json")
            if (lib.length() > 0) append("（声纹库已放入）")
        }
        when (Prefs.polishMode(ctx)) {
            Prefs.POLISH_OFF -> append("\n润色：已关闭")
            else -> com.bob.recordinghelper.llm.LlmEngine.modelIn(llmDir(ctx))?.let {
                append("\n润色模型：").append(it.name.removeSuffix(".gguf")).append("（充电时自动润色，排队 ")
                    .append(com.bob.recordinghelper.llm.PolishQueue.size(ctx)).append(" 条）")
            } ?: append("\n润色：本地模型未放入（${llmDir(ctx).absolutePath}）")
        }
        if (!KeepAwake.unrestricted(ctx)) append("\n⚠ 未关闭电池优化：熄屏后可能停止转写/润色")
        val installed = installTime(ctx).toString()
        val bad = SenseVoice.Engine.entries.filter {
            val f = File(ctx.filesDir, "asr-bad-${it.name}")
            f.exists() && f.readText().trim() == installed
        }
        if (bad.isNotEmpty()) append("\n已停用（曾崩溃，更新 App 后重试）：").append(bad.joinToString { it.name })
        Prefs.lastAsr(ctx)?.let { append("\n上次：").append(it) }
    }
}
