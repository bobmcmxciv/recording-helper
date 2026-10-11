package com.bob.recordinghelper.llm

import java.io.Closeable
import java.io.File

/**
 * Local LLM on the CPU through llama.cpp (libllmjni.so). One model per instance; not thread-safe except [cancel].
 * Qwen3.5 GGUF (4B on 16 GB phones, 9B on the 24 GB tablet), 4 threads: measured with llama-bench, 4B on the X9
 * prefill 58 / generate 11.4 tok/s, 9B on the Mi Pad 8 Pro 40 / 9.2 tok/s (6 threads were not faster).
 */
class LlmEngine private constructor(private var handle: Long, val model: File) : TextModel, Closeable {

    data class Stats(val promptTokens: Long, val generatedTokens: Long, val prefillMs: Long, val generateMs: Long)

    /** Output as it is produced (whole UTF-8 characters). Called on the generating thread. */
    fun interface Sink {
        fun onToken(bytes: ByteArray)
    }

    val name: String get() = model.name.removeSuffix(".gguf")

    override fun generate(prompt: String, maxTokens: Int, onText: (String) -> Unit): String? {
        check(handle != 0L) { "closed" }
        val out = nativeGenerate(handle, prompt.toByteArray(Charsets.UTF_8), maxTokens,
            Sink { onText(String(it, Charsets.UTF_8)) }) ?: return null
        return String(out, Charsets.UTF_8)
    }

    override fun countTokens(text: String): Int = nativeCountTokens(handle, text.toByteArray(Charsets.UTF_8))

    fun lastStats(): Stats = nativeLastStats(handle).let { Stats(it[0], it[1], it[2], it[3]) }

    override fun cancel() {
        if (handle != 0L) nativeCancel(handle)
    }

    override fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0
    }

    companion object {
        val loadError: Throwable? = try {
            System.loadLibrary("llmjni"); null
        } catch (t: Throwable) {
            t
        }

        /** The model in [dir] (the first *.gguf), or null. */
        fun modelIn(dir: File): File? = dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") && f.length() > 0 }
            ?.minByOrNull { if (it.name == "model.gguf") "" else it.name }

        fun load(file: File, threads: Int = 4, contextTokens: Int = 4096): LlmEngine {
            loadError?.let { throw IllegalStateException("llm library unavailable: $it", it) }
            val features = try { File("/proc/cpuinfo").readText().lowercase() } catch (_: Exception) { "" }
            check(Regex("\\bi8mm\\b").containsMatchIn(features) && Regex("\\basimddp\\b").containsMatchIn(features)) {
                "当前优化版 LLM 库需要 ARM dotprod/i8mm 指令集；此设备未确认支持。请使用兼容 CPU 的原生库构建，不会回退到云端。"
            }
            val h = nativeLoad(file.absolutePath.toByteArray(Charsets.UTF_8), threads, contextTokens)
            check(h != 0L) { "could not load ${file.name}" }
            return LlmEngine(h, file)
        }

        @JvmStatic private external fun nativeLoad(path: ByteArray, threads: Int, nCtx: Int): Long
        @JvmStatic private external fun nativeFree(h: Long)
        @JvmStatic private external fun nativeCancel(h: Long)
        @JvmStatic private external fun nativeCountTokens(h: Long, text: ByteArray): Int
        @JvmStatic private external fun nativeGenerate(h: Long, prompt: ByteArray, maxTokens: Int, sink: Sink): ByteArray?
        @JvmStatic private external fun nativeLastStats(h: Long): LongArray
    }
}

/** What [Polisher] needs from a model (a fake one in the JVM tests). */
interface TextModel {
    /** [prompt] is complete (chat template applied). Returns the output, or null on failure. */
    fun generate(prompt: String, maxTokens: Int, onText: (String) -> Unit): String?
    fun countTokens(text: String): Int
    fun cancel() {}
}
