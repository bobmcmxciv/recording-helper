package com.bob.recordinghelper.local

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import com.bob.recordinghelper.KeepAwake
import com.bob.recordinghelper.ModelGate
import com.bob.recordinghelper.LocalAsr
import com.bob.recordinghelper.PhoneAsrBatch
import android.os.Environment
import android.media.MediaMetadataRetriever
import org.json.JSONObject
import java.io.File
import android.net.Uri
import android.provider.DocumentsContract

/** Configuration helper; requires an explicit instrumentation argument to change settings. */
class DeviceSetupTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val audioExtensions = setOf("mp3", "m4a", "wav", "amr", "awb", "aac", "ogg", "opus", "flac", "3gp", "3gpp", "wma", "mp4")

    @Test fun engineDiagnostics() {
        val last = ctx.getSharedPreferences("rh_prefs", android.content.Context.MODE_PRIVATE).getString("lastAsr", "").orEmpty()
        val out = JSONObject().put("installed", ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime)
            .put("queue", RecordingQueue.of(ctx).counts())
            .put("engineError", if (last.startsWith("引擎不可用")) last else "not_an_engine_error")
        for (engine in listOf("CPU", "NEURON", "NNAPI", "QNN")) {
            val trying = File(ctx.filesDir, "asr-trying-$engine")
            val bad = File(ctx.filesDir, "asr-bad-$engine")
            out.put(engine, JSONObject().put("tryingTime", trying.lastModified()).put("badInstall", if (bad.exists()) bad.readText().trim() else ""))
        }
        val errors = org.json.JSONArray()
        RecordingQueue.of(ctx).readableDatabase.rawQuery("SELECT error FROM queue WHERE state='error' LIMIT 5", null).use {
            while (it.moveToNext()) errors.put(it.getString(0))
        }
        out.put("queueErrors", errors)
        report("engine_diagnostics", out)
    }

    @Test fun cleanupSyntheticQueue() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("cleanupSynthetic") == "yes")
        val db = RecordingQueue.of(ctx).writableDatabase
        val keys = mutableListOf<String>()
        db.rawQuery("SELECT key,title FROM queue", null).use { c -> while (c.moveToNext()) {
            if (Regex("RH-auto-check-[0-9a-f-]{36}\\.wav").matches(c.getString(1))) keys.add(c.getString(0))
        } }
        keys.forEach { db.delete("queue", "key=?", arrayOf(it)) }
        report("synthetic_queue_cleanup", JSONObject().put("removedTestReferences", keys.size))
    }

    private fun recordingFiles(): List<File> {
        val prefs = ctx.getSharedPreferences("rh_prefs", android.content.Context.MODE_PRIVATE)
        val roots = prefs.getString("scanDirs", "MIUI/sound_recorder\nMusic/Recordings\nRecordings\nRecord\nSounds\nRecorder\nVoice Recorder")!!.lines().filter { it.isNotBlank() }
        val found = linkedMapOf<String, File>()
        fun visit(dir: File, depth: Int) {
            if (depth > 3 || found.size >= 20_000) return
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) visit(f, depth + 1)
                else if (f.isFile && f.extension.lowercase() in audioExtensions && f.length() > 0) found[f.canonicalPath] = f
            }
        }
        for (root in roots) visit(if (root.startsWith('/')) File(root) else File(Environment.getExternalStorageDirectory(), root), 0)
        return found.values.toList()
    }

    private fun safeInventory(files: List<File>): JSONObject {
        val result = AutoImportSettings.scheduledStatus(ctx)
        val now = System.currentTimeMillis()
        val cutoff = ScanWindow.cutoff(now, result.getInt("days"), result.getLong("since"))
        val records = LocalStore.of(ctx).all("recording")
        return result.put("audioFiles", files.size).put("inventoryMayBeTruncated", files.size >= 20_000)
            .put("stableAudioFiles", files.count { it.lastModified() <= now - 60_000 })
            .put("excludedByCurrentWindow", files.count { it.lastModified() < cutoff })
            .put("insideCurrentWindow", files.count { ScanWindow.eligible(it.lastModified(), it.length(), now, cutoff) })
            .put("formats", JSONObject(files.groupingBy { it.extension.lowercase() }.eachCount()))
            .put("localRecordingCount", records.size)
            .put("localStatuses", JSONObject(records.groupingBy { it.optString("status") }.eachCount()))
            .put("pendingImportCopies", File(ctx.filesDir, "imports").listFiles { f -> f.extension == "audio" }?.size ?: 0)
            .put("asrModelReady", LocalAsr.modelReady(ctx))
    }

    private fun report(name: String, value: JSONObject) {
        // Diagnostics intentionally exclude file names, paths, voices and transcript contents.
        File(ctx.getExternalFilesDir(null), "$name.json").writeText(value.toString(2))
        InstrumentationRegistry.getInstrumentation().addResults(android.os.Bundle().apply { putString(name, value.toString()) })
    }

    private data class DocumentAudio(val uri: Uri, val name: String, val bytes: Long, val modified: Long)
    private var treeReadErrors = 0
    private fun treeFiles(): List<DocumentAudio> {
        val found = linkedMapOf<String, DocumentAudio>()
        fun visit(tree: Uri, parent: String, depth: Int) {
            if (depth > 5 || found.size >= 20_000) return
            ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent),
                arrayOf("document_id", "_display_name", "mime_type", "_size", "last_modified"), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0); val name = c.getString(1).orEmpty()
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) { visit(tree, id, depth + 1); continue }
                    if (name.substringAfterLast('.', "").lowercase() !in audioExtensions || c.getLong(3) <= 0) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    found[uri.toString()] = DocumentAudio(uri, name, c.getLong(3), c.getLong(4))
                }
            }
        }
        for (raw in LocalSettings.treeUris(ctx)) try {
            val uri = Uri.parse(raw); visit(uri, DocumentsContract.getTreeDocumentId(uri), 0)
        } catch (_: Exception) { treeReadErrors++ }
        return found.values.toList()
    }
    private fun treeInventory(files: List<DocumentAudio>): JSONObject {
        val result = safeInventory(emptyList())
        val now = System.currentTimeMillis()
        val cutoff = ScanWindow.cutoff(now, result.getInt("days"), result.getLong("since"))
        return result.put("sourceMode", "authorized_directory").put("audioFiles", files.size)
            .put("treeReadErrors", treeReadErrors).put("inventoryMayBeTruncated", files.size >= 20_000)
            .put("stableAudioFiles", files.count { it.modified <= now - 60_000 })
            .put("excludedByCurrentWindow", files.count { it.modified < cutoff })
            .put("insideCurrentWindow", files.count { ScanWindow.eligible(it.modified, it.bytes, now, cutoff) })
            .put("formats", JSONObject(files.groupingBy { it.name.substringAfterLast('.').lowercase() }.eachCount()))
    }

    @Test fun recordingInventory() {
        val inventory = RecordingSources.collect(ctx)
        val out = AutoImportSettings.scheduledStatus(ctx)
        val cutoff = ScanWindow.cutoff(System.currentTimeMillis(), out.getInt("days"), out.getLong("since"))
        report("recording_inventory", out.put("sourceMode", "merged_paths_and_trees")
            .put("audioFiles", inventory.files.size).put("directFiles", inventory.directCount).put("treeFiles", inventory.treeCount)
            .put("errors", inventory.errors).put("truncated", inventory.truncated)
            .put("insideCurrentWindow", inventory.files.count { ScanWindow.eligible(it.modified, it.bytes, System.currentTimeMillis(), cutoff) })
            .put("queue", RecordingQueue.of(ctx).counts()).put("asrModelReady", LocalAsr.modelReady(ctx))
            .put("acceleration", LocalSettings.prefs(ctx).getBoolean("asrAcceleration", false)))
    }

    @Test fun queuePersistenceAndPauseContract() {
        val name = "queue-contract-${java.util.UUID.randomUUID()}.db"
        var queue = RecordingQueue(ctx, name)
        try {
            val a = RecordingSource("content://test/a", "a", "synthetic-a", 10, 100)
            val b = RecordingSource("content://test/b", "b", "synthetic-b", 20, 200)
            assertTrue(queue.add(a)); assertTrue(queue.add(b, false))
            assertFalse(queue.add(a.copy(source = "content://renewed/a")))
            assertEquals("b", queue.claim(false)!!.key) // Paused automatic import still permits explicit manual items.
            assertNull(queue.next(false))
            queue.state("b", "done")
            assertEquals("content://renewed/a", queue.claim(true)!!.source)
            queue.close(); queue = RecordingQueue(ctx, name)
            queue.recover()
            assertEquals("queued", queue.status("a")); assertEquals("done", queue.status("b"))
            queue.failure("a", "synthetic failure"); assertNull(queue.next(true))
            queue.retryErrors(); assertEquals("a", queue.next(true)!!.key)
            queue.resetUnstartedAutomatic(); assertNull(queue.status("a")); assertEquals("done", queue.status("b"))
        } finally { queue.close(); ctx.deleteDatabase(name) }
    }

    /** No direct batch call: a new synthetic file must be discovered and processed by both production workers. */
    @Test(timeout = 240_000) fun automaticDiscoveryToTranscript() {
        org.junit.Assume.assumeTrue("Auto import must already be enabled", AutoImportSettings.status(ctx).getBoolean("enabled"))
        val ins = InstrumentationRegistry.getInstrumentation()
        // First-run setup begins in the visible app. Keep the harness visible too: ColorOS can
        // freeze an invisible harness AFTER transcription succeeds, during synthetic-file cleanup.
        val activity = ins.startActivitySync(android.content.Intent(ctx, com.bob.recordinghelper.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        ins.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        // Let the small launch scan finish before adding the fixture; the real immediate scan below
        // is still responsible for discovering it. Avoid direct calls into R8-inlined WM companions.
        Thread.sleep(3000)
        val root = RecordingSources.candidates(ctx).firstOrNull { it.isDirectory && it.canWrite() }
        assertNotNull("No writable recorder path for the synthetic fixture", root)
        val file = File(root, "RH-auto-check-${java.util.UUID.randomUUID()}.wav")
        val before = AutoImportSettings.status(ctx)
        val started = android.os.SystemClock.elapsedRealtime()
        val result = JSONObject().put("route", "automatic_discovery_to_transcript").put("stage", "prepare")
        KeepAwake.acquire(ctx, "自动发现验收与清理（合成文件）")
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("meeting.wav").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            assertTrue(file.setLastModified(System.currentTimeMillis() - 90_000))
            val item = RecordingSources.file(file)
            val id = "rec-" + LocalStore.hash(LocalSettings.device(ctx) + ":" + item.key)
            KeepAwake.hold(ctx, "验证新增录音自动发现（合成音频）") {
                result.put("stage", "discover_and_transcribe"); report("auto_discovery_check", result)
                AutoImportSettings.requestScan(ctx)
                val deadline = android.os.SystemClock.elapsedRealtime() + 210_000L
                val queue = RecordingQueue.of(ctx)
                while (android.os.SystemClock.elapsedRealtime() < deadline && queue.status(item.key) !in listOf("done", "error")) Thread.sleep(1000)
                assertEquals("Automatic discovery/worker did not complete", "done", queue.status(item.key))
                val record = LocalStore.of(ctx).get(id)
                assertNotNull(record)
                assertTrue("Synthetic speech was not transcribed", record!!.optString("text").contains("报告"))
                result.put("ok", true).put("stage", "complete").put("transcriptCharacters", record.optString("text").length)
            }
        } finally {
            try {
                result.put("processingElapsedMs", android.os.SystemClock.elapsedRealtime() - started)
                file.delete() // Only the UUID-named synthetic fixture created by this test.
                result.put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
                    .put("settingsUnchanged", before.toString() == AutoImportSettings.status(ctx).toString())
                report("auto_discovery_check", result)
            } finally {
                ins.runOnMainSync { activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); activity.finish() }
                KeepAwake.release(ctx)
            }
        }
    }

    /** Production discovery -> durable queue -> actual WorkManager worker -> ASR -> local DB. */
    @Test(timeout = 360_000) fun queuedHistoricalRecording() {
        org.junit.Assume.assumeTrue("Explicit -e importExisting one is required",
            InstrumentationRegistry.getArguments().getString("importExisting") == "one")
        val before = AutoImportSettings.status(ctx)
        val inventory = RecordingSources.collect(ctx)
        val queue = RecordingQueue.of(ctx)
        val db = LocalStore.of(ctx)
        val result = JSONObject().put("route", "production_recording_queue").put("found", inventory.files.size)
            .put("directFiles", inventory.directCount).put("treeFiles", inventory.treeCount)
            .put("acceleration", LocalSettings.prefs(ctx).getBoolean("asrAcceleration", false)).put("stage", "select")
        val now = System.currentTimeMillis()
        var duration = 0L
        val sample = inventory.files.filter { !it.source.startsWith("content://") && it.modified < now - 60_000 &&
            it.bytes in 8192L..8_388_608L && queue.status(it.key) == null && RecordingSources.savedRecording(ctx, it) == null }
            .sortedBy { it.bytes }.take(80).firstOrNull { item ->
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(item.source)
                    val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    if (ms in 8_000L..60_000L) { duration = ms; true } else false
                } catch (_: Exception) { false } finally { retriever.release() }
            }
        assertNotNull("No unimported 8–60 second sample available", sample)
        val item = sample!!
        val id = "rec-" + LocalStore.hash(LocalSettings.device(ctx) + ":" + item.key)
        val started = android.os.SystemClock.elapsedRealtime()
        result.put("durationMs", duration).put("bytes", item.bytes).put("stage", "queued")
        try {
            KeepAwake.hold(ctx, "验证自动转写队列（单条）") {
                assertTrue(queue.add(item, false))
                assertFalse("Discovery dedup failed", queue.add(item, false))
                RecordingQueueWorker.schedule(ctx)
                report("queue_import_check", result)
                val deadline = android.os.SystemClock.elapsedRealtime() + 300_000L
                while (android.os.SystemClock.elapsedRealtime() < deadline && queue.status(item.key) !in listOf("done", "error")) Thread.sleep(1000)
                result.put("queueState", queue.status(item.key)).put("stage", "verify")
                assertEquals("Production queue did not complete", "done", queue.status(item.key))
                val rec = db.get(id)
                assertNotNull("Missing local transcript", rec)
                result.put("localSaved", true).put("recordStatus", rec!!.optString("status"))
                    .put("transcriptCharacters", if (rec.optString("status") == "silent") 0 else rec.optString("text").length)
                assertFalse("Completed queue item was duplicated", queue.add(item))
                val original = File(item.source)
                assertTrue(original.isFile && original.length() == item.bytes && original.lastModified() == item.modified)
                result.put("originalUnchanged", true).put("duplicateAvoided", true).put("ok", true).put("stage", "complete")
            }
        } catch (e: Throwable) {
            result.put("ok", false).put("failureType", e.javaClass.simpleName)
            throw AssertionError("Queue test failed at ${result.optString("stage")}: ${e.javaClass.simpleName}")
        } finally {
            result.put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
                .put("settingsUnchanged", before.toString() == AutoImportSettings.status(ctx).toString())
            report("queue_import_check", result)
        }
    }

    /** Explicitly tests an existing SAF recording through the real persistent-import queue. */
    @Test(timeout = 240_000) fun importExistingTreeRecording() {
        org.junit.Assume.assumeTrue("Explicit -e importExisting one is required",
            InstrumentationRegistry.getArguments().getString("importExisting") == "one")
        val before = AutoImportSettings.status(ctx)
        val files = treeFiles()
        val result = treeInventory(files).put("route", "saf_persistent_import_queue").put("stage", "select")
        val now = System.currentTimeMillis()
        val since = before.getLong("since").takeIf { it > 0 } ?: now - 60_000
        var duration = 0L
        val sample = files.filter { it.modified < since && it.modified <= now - 60_000 && it.bytes in 8192L..8_388_608L }
            .sortedBy { it.bytes }.take(60).firstOrNull { entry ->
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(ctx, entry.uri)
                    val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    if (ms in 8_000L..120_000L) { duration = ms; true } else false
                } catch (_: Exception) { false } finally { retriever.release() }
            }
        if (sample == null) {
            report("history_import_check", result.put("ok", false).put("reason", "no_short_historical_sample"))
            fail("No 8–120 second historical recording available in the authorized directory")
            return
        }
        val db = LocalStore.of(ctx)
        val idsBefore = db.all("recording").map { it.getString("id") }.toSet()
        val imports = File(ctx.filesDir, "imports")
        val copiesBefore = imports.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val started = android.os.SystemClock.elapsedRealtime()
        result.put("sampleExtension", sample.name.substringAfterLast('.').lowercase()).put("sampleBytes", sample.bytes)
            .put("sampleDurationMs", duration)
        try {
            KeepAwake.hold(ctx, "检查授权目录已有录音（单条）") {
                result.put("stage", "enqueue")
                LocalImport.audio(ctx, sample.uri)
                val newCopies = imports.listFiles()?.filter { it.extension == "audio" && it.name !in copiesBefore } ?: emptyList()
                result.put("durableCopyObserved", newCopies.any { it.length() == sample.bytes })
                    .put("stage", "waiting_for_asr")
                report("history_import_check", result)
                val deadline = android.os.SystemClock.elapsedRealtime() + 180_000L
                var record: JSONObject? = null
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    record = db.all("recording").firstOrNull { it.getString("id") !in idsBefore && it.optString("title") == sample.name }
                    if (record != null) break
                    Thread.sleep(1000)
                }
                assertNotNull("The durable import did not reach the local recording database within 180 seconds", record)
                result.put("localSaved", true).put("recordStatus", record!!.optString("status"))
                    .put("transcriptCharacters", if (record.optString("status") == "silent") 0 else record.optString("text").length)
                repeat(10) { if (newCopies.any { it.exists() }) Thread.sleep(500) }
                result.put("importCopyCleanedAfterSave", newCopies.none { it.exists() })
                var unchanged = false
                ctx.contentResolver.query(sample.uri, arrayOf("_size", "last_modified"), null, null, null)?.use { c ->
                    if (c.moveToFirst()) unchanged = c.getLong(0) == sample.bytes && c.getLong(1) == sample.modified
                }
                assertTrue("Original recording changed", unchanged)
                result.put("originalUnchanged", true).put("ok", true).put("stage", "complete")
            }
        } catch (e: Throwable) {
            result.put("ok", false).put("failureType", e.javaClass.simpleName)
            throw AssertionError("SAF history import failed at ${result.optString("stage")}: ${e.javaClass.simpleName}")
        } finally {
            val after = AutoImportSettings.status(ctx)
            result.put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
                .put("pendingImportCopiesAfter", imports.listFiles { f -> f.extension == "audio" }?.size ?: 0)
                .put("settingsUnchanged", listOf("enabled", "since", "days", "syncEnabled").all { before.get(it) == after.get(it) })
            report("history_import_check", result)
        }
    }

    /** Exercises the same existing-file ASR + persistence path used by AutoScanWorker.scanLocal, one file only. */
    @Test(timeout = 240_000) fun importExistingRecording() {
        org.junit.Assume.assumeTrue("Explicit -e importExisting one is required",
            InstrumentationRegistry.getArguments().getString("importExisting") == "one")
        val before = AutoImportSettings.status(ctx)
        val files = recordingFiles()
        val result = safeInventory(files).put("route", "history_scan_local_batch").put("stage", "select")
        val db = LocalStore.of(ctx)
        val now = System.currentTimeMillis()
        val since = before.getLong("since").takeIf { it > 0 } ?: now - 60_000
        var duration = 0L
        val sample = files.filter { it.lastModified() < since && it.lastModified() <= now - 60_000 && it.length() in 8192L..8_388_608L }
            .sortedBy { it.length() }.take(60).firstOrNull { f ->
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(f.absolutePath)
                    val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    if (ms in 8_000L..120_000L) { duration = ms; true } else false
                } catch (_: Exception) { false } finally { retriever.release() }
            }
        if (sample == null) {
            report("history_import_check", result.put("ok", false).put("reason", "no_short_historical_sample"))
            fail("No 8–120 second historical recording available for bounded verification")
            return
        }
        val bytes = sample.length(); val modified = sample.lastModified()
        val key = "${sample.absolutePath}|$bytes|$modified"
        val id = "rec-" + LocalStore.hash(LocalSettings.device(ctx) + ":" + key)
        val alreadyImported = db.get(id) != null
        val started = android.os.SystemClock.elapsedRealtime()
        result.put("sampleExtension", sample.extension.lowercase()).put("sampleBytes", bytes)
            .put("sampleDurationMs", duration).put("alreadyImported", alreadyImported)
        try {
            KeepAwake.hold(ctx, "检查已有录音导入（单条）") { ModelGate.hold {
                result.put("stage", "transcribe")
                PhoneAsrBatch(ctx).use { batch ->
                    val outcome = batch.process(sample, key, sample.name, java.time.Instant.ofEpochMilli(modified).toString()) {}
                    result.put("outcome", outcome.name)
                    assertTrue("Existing recording did not import", outcome == PhoneAsrBatch.Outcome.SENT || outcome == PhoneAsrBatch.Outcome.SILENT)
                    val record = db.get(id)
                    assertNotNull("Imported recording missing from local database", record)
                    result.put("localSaved", true).put("recordStatus", record!!.optString("status"))
                        .put("transcriptCharacters", if (record.optString("status") == "silent") 0 else record.optString("text").length)
                    val count = db.all("recording").size
                    assertEquals(PhoneAsrBatch.Outcome.SENT, batch.process(sample, key, sample.name, null) {})
                    assertEquals("Retry duplicated a record", count, db.all("recording").size)
                    result.put("duplicateAvoided", true)
                }
            } }
            assertTrue(sample.exists() && sample.length() == bytes && sample.lastModified() == modified)
            result.put("originalUnchanged", true).put("ok", true).put("stage", "complete")
        } catch (e: Throwable) {
            result.put("ok", false).put("failureType", e.javaClass.simpleName)
            // No exception details containing a private file path or transcription are emitted.
            throw AssertionError("Historical import check failed at ${result.optString("stage")}: ${e.javaClass.simpleName}")
        } finally {
            val after = AutoImportSettings.status(ctx)
            result.put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
                .put("settingsUnchanged", listOf("enabled", "since", "days", "syncEnabled").all { before.get(it) == after.get(it) })
            report("history_import_check", result)
        }
    }

    @Test fun autoImport() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val ctx = ins.targetContext
        val mode = InstrumentationRegistry.getArguments().getString("autoImport", "inspect")
        val syncBefore = LocalSettings.sync(ctx)
        if (mode in listOf("new", "recent", "all") && InstrumentationRegistry.getArguments().getString("cleanupSynthetic") == "yes") cleanupSyntheticQueue()
        when (mode) {
            "new" -> AutoImportSettings.configure(ctx, true, false)
            "all" -> AutoImportSettings.configure(ctx, true, true)
            "recent" -> AutoImportSettings.enable(ctx, "recent", InstrumentationRegistry.getArguments().getString("days", "60").toInt())
            "off" -> AutoImportSettings.configure(ctx, false)
            "await_choice" -> {
                // Undo only the operator's unconfirmed beta2 60-day setup, never a changed user setting.
                val p = ctx.getSharedPreferences("rh_prefs", android.content.Context.MODE_PRIVATE)
                require(!p.getBoolean("autoScan", false) && p.getLong("scanSince", 0) == 0L &&
                    p.getString("scanDays", "0") == "60" && !p.contains("scanScope")) { "Settings changed; do not overwrite the user's choice" }
                AutoImportSettings.pause(ctx, true)
                check(p.edit().remove("scanSince").remove("scanScope").remove("recentScanDays").putString("scanDays", "0").commit())
                assertNull(com.bob.recordinghelper.Prefs.scanScope(ctx))
            }
            "inspect" -> Unit
            else -> error("Unknown autoImport argument")
        }
        assertEquals("Setup must not change sync preferences", syncBefore, LocalSettings.sync(ctx))
        if (mode in listOf("new", "recent", "all") && InstrumentationRegistry.getArguments().getString("retryQueue") == "yes") {
            RecordingQueue.of(ctx).retryErrors(); RecordingQueueWorker.schedule(ctx)
        }
        val status = AutoImportSettings.scheduledStatus(ctx)
        if (mode == "new" || mode == "all" || mode == "recent") {
            assertTrue(status.getBoolean("enabled"))
            assertTrue(status.getBoolean("hasAccess"))
            assertTrue("No recorder directory available", status.getInt("authorizedTrees") > 0 || status.getInt("readableDirectoryCount") > 0)
            assertEquals(1, status.getInt("scheduledJobs"))
            if (mode == "new") assertTrue(status.getLong("since") > 0)
            if (mode == "recent") { assertEquals(0L, status.getLong("since")); assertTrue(status.getInt("days") > 0) }
        }
        ins.sendStatus(0, android.os.Bundle().apply { putString("auto_import", status.toString()) })
    }
}
