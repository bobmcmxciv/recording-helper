package com.bob.recordinghelper.local

import android.content.Context
import android.util.AtomicFile
import com.bob.recordinghelper.LocalAsr
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Private app storage, excluded from LocalStore / sync / export. Legacy libraries are not overwritten. */
class VoiceLibrary(private val file: File) {
    data class Profile(val id: String, val name: String, val self: Boolean, val model: String, val created: Long, val vectors: List<FloatArray>)
    @Synchronized fun all(): List<Profile> {
        val atomic = AtomicFile(file)
        return try {
            val arr = JSONObject(String(atomic.readFully(), Charsets.UTF_8)).getJSONArray("profiles")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i); val a = o.getJSONArray("vectors")
                Profile(o.getString("id"), o.getString("name"), o.optBoolean("self"), o.getString("model"), o.getLong("created"),
                    (0 until a.length()).map { j -> a.getJSONArray(j).let { v -> FloatArray(v.length()) { v.getDouble(it).toFloat() } } })
            }.filter { it.vectors.isNotEmpty() && it.vectors.all(VoiceMatcher::valid) }
        } catch (e: java.io.FileNotFoundException) { emptyList() }
    }
    @Synchronized fun put(name: String, self: Boolean, model: String, vectors: List<FloatArray>): Profile {
        require(name.trim().length in 1..40 && model.matches(Regex("[a-f0-9]{64}"))) { "姓名或模型标识无效" }
        require(self || name.trim() != "我的声音") { "本人声纹请使用一键录入我的声纹" }
        require(VoiceMatcher.coherent(vectors)) { "有效语音不足或样本不一致，请在安静环境由同一人重录" }
        val list = all().toMutableList()
        require(self || list.none { it.name == name.trim() }) { "此名称已存在，请使用不同名称或先删除旧条目" }
        val profile = Profile(if (self) "self" else UUID.randomUUID().toString(), if (self) "我的声音" else name.trim(), self,
            model, System.currentTimeMillis(), listOf(VoiceMatcher.center(vectors)))
        if (self) list.removeAll { it.self }
        list.add(profile); write(list); return profile
    }
    @Synchronized fun delete(id: String) { write(all().filterNot { it.id == id }) }
    private fun write(profiles: List<Profile>) {
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        for (p in profiles) arr.put(JSONObject().put("id", p.id).put("name", p.name).put("self", p.self).put("model", p.model).put("created", p.created)
            .put("vectors", JSONArray(p.vectors.map { JSONArray(it.toList()) })))
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(JSONObject().put("schema", 1).put("profiles", arr).toString().toByteArray()); atomic.finishWrite(stream) }
        catch (t: Throwable) { atomic.failWrite(stream); throw t }
    }
    companion object {
        @Volatile private var instance: VoiceLibrary? = null
        fun of(ctx: Context): VoiceLibrary = instance ?: synchronized(this) {
            instance ?: VoiceLibrary(File(ctx.filesDir, "voice-library-v1.json")).also { instance = it }
        }
        private var cachedKey = ""
        private var cachedHash = ""
        @Synchronized fun modelHash(ctx: Context): String {
            val f = File(LocalAsr.modelDir(ctx), "spk.tflite")
            require(f.length() > 0) { "请先在模型中心导入 spk.tflite 声纹模型" }
            val key = "${f.absolutePath}|${f.length()}|${f.lastModified()}"
            if (key != cachedKey) {
                val digest = MessageDigest.getInstance("SHA-256")
                f.inputStream().use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) } }
                cachedHash = digest.digest().joinToString("") { "%02x".format(it) }; cachedKey = key
            }
            return cachedHash
        }
        fun compatible(ctx: Context): Map<String, List<FloatArray>> {
            val hash = modelHash(ctx)
            return of(ctx).all().filter { it.model == hash }.associate { it.name to it.vectors }
        }
    }
}
