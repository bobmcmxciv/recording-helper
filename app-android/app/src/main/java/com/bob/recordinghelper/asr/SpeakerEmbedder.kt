package com.bob.recordinghelper.asr

import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * CAM++ speaker embedding (3D-Speaker campplus zh-cn 16k, the model of the server's voiceprint library) on the CPU.
 * spk.tflite is the PyTorch model converted with litert-torch for fixed 1.5 s windows: input [B, 148, 80] CMN fbank
 * (povey window, int16 scale), output [B, 192]; outputs match PyTorch to 1e-5.
 */
class SpeakerEmbedder(model: File, threads: Int = 4) : SpeakerTracker.Embedder, Closeable {
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(threads))

    /** Where the model runs, for the logs and the live view. */
    val device: String = "CPU"

    /**
     * Windows per call, from the model's input shape [B, 148, 80]. The shipped model takes 8: one call costs about as
     * much fixed overhead as one window (the graph is ~1700 small ops), so 8 at once is ~1.8x faster per window.
     */
    override val batch: Int

    private val input: ByteBuffer
    private val output: ByteBuffer
    private val window = SpeakerTracker.FRAMES * SpeakerTracker.MELS

    init {
        val shape = interpreter.getInputTensor(0).shape()
        require(shape.size == 3 && shape[1] == SpeakerTracker.FRAMES && shape[2] == SpeakerTracker.MELS) {
            "unexpected speaker model input ${shape.contentToString()}"
        }
        batch = shape[0]
        input = ByteBuffer.allocateDirect(batch * window * 4).order(ByteOrder.nativeOrder())
        output = ByteBuffer.allocateDirect(batch * SpeakerTracker.DIM * 4).order(ByteOrder.nativeOrder())
    }

    override fun embed(features: FloatArray): FloatArray = embedBatch(listOf(features))[0]

    override fun embedBatch(features: List<FloatArray>): List<FloatArray> {
        val out = ArrayList<FloatArray>(features.size)
        for (from in features.indices step batch) {
            val chunk = features.subList(from, minOf(features.size, from + batch))
            input.clear()
            val fb = input.asFloatBuffer()
            for (i in 0 until batch) fb.put(chunk[minOf(i, chunk.size - 1)]) // pad a short chunk with its last window
            output.clear()
            interpreter.run(input, output)
            output.rewind()
            val ob = output.asFloatBuffer()
            for (i in chunk.indices) {
                val e = FloatArray(SpeakerTracker.DIM)
                ob.position(i * SpeakerTracker.DIM)
                ob.get(e)
                out.add(SpeakerTracker.normalized(e))
            }
        }
        return out
    }

    override fun close() = interpreter.close()

    companion object {
        /**
         * Voiceprint library: {"name": [192 floats] or [[192 floats], ...]} (voiceprints.json next to the model, as
         * exported from the server). Missing or unreadable file: empty map (speakers stay 说话人N).
         */
        fun loadLibrary(file: File): Map<String, List<FloatArray>> {
            if (!file.exists()) return emptyMap()
            return try {
                val o = JSONObject(file.readText())
                val out = LinkedHashMap<String, List<FloatArray>>()
                for (name in o.keys()) {
                    val a = o.getJSONArray(name)
                    fun vec(j: org.json.JSONArray) = FloatArray(j.length()) { j.getDouble(it).toFloat() }
                    out[name] = if (a.length() > 0 && a.get(0) is org.json.JSONArray) {
                        (0 until a.length()).map { vec(a.getJSONArray(it)) }
                    } else listOf(vec(a))
                }
                out.filterValues { vs -> vs.isNotEmpty() && vs.all(com.bob.recordinghelper.local.VoiceMatcher::valid) }
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }
}
