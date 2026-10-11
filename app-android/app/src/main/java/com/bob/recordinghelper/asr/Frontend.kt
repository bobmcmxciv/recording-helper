package com.bob.recordinghelper.asr

import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * SenseVoice input features, matching FunASR WavFrontend (torchaudio kaldi.fbank → LFR → CMVN):
 * 25 ms hamming frames every 10 ms, DC removal, preemphasis 0.97, 512-point power spectrum, 80 mel bins
 * (20 Hz – 8 kHz), log; then 7 frames stacked every 6 (560 dims); then (x + shift) * scale from am.mvn.
 */
class Frontend(private val shift: FloatArray, private val scale: FloatArray) {

    init {
        require(shift.size == DIM && scale.size == DIM) { "am.mvn must hold $DIM shift and scale values" }
    }

    /** [pcm] holds 16 kHz mono samples on the int16 scale. Returns ceil(frames / 6) vectors of 560 floats. */
    fun features(pcm: FloatArray, n: Int = pcm.size): Array<FloatArray> {
        val flat = featuresFlat(pcm, n)
        return Array(flat.size / DIM) { flat.copyOfRange(it * DIM, (it + 1) * DIM) }
    }

    /**
     * Same as [features] as one array (frames × 560), computed in C++ ([NativeAudio.features], several times
     * faster) when the native library is present and [useNative] is set, otherwise in Kotlin.
     */
    fun featuresFlat(pcm: FloatArray, n: Int = pcm.size, useNative: Boolean = true): FloatArray {
        if (n < FRAME_LEN) return FloatArray(0)
        if (useNative && NativeAudio.loadError == null) {
            val frames = 1 + (n - FRAME_LEN) / FRAME_SHIFT
            val out = FloatArray((frames + LFR_N - 1) / LFR_N * DIM)
            if (NativeAudio.features(pcm, n, shift, scale, out) >= 0) return out
        }
        val rows = lfrCmvn(fbank(pcm, n))
        val out = FloatArray(rows.size * DIM)
        for (i in rows.indices) System.arraycopy(rows[i], 0, out, i * DIM, DIM)
        return out
    }

    fun fbank(pcm: FloatArray, n: Int = pcm.size): Array<FloatArray> = kaldiFbank(pcm, n, WINDOW)

    fun lfrCmvn(fb: Array<FloatArray>): Array<FloatArray> {
        val t = fb.size
        if (t == 0) return emptyArray()
        val tLfr = (t + LFR_N - 1) / LFR_N
        return Array(tLfr) { i ->
            val v = FloatArray(DIM)
            for (j in 0 until LFR_M) {
                // FunASR pads 3 copies of the first frame on the left and repeats the last frame on the right.
                val src = fb[min(max(i * LFR_N + j - (LFR_M - 1) / 2, 0), t - 1)]
                for (k in 0 until MELS) {
                    val d = j * MELS + k
                    v[d] = (src[k] + shift[d]) * scale[d]
                }
            }
            v
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_LEN = 400
        const val FRAME_SHIFT = 160
        const val FFT = 512
        const val MELS = 80
        const val LFR_M = 7
        const val LFR_N = 6
        const val DIM = MELS * LFR_M
        private const val PREEMPH = 0.97
        private const val EPS = 1.1920928955078125e-07 // torch.finfo(torch.float32).eps

        val WINDOW = DoubleArray(FRAME_LEN) { 0.54 - 0.46 * cos(2 * PI * it / (FRAME_LEN - 1)) }
        val POVEY = DoubleArray(FRAME_LEN) { Math.pow(0.5 - 0.5 * cos(2 * PI * it / (FRAME_LEN - 1)), 0.85) }

        private fun mel(f: Double) = 1127.0 * ln(1.0 + f / 700.0)

        // Triangular filters spaced evenly on the mel scale, evaluated at the 256 FFT bin centers (kaldi get_mel_banks).
        private val MEL_BINS: Array<IntArray>
        private val MEL_WEIGHTS: Array<DoubleArray>

        init {
            val lo = mel(20.0)
            val hi = mel(SAMPLE_RATE / 2.0)
            val delta = (hi - lo) / (MELS + 1)
            val binMel = DoubleArray(FFT / 2) { mel(SAMPLE_RATE.toDouble() / FFT * it) }
            val bins = ArrayList<IntArray>()
            val weights = ArrayList<DoubleArray>()
            for (m in 0 until MELS) {
                val left = lo + m * delta
                val center = lo + (m + 1) * delta
                val right = lo + (m + 2) * delta
                val idx = ArrayList<Int>()
                val w = ArrayList<Double>()
                for (k in 0 until FFT / 2) {
                    val up = (binMel[k] - left) / (center - left)
                    val down = (right - binMel[k]) / (right - center)
                    val v = max(0.0, min(up, down))
                    if (v > 0) { idx.add(k); w.add(v) }
                }
                bins.add(idx.toIntArray())
                weights.add(w.toDoubleArray())
            }
            MEL_BINS = bins.toTypedArray()
            MEL_WEIGHTS = weights.toTypedArray()
        }

        private val COS = DoubleArray(FFT / 2) { cos(2 * PI * it / FFT) }
        private val SIN = DoubleArray(FFT / 2) { -kotlin.math.sin(2 * PI * it / FFT) }

        /**
         * kaldi fbank (log mel, 80 bins) of [n] samples starting at [offset], samples on the int16 scale. [window]:
         * [WINDOW] (hamming, SenseVoice) or [POVEY] (torchaudio's default: the CAM++ speaker model and the server's
         * voiceprint library).
         */
        fun kaldiFbank(pcm: FloatArray, n: Int, window: DoubleArray, offset: Int = 0): Array<FloatArray> {
            if (n < FRAME_LEN) return emptyArray()
            val frames = 1 + (n - FRAME_LEN) / FRAME_SHIFT
            val out = Array(frames) { FloatArray(MELS) }
            val re = DoubleArray(FFT)
            val im = DoubleArray(FFT)
            val power = DoubleArray(FFT / 2 + 1)
            for (f in 0 until frames) {
                val off = offset + f * FRAME_SHIFT
                var mean = 0.0
                for (i in 0 until FRAME_LEN) mean += pcm[off + i]
                mean /= FRAME_LEN
                // Preemphasis runs on the DC-free frame; the first sample is its own predecessor (kaldi replicate pad).
                var prev = pcm[off] - mean
                for (i in 0 until FRAME_LEN) {
                    val x = pcm[off + i] - mean
                    re[i] = (x - PREEMPH * prev) * window[i]
                    prev = x
                }
                re.fill(0.0, FRAME_LEN, FFT)
                im.fill(0.0)
                fft(re, im)
                for (k in 0..FFT / 2) power[k] = re[k] * re[k] + im[k] * im[k]
                val row = out[f]
                for (m in 0 until MELS) {
                    var e = 0.0
                    val bins = MEL_BINS[m]
                    val weights = MEL_WEIGHTS[m]
                    for (j in bins.indices) e += power[bins[j]] * weights[j]
                    row[m] = ln(max(e, EPS)).toFloat()
                }
            }
            return out
        }

        /** In-place iterative radix-2 FFT of length 512. */
        private fun fft(re: DoubleArray, im: DoubleArray) {
            val n = FFT
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var len = 2
            while (len <= n) {
                val step = n / len
                val half = len / 2
                var start = 0
                while (start < n) {
                    for (k in 0 until half) {
                        val wr = COS[k * step]
                        val wi = SIN[k * step]
                        val a = start + k
                        val b = a + half
                        val xr = re[b] * wr - im[b] * wi
                        val xi = re[b] * wi + im[b] * wr
                        re[b] = re[a] - xr; im[b] = im[a] - xi
                        re[a] += xr; im[a] += xi
                    }
                    start += len
                }
                len = len shl 1
            }
        }

        /** Reads the AddShift and Rescale vectors from a kaldi nnet-text am.mvn. */
        fun load(amMvn: File): Frontend {
            val text = amMvn.readText()
            fun vector(tag: String): FloatArray {
                val at = text.indexOf(tag)
                require(at >= 0) { "$tag missing in ${amMvn.name}" }
                val open = text.indexOf('[', at)
                val close = text.indexOf(']', open)
                return text.substring(open + 1, close).trim().split(Regex("\\s+")).map { it.toFloat() }.toFloatArray()
            }
            return Frontend(vector("<AddShift>"), vector("<Rescale>"))
        }
    }
}
