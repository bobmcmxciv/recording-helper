package com.bob.recordinghelper.local

import android.content.Context
import android.os.Environment
import com.bob.recordinghelper.Prefs
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cross-version and cross-install state survival mirror:
 * 1. Recovers legacy 1.x / 2.0-beta state (`filesDir/scan-ledger.txt`, `SharedPreferences("ledger")`,
 *    `filesDir/polish-queue/` JSON jobs, and `todo-done-ids.txt`).
 * 2. Mirrors `state-v2.json` (recordings + todos), `scan-ledger.txt`, and `asr-partial/` checkpoints to
 *    shared storage (`Recordings/.recording-helper-state/` when file access is granted) and `getExternalFilesDir`.
 *    Even if the user switches APKs or reinstalls a test build, already-transcribed recordings, processed todos,
 *    and in-progress partial transcriptions are automatically restored without re-converting from scratch.
 */
object StateMirror {
    private const val DIR_NAME = ".recording-helper-state"
    private const val STATE_FILE = "state-v2.json"
    private const val LEDGER_FILE = "scan-ledger.txt"
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rh-state-mirror").apply { isDaemon = true }
    }
    private val savePending = AtomicBoolean(false)
    @Volatile private var hydratedInternal = false
    @Volatile private var hydratedExternal = false

    fun mirrorDirs(ctx: Context): List<File> {
        val app = ctx.applicationContext
        val out = ArrayList<File>(2)
        if (runCatching { AutoImportSettings.hasFileAccess(app) }.getOrDefault(false)) {
            runCatching {
                val ext = File(Environment.getExternalStorageDirectory(), "Recordings/$DIR_NAME")
                if (ext.exists() || ext.mkdirs()) {
                    val nomedia = File(ext, ".nomedia")
                    if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
                    out.add(ext)
                }
            }
        }
        runCatching {
            app.getExternalFilesDir(null)?.let { base ->
                val dir = File(base, "state-mirror")
                if (dir.exists() || dir.mkdirs()) out.add(dir)
            }
        }
        return out
    }

    fun partialFile(ctx: Context, fileName: String): File? {
        val dir = mirrorDirs(ctx).firstOrNull() ?: return null
        return File(dir, "asr-partial/$fileName")
    }

    fun readLedger(ctx: Context): List<String> {
        val app = ctx.applicationContext
        val set = LinkedHashSet<String>()
        val v1File = File(app.filesDir, "scan-ledger.txt")
        if (v1File.isFile) {
            runCatching { v1File.forEachLine { if (it.isNotBlank()) set.add(it.trim()) } }
        }
        for (dir in mirrorDirs(app)) {
            val f = File(dir, LEDGER_FILE)
            if (f.isFile) {
                runCatching { f.forEachLine { if (it.isNotBlank()) set.add(it.trim()) } }
            }
        }
        return set.toList()
    }

    fun appendLedger(ctx: Context, key: String) {
        if (key.isBlank() || '\n' in key) return
        val line = "$key\n"
        for (dir in mirrorDirs(ctx)) {
            runCatching {
                File(dir, LEDGER_FILE).appendText(line)
            }
        }
    }

    fun readDoneTodoIds(ctx: Context): Set<String> {
        val app = ctx.applicationContext
        val candidates = buildList {
            add(File(app.filesDir, "todo-done-ids.txt"))
            app.getExternalFilesDir(null)?.let { add(File(it, "todo-done-ids.txt")) }
            for (dir in mirrorDirs(app)) add(File(dir, "todo-done-ids.txt"))
        }
        val ids = HashSet<String>()
        for (f in candidates) {
            if (f.isFile && f.canRead()) {
                runCatching {
                    f.forEachLine { line ->
                        val id = line.trim()
                        if (id.startsWith("todo-")) ids.add(id)
                    }
                }
            }
        }
        return ids
    }

    @Synchronized
    fun hydrateIfNeeded(ctx: Context) {
        val app = ctx.applicationContext
        val hasExt = runCatching { AutoImportSettings.hasFileAccess(app) }.getOrDefault(false)
        if (hydratedInternal && (!hasExt || hydratedExternal)) return
        hydratedInternal = true
        if (hasExt) hydratedExternal = true

        val store = LocalStore.of(app)
        var changed = false

        // 1. Merge 1.x ledger & external mirror ledger into ScanLedger
        runCatching {
            val extraKeys = readLedger(app)
            if (extraKeys.isNotEmpty()) {
                Prefs.ledgerMergeAll(app, extraKeys)
            }
        }

        // 2. Recover transcripts sitting in legacy filesDir/polish-queue/
        runCatching {
            val polishDir = File(app.filesDir, "polish-queue")
            val files = polishDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()
            if (files.isNotEmpty()) {
                val existingTitles = store.all("recording").mapTo(HashSet()) { it.optString("title") }
                for (f in files) {
                    runCatching {
                        val o = JSONObject(f.readText())
                        val title = o.optString("title").trim()
                        val text = o.optString("text").trim()
                        val enq = o.optLong("enqueuedAt", System.currentTimeMillis())
                        if (text.isNotEmpty() && (title.isEmpty() || title !in existingTitles)) {
                            val occurredAt = java.time.Instant.ofEpochMilli(enq).toString()
                            store.transcript(
                                key = "legacy-polish:${o.optString("id").ifBlank { f.nameWithoutExtension }}",
                                title = title.ifBlank { "历史转写记录" },
                                text = text,
                                occurredAt = occurredAt,
                            )
                            if (title.isNotEmpty()) existingTitles.add(title)
                            changed = true
                        }
                    }
                }
            }
        }

        // 3. Apply todo-done-ids.txt if present (before backup merge so externalDoneTodoIds is populated)
        runCatching {
            val doneIds = readDoneTodoIds(app)
            if (doneIds.isNotEmpty() && store.applyDoneTodoIds(doneIds) > 0) {
                changed = true
            }
        }

        // 4. Restore recordings and todos from external state-v2.json mirror if present
        for (dir in mirrorDirs(app)) {
            val stateFile = File(dir, STATE_FILE)
            if (stateFile.isFile && stateFile.length() > 0) {
                runCatching {
                    val summary = store.importBackup(stateFile.readText(), triggerMirror = false)
                    if (summary.recordingsImported > 0 || summary.todosImported > 0 || summary.todosSkippedOrDone > 0) {
                        changed = true
                    }
                }
            }
        }

        // 5. Ensure all saved recordings with sourceKey are in ScanLedger
        runCatching {
            val recKeys = store.all("recording").mapNotNull { it.optString("sourceKey").takeIf { k -> k.isNotBlank() } }
            if (recKeys.isNotEmpty()) {
                Prefs.ledgerMergeAll(app, recKeys)
            }
        }

        if (changed || store.all("recording").isNotEmpty() || store.all("todo").isNotEmpty()) {
            saveStateAsync(app)
        }
    }

    fun saveStateAsync(ctx: Context) {
        val app = ctx.applicationContext
        if (!savePending.compareAndSet(false, true)) return
        ioExecutor.execute {
            try {
                Thread.sleep(400)
            } catch (_: InterruptedException) {
            } finally {
                savePending.set(false)
            }
            saveStateNow(app)
        }
    }

    fun saveStateNow(ctx: Context) {
        val app = ctx.applicationContext
        val dirs = mirrorDirs(app)
        if (dirs.isEmpty()) return
        val json = runCatching { LocalStore.of(app).export() }.getOrNull() ?: return
        val ledgerKeys = runCatching { Prefs.ledgerAllKeys(app) }.getOrDefault(emptySet())
        val ledgerText = if (ledgerKeys.isNotEmpty()) ledgerKeys.joinToString("\n") + "\n" else ""
        for (dir in dirs) {
            runCatching {
                val tmp = File(dir, "$STATE_FILE.tmp")
                val dst = File(dir, STATE_FILE)
                tmp.writeText(json)
                if (!tmp.renameTo(dst)) {
                    dst.writeText(json)
                    tmp.delete()
                }
            }
            if (ledgerText.isNotEmpty()) {
                runCatching {
                    val ledgerFile = File(dir, LEDGER_FILE)
                    if (!ledgerFile.exists() || ledgerFile.length() < ledgerText.length) {
                        val tmp = File(dir, "$LEDGER_FILE.tmp")
                        tmp.writeText(ledgerText)
                        if (!tmp.renameTo(ledgerFile)) {
                            ledgerFile.writeText(ledgerText)
                            tmp.delete()
                        }
                    }
                }
            }
        }
    }
}
