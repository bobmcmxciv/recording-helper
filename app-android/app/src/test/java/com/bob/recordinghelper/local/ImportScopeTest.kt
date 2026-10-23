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
    @Test fun allAliasesMatchAcrossFileSafAndMediaStoreRoundedTimestamps() {
        val keys = RecordingSources.allAliases("/storage/emulated/0/MIUI/sound_recorder/call_rec/a.mp3", 4096, 1775800123456L, "/storage/emulated/0")
        assertTrue(keys.contains("/storage/emulated/0/MIUI/sound_recorder/call_rec/a.mp3|4096|1775800123456"))
        assertTrue(keys.contains("/storage/emulated/0/MIUI/sound_recorder/call_rec/a.mp3|4096|1775800123000"))
        assertTrue(keys.contains("saf:primary:MIUI/sound_recorder/call_rec/a.mp3|4096|1775800123456"))
        assertTrue(keys.contains("saf:primary:MIUI/sound_recorder/call_rec/a.mp3|4096|1775800123000"))
        val fromSaf = RecordingSources.allAliases("saf:primary:MIUI/sound_recorder/call_rec/a.mp3", 4096, 1775800123456L, "/storage/emulated/0")
        assertTrue(fromSaf.contains("/storage/emulated/0/MIUI/sound_recorder/call_rec/a.mp3|4096|1775800123000"))
    }
    @Test fun resolveLocalFileRecoversFromMediaStoreAndSafSources() {
        val dir = java.nio.file.Files.createTempDirectory("rh-resolve-test").toFile()
        try {
            val recDir = java.io.File(dir, "MIUI/sound_recorder/call_rec").apply { mkdirs() }
            val audio = java.io.File(recDir, "call.mp3").apply { writeBytes(ByteArray(256)); setLastModified(1775800123456L) }
            val mediaItem = RecordingSource(
                "content://media/external/audio/media/999",
                "${audio.canonicalPath}|256|1775800123000",
                "call.mp3", 256, 1775800123000L
            )
            assertEquals(audio.canonicalFile, RecordingSources.resolveLocalFile(mediaItem, dir)?.canonicalFile)
            val safItem = RecordingSource(
                "content://com.android.externalstorage.documents/tree/primary%3AMIUI/document/primary%3AMIUI%2Fsound_recorder%2Fcall_rec%2Fcall.mp3",
                "saf:primary:MIUI/sound_recorder/call_rec/call.mp3|256|1775800123456",
                "call.mp3", 256, 1775800123456L
            )
            assertEquals(audio.canonicalFile, RecordingSources.resolveLocalFile(safItem, dir)?.canonicalFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun pausedAutoScanMustYieldBackgroundAnalysis() {
        // Regression test: pausing automatic tasks (autoScan=false) must NOT unleash background 4-core LLM analysis.
        assertTrue(
            AnalysisWorker.shouldYieldBackground(
                busy = false,
                analysisPaused = false,
                now = false,
                urgent = false,
                autoScan = false,
                pendingTranscriptions = 0,
            )
        )
        // Background analysis also yields while speech transcriptions are still pending.
        assertTrue(
            AnalysisWorker.shouldYieldBackground(
                busy = false,
                analysisPaused = false,
                now = false,
                urgent = false,
                autoScan = true,
                pendingTranscriptions = 3,
            )
        )
        // Explicit "立即整理" (now=true / urgent=true) runs unless explicitly paused/cancelled or mic busy.
        assertFalse(
            AnalysisWorker.shouldYieldBackground(
                busy = false,
                analysisPaused = false,
                now = true,
                urgent = true,
                autoScan = false,
                pendingTranscriptions = 5,
            )
        )
        assertTrue(
            AnalysisWorker.shouldYieldBackground(
                busy = false,
                analysisPaused = true,
                now = true,
                urgent = true,
                autoScan = true,
                pendingTranscriptions = 0,
            )
        )
    }

    @Test fun analysisStatePrioritizesTargetRecordingAndSupportsCancel() {
        AnalysisState.clearAll()
        val r1 = org.json.JSONObject().put("id", "rec-1").put("title", "A")
        val r2 = org.json.JSONObject().put("id", "rec-2").put("title", "B")
        val r3 = org.json.JSONObject().put("id", "rec-3").put("title", "C")
        AnalysisState.enqueuePriority("rec-2")
        AnalysisState.enqueuePriority("rec-3")
        val ordered = AnalysisState.pollOrder(listOf(r1, r2, r3))
        assertEquals(listOf("rec-3", "rec-2", "rec-1"), ordered.map { it.getString("id") })
        assertTrue(AnalysisState.isUrgent())
        assertTrue(AnalysisState.removePriority("rec-3"))
        assertEquals(listOf("rec-2", "rec-1", "rec-3"), AnalysisState.pollOrder(listOf(r1, r2, r3)).map { it.getString("id") })
        AnalysisState.clearAll()
        assertFalse(AnalysisState.isUrgent())
        assertTrue(AnalysisState.snapshot().queuedIds.isEmpty())
    }

    @Test(expected = InterruptedException::class)
    fun cancelledModelChatThrowsInterruptedInsteadOfJsonError() {
        var cancelFlag = false
        val fakeModel = object : com.bob.recordinghelper.llm.TextModel {
            override fun countTokens(text: String): Int = 100
            override fun generate(prompt: String, maxTokens: Int, onText: (String) -> Unit): String? {
                onText("{\"summary\":\"半截")
                cancelFlag = true
                return "{\"summary\":\"半截" // truncated JSON returned by nativeCancel()
            }
            override fun cancel() {
                cancelFlag = true
            }
        }
        LocalAnalysis.analyze(fakeModel, "今天下午三点交周报。", "2026-10-22T10:00:00Z") { cancelFlag }
    }

    @Test fun brandDetectionMapsMainstreamPhoneVendors() {
        assertEquals("小米 / Redmi", LocalSettings.currentBrand("Xiaomi", "Redmi"))
        assertEquals("HONOR / 华为", LocalSettings.currentBrand("HONOR", "HONOR"))
        assertEquals("vivo / iQOO", LocalSettings.currentBrand("vivo", "iQOO"))
        assertEquals("OPPO / OnePlus / realme", LocalSettings.currentBrand("OPPO", "OnePlus"))
        assertEquals("Samsung / 通用", LocalSettings.currentBrand("Google", "Pixel"))
    }
}
