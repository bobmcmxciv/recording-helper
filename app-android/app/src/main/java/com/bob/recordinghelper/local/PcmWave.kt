package com.bob.recordinghelper.local

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Our own 16 kHz mono PCM16 WAV format. Header is checkpointed every write, not just on stop. */
class PcmWave(file: File) : Closeable {
    private val out = RandomAccessFile(file, "rw")
    var samples = 0L; private set
    private var synced = 0L
    init { require(out.length() == 0L); out.write(header(0)) }
    fun write(pcm: ShortArray, n: Int) {
        require(n in 0..pcm.size && samples + n <= MAX_SAMPLES)
        val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) bytes.putShort(pcm[i])
        out.seek(44 + samples * 2); out.write(bytes.array()); samples += n
        out.seek(0); out.write(header(samples))
        if (samples - synced >= RATE) { out.fd.sync(); synced = samples }
    }
    override fun close() { try { out.fd.sync() } finally { out.close() } }
    companion object {
        const val RATE = 16000
        const val MAX_SAMPLES = 2L * 3600 * RATE
        fun header(samples: Long): ByteArray {
            require(samples in 0..MAX_SAMPLES)
            val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + (samples * 2).toInt()).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt((samples * 2).toInt())
            return b.array()
        }
        fun count(file: File): Long = ((file.length() - 44).coerceAtLeast(0) / 2).coerceAtMost(MAX_SAMPLES)
        fun repair(file: File): Long {
            val n = count(file)
            RandomAccessFile(file, "rw").use {
                val expected = header(n); val old = ByteArray(44)
                if (it.length() >= 44) it.readFully(old)
                if (it.length() != 44 + n * 2 || !old.contentEquals(expected)) {
                    it.setLength(44 + n * 2); it.seek(0); it.write(expected); it.fd.sync()
                }
            }
            return n
        }
        fun read(file: File, from: Long, n: Int): FloatArray {
            require(from >= 0 && n >= 0 && from + n <= count(file))
            val bytes = ByteArray(n * 2)
            RandomAccessFile(file, "r").use { it.seek(44 + from * 2); it.readFully(bytes) }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(n) { b.short.toFloat() }
        }
    }
}
