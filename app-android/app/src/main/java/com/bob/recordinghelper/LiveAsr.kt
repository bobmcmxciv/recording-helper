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
        val polishActive: Boolean = false,
        val polishStartedAt: Long = 0L,
        val llmActive: Boolean = false,
        val llmTitle: String = "",
        val llmModel: String = "",
        val llmTask: String = "",
        val llmChunk: Int = 0,
        val llmChunks: Int = 0,
        val llmStartedAt: Long = 0L,
        val llmText: String = "",
        val llmStatus: String = "",
        val llmFinalSummary: String = "",
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
    private var polishActive = false
    private var polishStartedAt = 0L
    private val polishDoneText = StringBuilder()
    private var llmActive = false
    private var llmTitle = ""
    private var llmModel = ""
    private var llmTask = ""
    private var llmChunk = 0
    private var llmChunks = 0
    private var llmStartedAt = 0L
    private val llmStream = StringBuilder()
    private var llmGeneratedChars = 0
    private var llmStatus = ""
    private var llmFinalSummary = ""

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main: Handler? = runCatching { Looper.getMainLooper()?.let { Handler(it) } }.getOrNull()
    @Volatile private var posted = false

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun changed() {
        val h = main
        if (h == null) {
            listeners.forEach { it() }
            return
        }
        if (posted) return
        posted = true
        h.postDelayed({ posted = false; listeners.forEach { it() } }, 180)
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
            polishText = ""; polishStatus = "正在准备本地润色…"; polishDoneText.setLength(0)
            polishActive = true; polishStartedAt = System.currentTimeMillis()
        }
        changed()
    }

    /** 当前段（第 [chunk] 段，共 [chunks] 段）模型已输出的文字。 */
    fun polishProgress(chunk: Int, chunks: Int, partial: String, status: String = "") {
        synchronized(lock) {
            polishActive = true
            if (chunk > polishChunk && polishText.isNotEmpty()) polishDoneText.append(polishText).append('\n')
            polishChunk = chunk; polishChunks = chunks; polishText = partial
            polishStatus = when {
                status.isNotEmpty() -> status
                partial.isEmpty() -> "正在预填充第 ${chunk + 1}/$chunks 段原文（CPU 4 核计算中）…"
                else -> "正在流式润色第 ${chunk + 1}/$chunks 段（已生成 ${partial.length} 字）…"
            }
        }
        changed()
    }

    /** 一条录音润色结束；[text] 是完整润色稿（失败时为空）。 */
    fun polishDone(status: String, text: String = "") {
        synchronized(lock) {
            polishActive = false
            polishStatus = status
            if (text.isNotEmpty()) { polishDoneText.setLength(0); polishText = text; polishChunk = polishChunks }
        }
        changed()
    }

    /** 大模型开始提炼纪要/待办或生成时段总结。 */
    fun llmBegin(title: String, model: String, task: String, chunks: Int = 1) {
        synchronized(lock) {
            llmActive = true
            llmTitle = title
            llmModel = model
            llmTask = task
            llmChunk = 1
            llmChunks = maxOf(chunks, 1)
            llmStartedAt = System.currentTimeMillis()
            llmStream.setLength(0)
            llmGeneratedChars = 0
            llmStatus = "正在准备推理上下文…"
            llmFinalSummary = ""
        }
        changed()
    }

    /** 大模型进入第 [chunk]/[chunks] 段推理。 */
    fun llmChunk(chunk: Int, chunks: Int, status: String = "") {
        synchronized(lock) {
            llmActive = true
            llmChunk = chunk
            llmChunks = maxOf(chunks, 1)
            if (status.isNotEmpty()) llmStatus = status
        }
        changed()
    }

    /** 大模型正在预填充提示词或等待云端首包。 */
    fun llmPrefill(chunk: Int, chunks: Int, status: String) {
        synchronized(lock) {
            llmActive = true
            llmChunk = chunk
            llmChunks = maxOf(chunks, 1)
            llmGeneratedChars = 0
            llmStatus = status
        }
        changed()
    }

    /** 大模型流式输出一个片段（含 `<think>` 思考过程或 JSON 内容）。 */
    fun llmToken(piece: String) {
        if (piece.isEmpty()) return
        synchronized(lock) {
            llmActive = true
            llmStream.append(piece)
            llmGeneratedChars += piece.length
            val s = llmStream.toString()
            val thinking = s.lastIndexOf("<think>") > s.lastIndexOf("</think>")
            val stage = if (thinking) "正在深度思考推理" else "正在流式生成"
            llmStatus = if (llmChunks > 1) {
                "$stage（第 $llmChunk/$llmChunks 段 · 已生成 $llmGeneratedChars 字）…"
            } else {
                "$stage（已生成 $llmGeneratedChars 字）…"
            }
        }
        changed()
    }

    /** 大模型完成或中断一条推理任务。 */
    fun llmFinish(status: String, finalSummary: String = "") {
        synchronized(lock) {
            llmActive = false
            llmStatus = status
            if (finalSummary.isNotBlank()) llmFinalSummary = finalSummary.trim()
        }
        changed()
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            active, title, fileNo, files, engine, phase, speakersOn, libraryNames, startedAt,
            rows.lastOrNull()?.end ?: 0.0, rows.size, rows.count { it.modelMs == 0L }, rows.sumOf { it.modelMs },
            speakerCount, rows.toList(), final, summary,
            polishTitle, polishModel, polishChunk, polishChunks, polishDoneText.toString() + polishText, polishStatus,
            polishActive, polishStartedAt,
            llmActive, llmTitle, llmModel, llmTask, llmChunk, llmChunks, llmStartedAt,
            llmStream.toString(), llmStatus, llmFinalSummary,
        )
    }
}
