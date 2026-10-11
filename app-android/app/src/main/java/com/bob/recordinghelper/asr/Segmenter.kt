package com.bob.recordinghelper.asr

import java.util.Random

/**
 * Cuts a 16 kHz stream into pieces the fixed-shape model accepts (at most [MAX_SAMPLES] ≈ 10 s → 167 LFR frames),
 * cutting at the quietest 200 ms between [MIN_SAMPLES] and the limit so words are not split.
 */
class Segmenter(private val onSegment: (FloatArray, Int, Long) -> Unit) {
    private var buf = FloatArray(MAX_SAMPLES * 2)
    private var len = 0
    private var consumed = 0L // samples already handed out, for segment start times

    fun feed(samples: FloatArray, n: Int) {
        var i = 0
        while (i < n) {
            val take = minOf(n - i, buf.size - len)
            System.arraycopy(samples, i, buf, len, take)
            len += take
            i += take
            while (len >= MAX_SAMPLES) emit(quietestCut())
        }
    }

    fun finish() {
        if (len >= MIN_TAIL) emit(len)
        len = 0
    }

    private fun emit(cut: Int) {
        onSegment(buf.copyOf(cut), cut, consumed)
        consumed += cut
        System.arraycopy(buf, cut, buf, 0, len - cut)
        len -= cut
    }

    /** Sample index of the middle of the quietest [SMOOTH]-block window inside [MIN_SAMPLES, MAX_SAMPLES]. */
    private fun quietestCut(): Int {
        val blocks = MAX_SAMPLES / BLOCK
        val energy = DoubleArray(blocks)
        for (b in 0 until blocks) {
            var e = 0.0
            for (k in b * BLOCK until (b + 1) * BLOCK) e += buf[k].toDouble() * buf[k]
            energy[b] = e
        }
        val first = MIN_SAMPLES / BLOCK
        var best = blocks - SMOOTH
        var bestE = Double.MAX_VALUE
        var run = 0.0
        for (b in first - SMOOTH until first) run += energy[b]
        for (b in first until blocks) {
            run += energy[b] - energy[b - SMOOTH]
            if (run < bestE) { bestE = run; best = b }
        }
        return (best - SMOOTH / 2 + 1) * BLOCK
    }

    companion object {
        /** 400 + 1001 * 160: the longest input that still yields 1002 fbank frames = 167 LFR frames. */
        const val MAX_SAMPLES = 160560
        const val MIN_SAMPLES = 6 * 16000
        const val MIN_TAIL = 16000 / 5 // drop trailing scraps under 0.2 s
        private const val BLOCK = 160
        private const val SMOOTH = 20

        /** Pads a segment to [MAX_SAMPLES] with faint noise (like the dither seen in training) instead of digital zero. */
        fun padded(seg: FloatArray, n: Int): FloatArray {
            val out = seg.copyOf(MAX_SAMPLES)
            val rnd = Random(n.toLong())
            for (i in n until MAX_SAMPLES) out[i] = rnd.nextGaussian().toFloat()
            return out
        }
    }
}
