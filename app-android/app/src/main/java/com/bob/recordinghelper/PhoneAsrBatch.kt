package com.bob.recordinghelper

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 本地转写模式下处理一批录音：在手机上转成文字，只上传文字，录音本身不出手机。
 * 一批共用一个已加载的模型（加载要 5–8 秒；手机上第一次要先为 NPU 编译，几分钟）。
 * 文字没传上去时把转写结果存在本地，下次直接上传，不再重新转写。
 */
class PhoneAsrBatch(private val ctx: Context) : java.io.Closeable {

    enum class Outcome {
        SENT,         // 文字已上传
        SILENT,       // 没识别出字（静音或空录音），没有可上传的
        NOT_SENT,     // 转写好了但文字没传上去（网络或服务器），结果已存在手机上，下次再传
        FAILED,       // 这个文件转写失败
        UNAVAILABLE,  // 转写引擎起不来，这一批都转不了（原因见 [unavailableReason]）
    }

    var unavailableReason: String? = null
        private set
    private var session: LocalAsr.Session? = null

    /**
     * [key] 标识这个文件（用来找存下的转写结果），[title] 是服务器看到的文件名（通话对方、时间从里面解析），
     * [occurredAt] 录音时间（ISO-8601，不知道就 null）。[status] 收进度文字。
     */
    fun process(
        f: File, key: String, title: String, occurredAt: String?,
        fileNo: Int = 1, files: Int = 1, status: (String) -> Unit,
    ): Outcome {
        val saved = savedText(key)
        val local = com.bob.recordinghelper.local.LocalStore.of(ctx)
        val localKey = com.bob.recordinghelper.local.LocalSettings.device(ctx) + ":" + key
        if (local.get("rec-" + com.bob.recordinghelper.local.LocalStore.hash(localKey)) != null) return Outcome.SENT
        if (saved.exists()) {
            status("保存到本机")
            val text = saved.readText()
            local.transcript(localKey, title, text, occurredAt)
            saved.delete()
            com.bob.recordinghelper.local.AnalysisWorker.schedule(ctx)
            return Outcome.SENT
        }
        if (unavailableReason != null) return Outcome.UNAVAILABLE
        val t0 = System.currentTimeMillis()
        val s = session ?: try {
            status("准备中（首次使用要为 NPU 编译模型，可能需要几分钟）")
            LiveAsr.begin(title, fileNo, files, "", false, 0)
            LiveAsr.phase("准备引擎（首次使用要为 NPU 编译模型，可能需要几分钟）")
            LocalAsr.open(ctx).also { session = it }
        } catch (t: Throwable) {
            unavailableReason = t.message?.take(80) ?: t.toString()
            Prefs.setLastAsr(ctx, "引擎不可用：$unavailableReason")
            LiveAsr.finish("引擎不可用：$unavailableReason")
            return Outcome.UNAVAILABLE
        }
        LiveAsr.begin(title, fileNo, files, s.engine.name, s.speakersOn, s.libraryNames)
        val out = try {
            s.transcribe(f, { seg -> status("已转 ${seg.end.toInt()} 秒") }, LiveAsr::on)
        } catch (t: Throwable) {
            Prefs.setLastAsr(ctx, "转写失败（${f.name}）：${t.message?.take(80)}")
            LiveAsr.finish("转写失败：${t.message?.take(80)}")
            return Outcome.FAILED
        }
        val r = out.result
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        val spk = when {
            !s.speakersOn -> ""
            r.speakerError != null -> "；说话人区分出错（${r.speakerError.take(60)}），只传纯文字"
            else -> "；说话人 %d 人（声纹在 %s %.1f 秒，与模型并行；最后多等 %.1f 秒）".format(
                r.speakerCount, s.speakerDevice, r.speakerMs / 1000.0, r.speakerWaitMs / 1000.0)
        }
        val line = ("%s，%.0f 秒录音用时 %.1f 秒（准备引擎 %.1f 秒；与解码并行的特征 %.1f 秒；模型 %.1f 秒；" +
            "跳过静音段 %d 个），%d 字").format(out.engine.name, r.audioSeconds, secs, out.initMs / 1000.0,
            r.featureMs / 1000.0, r.modelMs / 1000.0, r.silentSegments, r.text.length) + spk
        Prefs.setLastAsr(ctx, line + "\n音频：" + com.bob.recordinghelper.asr.AudioDecoder.lastStats)
        LiveAsr.finish(line)
        if (r.text.isBlank()) {
            val id = local.transcript(localKey, title, "（未识别到语音）", occurredAt)
            local.status(id, "silent")
            return Outcome.SILENT
        }
        // phone-asr：服务器把它当录音分析（文件名里的通话对方、通话/会议分类）；分出两人以上时是"名字：文字"逐行
        val upload = r.uploadText
        local.transcript(localKey, title, upload, occurredAt)
        com.bob.recordinghelper.local.AnalysisWorker.schedule(ctx)
        return Outcome.SENT
    }

    override fun close() {
        session?.close()
        session = null
    }

    private fun savedText(key: String): File {
        val hex = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(ctx.filesDir, "asr-unsent/$hex.txt")
    }
}
