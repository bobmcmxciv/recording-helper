package com.bob.recordinghelper.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.bob.recordinghelper.ScanLedger
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Durable local source of truth. Sync never owns or deletes the local transcript. */
class LocalStore private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "local-v2.db", null, 1) {
    private val appCtx = ctx.applicationContext
    private val ownerDevice = LocalSettings.device(ctx)
    private val externalDoneTodoIds = HashSet<String>()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE items(id TEXT PRIMARY KEY, kind TEXT NOT NULL, body TEXT NOT NULL, version INTEGER NOT NULL, rev INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE TABLE conflicts(id INTEGER PRIMARY KEY AUTOINCREMENT, item_id TEXT, body TEXT, created INTEGER)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    /** Live items only; tombstones (deleted=true) stay in the table so that sync can propagate them. */
    @Synchronized fun all(kind: String): List<JSONObject> = readableDatabase.rawQuery(
        "SELECT body FROM items WHERE kind=? ORDER BY rowid DESC", arrayOf(kind)).use { c ->
        buildList { while (c.moveToNext()) { val o = JSONObject(c.getString(0)); if (!o.optBoolean("deleted")) add(o) } }
    }

    /** All items of [kind], including tombstoned (`deleted=true`) entries so deleted todos/recordings never resurrect. */
    @Synchronized fun allIncludingDeleted(kind: String): List<JSONObject> = readableDatabase.rawQuery(
        "SELECT body FROM items WHERE kind=? ORDER BY rowid DESC", arrayOf(kind)).use { c ->
        buildList { while (c.moveToNext()) add(JSONObject(c.getString(0))) }
    }

    @Synchronized fun get(id: String): JSONObject? = readableDatabase.rawQuery(
        "SELECT body FROM items WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }

    @Synchronized fun recordingIds(): Set<String> = readableDatabase.rawQuery(
        "SELECT id FROM items WHERE kind='recording'", null).use { c ->
        HashSet<String>(c.count).apply { while (c.moveToNext()) add(c.getString(0)) }
    }

    @Synchronized fun savedLookup(primaryPath: String, currentOwner: String = ownerDevice): SavedLookup =
        SavedLookup(allIncludingDeleted("recording"), primaryPath, currentOwner)

    @Synchronized fun put(kind: String, body: JSONObject) {
        val id = body.getString("id")
        writableDatabase.execSQL("INSERT INTO items(id,kind,body,version) VALUES(?,?,?,1) ON CONFLICT(id) DO UPDATE SET body=excluded.body, version=version+1, dirty=1",
            arrayOf(id, kind, body.toString()))
    }

    @Synchronized fun transcript(
        key: String,
        title: String,
        text: String,
        occurredAt: String?,
        sourceKey: String? = null,
        audioBytes: Long = 0L,
    ): String {
        val id = "rec-" + hash(key)
        val existingById = get(id)
        if (existingById != null) {
            if (!existingById.optBoolean("deleted")) {
                var dirty = false
                if (existingById.optString("text").isBlank() && text.isNotBlank()) {
                    existingById.put("text", text); dirty = true
                }
                if (!sourceKey.isNullOrBlank() && existingById.optString("sourceKey").isBlank()) {
                    existingById.put("sourceKey", sourceKey); dirty = true
                }
                if (audioBytes > 0L && existingById.optLong("audioBytes", 0L) <= 0L) {
                    existingById.put("audioBytes", audioBytes); dirty = true
                }
                if (dirty) {
                    put("recording", existingById)
                    StateMirror.saveStateAsync(appCtx)
                }
            }
            return id
        }
        // Check if the same recording already exists under a different ownerDevice UUID or path alias
        val matched = findExistingRecording(title, sourceKey, audioBytes)
        if (matched != null) {
            val matchedId = matched.getString("id")
            if (!matched.optBoolean("deleted")) {
                var dirty = false
                if (matched.optString("text").isBlank() && text.isNotBlank()) {
                    matched.put("text", text); dirty = true
                }
                if (!sourceKey.isNullOrBlank() && matched.optString("sourceKey").isBlank()) {
                    matched.put("sourceKey", sourceKey); dirty = true
                }
                if (audioBytes > 0L && matched.optLong("audioBytes", 0L) <= 0L) {
                    matched.put("audioBytes", audioBytes); dirty = true
                }
                if (dirty) {
                    put("recording", matched)
                    StateMirror.saveStateAsync(appCtx)
                }
            }
            return matchedId
        }
        val body = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("text", text)
            .put("occurredAt", occurredAt ?: java.time.Instant.now().toString())
            .put("status", "pending")
            .put("ownerDevice", ownerDevice)
            .put("summary", "")
            .put("createdAt", System.currentTimeMillis())
        if (!sourceKey.isNullOrBlank()) body.put("sourceKey", sourceKey)
        if (audioBytes > 0L) body.put("audioBytes", audioBytes)
        put("recording", body)
        StateMirror.saveStateAsync(appCtx)
        return id
    }

    private fun findExistingRecording(title: String, sourceKey: String?, audioBytes: Long): JSONObject? {
        if (title.isBlank() && sourceKey.isNullOrBlank()) return null
        val allRecs = allIncludingDeleted("recording")
        if (!sourceKey.isNullOrBlank()) {
            allRecs.firstOrNull { it.optString("sourceKey") == sourceKey && (it.optBoolean("deleted") || it.optString("text").isNotBlank()) }
                ?.let { return it }
        }
        if (title.isNotBlank() && audioBytes > 0L) {
            allRecs.firstOrNull {
                it.optString("title") == title && it.optLong("audioBytes", 0L) == audioBytes &&
                    (it.optBoolean("deleted") || it.optString("text").isNotBlank())
            }?.let { return it }
        }
        if (title.isNotBlank() && ScanLedger.hasRecordingTime(title)) {
            allRecs.firstOrNull {
                it.optString("title") == title && (it.optBoolean("deleted") || it.optString("text").isNotBlank())
            }?.let { return it }
        }
        return null
    }

    @Synchronized fun status(id: String, status: String, error: String = "") {
        get(id)?.let {
            if (status == "pending") it.put("ownerDevice", ownerDevice)
            put("recording", it.put("status", status).put("error", error))
        }
    }

    /** Reset any orphaned 'analyzing' status left behind by a killed or cancelled worker back to 'pending'. */
    @Synchronized fun recoverAnalyzing(excludeId: String? = null) {
        for (r in all("recording")) {
            val id = r.optString("id")
            if (id != excludeId && r.optString("status") == "analyzing") {
                put("recording", r.put("status", "pending").put("error", "").put("ownerDevice", ownerDevice))
            }
        }
    }

    /** Re-queues recordings that only have rule-based quick extraction so a newly available LLM can deeply analyze them. */
    @Synchronized fun requeueQuickExtracted(): Int {
        var count = 0
        for (r in all("recording")) {
            if (r.optBoolean("quickExtracted") && r.optString("status") == "ready") {
                put("recording", r.put("status", "pending").put("error", "").put("ownerDevice", ownerDevice))
                count++
            }
        }
        return count
    }

    @Synchronized fun analyzed(id: String, polished: String, result: JSONObject) {
        val rec = get(id) ?: return
        val recTitle = rec.optString("title")
        val allRecsById = allIncludingDeleted("recording").associateBy { it.optString("id") }
        val allTodos = allIncludingDeleted("todo")
        val existingForRecByNorm = HashMap<String, JSONObject>()
        val processedNormTitles = HashSet<String>()
        for (td in allTodos) {
            val norm = normalizeTodoTitle(td.optString("title"))
            if (norm.isEmpty()) continue
            val tdRecId = td.optString("recordingId")
            val tdRecTitle = allRecsById[tdRecId]?.optString("title").orEmpty()
            val isProcessed = td.optBoolean("done") || td.optBoolean("reviewed") || td.optBoolean("deleted")
            if (tdRecId == id) {
                existingForRecByNorm.putIfAbsent(norm, td)
                if (isProcessed) processedNormTitles.add(norm)
            } else if (recTitle.isNotBlank() && tdRecTitle == recTitle && isProcessed) {
                processedNormTitles.add(norm)
            } else if (isProcessed && norm.length >= 6) {
                processedNormTitles.add(norm)
            }
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            rec.put("polished", polished)
                .put("summary", result.getString("summary"))
                .put("status", "ready")
                .put("error", "")
                .put("analyzedAt", System.currentTimeMillis())
                .put("quickExtracted", result.optBoolean("quickExtracted", false))
            if (result.has("analysisTrace")) rec.put("analysisTrace", result.getString("analysisTrace"))
            if (result.has("analysisModel")) rec.put("analysisModel", result.getString("analysisModel"))
            if (result.has("analysisStats")) rec.put("analysisStats", result.getString("analysisStats"))
            put("recording", rec)
            val keepTids = HashSet<String>()
            val todos = result.getJSONArray("todos")
            for (i in 0 until todos.length()) {
                val t = todos.getJSONObject(i)
                val rawTitle = t.optString("title").trim()
                if (rawTitle.isEmpty()) continue
                val norm = normalizeTodoTitle(rawTitle)
                val tid = "todo-" + hash("$id|$rawTitle")
                val isPreDone = tid in externalDoneTodoIds
                val existing = get(tid) ?: existingForRecByNorm[norm]
                if (existing != null) {
                    val existingId = existing.getString("id")
                    keepTids.add(existingId)
                    if (existing.optBoolean("deleted")) {
                        // Never resurrect a todo the user already deleted/dismissed.
                        continue
                    } else if (isPreDone && !existing.optBoolean("done")) {
                        put("todo", existing.put("done", true).put("reviewed", true))
                    } else if (existing.optBoolean("done") || existing.optBoolean("reviewed")) {
                        // Already processed by user: skip without overwriting status.
                        continue
                    } else {
                        put("todo", existing
                            .put("due", t.optString("due"))
                            .put("category", t.optString("category"))
                            .put("evidence", t.optString("evidence")))
                    }
                } else {
                    if (norm in processedNormTitles) {
                        // Already processed/done/dismissed under an earlier recording ID or version: skip!
                        continue
                    }
                    keepTids.add(tid)
                    put("todo", JSONObject(t.toString()).put("id", tid).put("recordingId", id)
                        .put("done", isPreDone).put("reviewed", isPreDone).put("deleted", false)
                        .put("createdAt", System.currentTimeMillis()))
                }
            }
            for (old in todosFor(id)) {
                val oldId = old.optString("id")
                if (oldId !in keepTids && !old.optBoolean("reviewed") && !old.optBoolean("done")) {
                    put("todo", old.put("deleted", true))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        StateMirror.saveStateAsync(appCtx)
    }

    @Synchronized fun applyDoneTodoIds(doneIds: Set<String>): Int {
        if (doneIds.isEmpty()) return 0
        externalDoneTodoIds.addAll(doneIds)
        var updated = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in doneIds) {
                val existing = get(id) ?: continue
                if (!existing.optBoolean("done") || !existing.optBoolean("reviewed")) {
                    put("todo", existing.put("done", true).put("reviewed", true))
                    updated++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (updated > 0) StateMirror.saveStateAsync(appCtx)
        return updated
    }

    data class ImportSummary(val recordingsImported: Int, val todosImported: Int, val todosSkippedOrDone: Int)

    @Synchronized fun importBackup(rawJson: String, triggerMirror: Boolean = true): ImportSummary {
        val merged = mergeBackupObjects(
            existingRecordings = allIncludingDeleted("recording"),
            existingTodos = allIncludingDeleted("todo"),
            rawJson = rawJson,
            defaultOwner = ownerDevice,
            doneTodoIds = externalDoneTodoIds,
        )
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (rec in merged.recordingsToWrite) {
                put("recording", rec)
            }
            for (todo in merged.todosToWrite) {
                put("todo", todo)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (triggerMirror && (merged.recordingsToWrite.isNotEmpty() || merged.todosToWrite.isNotEmpty())) {
            StateMirror.saveStateAsync(appCtx)
        }
        return ImportSummary(
            recordingsImported = merged.recordingsToWrite.size,
            todosImported = merged.todosToWrite.size,
            todosSkippedOrDone = merged.todosSkippedOrDone,
        )
    }

    @Synchronized fun toggle(id: String) {
        get(id)?.let {
            put("todo", it.put("done", !it.optBoolean("done")).put("reviewed", true))
            StateMirror.saveStateAsync(appCtx)
        }
    }
    @Synchronized fun setDone(id: String, done: Boolean) {
        get(id)?.let {
            put("todo", it.put("done", done).put("reviewed", true))
            StateMirror.saveStateAsync(appCtx)
        }
    }
    @Synchronized fun review(id: String) {
        get(id)?.let {
            if (!it.optBoolean("reviewed")) {
                put("todo", it.put("reviewed", true))
                StateMirror.saveStateAsync(appCtx)
            }
        }
    }
    @Synchronized fun setCategory(id: String, category: String) {
        get(id)?.let {
            put("todo", it.put("category", LocalAnalysis.category(category)).put("reviewed", true))
            StateMirror.saveStateAsync(appCtx)
        }
    }
    @Synchronized fun setDue(id: String, due: String) {
        get(id)?.let {
            put("todo", it.put("due", due.trim()).put("reviewed", true))
            StateMirror.saveStateAsync(appCtx)
        }
    }
    @Synchronized fun updateTodo(id: String, title: String, due: String) {
        get(id)?.let {
            put("todo", it.put("title", title.trim()).put("due", due.trim()).put("reviewed", true))
            StateMirror.saveStateAsync(appCtx)
        }
    }
    fun addTodo(title: String, due: String = ""): String {
        val id = "todo-" + UUID.randomUUID()
        put("todo", JSONObject().put("id", id).put("title", title.trim()).put("due", due.trim())
            .put("done", false).put("reviewed", true).put("createdAt", System.currentTimeMillis()))
        StateMirror.saveStateAsync(appCtx)
        return id
    }
    @Synchronized fun todosFor(recordingId: String): List<JSONObject> = all("todo").filter { it.optString("recordingId") == recordingId }

    /** Tombstone instead of a row delete, so that every synced device learns about it. */
    @Synchronized fun delete(id: String) {
        val item = get(id) ?: return
        val kind = if (id.startsWith("rec-")) "recording" else "todo"
        put(kind, item.put("deleted", true))
        if (kind == "recording") todosFor(id).forEach { put("todo", it.put("deleted", true)) }
        StateMirror.saveStateAsync(appCtx)
    }
    @Synchronized fun pending(): List<JSONObject> = readableDatabase.rawQuery(
        "SELECT id,kind,body,version,rev FROM items WHERE dirty=1 LIMIT 50", null).use { c -> buildList {
        while (c.moveToNext()) add(JSONObject().put("id", c.getString(0)).put("kind", c.getString(1))
            .put("body", JSONObject(c.getString(2))).put("localVersion", c.getLong(3)).put("baseRev", c.getLong(4)))
    } }
    @Synchronized fun receive(item: JSONObject, sentVersion: Long? = null, conflict: Boolean = false) {
        val id = item.getString("id")
        val db = writableDatabase
        db.beginTransaction()
        try {
            var version = 0L; var dirty = false; var oldRev = 0L
            db.rawQuery("SELECT version,dirty,rev FROM items WHERE id=?", arrayOf(id)).use {
                if (it.moveToFirst()) { version = it.getLong(0); dirty = it.getInt(1) == 1; oldRev = it.getLong(2) }
            }
            val rev = item.getLong("rev")
            if (rev < oldRev) return
            // Edits made during a network request must not be overwritten by its acknowledgement.
            if (dirty && (sentVersion == null || version != sentVersion)) {
                db.execSQL("UPDATE items SET rev=? WHERE id=?", arrayOf(rev, id))
            } else {
                if (conflict) get(id)?.let { old -> db.insert("conflicts", null, ContentValues().apply {
                    put("item_id", id); put("body", old.toString()); put("created", System.currentTimeMillis())
                }) }
                db.insertWithOnConflict("items", null, ContentValues().apply {
                    put("id", id); put("kind", item.getString("kind")); put("body", item.getJSONObject("body").toString())
                    put("version", version + 1); put("rev", rev); put("dirty", 0)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun export(): String {
        val conflicts = JSONArray()
        readableDatabase.rawQuery("SELECT item_id,body,created FROM conflicts ORDER BY id", null).use { c ->
            while (c.moveToNext()) conflicts.put(JSONObject().put("id", c.getString(0)).put("body", JSONObject(c.getString(1))).put("createdAt", c.getLong(2)))
        }
        return JSONObject().put("schema", 2).put("recordings", JSONArray(all("recording")))
            .put("todos", JSONArray(all("todo"))).put("conflicts", conflicts).toString(2)
    }
    @Synchronized fun resetSync() { writableDatabase.execSQL("UPDATE items SET rev=0, dirty=1") }

    /**
     * Device-UUID-independent index of already-transcribed recordings so switching APKs, restoring backups,
     * changing storage paths (`/sdcard/` vs `/storage/emulated/0/` vs SAF), or changing device UUIDs never
     * triggers duplicate re-transcription.
     */
    class SavedLookup(
        recordings: List<JSONObject>,
        private val primaryPath: String,
        private val currentOwner: String,
    ) {
        private val byId = HashMap<String, JSONObject>(recordings.size * 2)
        private val byAlias = HashMap<String, JSONObject>(recordings.size * 4)
        private val byTitleBytes = HashMap<String, JSONObject>(recordings.size * 2)
        private val byTitle = HashMap<String, JSONObject>(recordings.size * 2)

        init {
            for (r in recordings) {
                val hasContent = r.optBoolean("deleted") || r.optString("text").isNotBlank() || r.optString("status") in listOf("ready", "silent")
                if (!hasContent) continue
                val id = r.optString("id")
                if (id.isNotBlank()) byId.putIfAbsent(id, r)
                val title = r.optString("title")
                val bytes = r.optLong("audioBytes", 0L)
                if (title.isNotBlank() && bytes > 0L) {
                    byTitleBytes.putIfAbsent("$title|$bytes", r)
                }
                if (title.isNotBlank() && (ScanLedger.hasRecordingTime(title) || title.contains('.') || title.length >= 8)) {
                    byTitle.putIfAbsent(title, r)
                }
                val sk = r.optString("sourceKey")
                if (sk.isNotBlank()) {
                    byAlias.putIfAbsent(sk, r)
                    val ps = ScanLedger.pathSizeOf(sk)
                    if (ps != null) {
                        val path = ps.substringBeforeLast('|')
                        val b = ps.substringAfterLast('|').toLongOrNull() ?: bytes
                        val mtime = sk.substringAfterLast('|').toLongOrNull() ?: 0L
                        if (b > 0L) {
                            for (a in RecordingSources.allAliases(path, b, mtime, primaryPath)) {
                                byAlias.putIfAbsent(a, r)
                            }
                            val name = path.substringAfterLast('/')
                            if (name.isNotBlank()) byTitleBytes.putIfAbsent("$name|$b", r)
                        }
                    }
                }
            }
        }

        fun find(item: RecordingSource): JSONObject? {
            val path = item.key.removeSuffix("|${item.bytes}|${item.modified}")
            val aliases = RecordingSources.allAliases(path, item.bytes, item.modified, primaryPath)
            for (a in aliases) {
                byId["rec-" + hash("$currentOwner:$a")]?.let { return it }
                byAlias[a]?.let { return it }
            }
            if (item.title.isNotBlank() && item.bytes > 0L) {
                byTitleBytes["${item.title}|${item.bytes}"]?.let { return it }
            }
            if (item.title.isNotBlank() && (ScanLedger.hasRecordingTime(item.title) || item.title.contains('.') || item.title.length >= 8)) {
                byTitle[item.title]?.takeIf {
                    val savedBytes = it.optLong("audioBytes", 0L)
                    savedBytes <= 0L || item.bytes <= 0L || savedBytes == item.bytes
                }?.let { return it }
            }
            return null
        }
    }

    data class MergeResult(
        val recordingsToWrite: List<JSONObject>,
        val todosToWrite: List<JSONObject>,
        val todosSkippedOrDone: Int,
    )

    companion object {
        @Volatile private var instance: LocalStore? = null
        fun of(ctx: Context): LocalStore = instance ?: synchronized(this) {
            instance ?: LocalStore(ctx.applicationContext).also { instance = it }
        }
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

        fun normalizeTodoTitle(raw: String): String =
            raw.trim().lowercase().replace(Regex("[\\s\\p{Punct}，。！？、；：“”‘’（）()【】\\[\\]《》·—\\-]+"), "")

        private fun statusRank(status: String): Int = when (status) {
            "ready" -> 4
            "silent" -> 3
            "analyzing" -> 2
            "pending" -> 1
            else -> 0
        }

        /** Pure JVM-testable backup & cross-version state merger. */
        fun mergeBackupObjects(
            existingRecordings: List<JSONObject>,
            existingTodos: List<JSONObject>,
            rawJson: String,
            defaultOwner: String,
            doneTodoIds: Set<String> = emptySet(),
        ): MergeResult {
            val root = JSONObject(rawJson.trim())
            val recById = HashMap<String, JSONObject>()
            val recByTitle = HashMap<String, JSONObject>()
            for (r in existingRecordings) {
                val id = r.optString("id")
                if (id.isNotBlank()) recById[id] = r
                val title = r.optString("title")
                if (title.isNotBlank()) recByTitle.putIfAbsent(title, r)
            }

            val recordingsToWrite = ArrayList<JSONObject>()
            val idRemap = HashMap<String, String>()
            val inRecs = root.optJSONArray("recordings") ?: JSONArray()
            for (i in 0 until inRecs.length()) {
                val raw = inRecs.optJSONObject(i) ?: continue
                val title = raw.optString("title").ifBlank {
                    raw.optString("original_name").ifBlank { raw.optString("filename") }
                }.trim()
                val text = raw.optString("text").ifBlank {
                    raw.optString("transcript").ifBlank { raw.optString("raw_text") }
                }.trim()
                val summary = raw.optString("summary").ifBlank { raw.optString("report_md") }.trim()
                val polished = raw.optString("polished").trim()
                val rawId = raw.optString("id").trim()
                val incomingId = if (rawId.startsWith("rec-")) rawId else "rec-" + hash(rawId.ifBlank { "$title|$text" })
                val rawStatus = raw.optString("status", if (summary.isNotBlank()) "ready" else "pending")
                val incomingStatus = when (rawStatus) {
                    "done", "ready" -> if (summary.isNotBlank()) "ready" else "pending"
                    "silent" -> "silent"
                    else -> "pending"
                }

                val existing = recById[incomingId] ?: (if (title.isNotBlank()) recByTitle[title] else null)
                if (existing != null) {
                    val canonId = existing.getString("id")
                    if (rawId.isNotBlank()) idRemap[rawId] = canonId
                    idRemap[incomingId] = canonId
                    if (existing.optBoolean("deleted")) continue
                    val copy = JSONObject(existing.toString())
                    var changed = false
                    if (copy.optString("text").isBlank() && text.isNotBlank()) {
                        copy.put("text", text); changed = true
                    }
                    if (copy.optString("polished").isBlank() && polished.isNotBlank()) {
                        copy.put("polished", polished); changed = true
                    }
                    if (copy.optString("summary").isBlank() && summary.isNotBlank()) {
                        copy.put("summary", summary); changed = true
                    }
                    if (statusRank(incomingStatus) > statusRank(copy.optString("status"))) {
                        copy.put("status", incomingStatus); changed = true
                    }
                    if (raw.optString("sourceKey").isNotBlank() && copy.optString("sourceKey").isBlank()) {
                        copy.put("sourceKey", raw.optString("sourceKey")); changed = true
                    }
                    if (raw.optLong("audioBytes", 0L) > 0L && copy.optLong("audioBytes", 0L) <= 0L) {
                        copy.put("audioBytes", raw.optLong("audioBytes")); changed = true
                    }
                    if (changed) {
                        recordingsToWrite.add(copy)
                        recById[canonId] = copy
                        if (title.isNotBlank()) recByTitle[title] = copy
                    }
                } else if (text.isNotBlank() || summary.isNotBlank() || raw.optBoolean("deleted")) {
                    val created = JSONObject(raw.toString())
                        .put("id", incomingId)
                        .put("title", title.ifBlank { "录音记录" })
                        .put("text", text)
                        .put("summary", summary)
                        .put("polished", polished)
                        .put("status", incomingStatus)
                        .put("ownerDevice", raw.optString("ownerDevice").ifBlank { defaultOwner })
                        .put("occurredAt", raw.optString("occurredAt").ifBlank {
                            raw.optString("recorded_at").ifBlank { java.time.Instant.now().toString() }
                        })
                        .put("createdAt", raw.optLong("createdAt", System.currentTimeMillis()))
                    recordingsToWrite.add(created)
                    recById[incomingId] = created
                    if (title.isNotBlank()) recByTitle.putIfAbsent(title, created)
                    if (rawId.isNotBlank()) idRemap[rawId] = incomingId
                }
            }

            val todoById = HashMap<String, JSONObject>()
            val todoByRecAndNorm = HashMap<String, JSONObject>()
            val processedNorms = HashSet<String>()
            for (t in existingTodos) {
                val id = t.optString("id")
                if (id.isNotBlank()) todoById[id] = t
                val norm = normalizeTodoTitle(t.optString("title"))
                val recId = t.optString("recordingId")
                if (norm.isNotEmpty()) {
                    todoByRecAndNorm.putIfAbsent("$recId|$norm", t)
                    if (t.optBoolean("done") || t.optBoolean("reviewed") || t.optBoolean("deleted")) {
                        processedNorms.add(norm)
                    }
                }
            }

            val todosToWrite = ArrayList<JSONObject>()
            var skippedOrDone = 0
            val inTodos = root.optJSONArray("todos") ?: JSONArray()
            for (i in 0 until inTodos.length()) {
                val raw = inTodos.optJSONObject(i) ?: continue
                val title = raw.optString("title").ifBlank { raw.optString("content") }.trim()
                if (title.isEmpty()) continue
                val norm = normalizeTodoTitle(title)
                val rawRecId = raw.optString("recordingId").ifBlank { raw.optString("recording_id") }
                val recId = idRemap[rawRecId] ?: rawRecId
                val rawId = raw.optString("id").trim()
                val tid = if (rawId.startsWith("todo-")) rawId else "todo-" + hash("$recId|$title")
                val v1Status = raw.optString("status")
                val inDone = raw.optBoolean("done") || v1Status == "done" || tid in doneTodoIds || rawId in doneTodoIds
                val inDeleted = raw.optBoolean("deleted") || v1Status in listOf("rejected", "dropped")
                val inReviewed = raw.optBoolean("reviewed") || inDone || inDeleted

                val existing = todoById[tid] ?: todoByRecAndNorm["$recId|$norm"]
                if (existing != null) {
                    val exDone = existing.optBoolean("done")
                    val exDeleted = existing.optBoolean("deleted")
                    val exReviewed = existing.optBoolean("reviewed")
                    val mergedDone = exDone || inDone
                    val mergedDeleted = exDeleted || inDeleted
                    val mergedReviewed = exReviewed || inReviewed
                    if (mergedDone || mergedDeleted || mergedReviewed) skippedOrDone++
                    if (mergedDone != exDone || mergedDeleted != exDeleted || mergedReviewed != exReviewed) {
                        val updated = JSONObject(existing.toString())
                            .put("done", mergedDone)
                            .put("deleted", mergedDeleted)
                            .put("reviewed", mergedReviewed)
                        todosToWrite.add(updated)
                        todoById[updated.getString("id")] = updated
                    }
                } else {
                    if (!inDone && !inDeleted && norm in processedNorms) {
                        skippedOrDone++
                        continue
                    }
                    if (inDone || inDeleted || inReviewed) {
                        skippedOrDone++
                        if (norm.isNotEmpty()) processedNorms.add(norm)
                    }
                    val created = JSONObject(raw.toString())
                        .put("id", tid)
                        .put("recordingId", recId)
                        .put("title", title)
                        .put("due", raw.optString("due").ifBlank { raw.optString("deadline") })
                        .put("category", LocalAnalysis.category(raw.optString("category")))
                        .put("done", inDone)
                        .put("reviewed", inReviewed)
                        .put("deleted", inDeleted)
                        .put("createdAt", raw.optLong("createdAt", System.currentTimeMillis()))
                    todosToWrite.add(created)
                    todoById[tid] = created
                    if (norm.isNotEmpty()) todoByRecAndNorm["$recId|$norm"] = created
                }
            }
            return MergeResult(recordingsToWrite, todosToWrite, skippedOrDone)
        }
    }
}
