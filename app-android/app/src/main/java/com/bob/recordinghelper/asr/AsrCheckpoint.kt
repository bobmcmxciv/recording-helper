package com.bob.recordinghelper.asr

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Piece-level checkpoint for long audio transcriptions (`asr-partial/<sha1>.jsonl`).
 * Each ≤10 s piece produced by [Segmenter] is appended as one JSON line as soon as [SenseVoice] finishes it.
 * If the app is paused, switched, updated, or preempted mid-recording, the next run restores all completed
 * pieces in 0 ms (skipping feature extraction and NPU/CPU inference) and resumes from the remaining piece.
 */
class AsrCheckpoint(private val file: File, private val mirrorFile: File? = null) {

    data class Entry(
        val index: Int,
        val start: Long,
        val n: Int,
        val text: String,
        val ids: IntArray? = null,
    )

    @Synchronized
    fun load(): Map<Int, Entry> {
        val out = LinkedHashMap<Int, Entry>()
        val mirror = mirrorFile
        val source = when {
            file.isFile && file.length() > 0 && (mirror == null || file.length() >= mirror.length()) -> file
            mirror != null && mirror.isFile && mirror.length() > 0 -> {
                runCatching {
                    file.parentFile?.mkdirs()
                    mirror.copyTo(file, overwrite = true)
                }
                if (file.isFile && file.length() > 0) file else mirror
            }
            else -> return out
        }
        runCatching {
            source.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEachLine
                runCatching {
                    val obj = JSONObject(trimmed)
                    val idx = obj.getInt("i")
                    val start = obj.getLong("s")
                    val n = obj.getInt("n")
                    val text = obj.optString("t", "")
                    val idsArr = obj.optJSONArray("ids")
                    val ids = if (idsArr != null) IntArray(idsArr.length()) { idsArr.optInt(it) } else null
                    if (idx >= 0 && start >= 0L && n > 0) {
                        out[idx] = Entry(idx, start, n, text, ids)
                    }
                }
            }
        }
        return out
    }

    @Synchronized
    fun save(entry: Entry) {
        val obj = JSONObject()
            .put("i", entry.index)
            .put("s", entry.start)
            .put("n", entry.n)
            .put("t", entry.text)
        if (entry.ids != null) {
            val arr = JSONArray()
            for (v in entry.ids) arr.put(v)
            obj.put("ids", arr)
        }
        val line = obj.toString() + "\n"
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(line)
        }
        val mirror = mirrorFile
        if (mirror != null && mirror.absolutePath != file.absolutePath) {
            runCatching {
                mirror.parentFile?.mkdirs()
                mirror.appendText(line)
            }
        }
    }

    @Synchronized
    fun clear() {
        runCatching { if (file.exists()) file.delete() }
        val mirror = mirrorFile
        if (mirror != null) {
            runCatching { if (mirror.exists()) mirror.delete() }
        }
    }
}
