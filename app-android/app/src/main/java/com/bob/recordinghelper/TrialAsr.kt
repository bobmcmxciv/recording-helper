package com.bob.recordinghelper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * 试转：在手机上把一个录音完整转写一遍（含说话人区分），过程显示在转写过程页，结果写到
 * files/trial/<文件名>.result.json，不上传任何东西。录音只能来自 App 自己的 files/trial/ 目录
 * （页面上选的文件会先复制进去；测试时也可以用 adb 放进去，再用 trial 参数打开转写过程页）。
 */
object TrialAsr {
    private const val TAG = "RhTrial"

    @Volatile var running = false
        private set

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "trial").apply { mkdirs() }

    /** [name] is a bare file name inside [dir]; anything else is refused. Returns false when it cannot start. */
    fun start(ctx: Context, name: String): Boolean {
        if (running || name.isEmpty() || '/' in name || '\\' in name || name.startsWith(".")) return false
        val f = File(dir(ctx), name)
        if (!f.isFile || !LocalAsr.modelReady(ctx)) return false
        running = true
        val app = ctx.applicationContext
        thread(name = "asr-trial") {
            val t0 = System.currentTimeMillis()
            KeepAwake.acquire(app, "试转：$name")
            try {
                LiveAsr.begin("试转（不上传）：$name", 1, 1, "", false, 0)
                LiveAsr.phase("读取本地声纹库")
                val vp = "本地声纹库（不联网）"
                LiveAsr.phase("准备引擎（首次使用要为 NPU 编译模型，可能需要几分钟）")
                var polishSrc = ""
                var result: JSONObject? = null
                ModelGate.hold { LocalAsr.open(app).use { s ->
                    LiveAsr.begin("试转（不上传）：$name", 1, 1, s.engine.name, s.speakersOn, s.libraryNames)
                    val out = s.transcribe(f, {}, LiveAsr::on)
                    val r = out.result
                    val secs = (System.currentTimeMillis() - t0) / 1000.0
                    val o = JSONObject()
                        .put("file", name).put("engine", out.engine.name).put("audio_s", r.audioSeconds)
                        .put("wall_s", secs).put("init_ms", out.initMs).put("model_ms", r.modelMs)
                        .put("feature_ms", r.featureMs).put("total_ms", r.totalMs).put("silent", r.silentSegments)
                        .put("speakers", r.speakerCount).put("speaker_ms", r.speakerMs)
                        .put("speaker_wait_ms", r.speakerWaitMs).put("speaker_error", r.speakerError ?: JSONObject.NULL)
                        .put("text", r.text).put("voiceprints", vp)
                    o.put("segments", JSONArray().apply {
                        r.speakerSegments.forEach { put(JSONObject().put("start", it.start).put("end", it.end).put("speaker", it.speaker).put("text", it.text)) }
                    })
                    polishSrc = r.uploadText
                    result = o
                    File(dir(app), "$name.result.json").apply { writeText(o.toString(1)); setReadable(true, false) }
                    val line = ("试转完成：%s，%.0f 秒录音用时 %.1f 秒（准备引擎 %.1f 秒，模型 %.1f 秒，声纹（%s）%.1f 秒，" +
                        "最后等声纹 %.1f 秒），说话人 %d 人，%d 段").format(out.engine.name, r.audioSeconds, secs,
                        out.initMs / 1000.0, r.modelMs / 1000.0, s.speakerDevice, r.speakerMs / 1000.0, r.speakerWaitMs / 1000.0,
                        r.speakerCount, r.speakerSegments.size) +
                        (r.speakerError?.let { "；说话人区分出错：${it.take(160)}" } ?: "") + "；声纹库：$vp；未上传"
                    android.util.Log.i(TAG, line)
                    LiveAsr.finish(line)
                } }
                // with a local LLM: polish the result right away, after the ASR models are closed (HyperOS kills
                // an app above ~6 GB PSS, and a large LLM next to SenseVoice and CAM++ goes over it)
                val o = result
                if (o != null && Prefs.polishMode(app) == Prefs.POLISH_LOCAL) com.bob.recordinghelper.llm.LlmEngine.modelIn(LocalAsr.llmDir(app))?.let { mf ->
                    if (polishSrc.isNotBlank()) try {
                        ModelGate.hold { com.bob.recordinghelper.llm.LlmEngine.load(mf).use { eng ->
                            LiveAsr.polishBegin("试转：$name", eng.name)
                            val p = com.bob.recordinghelper.llm.Polisher(eng).polish(polishSrc) {
                                LiveAsr.polishProgress(it.chunk, it.chunks, it.partial)
                            }
                            LiveAsr.polishDone("试转润色完成（%d 段，%d 段保留原文，%.0f 秒）".format(p.chunks, p.keptOriginal, p.ms / 1000.0), p.text)
                            o.put("polished", p.text).put("polish_model", eng.name).put("polish_ms", p.ms)
                                .put("polish_chunks", p.chunks).put("polish_kept_original", p.keptOriginal)
                                .put("polish_source", polishSrc).put("polish_rejected", JSONArray(p.rejected))
                        } }
                    } catch (t: Throwable) {
                        o.put("polish_error", t.toString())
                        LiveAsr.polishDone("试转润色失败：${t.message?.take(80)}")
                    }
                    File(dir(app), "$name.result.json").apply { writeText(o.toString(1)); setReadable(true, false) }
                }
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "trial failed: $name", t)
                File(dir(app), "$name.result.json").apply {
                    writeText(JSONObject().put("file", name).put("error", t.toString()).toString()); setReadable(true, false)
                }
                LiveAsr.finish("试转失败：${t.message?.take(120)}")
            } finally {
                running = false
                KeepAwake.release(app)
            }
        }
        return true
    }
}
