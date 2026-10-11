package com.bob.recordinghelper.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Durable local source of truth. Sync never owns or deletes the local transcript. */
class LocalStore private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "local-v2.db", null, 1) {
    private val ownerDevice = LocalSettings.device(ctx)
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
    @Synchronized fun get(id: String): JSONObject? = readableDatabase.rawQuery(
        "SELECT body FROM items WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
    @Synchronized fun put(kind: String, body: JSONObject) {
        val id = body.getString("id")
        writableDatabase.execSQL("INSERT INTO items(id,kind,body,version) VALUES(?,?,?,1) ON CONFLICT(id) DO UPDATE SET body=excluded.body, version=version+1, dirty=1",
            arrayOf(id, kind, body.toString()))
    }
    @Synchronized fun transcript(key: String, title: String, text: String, occurredAt: String?): String {
        val id = "rec-" + hash(key)
        if (get(id) == null) put("recording", JSONObject().put("id", id).put("title", title)
            .put("text", text).put("occurredAt", occurredAt ?: java.time.Instant.now().toString())
            .put("status", "pending").put("ownerDevice", ownerDevice).put("summary", "").put("createdAt", System.currentTimeMillis()))
        return id
    }
    @Synchronized fun status(id: String, status: String, error: String = "") {
        get(id)?.let {
            if (status == "pending") it.put("ownerDevice", ownerDevice)
            put("recording", it.put("status", status).put("error", error))
        }
    }
    @Synchronized fun analyzed(id: String, polished: String, result: JSONObject) {
        val rec = get(id) ?: return
        val db = writableDatabase
        db.beginTransaction()
        try {
            put("recording", rec.put("polished", polished).put("summary", result.getString("summary"))
                .put("status", "ready").put("error", ""))
            val todos = result.getJSONArray("todos")
            for (i in 0 until todos.length()) {
                val t = todos.getJSONObject(i)
                val tid = "todo-" + hash(id + "|" + t.getString("title"))
                if (get(tid) == null) put("todo", t.put("id", tid).put("recordingId", id)
                    .put("done", false).put("reviewed", false).put("createdAt", System.currentTimeMillis()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun toggle(id: String) { get(id)?.let { put("todo", it.put("done", !it.optBoolean("done")).put("reviewed", true)) } }
    @Synchronized fun setDone(id: String, done: Boolean) { get(id)?.let { put("todo", it.put("done", done).put("reviewed", true)) } }
    @Synchronized fun review(id: String) { get(id)?.let { if (!it.optBoolean("reviewed")) put("todo", it.put("reviewed", true)) } }
    @Synchronized fun updateTodo(id: String, title: String, due: String) {
        get(id)?.let { put("todo", it.put("title", title.trim()).put("due", due.trim()).put("reviewed", true)) }
    }
    fun addTodo(title: String, due: String = ""): String {
        val id = "todo-" + UUID.randomUUID()
        put("todo", JSONObject().put("id", id).put("title", title.trim()).put("due", due.trim())
            .put("done", false).put("reviewed", true).put("createdAt", System.currentTimeMillis()))
        return id
    }
    @Synchronized fun todosFor(recordingId: String): List<JSONObject> = all("todo").filter { it.optString("recordingId") == recordingId }

    /** Tombstone instead of a row delete, so that every synced device learns about it. */
    @Synchronized fun delete(id: String) {
        val item = get(id) ?: return
        val kind = if (id.startsWith("rec-")) "recording" else "todo"
        put(kind, item.put("deleted", true))
        if (kind == "recording") todosFor(id).forEach { put("todo", it.put("deleted", true)) }
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
    companion object {
        @Volatile private var instance: LocalStore? = null
        fun of(ctx: Context): LocalStore = instance ?: synchronized(this) {
            instance ?: LocalStore(ctx.applicationContext).also { instance = it }
        }
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
