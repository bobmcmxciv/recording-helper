package com.bob.recordinghelper.local

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object MicArchive {
    fun dir(ctx: Context) = File(ctx.filesDir, "mic-recordings").apply { mkdirs() }
    private fun file(ctx: Context, id: String, ext: String): File {
        require(id.matches(Regex("[0-9a-f-]{36}")))
        return File(dir(ctx), "$id.$ext")
    }
    fun audio(ctx: Context, id: String) = file(ctx, id, "wav")
    @Synchronized fun create(ctx: Context, mode: String, name: String): JSONObject {
        val o = JSONObject().put("id", UUID.randomUUID().toString()).put("mode", mode).put("name", name)
            .put("created", System.currentTimeMillis()).put("state", "recording").put("segments", JSONArray())
            .put("title", "现场录音 " + java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' '))
        save(ctx, o); return o
    }
    @Synchronized fun get(ctx: Context, id: String): JSONObject = JSONObject(String(AtomicFile(file(ctx, id, "json")).readFully(), Charsets.UTF_8))
    @Synchronized fun update(ctx: Context, id: String, block: (JSONObject) -> Unit) { val o = get(ctx, id); block(o); save(ctx, o) }
    private fun save(ctx: Context, o: JSONObject) {
        val a = AtomicFile(file(ctx, o.getString("id"), "json")); val s = a.startWrite()
        try { s.write(o.toString().toByteArray()); a.finishWrite(s) } catch (t: Throwable) { a.failWrite(s); throw t }
    }
    fun key(ctx: Context, id: String) = LocalSettings.device(ctx) + ":mic:" + id
    fun saved(ctx: Context, id: String) = LocalStore.of(ctx).get("rec-" + LocalStore.hash(key(ctx, id))) != null
    @Synchronized fun recordings(ctx: Context): List<JSONObject> = dir(ctx).listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { f -> runCatching { get(ctx, f.nameWithoutExtension) }.getOrNull() }
        .filter { it.optString("mode") == "record" && audio(ctx, it.getString("id")).exists() }.sortedByDescending { it.optLong("created") }

    /** Only our temporary enrollment samples are removed after process death; ordinary recordings remain. */
    @Synchronized fun recover(ctx: Context) {
        if (MicState.busy) return
        for (f in dir(ctx).listFiles { f -> f.extension == "json" }.orEmpty()) {
            val o = runCatching { get(ctx, f.nameWithoutExtension) }.getOrNull() ?: continue
            val id = o.getString("id"); val wav = audio(ctx, id)
            if (o.optString("mode") != "record") {
                wav.delete()
                if (o.optString("state") == "recording") update(ctx, id) { it.put("state", "interrupted") }
            } else if (wav.exists()) {
                val n = PcmWave.repair(wav)
                if (o.optString("state") in listOf("recording", "transcribing")) update(ctx, id) { it.put("state", "waiting").put("samples", n) }
            }
        }
    }
    fun retry(ctx: Context, id: String) {
        require(!MicState.busy) { "请先结束当前录音或采样" }
        val o = get(ctx, id); require(o.optString("mode") == "record")
        val wav = audio(ctx, id); require(wav.length() > 44) { "没有可用音频" }
        PcmWave.repair(wav)
        val q = RecordingQueue.of(ctx)
        val source = RecordingSource(wav.absolutePath, "mic:$id", o.getString("title"), wav.length(), wav.lastModified())
        q.add(source, false)
        // A failed manual microphone reference can be retried without re-enabling automatic import.
        q.writableDatabase.execSQL("UPDATE queue SET state='queued',error='' WHERE key=? AND state='error'", arrayOf(source.key))
        RecordingQueueWorker.schedule(ctx)
        update(ctx, id) { it.put("state", "queued") }
    }
}

object MicState {
    data class Snapshot(val busy: Boolean = false, val capturing: Boolean = false, val mode: String = "record", val id: String = "",
        val samples: Long = 0, val processed: Long = 0, val level: Int = 0, val message: String = "点击开始，仅在你操作后使用麦克风", val text: String = "")
    @Volatile private var value = Snapshot()
    val busy: Boolean get() = value.busy
    fun snapshot() = value
    @Synchronized fun update(block: (Snapshot) -> Snapshot) { value = block(value) }
}
