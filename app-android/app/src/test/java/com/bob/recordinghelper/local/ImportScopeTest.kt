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

    @Test fun savedLookupMatchesAcrossDeviceUuidsAndPathAliases() {
        val recFromOldInstall = org.json.JSONObject()
            .put("id", "rec-old-uuid-1")
            .put("title", "周骞(18601724862)_20261021130737.mp3")
            .put("text", "下周二前提交接口联调报告。")
            .put("status", "pending")
            .put("ownerDevice", "old-device-uuid")
            .put("sourceKey", "/sdcard/MIUI/sound_recorder/call_rec/周骞(18601724862)_20261021130737.mp3|40960|1775800123456")
            .put("audioBytes", 40960L)
        val lookup = LocalStore.SavedLookup(
            recordings = listOf(recFromOldInstall),
            primaryPath = "/storage/emulated/0",
            currentOwner = "brand-new-device-uuid",
        )
        // Scanned via canonical path with rounded MediaStore mtime under a brand new device UUID:
        val itemCanonical = RecordingSource(
            "/storage/emulated/0/MIUI/sound_recorder/call_rec/周骞(18601724862)_20261021130737.mp3",
            "/storage/emulated/0/MIUI/sound_recorder/call_rec/周骞(18601724862)_20261021130737.mp3|40960|1775800123000",
            "周骞(18601724862)_20261021130737.mp3",
            40960L,
            1775800123000L,
        )
        assertNotNull(lookup.find(itemCanonical))
        // Scanned via SAF tree URI with different mtime:
        val itemSaf = RecordingSource(
            "content://com.android.externalstorage.documents/tree/primary%3AMIUI",
            "saf:primary:MIUI/sound_recorder/call_rec/周骞(18601724862)_20261021130737.mp3|40960|1779999999000",
            "周骞(18601724862)_20261021130737.mp3",
            40960L,
            1779999999000L,
        )
        assertNotNull(lookup.find(itemSaf))
        // Unrelated untranscribed recording returns null:
        val itemNew = RecordingSource(
            "/storage/emulated/0/MIUI/sound_recorder/call_rec/李辉(18601729340)_20261023174117.mp3",
            "/storage/emulated/0/MIUI/sound_recorder/call_rec/李辉(18601729340)_20261023174117.mp3|81920|1775800999000",
            "李辉(18601729340)_20261023174117.mp3",
            81920L,
            1775800999000L,
        )
        assertNull(lookup.find(itemNew))
    }

    @Test fun backupMergeSkipsProcessedTodosAndPreservesInProgressTranscripts() {
        val existingRec = org.json.JSONObject()
            .put("id", "rec-local-1")
            .put("title", "项目推进会_20261022103053.mp3")
            .put("text", "这是原始版已经转出来的录音文字稿，正在等待整理。")
            .put("summary", "")
            .put("status", "pending")
            .put("ownerDevice", "dev-1")
        val doneTodo = org.json.JSONObject()
            .put("id", "todo-local-done")
            .put("recordingId", "rec-local-1")
            .put("title", "周五前发送测试包给客户！")
            .put("done", true)
            .put("reviewed", true)
            .put("deleted", false)
        val backupJson = org.json.JSONObject()
            .put("schema", 2)
            .put("recordings", org.json.JSONArray()
                .put(org.json.JSONObject()
                    .put("id", "rec-old-1")
                    .put("title", "项目推进会_20261022103053.mp3")
                    .put("text", "这是原始版已经转出来的录音文字稿，正在等待整理。")
                    .put("summary", "完成项目推进排期")
                    .put("status", "ready"))
                .put(org.json.JSONObject()
                    .put("id", "rec-old-2")
                    .put("title", "王锦荣_20261023152804.mp3")
                    .put("text", "第二条已转写文字稿")
                    .put("summary", "")
                    .put("status", "pending")))
            .put("todos", org.json.JSONArray()
                // Same todo with slightly different punctuation but marked done=false in backup: must NOT revert doneTodo!
                .put(org.json.JSONObject()
                    .put("id", "todo-old-dup")
                    .put("recordingId", "rec-old-1")
                    .put("title", "周五前发送测试包给客户")
                    .put("done", false)
                    .put("reviewed", false))
                // New unprocessed todo on second recording:
                .put(org.json.JSONObject()
                    .put("id", "todo-old-new")
                    .put("recordingId", "rec-old-2")
                    .put("title", "下周一核对预算表")
                    .put("done", false)
                    .put("reviewed", false))
                // Todo whose ID is in todo-done-ids.txt:
                .put(org.json.JSONObject()
                    .put("id", "todo-from-done-list")
                    .put("recordingId", "rec-old-2")
                    .put("title", "已通过外部列表完成的待办")
                    .put("done", false)))
            .toString()

        val res = LocalStore.mergeBackupObjects(
            existingRecordings = listOf(existingRec),
            existingTodos = listOf(doneTodo),
            rawJson = backupJson,
            defaultOwner = "dev-1",
            doneTodoIds = setOf("todo-from-done-list"),
        )
        // Both existingRec (upgraded to ready with summary) and rec-old-2 (pending transcript) are written:
        assertEquals(2, res.recordingsToWrite.size)
        val upgraded = res.recordingsToWrite.first { it.getString("id") == "rec-local-1" }
        assertEquals("ready", upgraded.getString("status"))
        assertEquals("完成项目推进排期", upgraded.getString("summary"))
        // Duplicate undone todo was skipped; new todo and pre-done todo were written:
        assertEquals(2, res.todosToWrite.size)
        assertTrue(res.todosToWrite.none { it.getString("id") == "todo-old-dup" })
        val fromDoneList = res.todosToWrite.first { it.getString("id") == "todo-from-done-list" }
        assertTrue(fromDoneList.getBoolean("done"))
        assertTrue(fromDoneList.getBoolean("reviewed"))
    }

    @Test fun asrCheckpointSavesRestoresAndRecoversFromMirror() {
        val dir = java.nio.file.Files.createTempDirectory("rh-ckpt-test").toFile()
        try {
            val localFile = java.io.File(dir, "local/part.jsonl")
            val mirrorFile = java.io.File(dir, "mirror/part.jsonl")
            val ckpt = com.bob.recordinghelper.asr.AsrCheckpoint(localFile, mirrorFile)
            ckpt.save(com.bob.recordinghelper.asr.AsrCheckpoint.Entry(0, 0L, 160000, "第一段文字。", intArrayOf(1, 2, 3)))
            ckpt.save(com.bob.recordinghelper.asr.AsrCheckpoint.Entry(1, 160000L, 160000, "第二段文字。", null))
            // Append a torn line to simulate mid-write process kill:
            localFile.appendText("{\"i\":2,\"s\":320000,\"t\":\"半截")

            val loaded = ckpt.load()
            assertEquals(2, loaded.size)
            assertEquals("第一段文字。", loaded[0]?.text)
            assertArrayEquals(intArrayOf(1, 2, 3), loaded[0]?.ids)
            assertEquals("第二段文字。", loaded[1]?.text)

            // Simulate APK reinstall wiping localFile while mirrorFile survives:
            localFile.delete()
            assertFalse(localFile.exists())
            val recovered = com.bob.recordinghelper.asr.AsrCheckpoint(localFile, mirrorFile).load()
            assertEquals(2, recovered.size)
            assertEquals("第一段文字。", recovered[0]?.text)
            assertEquals("第二段文字。", recovered[1]?.text)
            assertTrue(localFile.exists())

            ckpt.clear()
            assertFalse(localFile.exists())
            assertFalse(mirrorFile.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
