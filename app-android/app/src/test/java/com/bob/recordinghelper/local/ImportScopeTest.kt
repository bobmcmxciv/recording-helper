package com.bob.recordinghelper.local

import org.junit.Assert.*
import org.junit.Test

class ImportScopeTest {
    private val now = 2_000_000_000_000L
    @Test fun freshInstallDoesNotChooseAnyRange() {
        assertNull(ImportScope.restore(null, false, 0, 0))
    }
    @Test fun legacyEnabledOrPausedRangesArePreserved() {
        assertEquals("new", ImportScope.restore(null, true, now, 0))
        assertEquals("recent", ImportScope.restore(null, true, 0, 30))
        assertEquals("all", ImportScope.restore(null, true, 0, 0))
    }
    @Test fun explicitOptOutOverridesOldRange() {
        assertEquals("off", ImportScope.restore("off", true, now, 60))
    }
    @Test fun savedChoiceIsRestoredWithoutAHiddenDefault() {
        for (mode in ImportScope.choices) assertEquals(mode, ImportScope.restore(mode, true, 0, 0))
        assertNull(ImportScope.restore("unknown", true, 0, 60))
    }
    @Test fun resumeNewChoicePreservesStartAcrossPause() {
        assertEquals(ImportScope(now - 120_000, 0), ImportScope.select("new", 0, now, now - 120_000, true))
    }
    @Test(expected = IllegalArgumentException::class) fun blankChoiceCannotEnableProcessing() { ImportScope.select("", 0, now) }
    @Test(expected = IllegalArgumentException::class) fun offIsNotAnAutomaticRange() { ImportScope.select("off", 0, now) }
    @Test(expected = IllegalArgumentException::class) fun enormousDayCountIsRejected() { ImportScope.select("recent", 36501, now) }
    @Test fun recentClearsTheHiddenNewOnlyCutoff() {
        val range = ImportScope.select("recent", 60, now, now - 120_000, true)
        assertEquals(0L, range.since); assertEquals(60, range.days)
        assertEquals(now - 60L * 86_400_000, ScanWindow.cutoff(now, range.days, range.since))
    }
    @Test fun allClearsBothFilters() {
        assertEquals(ImportScope(0, 0), ImportScope.select("all", 60, now, now - 120_000, true))
    }
    @Test fun newPreservesEnabledFixedStart() {
        assertEquals(ImportScope(now - 120_000, 0), ImportScope.select("new", 60, now, now - 120_000, true))
    }
    @Test fun firstEnableUsesNow() {
        assertEquals(ImportScope(now, 0), ImportScope.select("new", 60, now, now - 120_000, false))
    }
    @Test(expected = IllegalArgumentException::class) fun recentRequiresPositiveDays() { ImportScope.select("recent", 0, now) }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownMode() { ImportScope.select("typo", 60, now) }
    @Test fun fileVersionsHaveStableDifferentKeys() {
        val key = RecordingSources.fileKey("/storage/emulated/0/Music/Recordings/a.m4a", 123, 456)
        assertEquals(key, RecordingSources.fileKey("/storage/emulated/0/Music/Recordings/a.m4a", 123, 456))
        assertNotEquals(key, RecordingSources.fileKey("/storage/emulated/0/Music/Recordings/a.m4a", 124, 456))
        assertNotEquals(key, RecordingSources.fileKey("/storage/emulated/0/Music/Recordings/a.m4a", 123, 457))
    }
    @Test fun primaryStorageAliasesKeepUpgradeDeduplication() {
        val keys = RecordingSources.aliases("/storage/emulated/0/Music/Recordings/a.m4a", 123, 456, "/storage/emulated/0")
        assertTrue(keys.contains("/sdcard/Music/Recordings/a.m4a|123|456"))
        assertTrue(keys.contains("/storage/self/primary/Music/Recordings/a.m4a|123|456"))
        assertEquals(3, keys.size)
        assertEquals(listOf("/storage/ABCD/a.m4a|123|456"), RecordingSources.aliases("/storage/ABCD/a.m4a", 123, 456, "/storage/emulated/0"))
    }
}
