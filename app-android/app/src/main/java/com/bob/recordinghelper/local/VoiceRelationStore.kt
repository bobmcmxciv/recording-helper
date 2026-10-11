package com.bob.recordinghelper.local

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Pending evidence only: never synced/exported and never treated as an enrolled identity. */
class VoiceRelationStore(private val file: File) {
    data class Entry(val id: String, val title: String, val uri: String, val seconds: Double,
        val limited: Boolean, val reason: String, val evidence: VoiceRelations.Evidence?)
    data class Report(val model: String, val anchor: String, val entries: List<Entry>, val created: Long = System.currentTimeMillis())
    @Synchronized fun read(): Report? = try {
        val o = JSONObject(String(AtomicFile(file).readFully(), Charsets.UTF_8))
        val a = o.getJSONArray("entries")
        require(a.length() <= MAX_ENTRIES)
        Report(o.getString("model"), o.getString("anchor"), (0 until a.length()).map { i ->
            val e = a.getJSONObject(i); val id = e.getString("id"); val v = e.optJSONObject("evidence")
            val evidence = v?.let {
                val samples = it.getJSONArray("samples").let { vs -> (0 until vs.length()).map { j -> vector(vs.getJSONArray(j)) } }
                require(samples.size in 6..12 && VoiceMatcher.coherent(samples))
                VoiceRelations.Evidence(id, it.getDouble("selfScore"), it.getDouble("otherSelfScore"),
                    it.getDouble("selfSeconds"), it.getDouble("otherSeconds"), vector(it.getJSONArray("other")), samples,
                    it.getDouble("previewStart"), it.getDouble("previewSeconds"))
            }
            Entry(id, e.getString("title"), e.getString("uri"), e.getDouble("seconds"), e.getBoolean("limited"), e.getString("reason"), evidence)
        }, o.getLong("created"))
    } catch (_: java.io.FileNotFoundException) { null }
    @Synchronized fun write(report: Report) {
        require(report.entries.size <= MAX_ENTRIES && report.entries.map { it.id }.distinct().size == report.entries.size)
        val entries = JSONArray(report.entries.map { e ->
            JSONObject().put("id", e.id).put("title", e.title).put("uri", e.uri).put("seconds", e.seconds)
                .put("limited", e.limited).put("reason", e.reason).apply {
                    e.evidence?.let { v -> put("evidence", JSONObject().put("selfScore", v.selfScore).put("otherSelfScore", v.otherSelfScore)
                        .put("selfSeconds", v.selfSeconds).put("otherSeconds", v.otherSeconds).put("other", JSONArray(v.other.toList()))
                        .put("samples", JSONArray(v.samples.map { JSONArray(it.toList()) })).put("previewStart", v.previewStart).put("previewSeconds", v.previewSeconds)) }
                }
        })
        val body = JSONObject().put("schema", 1).put("model", report.model).put("anchor", report.anchor).put("created", report.created).put("entries", entries)
        file.parentFile?.mkdirs(); val a = AtomicFile(file); val out = a.startWrite()
        try { out.write(body.toString().toByteArray(Charsets.UTF_8)); a.finishWrite(out) }
        catch (t: Throwable) { a.failWrite(out); throw t }
    }
    @Synchronized fun clear() { AtomicFile(file).delete() }
    companion object {
        const val MAX_ENTRIES = 40
        fun mergeEntries(old: List<Entry>, incoming: Entry): List<Entry> {
            // A rewritten file is not a second independent source and its old URI no longer points
            // to the original evidence. A temporary read failure, however, must not erase good data.
            val keep = old.filterNot { it.id == incoming.id || (!incoming.id.startsWith("failed-") && it.uri == incoming.uri) }
            return (keep + incoming).also {
                require(it.size <= MAX_ENTRIES) { "候选依据已达 40 段上限；请先移除不需要的依据，本次未覆盖旧结果" }
            }
        }
        fun of(ctx: Context) = VoiceRelationStore(File(ctx.filesDir, "voice-relations-v1.json"))
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun anchor(profile: VoiceLibrary.Profile) = digest((profile.model + ":" + profile.id + ":" + profile.created + ":" +
            profile.vectors.joinToString("|") { v -> v.joinToString(",") { it.toRawBits().toString() } }).toByteArray())
        fun currentSelf(ctx: Context): VoiceLibrary.Profile {
            val model = VoiceLibrary.modelHash(ctx)
            return VoiceLibrary.of(ctx).all().singleOrNull { it.self && it.model == model }
                ?: error("请先一键录入本人的声纹；更换模型后需重新录入")
        }
        private fun vector(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }.also { require(VoiceMatcher.valid(it)) }
    }
}
