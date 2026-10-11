package com.bob.recordinghelper.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

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
        if (!inserted) writableDatabase.execSQL("UPDATE queue SET source=?,title=? WHERE key=?",
            arrayOf(item.source, item.title, item.key))
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
        readableDatabase.rawQuery("SELECT key,source FROM queue", null).use { c ->
            while (c.moveToNext()) if ("rec-" + LocalStore.hash("$owner:" + c.getString(0)) == recordingId) return c.getString(1)
        }
        return null
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
