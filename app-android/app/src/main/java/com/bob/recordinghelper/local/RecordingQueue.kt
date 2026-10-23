package com.bob.recordinghelper.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Environment
import com.bob.recordinghelper.Prefs
import org.json.JSONObject
import java.io.File

/** Only references and metadata are queued; never copy the entire historical audio library. */
class RecordingQueue internal constructor(ctx: Context, name: String = "recording-queue.db") : SQLiteOpenHelper(ctx, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE queue(key TEXT PRIMARY KEY, source TEXT NOT NULL, title TEXT NOT NULL, bytes INTEGER, modified INTEGER, automatic INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'queued', attempts INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE INDEX queue_state ON queue(state, modified)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    @Synchronized fun add(item: RecordingSource, automatic: Boolean = true): Boolean {
        val inserted = writableDatabase.insertWithOnConflict("queue", null, ContentValues().apply {
        put("key", item.key); put("source", item.source); put("title", item.title); put("bytes", item.bytes)
        put("modified", item.modified); put("automatic", if (automatic) 1 else 0)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        // Re-authorization can change the tree URI without changing the recording identity.
        if (!inserted) writableDatabase.execSQL(
            "UPDATE queue SET source=?,title=?,state=CASE WHEN state='error' AND source!=? THEN 'queued' ELSE state END,error=CASE WHEN state='error' AND source!=? THEN '' ELSE error END WHERE key=?",
            arrayOf(item.source, item.title, item.source, item.source, item.key))
        return inserted
    }
    @Synchronized fun next(allowAutomatic: Boolean): RecordingSource? = readableDatabase.rawQuery(
        "SELECT source,key,title,bytes,modified FROM queue WHERE state='queued'" +
            (if (allowAutomatic) "" else " AND automatic=0") + " ORDER BY automatic ASC, modified DESC LIMIT 1", null).use { c ->
        if (c.moveToFirst()) RecordingSource(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4)) else null
    }
    @Synchronized fun addAll(items: List<RecordingSource>): Int {
        val db = writableDatabase; var added = 0
        db.beginTransaction()
        try { items.forEach { if (add(it)) added++ }; db.setTransactionSuccessful() }
        finally { db.endTransaction() }
        return added
    }
    @Synchronized fun claim(allowAutomatic: Boolean): RecordingSource? = next(allowAutomatic)?.also { state(it.key, "processing") }
    @Synchronized fun recover() { writableDatabase.execSQL("UPDATE queue SET state='queued' WHERE state='processing'") }
    @Synchronized fun state(key: String, state: String, error: String = "") {
        writableDatabase.execSQL("UPDATE queue SET state=?,error=? WHERE key=?", arrayOf(state, error, key))
    }
    @Synchronized fun failure(key: String, error: String) {
        writableDatabase.execSQL("UPDATE queue SET state='error',attempts=attempts+1,error=? WHERE key=?", arrayOf(error, key))
    }
    @Synchronized fun retryErrors() { writableDatabase.execSQL("UPDATE queue SET state='queued',error='' WHERE state='error'") }
    @Synchronized fun retryUnavailable() { writableDatabase.execSQL("UPDATE queue SET state='queued',error='' WHERE state='error' AND error LIKE '%(UNAVAILABLE)%'") }
    @Synchronized fun status(key: String): String? = readableDatabase.rawQuery("SELECT state FROM queue WHERE key=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    @Synchronized fun counts(): JSONObject = JSONObject().also { out ->
        readableDatabase.rawQuery("SELECT state,COUNT(*) FROM queue GROUP BY state", null).use {
            while (it.moveToNext()) out.put(it.getString(0), it.getInt(1))
        }
    }
    @Synchronized fun pending(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM queue WHERE state IN ('queued','processing')", null).use {
        it.moveToFirst(); it.getInt(0)
    }
    /** The original audio reference (path or content URI) behind a recording id, if it came from the queue. */
    @Synchronized fun sourceFor(ctx: Context, recordingId: String): String? {
        val owner = LocalSettings.device(ctx)
        val primary = Environment.getExternalStorageDirectory().canonicalPath
        val rec = LocalStore.of(ctx).get(recordingId)
        val recSourceKey = rec?.optString("sourceKey").orEmpty()
        val recTitle = rec?.optString("title").orEmpty()
        val recBytes = rec?.optLong("audioBytes", 0L) ?: 0L
        val rows = mutableListOf<RecordingSource>()
        readableDatabase.rawQuery("SELECT source,key,title,bytes,modified FROM queue", null).use { c ->
            while (c.moveToNext()) {
                val item = RecordingSource(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4))
                if ("rec-" + LocalStore.hash("$owner:${item.key}") == recordingId ||
                    (recSourceKey.isNotBlank() && item.key == recSourceKey)) {
                    return RecordingSources.resolveLocalFile(item)?.path ?: item.source
                }
                rows.add(item)
            }
        }
        for (item in rows) {
            val path = item.key.removeSuffix("|${item.bytes}|${item.modified}")
            val aliases = RecordingSources.allAliases(path, item.bytes, item.modified, primary)
            if (aliases.any { "rec-" + LocalStore.hash("$owner:$it") == recordingId || (recSourceKey.isNotBlank() && it == recSourceKey) }) {
                return RecordingSources.resolveLocalFile(item)?.path ?: item.source
            }
        }
        if (recTitle.isNotBlank()) {
            rows.firstOrNull { it.title == recTitle && (recBytes <= 0L || it.bytes == recBytes) }?.let {
                return RecordingSources.resolveLocalFile(it)?.path ?: it.source
            }
        }
        return null
    }
    @Synchronized fun removeAutomatic(key: String) {
        writableDatabase.execSQL("DELETE FROM queue WHERE key=? AND automatic=1 AND state IN ('queued','processing','error')", arrayOf(key))
    }
    /** Reconcile automatic queue entries after permission/source-mode changes so orphaned URIs do not fail in batches. */
    @Synchronized fun reconcileAutomatic(ctx: Context, eligible: List<RecordingSource>, inventoryComplete: Boolean) {
        val owner = LocalSettings.device(ctx)
        val primary = Environment.getExternalStorageDirectory().canonicalPath
        val savedLookup = LocalStore.of(ctx).savedLookup(primary, owner)
        val eligibleByKey = eligible.associateBy { it.key }
        val aliasToEligible = HashMap<String, RecordingSource>(eligible.size * 4)
        val identityToEligible = HashMap<String, RecordingSource>(eligible.size)
        for (e in eligible) {
            val path = e.key.removeSuffix("|${e.bytes}|${e.modified}")
            for (a in RecordingSources.allAliases(path, e.bytes, e.modified, primary)) aliasToEligible.putIfAbsent(a, e)
            identityToEligible.putIfAbsent("${e.title}|${e.bytes}", e)
        }
        data class QueueRow(val item: RecordingSource, val state: String, val error: String)
        val rows = readableDatabase.rawQuery("SELECT source,key,title,bytes,modified,state,error FROM queue WHERE automatic=1", null).use { c ->
            buildList(c.count) {
                while (c.moveToNext()) add(QueueRow(
                    RecordingSource(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4)),
                    c.getString(5), c.getString(6)
                ))
            }
        }
        val doneIdentities = HashSet<String>()
        val doneCanonicalKeys = HashSet<String>()
        for (r in rows) {
            val hasSaved = r.state == "done" || savedLookup.find(r.item) != null
            if (hasSaved) {
                val idKey = "${r.item.title}|${r.item.bytes}"
                doneIdentities.add(idKey)
                aliasToEligible[r.item.key]?.let { doneCanonicalKeys.add(it.key) }
                identityToEligible[idKey]?.let { doneCanonicalKeys.add(it.key) }
            }
        }
        val ledgerToAdd = mutableListOf<String>()
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in rows) {
                val key = r.item.key
                val idKey = "${r.item.title}|${r.item.bytes}"
                val fresh = eligibleByKey[key]
                val isDone = r.state == "done" || idKey in doneIdentities || key in doneCanonicalKeys
                if (fresh != null) {
                    if (isDone) {
                        if (r.state != "done" || r.item.source != fresh.source) {
                            db.execSQL("UPDATE queue SET source=?,state='done',error='' WHERE key=?", arrayOf(fresh.source, key))
                        }
                        if (r.state != "done") ledgerToAdd.add(key)
                    } else if (r.state == "error") {
                        if (r.item.source != fresh.source || r.error.contains("授权") ||
                            (r.error.contains("无法读取") && RecordingSources.resolveLocalFile(fresh) != null)) {
                            db.execSQL("UPDATE queue SET source=?,state='queued',error='' WHERE key=?", arrayOf(fresh.source, key))
                        }
                    }
                } else if (r.state != "processing") {
                    val hasReplacement = key in aliasToEligible || idKey in identityToEligible
                    if (hasReplacement) {
                        db.execSQL("DELETE FROM queue WHERE key=?", arrayOf(key))
                    } else if (r.state == "queued" || r.state == "error") {
                        val brokenSource = (r.item.source.startsWith("content://") && RecordingSources.resolveLocalFile(r.item) == null) ||
                            (!r.item.source.startsWith("content://") && !File(r.item.source).isFile)
                        if (inventoryComplete || brokenSource) {
                            db.execSQL("DELETE FROM queue WHERE key=?", arrayOf(key))
                        }
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        ledgerToAdd.forEach { Prefs.ledgerAdd(ctx, it) }
    }
    /** Changing scope does not delete recordings; only stale, not-yet-started automatic references are removed. */
    @Synchronized fun resetUnstartedAutomatic() { writableDatabase.execSQL("DELETE FROM queue WHERE automatic=1 AND state IN ('queued','error')") }
    companion object {
        @Volatile private var instance: RecordingQueue? = null
        fun of(ctx: Context): RecordingQueue = instance ?: synchronized(this) {
            instance ?: RecordingQueue(ctx.applicationContext).also { instance = it }
        }
    }
}
