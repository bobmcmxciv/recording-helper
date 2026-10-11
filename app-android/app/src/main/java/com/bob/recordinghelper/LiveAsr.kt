package com.bob.recordinghelper

import android.os.Handler
import android.os.Looper
import com.bob.recordinghelper.asr.Transcriber
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 本地转写的实时状态（整个进程一份）：后台的 [PhoneAsrBatch] 写，转写过程页 [TranscribeLiveActivity] 读。
 * 每个 10 秒左右的片段从模型回来就记一行（时间、文字、模型耗时）；说话人线程每处理一分钟音频重新归类一次，
 * 已显示的行跟着更新说话人；整段结束后换成按说话人合并好的最终结果。监听者在主线程收到通知（最多每 200 ms 一次）。
 */
object LiveAsr {

    data class Row(val index: Int, val start: Double, val end: Double, val text: String, val modelMs: Long, val speaker: Int)

    data class Snapshot(
        val active: Boolean,
        val title: String,
        val fileNo: Int,
        val files: Int,
        val engine: String,
        val phase: String,
        val speakersOn: Boolean,
        val libraryNames: Int,
        val startedAt: Long,
        val audioDone: Double,
        val pieces: Int,
        val silent: Int,
        val modelMs: Long,
        val speakerCount: Int,
        val rows: List<Row>,
        val final: List<Transcriber.Segment>,
        val summary: String,
        val polishTitle: String,
        val polishModel: String,
        val polishChunk: Int,
        val polishChunks: Int,
        val polishText: String,
        val polishStatus: String,
    )

    private val lock = Any()
    private var active = false
    private var title = ""
    private var fileNo = 0
    private var files = 0
    private var engine = ""
    private var phase = "还没有转写任务"
    private var speakersOn = false
    private var libraryNames = 0
    private var startedAt = 0L
    private val rows = ArrayList<Row>()
    private var speakerByPiece = IntArray(0)
    private var speakerCount = 0
    private var final: List<Transcriber.Segment> = emptyList()
    private var summary = ""
    private var polishTitle = ""
    private var polishModel = ""
    private var polishChunk = 0
    private var polishChunks = 0
    private var polishText = ""
    private var polishStatus = ""
    private val polishDoneText = StringBuilder()

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var posted = false

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun changed() {
        if (posted) return
        posted = true
        main.postDelayed({ posted = false; listeners.forEach { it() } }, 200)
    }

    /** 一个文件开始转写。 */
    fun begin(title: String, fileNo: Int, files: Int, engine: String, speakersOn: Boolean, libraryNames: Int) {
        synchronized(lock) {
            active = true
            this.title = title; this.fileNo = fileNo; this.files = files; this.engine = engine
            this.speakersOn = speakersOn; this.libraryNames = libraryNames
            phase = "转写中"
            startedAt = System.currentTimeMillis()
            rows.clear(); speakerByPiece = IntArray(0); speakerCount = 0; final = emptyList(); summary = ""
        }
        changed()
    }

    /** 准备引擎等阶段性提示。 */
    fun phase(text: String) {
        synchronized(lock) { phase = text }
        changed()
    }

    fun on(e: Transcriber.Live) {
        synchronized(lock) {
            when (e) {
                is Transcriber.Live.Piece -> rows.add(Row(e.index, e.start, e.end, e.text, e.modelMs,
                    speakerByPiece.getOrElse(e.index) { 0 }))
                is Transcriber.Live.Speakers -> {
                    speakerByPiece = e.byPiece
                    speakerCount = e.count
                    for (i in rows.indices) rows[i] = rows[i].copy(speaker = e.byPiece.getOrElse(rows[i].index) { rows[i].speaker })
                }
                is Transcriber.Live.Final -> {
                    final = e.segments
                    speakerCount = e.segments.map { it.speaker }.toSet().size
                }
            }
        }
        changed()
    }

    /** 一个文件结束（成功或失败），[summary] 是给人看的一句话。 */
    fun finish(summary: String) {
        synchronized(lock) { active = false; phase = "已完成"; this.summary = summary }
        changed()
    }

    /** 润色开始一条录音。 */
    fun polishBegin(title: String, model: String) {
        synchronized(lock) {
            polishTitle = title; polishModel = model; polishChunk = 0; polishChunks = 0
            polishText = ""; polishStatus = "润色中"; polishDoneText.setLength(0)
        }
        changed()
    }

    /** 当前段（第 [chunk] 段，共 [chunks] 段）模型已输出的文字。 */
    fun polishProgress(chunk: Int, chunks: Int, partial: String) {
        synchronized(lock) {
            if (chunk > polishChunk && polishText.isNotEmpty()) polishDoneText.append(polishText).append('\n')
            polishChunk = chunk; polishChunks = chunks; polishText = partial
        }
        changed()
    }

    /** 一条录音润色结束；[text] 是完整润色稿（失败时为空）。 */
    fun polishDone(status: String, text: String = "") {
        synchronized(lock) {
            polishStatus = status
            if (text.isNotEmpty()) { polishDoneText.setLength(0); polishText = text; polishChunk = polishChunks }
        }
        changed()
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(active, title, fileNo, files, engine, phase, speakersOn, libraryNames, startedAt,
            rows.lastOrNull()?.end ?: 0.0, rows.size, rows.count { it.modelMs == 0L }, rows.sumOf { it.modelMs },
            speakerCount, rows.toList(), final, summary,
            polishTitle, polishModel, polishChunk, polishChunks, polishDoneText.toString() + polishText, polishStatus)
    }
}
