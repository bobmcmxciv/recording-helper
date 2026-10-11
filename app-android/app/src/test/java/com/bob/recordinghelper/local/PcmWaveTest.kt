package com.bob.recordinghelper.local

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmWaveTest {
    @Test fun roundTripAndHeaderLengths() {
        val file = File.createTempFile("rh-wav", ".wav")
        try {
            PcmWave(file).use { it.write(shortArrayOf(-32768, 0, 1234, 32767), 4) }
            assertEquals(52L, file.length()); assertEquals(4L, PcmWave.count(file))
            assertArrayEquals(floatArrayOf(-32768f, 0f, 1234f, 32767f), PcmWave.read(file, 0, 4), 0f)
            val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(44, b.getInt(4)); assertEquals(8, b.getInt(40)); assertEquals(16000, b.getInt(24))
        } finally { file.delete() }
    }
    @Test fun validRecoveryDoesNotInvalidateQueuedFileMtime() {
        val file = File.createTempFile("rh-wav", ".wav")
        try {
            PcmWave(file).use { it.write(ShortArray(10) { 2 }, 10) }
            val before = file.lastModified(); Thread.sleep(20)
            PcmWave.repair(file); assertEquals(before, file.lastModified())
        } finally { file.delete() }
    }
    @Test fun repairsInterruptedHeaderAndDropsHalfSample() {
        val file = File.createTempFile("rh-wav", ".wav")
        try {
            PcmWave(file).use { it.write(shortArrayOf(100, 200), 2) }
            RandomAccessFile(file, "rw").use { it.seek(40); it.writeInt(0); it.seek(it.length()); it.writeByte(1) }
            assertEquals(2L, PcmWave.repair(file)); assertEquals(48L, file.length())
            assertArrayEquals(floatArrayOf(100f, 200f), PcmWave.read(file, 0, 2), 0f)
        } finally { file.delete() }
    }
    @Test(expected = IllegalArgumentException::class) fun refusesUnboundedRecordingHeader() { PcmWave.header(PcmWave.MAX_SAMPLES + 1) }
}
