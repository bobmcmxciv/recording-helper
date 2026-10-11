package com.bob.recordinghelper.local

import org.junit.Assert.*
import org.junit.Test

class ScanWindowTest {
    private val now = 2_000_000_000_000L
    @Test fun onlyNewRecordingsAndStableFiles() {
        val since = now - 120_000L
        assertFalse(ScanWindow.eligible(since - 1L, 1024L, now, since))
        assertTrue(ScanWindow.eligible(since, 1024L, now, since))
        assertFalse(ScanWindow.eligible(now - 59_999L, 1024L, now, since))
        assertFalse(ScanWindow.eligible(now - 90_000L, 0L, now, since))
    }
    @Test fun fixedCutoffSurvivesLongOfflinePeriod() {
        val since = now - 90L * 86_400_000L
        assertEquals(since, ScanWindow.cutoff(now, 0, since))
        assertTrue(ScanWindow.eligible(since + 1L, 10L, now, since))
    }
    @Test fun rollingWindowAndStartTimeBothApply() {
        assertEquals(now - 86_400_000L, ScanWindow.cutoff(now, 1, now - 7L * 86_400_000L))
        assertEquals(now - 120_000L, ScanWindow.cutoff(now, 1, now - 120_000L))
    }
    @Test fun unknownTimesOnlyAllowedWhenHistoryExplicitlyIncluded() {
        assertFalse(ScanWindow.eligible(0L, 10L, now, now - 120_000L))
        assertTrue(ScanWindow.eligible(0L, 10L, now, 0L))
    }
}
