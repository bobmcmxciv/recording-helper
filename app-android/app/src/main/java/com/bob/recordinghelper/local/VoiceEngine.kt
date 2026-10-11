package com.bob.recordinghelper.local

import com.bob.recordinghelper.asr.Frontend
import com.bob.recordinghelper.asr.SpeakerEmbedder
import com.bob.recordinghelper.asr.SpeakerTracker
import java.io.Closeable
import java.io.File
import kotlin.math.sqrt

class VoiceEngine(model: File) : Closeable {
    private val embedder = SpeakerEmbedder(model)
    data class Frame(val offset: Int, val vector: FloatArray)
    data class Sample(val vectors: List<FloatArray>, val voicedSeconds: Double, val frames: List<Frame> = emptyList())
    fun extract(pcm: FloatArray, minAudibleFrames: Int = 60): Sample {
        require(minAudibleFrames in 60..150)
        val fs = ArrayList<FloatArray>()
        val offsets = ArrayList<Int>()
        var voiced = 0
        for (i in 0 until pcm.size / 160) {
            var energy = 0.0
            for (k in i * 160 until (i + 1) * 160) energy += pcm[k].toDouble() * pcm[k]
            if (sqrt(energy / 160) >= SpeakerTracker.FLOOR) voiced++
        }
        // Fixed windows; reject quiet / severely clipped windows, rather than enrolling silence.
        for (off in 0..(pcm.size - SpeakerTracker.WIN).coerceAtLeast(-1) step SpeakerTracker.HOP) {
            var energy = 0.0; var clipped = 0; var audible = 0
            for (i in off until off + SpeakerTracker.WIN) {
                energy += pcm[i].toDouble() * pcm[i]
                if (kotlin.math.abs(pcm[i]) > 32000) clipped++
            }
            for (f in 0 until 150) {
                var e = 0.0
                for (i in off + f * 160 until off + (f + 1) * 160) e += pcm[i].toDouble() * pcm[i]
                if (sqrt(e / 160) >= SpeakerTracker.FLOOR) audible++
            }
            if (sqrt(energy / SpeakerTracker.WIN) < SpeakerTracker.FLOOR || clipped > SpeakerTracker.WIN / 20 || audible < minAudibleFrames) continue
            val fb = Frontend.kaldiFbank(pcm, SpeakerTracker.WIN, Frontend.POVEY, off)
            val mean = FloatArray(80)
            for (row in fb) for (m in 0 until 80) mean[m] += row[m] / fb.size
            fs.add(FloatArray(148 * 80) { j -> fb[(j / 80) % fb.size][j % 80] - mean[j % 80] })
            offsets.add(off)
        }
        val frames = if (fs.isEmpty()) emptyList() else embedder.embedBatch(fs).mapIndexedNotNull { i, v ->
            if (VoiceMatcher.valid(v)) Frame(offsets[i], v) else null
        }
        return Sample(frames.map { it.vector }, voiced / 100.0, frames)
    }
    override fun close() = embedder.close()
}
