package com.bob.recordinghelper.asr

/**
 * libasrnative.so: in-process decoding of WAV / MP3 / AMR-NB / AMR-WB / AAC (no MediaCodec round trips per frame) and the
 * C++ version of [Frontend]'s features. [loadError] is non-null where the library cannot load (e.g. JVM unit tests);
 * callers then use the MediaCodec and Kotlin paths.
 */
object NativeAudio {
    val loadError: String? = try {
        System.loadLibrary("asrnative")
        null
    } catch (t: Throwable) {
        t.toString()
    }

    /** 0 when the file is not WAV / MP3 / AMR / M4A / ADTS AAC (or unreadable): use MediaCodec instead. */
    @JvmStatic external fun open(path: String): Long

    /** Sample rate of the decoded audio (the file's own rate). */
    @JvmStatic external fun sampleRate(handle: Long): Int

    /**
     * "wav", "mp3", "amr-nb", "amr-wb" (also AMR inside 3GP), "aac" (M4A or ADTS, fdk-aac), or "mp4-aac" (an AAC config
     * fdk-aac refused: demux only, see [nextSample]).
     */
    @JvmStatic external fun format(handle: Long): String

    @JvmStatic external fun channels(handle: Long): Int

    /** mp4-aac: AudioSpecificConfig, MediaCodec's csd-0. */
    @JvmStatic external fun aacConfig(handle: Long): ByteArray

    /** mp4-aac: next AAC access unit into [out]; returns its size, 0 at the end, minus the size if [out] is too small. */
    @JvmStatic external fun nextSample(handle: Long, out: ByteArray): Int

    /** Next mono samples on the int16 scale into [out]; returns the count, 0 at the end. */
    @JvmStatic external fun read(handle: Long, out: FloatArray): Int

    @JvmStatic external fun close(handle: Long)

    /** fbank + LFR + CMVN of pcm[0, n) into [out] (frames × 560); returns the frame count, -1 if [out] is too small. */
    @JvmStatic external fun features(pcm: FloatArray, n: Int, shift: FloatArray, scale: FloatArray, out: FloatArray): Int
}
