package com.bob.recordinghelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScanLedgerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun key(i: Int) = "/storage/emulated/0/MIUI/sound_recorder/call_rec/微信录音 李荣耀_$i.aac|${1000 + i}|${1758000000000L + i}"

    @Test
    fun keepsMoreThan3000AcrossReload() {
        val f = File(tmp.root, "scan-ledger.txt")
        val a = ScanLedger(f) { emptyList() }
        for (i in 0 until 8000) a.add(key(i))
        val b = ScanLedger(f) { error("legacy must not be read once the file exists") }
        assertEquals(8000, b.size())
        assertTrue(b.contains(key(0)) && b.contains(key(4321)) && b.contains(key(7999)))
        assertFalse(b.contains(key(8000)))
    }

    @Test
    fun migratesLegacyOnlyOnce() {
        val f = File(tmp.root, "scan-ledger.txt")
        var calls = 0
        val legacy = { calls++; listOf(key(1), key(2), "", key(2)) }
        val a = ScanLedger(f, legacy)
        assertTrue(a.contains(key(1)) && a.contains(key(2)))
        assertEquals(2, a.size())
        a.add(key(3))
        val b = ScanLedger(f, legacy)
        assertEquals(3, b.size())
        assertEquals(1, calls)
    }

    @Test
    fun duplicateAddWritesOneLine() {
        val f = File(tmp.root, "scan-ledger.txt")
        val a = ScanLedger(f) { emptyList() }
        a.add(key(5)); a.add(key(5)); a.add(key(6))
        assertEquals(listOf(key(5), key(6)), f.readLines())
    }

    @Test
    fun recordingTimeInNameIgnoresMtimeChange() {
        val f = File(tmp.root, "scan-ledger.txt")
        val p = "/storage/emulated/0/MIUI/sound_recorder/app_rec/微信录音 叶洋洋 数字基建_20251209151332.aac"
        // the key a scan wrote while the file still had its copy date, then Syncthing set the recording time
        ScanLedger(f) { emptyList() }.add("$p|554512|1771235328000")
        val b = ScanLedger(f) { emptyList() }
        assertTrue(b.contains("$p|554512|1765264436295"))
        assertFalse(b.contains("$p|554513|1765264436295"))
        assertFalse(b.contains("/storage/emulated/0/MIUI/sound_recorder/app_rec/other_20251209151332.aac|554512|1765264436295"))
    }

    @Test
    fun withoutRecordingTimeMtimeStillCounts() {
        val f = File(tmp.root, "scan-ledger.txt")
        val a = ScanLedger(f) { listOf("/storage/emulated/0/Music/Recordings/meeting.m4a|1000|1") }
        assertTrue(a.contains("/storage/emulated/0/Music/Recordings/meeting.m4a|1000|1"))
        assertFalse(a.contains("/storage/emulated/0/Music/Recordings/meeting.m4a|1000|2"))
        assertFalse(ScanLedger.hasRecordingTime("/x/录音_2025120915133.aac"))
        assertTrue(ScanLedger.hasRecordingTime("/x/张三(13800000000)_20250617143957.mp3"))
    }

    @Test
    fun tornLastLineDoesNotSpoilTheNextKey() {
        val f = File(tmp.root, "scan-ledger.txt")
        f.writeText(key(1) + "\n" + "/storage/emulated/0/half|12")
        val a = ScanLedger(f) { emptyList() }
        a.add(key(2))
        val b = ScanLedger(f) { emptyList() }
        assertTrue(b.contains(key(1)) && b.contains(key(2)))
        assertFalse(b.contains("/storage/emulated/0/half|12" + key(2)))
    }
}
