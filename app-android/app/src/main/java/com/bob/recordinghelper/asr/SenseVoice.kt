package com.bob.recordinghelper.asr

import com.qualcomm.qti.QnnDelegate
import org.json.JSONArray
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Fixed-shape SenseVoiceSmall (Chinese, language and text-norm prompts baked in) on TFLite.
 * Input: 167 feature frames × 560; output: 171 CTC frames × 25055 tokens (the first 4 frames are prompts).
 * [nativeLibDir] is the app's extracted native library directory; the Qualcomm HTP backend loads its DSP-side
 * "skel" library from there. [neuronOptions] overrides the Neuron compiler options (null: [defaultNeuronOptions]).
 */
class SenseVoice(
    model: File,
    tokens: File,
    val engine: Engine,
    cacheDir: File,
    nativeLibDir: String? = null,
    neuronOptions: String? = null,
) : Closeable {

    enum class Engine {
        /** MediaTek NPU through the phone's own NeuroPilot USDK adapter (libnnapi_neuron_shim.so). */
        NEURON,
        /** Qualcomm Hexagon NPU (HTP, fp16) through the QNN LiteRT delegate; needs the Snapdragon build's QNN libraries. */
        QNN,
        /** Android's system NNAPI (gone on Android 17 phones such as the Find X9 Pro). */
        NNAPI,
        CPU,
    }

    private val delegate: Delegate?
    private val interpreter: Interpreter
    private val input: ByteBuffer
    private val output: ByteBuffer
    private val vocab: Array<String>
    private val frames: Int
    private val vocabSize: Int
    private val row: FloatArray
    val initMs: Long

    /** Neuron compiler options actually used ("" = vendor defaults, or not the NEURON engine). */
    val compileOptions: String

    init {
        // 4 threads also covers ops a delegate leaves on the CPU
        val options = Interpreter.Options().setNumThreads(4)
        val compileOptions = if (engine == Engine.NEURON) neuronOptions ?: defaultNeuronOptions() else ""
        this.compileOptions = compileOptions
        // The model's content and the compiler options both change the compiled network, so both are in the cache key.
        val token = "sv-${model.length()}-${modelFingerprint(model, cacheDir)}-${engine.name.lowercase()}" +
            if (compileOptions.isEmpty()) "" else "-o" + Integer.toHexString(compileOptions.hashCode())
        delegate = when (engine) {
            Engine.CPU -> null
            Engine.QNN -> {
                val o = QnnDelegate.Options()
                o.setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
                o.setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_FP16)
                o.setHtpPerformanceMode(QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_SUSTAINED_HIGH_PERFORMANCE)
                if (nativeLibDir != null) o.setSkelLibraryDir(nativeLibDir)
                o.setCacheDir(cacheDir.apply { mkdirs() }.absolutePath)
                o.setModelToken(token)
                // Throws when the QNN libraries are absent (standard build) or the phone has no usable HTP.
                val d = QnnDelegate(o)
                if (!d.isAvailable) { d.close(); error("Qualcomm HTP not available") }
                d.also { options.addDelegate(it) }
            }
            else -> {
                val o = NnApiDelegate.Options()
                    .setAllowFp16(true)
                    .setUseNnapiCpu(false)
                    .setCacheDir(cacheDir.apply { mkdirs() }.absolutePath)
                    .setModelToken(token)
                if (engine == Engine.NEURON) {
                    NpuBridge.loadError?.let { error("NPU library unavailable: $it") }
                    NpuBridge.setCompileOptions(compileOptions)
                    NpuBridge.deviceNames().split(',').firstOrNull { "mdla" in it }?.let { o.setAcceleratorName(it) }
                    o.setNnApiSupportLibraryHandle(NpuBridge.supportLibraryHandle())
                }
                NnApiDelegate(o).also { options.addDelegate(it) }
            }
        }
        val t0 = System.nanoTime()
        interpreter = Interpreter(model, options)
        initMs = (System.nanoTime() - t0) / 1_000_000
        val inShape = interpreter.getInputTensor(0).shape()
        val outShape = interpreter.getOutputTensor(0).shape()
        require(inShape[1] == FRAMES && inShape[2] == Frontend.DIM) { "unexpected input shape ${inShape.contentToString()}" }
        frames = outShape[1]
        vocabSize = outShape[2]
        row = FloatArray(vocabSize)
        input = ByteBuffer.allocateDirect(FRAMES * Frontend.DIM * 4).order(ByteOrder.nativeOrder())
        output = ByteBuffer.allocateDirect(frames * vocabSize * 4).order(ByteOrder.nativeOrder())
        val arr = JSONArray(tokens.readText())
        vocab = Array(arr.length()) { arr.getString(it) }
    }

    /** True when the NPU delegate reported no errors while preparing the model. */
    val delegateHealthy: Boolean get() = (delegate as? NnApiDelegate)?.hasErrors() != true

    /** [features] must hold exactly [FRAMES] vectors (see [Segmenter.padded]). Returns the greedy CTC text. */
    fun run(features: Array<FloatArray>): String {
        require(features.size == FRAMES) { "expected $FRAMES frames, got ${features.size}" }
        input.clear()
        val fb = input.asFloatBuffer()
        for (v in features) fb.put(v)
        output.clear()
        interpreter.run(input, output)
        return decode()
    }

    /** [features] as one array of [FRAMES] × 560 floats ([Frontend.featuresFlat]). */
    fun runFlat(features: FloatArray): String {
        require(features.size == FRAMES * Frontend.DIM) { "expected ${FRAMES * Frontend.DIM} floats, got ${features.size}" }
        input.clear()
        input.asFloatBuffer().put(features)
        output.clear()
        interpreter.run(input, output)
        return decode()
    }

    /** Raw logits of the last [run], for checking against a reference. */
    fun lastOutput(): ByteBuffer = output.duplicate().apply { rewind() }

    /**
     * Argmax token of every output frame after the prompts in the last [run] ([FRAMES] values; frame j covers input
     * samples from j * 960 of the piece). Copy it if needed after the next run - it is overwritten.
     */
    val lastIds = IntArray(FRAMES)

    /** Greedy CTC over the frames after the 4 prompt frames: argmax per frame, collapse repeats, drop blank (0). */
    private fun decode(): String {
        output.rewind() // Interpreter.run() leaves the position at the end of what it wrote
        val f = output.asFloatBuffer()
        for (t in PROMPT_FRAMES until frames) {
            f.position(t * vocabSize)
            f.get(row)
            var best = 0
            var bestV = row[0]
            for (v in 1 until vocabSize) {
                if (row[v] > bestV) { bestV = row[v]; best = v }
            }
            if (t - PROMPT_FRAMES < FRAMES) lastIds[t - PROMPT_FRAMES] = best
        }
        return textOf(lastIds, 0, minOf(FRAMES, frames - PROMPT_FRAMES))
    }

    /**
     * Greedy CTC text of output frames [from, to) of [ids] (see [lastIds]). A token whose run starts before [from] is
     * left to the earlier range, so splitting a piece into ranges never duplicates a character.
     */
    fun textOf(ids: IntArray, from: Int, to: Int): String {
        val sb = StringBuilder()
        var prev = if (from > 0) ids[from - 1] else -1
        for (t in from until to) {
            val best = ids[t]
            if (best != prev && best != 0 && best < vocab.size) {
                val piece = vocab[best]
                if (!piece.startsWith("<|")) sb.append(piece.replace('▁', ' '))
            }
            prev = best
        }
        return sb.toString()
    }

    override fun close() {
        interpreter.close()
        (delegate as? AutoCloseable)?.close()
    }

    companion object {
        const val FRAMES = 167
        private const val PROMPT_FRAMES = 4

        /**
         * First 12 hex digits of the model's SHA-256, for the NPU cache key. The size alone is not enough: the
         * punctuation and plain models are exactly the same length, and a cache compiled from one was silently used
         * for the other. Remembered in [cacheDir] by path + size + mtime, so the 469 MB file is hashed once per change;
         * when it changes, the caches compiled from the old model (~0.5 GB each) are deleted.
         */
        fun modelFingerprint(model: File, cacheDir: File): String {
            val memo = File(cacheDir, "model-fingerprint.txt")
            val stamp = "${model.absolutePath}|${model.length()}|${model.lastModified()}"
            val old = if (memo.exists()) memo.readLines() else emptyList()
            if (old.size == 2 && old[0] == stamp) return old[1]
            val md = MessageDigest.getInstance("SHA-256")
            model.inputStream().use { s ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            val hex = md.digest().take(6).joinToString("") { "%02x".format(it) }
            if (old.size != 2 || old[1] != hex) cacheDir.listFiles()?.forEach { if (it != memo) it.deleteRecursively() }
            cacheDir.mkdirs()
            memo.writeText("$stamp\n$hex")
            return hex
        }

        /**
         * Neuron compiler options the app uses when none are given: the per-chip multi-core set where one is known.
         * Inside the app on the X9 it cut NPU time by about a third (6-min call 5.1 -> 3.4 s, 49-min meeting
         * 37.8 -> 23.7 s, text byte-identical) for a one-off first compile of ~5 min instead of ~50 s.
         */
        fun defaultNeuronOptions(): String =
            if (android.os.Build.VERSION.SDK_INT >= 31) googleNeuronOptions(android.os.Build.SOC_MODEL) ?: "" else ""

        private const val GOOGLE_COMMON = "--relax-fp32 --opt 3 --opt-footprint --opt-accuracy --gno LTS,Inception " +
            "--gno-exp --gno-non-4d-tiling --fc-to-conv --mdla-broadcast-act-wgt 1 --broadcast-flow-distance 63 " +
            "--mdla-set-conv-xy-split-ic-threshold 99999 --mdla-mlo --mdla-conv-exp 1 --mem-opt 3 --stable-linearize"

        /**
         * Neuron options from Google's LiteRT package for MediaTek (google-ai-edge/LiteRT issue 6462), sized per chip.
         * Multi-core needs "--opt 3" together with "--num-mdla". Shell, 10 s segment: Dimensity 9500 (MT6993, 4 MDLA,
         * 7168 KB L1) 88.7 -> 50.5 ms, first compile ~297 s; Dimensity 9400 (MT6991, 2 MDLA, 3072 KB L1) 124 -> 65.6 ms,
         * ~117 s; outputs identical to the reference. The 4-core set aborts in compilation on the 9400. Other chips: null.
         */
        fun googleNeuronOptions(socModel: String): String? = when (socModel.uppercase()) {
            "MT6993" -> "$GOOGLE_COMMON --l1-size-kb 7168 --num-mdla 4 --mdla-flash-attention-mode 0"
            "MT6991" -> "$GOOGLE_COMMON --l1-size-kb 3072 --num-mdla 2 --mdla-flash-attention-mode 0"
            else -> null
        }
    }
}
