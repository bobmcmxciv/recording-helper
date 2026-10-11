package com.bob.recordinghelper.asr

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Decodes any audio file Android can play into 16 kHz mono samples on the int16 scale, streamed in blocks
 * so hour-long recordings never sit in memory at once. WAV / MP3 / AMR / AAC (M4A, ADTS, AMR in 3GP) are decoded
 * in-process ([NativeAudio]); everything else goes through MediaCodec, preferring an in-process system decoder if
 * the phone has one (e.g. c2.android.inproc.aac.decoder on Android 17).
 */
object AudioDecoder {

    /** Diagnostics of the last decode: decoder, input format, and where the time went. */
    @Volatile var lastStats: String = ""

    /**
     * Calls [sink] with successive blocks (array, valid length). Returns the audio duration in seconds.
     * [allowNative] = false forces MediaCodec (for comparisons).
     */
    fun decode(file: File, allowNative: Boolean = true, sink: (FloatArray, Int) -> Unit): Double {
        if (allowNative && NativeAudio.loadError == null) {
            val h = NativeAudio.open(file.absolutePath)
            if (h != 0L) {
                try {
                    return if (NativeAudio.format(h) == "mp4-aac") decodeAac(h, sink) else decodeNative(h, sink)
                } finally {
                    NativeAudio.close(h)
                }
            }
        }
        return decodeMediaCodec(file, sink)
    }

    private fun decodeNative(h: Long, sink: (FloatArray, Int) -> Unit): Double {
        val t0 = System.nanoTime()
        val rate = NativeAudio.sampleRate(h)
        val resampler = Resampler(rate)
        val buf = FloatArray(16384)
        var total = 0L
        while (true) {
            val n = NativeAudio.read(h, buf)
            if (n <= 0) break
            total += n
            resampler.push(buf, n, sink)
        }
        resampler.flush(sink)
        lastStats = "%s %d Hz，进程内解码，解码循环共 %.1f 秒（含转写回调）".format(
            NativeAudio.format(h), rate, (System.nanoTime() - t0) / 1e9)
        return total.toDouble() / rate
    }

    /** A decoder that runs inside the app process, when the system offers one for [mime]. */
    private fun inProcessDecoder(mime: String): String? =
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            !info.isEncoder && ".inproc." in info.name && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }?.name

    private fun decodeMediaCodec(file: File, sink: (FloatArray, Int) -> Unit): Double {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("no audio track in ${file.name}")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            return runCodec(format, "MediaExtractor", sink) { buf ->
                val n = ex.readSampleData(buf, 0)
                if (n >= 0) ex.advance()
                n
            }
        } finally {
            ex.release()
        }
    }

    /** M4A/MP4 AAC: access units come from libasrnative's MP4 parser instead of the cross-process MediaExtractor. */
    private fun decodeAac(h: Long, sink: (FloatArray, Int) -> Unit): Double {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, NativeAudio.sampleRate(h), NativeAudio.channels(h))
        format.setByteBuffer("csd-0", ByteBuffer.wrap(NativeAudio.aacConfig(h)))
        var bytes = ByteArray(8192)
        return runCodec(format, "进程内 MP4 解析", sink) { buf ->
            var n = NativeAudio.nextSample(h, bytes)
            if (n < 0) {
                bytes = ByteArray(-n)
                n = NativeAudio.nextSample(h, bytes)
            }
            if (n == 0) -1 else { buf.put(bytes, 0, n); n }
        }
    }

    /**
     * Drives a decoder for [format]: [nextInput] fills an input buffer and returns its size, or a negative value at
     * the end of the stream. Prefers a decoder that runs inside the app process.
     */
    private fun runCodec(format: MediaFormat, demuxer: String, sink: (FloatArray, Int) -> Unit, nextInput: (ByteBuffer) -> Int): Double {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = inProcessDecoder(mime)?.let { MediaCodec.createByCodecName(it) } ?: MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var resampler = Resampler(rate)
        var pcmFloat = false
        var total = 0L
        val info = MediaCodec.BufferInfo()
        var outBuffers = 0
        var inputs = 0L
        var waitNs = 0L
        val t0 = System.nanoTime()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                // Keep every free input slot filled without blocking; the codec decodes on its own thread meanwhile.
                // (Feeding one frame and then waiting for its output idled most of the time: 40 s for a 6-minute mp3.)
                while (!inputDone) {
                    val i = codec.dequeueInputBuffer(0)
                    if (i < 0) break
                    val n = nextInput(codec.getInputBuffer(i)!!)
                    if (n < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        // presentation time is irrelevant here; keep it increasing
                        codec.queueInputBuffer(i, 0, n, inputs++ * 1000, 0)
                    }
                }
                val w0 = System.nanoTime()
                val o = codec.dequeueOutputBuffer(info, 2_000)
                waitNs += System.nanoTime() - w0
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    resampler = Resampler(rate)
                } else if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val frames = if (pcmFloat) info.size / 4 / channels else info.size / 2 / channels
                    val mono = FloatArray(frames)
                    if (pcmFloat) {
                        val fb = ob.asFloatBuffer()
                        for (k in 0 until frames) {
                            var s = 0f
                            for (c in 0 until channels) s += fb.get(k * channels + c)
                            mono[k] = s / channels * 32768f
                        }
                    } else {
                        val sb = ob.asShortBuffer()
                        for (k in 0 until frames) {
                            var s = 0f
                            for (c in 0 until channels) s += sb.get(k * channels + c)
                            mono[k] = s / channels
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    outBuffers++
                    total += frames
                    resampler.push(mono, frames, sink)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            resampler.flush(sink)
            lastStats = "%s %d Hz×%d %s + %s，%d 块，等待解码器 %.1f 秒（解码循环共 %.1f 秒，含转写回调）".format(
                mime, rate, channels, codec.name, demuxer, outBuffers, waitNs / 1e9, (System.nanoTime() - t0) / 1e9)
        } finally {
            codec.stop()
            codec.release()
        }
        return total.toDouble() / rate
    }

    /**
     * Polyphase windowed-sinc resampler to 16 kHz, low-pass at 0.45 x the lower rate. The rate ratio is reduced to
     * p/q so every output sample falls on one of q fractional phases whose taps are precomputed.
     */
    class Resampler(private val inRate: Int) {
        private val p: Int
        private val q: Int
        private val half: Int
        private val taps: Array<FloatArray> // [phase][2 * half], tap j applies to input sample center - half + 1 + j
        private var hist = FloatArray(0)    // input samples still needed
        private var histStart = 0L          // absolute index of hist[0]
        private var nextOut = 0L            // absolute index of the next output sample
        private val out = FloatArray(4096)
        private var outLen = 0

        init {
            val g = gcd(inRate, OUT_RATE)
            p = inRate / g
            q = OUT_RATE / g
            val ratio = inRate.toDouble() / OUT_RATE
            val cutoff = 0.45 * minOf(1.0, 1.0 / ratio) * 2 // 2 x cutoff in cycles per input sample
            half = if (inRate == OUT_RATE) 0 else (HALF_TAPS * maxOf(1.0, ratio)).toInt()
            taps = Array(if (half == 0) 0 else q) { phase ->
                FloatArray(2 * half) { j ->
                    val t = (half - 1 - j) + phase.toDouble() / q
                    val x = t * cutoff
                    val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                    val w = t / (half + 1)
                    val window = if (w <= -1 || w >= 1) 0.0 else 0.5 * (1 + cos(PI * w))
                    (cutoff * sinc * window).toFloat()
                }
            }
        }

        fun push(x: FloatArray, n: Int, sink: (FloatArray, Int) -> Unit) {
            if (half == 0) { sink(x, n); return }
            hist = hist.copyOf(hist.size + n).also { System.arraycopy(x, 0, it, hist.size, n) }
            produce(false, sink)
        }

        fun flush(sink: (FloatArray, Int) -> Unit) {
            if (half == 0) return
            produce(true, sink)
            if (outLen > 0) { sink(out, outLen); outLen = 0 }
        }

        private fun produce(final: Boolean, sink: (FloatArray, Int) -> Unit) {
            val h = hist
            val len = h.size
            val width = 2 * half
            while (true) {
                val num = nextOut * p
                // Index of the centre input sample inside hist; Int from here on so the tap loop stays cheap
                // (a 48 kHz six-minute call is ~6 M outputs × taps).
                val c = (num / q - histStart).toInt()
                if (c >= len || (!final && c + half >= len)) break
                val row = taps[(num % q).toInt()]
                val first = c - half + 1
                var acc = 0f
                if (first >= 0 && first + width <= len) {
                    for (j in 0 until width) acc += h[first + j] * row[j]
                } else {
                    for (j in 0 until width) {
                        val k = first + j
                        if (k in 0 until len) acc += h[k] * row[j]
                    }
                }
                out[outLen++] = acc
                if (outLen == out.size) { sink(out, outLen); outLen = 0 }
                nextOut++
            }
            // Drop input that no future output sample can reach.
            val keepFrom = nextOut * p / q - half + 1
            if (keepFrom > histStart) {
                val drop = minOf((keepFrom - histStart).toInt(), hist.size)
                hist = hist.copyOfRange(drop, hist.size)
                histStart += drop
            }
        }

        companion object {
            const val OUT_RATE = 16000
            // Taps per side at the output rate; scaled by the decimation ratio. 8 keeps the alias rejection
            // speech recognition needs at half the cost of 16.
            private const val HALF_TAPS = 8
            private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        }
    }
}
