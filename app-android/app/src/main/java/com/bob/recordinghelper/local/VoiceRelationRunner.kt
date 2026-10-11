package com.bob.recordinghelper.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.bob.recordinghelper.LocalAsr
import com.bob.recordinghelper.asr.AudioDecoder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException

object VoiceRelationRunner {
    const val MAX_SECONDS = 600
    private class LimitReached : RuntimeException()
    data class Decoded(val id: String, val seconds: Double, val limited: Boolean, val windows: List<VoiceRelations.Window>)
    fun decode(file: File, engine: VoiceEngine, alive: () -> Boolean, progress: (Int) -> Unit = {}): Decoded {
        val hash = MessageDigest.getInstance("SHA-256")
        val windows = arrayListOf<VoiceRelations.Window>()
        val chunk = FloatArray(12 * 16000); var used = 0; var total = 0; var limited = false
        fun check() { if (!alive()) throw CancellationException() }
        fun process() {
            check()
            val base = total - used
            // At least two-thirds of each window must be audible. Pause-dominated windows are not
            // independent speaker evidence; do not lower identity/cluster thresholds to absorb them.
            val sample = engine.extract(if (used == chunk.size) chunk else chunk.copyOf(used), minAudibleFrames = 100)
            windows.addAll(sample.frames.map { VoiceRelations.Window(base.toLong() + it.offset, it.vector) })
            used = 0; progress(total / 16000); check()
        }
        try {
            AudioDecoder.decode(file) { x, n ->
                check()
                val count = minOf(n, MAX_SECONDS * 16000 - total)
                // Fingerprint decoded PCM, not filename/URI/container headers. Same audio does not add a vote.
                val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count) bytes.putShort(x[i].toInt().coerceIn(-32768, 32767).toShort())
                hash.update(bytes.array())
                var off = 0
                while (off < count) {
                    val take = minOf(chunk.size - used, count - off)
                    System.arraycopy(x, off, chunk, used, take); used += take; off += take; total += take
                    if (used == chunk.size) process()
                }
                if (total == MAX_SECONDS * 16000) { limited = true; throw LimitReached() }
            }
        } catch (_: LimitReached) { /* bounded prefix only, decoder closes in its finally */ }
        if (used > 0) process()
        return Decoded(hash.digest().joinToString("") { "%02x".format(it) }, total / 16000.0, limited, windows)
    }
    fun run(ctx: Context, uris: List<Uri>, alive: () -> Boolean, progress: (String) -> Unit): VoiceRelationStore.Report {
        require(uris.size in 1..8) { "每次请选择 1 到 8 段录音" }
        val self = VoiceRelationStore.currentSelf(ctx); val anchor = VoiceRelationStore.anchor(self)
        val store = VoiceRelationStore.of(ctx); val old = store.read()
        val entries = linkedMapOf<String, VoiceRelationStore.Entry>()
        if (old?.model == self.model && old.anchor == anchor) old.entries.forEach { entries[it.id] = it }
        val known = VoiceLibrary.of(ctx).all().filter { !it.self && it.model == self.model }.associate { it.id to it.vectors }
        val temp = File(ctx.cacheDir, "voice-relation-input").apply { mkdirs() }
        // Only abandoned copies in this feature-owned temporary directory; never original recordings.
        temp.listFiles()?.filter { it.name.matches(Regex("[0-9a-f-]{36}\\.audio")) }?.forEach { it.delete() }
        VoiceEngine(File(LocalAsr.modelDir(ctx), "spk.tflite")).use { engine ->
            for ((index, uri) in uris.withIndex()) {
                if (!alive()) throw CancellationException()
                require(uri.scheme == "content") { "请通过系统文件选择器选择录音" }
                var title = "所选录音 ${index + 1}"
                runCatching { ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) title = it.getString(0)?.take(120) ?: title
                } }
                val file = File(temp, "${UUID.randomUUID()}.audio")
                val entry = try {
                    progress("第 ${index + 1}/${uris.size} 段 · 正在读取所选录音")
                    ctx.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { out ->
                        val bytes = ByteArray(65536); var copied = 0L
                        while (true) {
                            if (!alive()) throw CancellationException()
                            val n = input.read(bytes); if (n < 0) break
                            copied += n; require(copied <= 256L * 1024 * 1024 && temp.usableSpace > 32L * 1024 * 1024) { "文件超过 256 MB 或空间不足" }
                            out.write(bytes, 0, n)
                        }
                    } }
                    val d = decode(file, engine, alive) { progress("第 ${index + 1}/${uris.size} 段 · 已分析前 $it 秒（每段最多 10 分钟）") }
                    val r = VoiceRelations.analyze(d.id, d.windows, VoiceMatcher.center(self.vectors), known)
                    VoiceRelationStore.Entry(d.id, title, uri.toString(), d.seconds, d.limited, r.reason, r.evidence)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    VoiceRelationStore.Entry("failed-" + VoiceRelationStore.digest(uri.toString().toByteArray()), title, uri.toString(), 0.0, false,
                        "无法读取或分析，请检查文件权限、格式及空间后重新选择", null)
                } finally { file.delete() }
                val merged = VoiceRelationStore.mergeEntries(entries.values.toList(), entry)
                entries.clear(); merged.forEach { entries[it.id] = it }
            }
        }
        if (!alive()) throw CancellationException()
        require(VoiceRelationStore.anchor(VoiceRelationStore.currentSelf(ctx)) == anchor) { "本人声纹或模型已变更，本次结果未保存，请重新分析" }
        return VoiceRelationStore.Report(self.model, anchor, entries.values.toList())
    }
}
