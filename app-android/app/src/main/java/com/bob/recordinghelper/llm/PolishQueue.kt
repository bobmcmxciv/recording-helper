package com.bob.recordinghelper.llm

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Transcripts waiting to be polished (files/polish-queue/<recording id>.json, survives restarts). A job is added once
 * the raw text is on the server (so the polished version can be attached to the same recording) and removed once the
 * polished text is uploaded, or after [MAX_ATTEMPTS] failed tries.
 */
object PolishQueue {
    const val MAX_ATTEMPTS = 3

    data class Job(val id: String, val title: String, val text: String, val enqueuedAt: Long, val attempts: Int)

    private fun dir(ctx: Context) = File(ctx.filesDir, "polish-queue").apply { mkdirs() }

    @Synchronized
    fun add(ctx: Context, id: String, title: String, text: String) {
        val safe = id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        if (safe.isEmpty() || text.isBlank()) return
        val f = File(dir(ctx), "$safe.json")
        if (f.exists()) return
        f.writeText(JSONObject().put("id", id).put("title", title).put("text", text)
            .put("enqueuedAt", System.currentTimeMillis()).put("attempts", 0).toString())
    }

    @Synchronized
    fun list(ctx: Context): List<Job> = (dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { f ->
            try {
                val o = JSONObject(f.readText())
                Job(o.getString("id"), o.optString("title"), o.getString("text"), o.optLong("enqueuedAt"), o.optInt("attempts"))
            } catch (_: Exception) {
                f.delete(); null
            }
        }
        .sortedBy { it.enqueuedAt }

    @Synchronized
    fun remove(ctx: Context, job: Job) {
        File(dir(ctx), job.id.filter { it.isLetterOrDigit() || it == '_' || it == '-' } + ".json").delete()
    }

    /** Counts a failed try; drops the job once it reached [MAX_ATTEMPTS]. Returns true if it was dropped. */
    @Synchronized
    fun failed(ctx: Context, job: Job): Boolean {
        val f = File(dir(ctx), job.id.filter { it.isLetterOrDigit() || it == '_' || it == '-' } + ".json")
        if (job.attempts + 1 >= MAX_ATTEMPTS) { f.delete(); return true }
        if (f.exists()) {
            val o = JSONObject(f.readText()).put("attempts", job.attempts + 1)
            f.writeText(o.toString())
        }
        return false
    }

    fun size(ctx: Context): Int = dir(ctx).list { _, n -> n.endsWith(".json") }?.size ?: 0
}
