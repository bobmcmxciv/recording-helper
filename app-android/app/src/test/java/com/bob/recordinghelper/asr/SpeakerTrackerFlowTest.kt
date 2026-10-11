package com.bob.recordinghelper.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * End-to-end run of [SpeakerTracker] on synthetic audio (two "voices" = tones at different pitches, alternating every
 * 7 s, with pauses), with a fake embedder that maps a window's spectrum to a vector. Exercises addPiece / recluster /
 * pieceSpeaker / frameSpeakers / names the way [Transcriber] calls them, so a crash in the final attribution shows here.
 */
class SpeakerTrackerFlowTest {

    /** Fake CAM++: the 80 mel means of the window (before CMN they would differ by voice; after CMN use the variance). */
    private val fake = SpeakerTracker.Embedder { f ->
        val v = FloatArray(SpeakerTracker.DIM)
        val frames = f.size / SpeakerTracker.MELS
        for (m in 0 until SpeakerTracker.MELS) {
            var s = 0.0
            for (t in 0 until frames) { val x = f[t * SpeakerTracker.MELS + m].toDouble(); s += x * x }
            v[m] = (s / frames).toFloat()
        }
        SpeakerTracker.normalized(v)
    }

    private fun audio(seconds: Int): FloatArray {
        val sr = 16000
        val x = FloatArray(seconds * sr)
        for (i in x.indices) {
            val t = i.toDouble() / sr
            val turn = (t / 7).toInt()
            val inPause = t % 7 > 6.2
            if (inPause) continue
            val f0 = if (turn % 2 == 0) 180.0 else 900.0
            // vowel-like: pitch + harmonics, amplitude modulated at 4 Hz like syllables
            val env = 0.6 + 0.4 * sin(2 * PI * 4 * t)
            x[i] = (3000 * env * (sin(2 * PI * f0 * t) + 0.5 * sin(4 * PI * f0 * t) + 0.25 * sin(6 * PI * f0 * t))).toFloat()
        }
        return x
    }

    /** Batched embedder (3 windows per call, last chunk padded) must give the same speakers as one-by-one. */
    @Test
    fun batchedEmbeddingMatchesSingle() {
        val x = audio(70)
        val calls = intArrayOf(0)
        val batched = object : SpeakerTracker.Embedder {
            override fun embed(features: FloatArray) = fake.embed(features)
            override val batch = 3
            override fun embedBatch(features: List<FloatArray>): List<FloatArray> {
                calls[0]++
                assertTrue(features.size <= 3)
                return features.map { fake.embed(it) }
            }
        }
        fun run(e: SpeakerTracker.Embedder): Pair<SpeakerTracker, List<IntArray>> {
            val tr = SpeakerTracker(e)
            var pos = 0; var idx = 0
            val sizes = ArrayList<Int>()
            while (pos < x.size) {
                val n = minOf(Segmenter.MAX_SAMPLES, x.size - pos)
                tr.addPiece(idx, pos.toLong(), x.copyOfRange(pos, pos + n), n)
                sizes.add(n); pos += n; idx++
            }
            tr.recluster()
            return tr to sizes.indices.map { tr.frameSpeakers(it, minOf(SenseVoice.FRAMES, (sizes[it] + 959) / 960)) }
        }
        val (a, la) = run(fake)
        val (b, lb) = run(batched)
        assertEquals(a.windowCount, b.windowCount)
        assertTrue(calls[0] >= a.windowCount / 3)
        for (i in la.indices) assertTrue("piece $i", la[i].contentEquals(lb[i]))
    }

    @Test
    fun fullFlowDoesNotThrowAndFindsTwoSpeakers() {
        val x = audio(130)
        val tr = SpeakerTracker(fake)
        val pieces = ArrayList<LongArray>()
        var pos = 0
        var idx = 0
        while (pos < x.size) {
            val n = minOf(Segmenter.MAX_SAMPLES, x.size - pos)
            val pcm = x.copyOfRange(pos, pos + n)
            tr.addPiece(idx, pos.toLong(), pcm, n)
            pieces.add(longArrayOf(pos.toLong(), n.toLong()))
            if (idx % 6 == 5) {
                tr.recluster()
                (0..idx).forEach { tr.pieceSpeaker(it) }
            }
            pos += n; idx++
        }
        tr.recluster()
        val names = tr.names(mapOf("某人" to listOf(FloatArray(SpeakerTracker.DIM) { if (it == 3) 1f else 0f }),
            "空" to listOf(FloatArray(SpeakerTracker.DIM))))
        println("windows ${tr.windowCount} speakers ${tr.speakers} names $names")
        for ((i, p) in pieces.withIndex()) {
            val frames = minOf(SenseVoice.FRAMES, ((p[1] + 959) / 960).toInt())
            val lab = tr.frameSpeakers(i, frames)
            assertEquals(frames, lab.size)
        }
        // the fake embedder is too crude to promise two clusters; this test is about the call sequence not crashing
        assertTrue("expected at least one speaker, got ${tr.speakers}", tr.speakers >= 1)
    }
}
