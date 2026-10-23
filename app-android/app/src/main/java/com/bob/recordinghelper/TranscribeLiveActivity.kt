package com.bob.recordinghelper

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bob.recordinghelper.databinding.ActivityLiveBinding
import com.bob.recordinghelper.local.AnalysisState
import com.bob.recordinghelper.local.LocalStore
import com.bob.recordinghelper.ui.Ui
import org.json.JSONObject
import java.io.File

/**
 * Live view of on-device speech transcription AND LLM summarization/reasoning stream:
 * shows every ~10 s ASR piece, speaker clustering, and live/recent LLM reasoning tokens, speed, and extracted summary.
 */
class TranscribeLiveActivity : AppCompatActivity() {
    private lateinit var b: ActivityLiveBinding
    private var follow = true
    private var touching = false
    private var focusRecordingId: String? = null
    @Volatile private var storedRec: JSONObject? = null
    private val handler = Handler(Looper.getMainLooper())
    private val listener = { render() }
    private val ticker = object : Runnable {
        override fun run() {
            val s = LiveAsr.snapshot()
            val a = AnalysisState.snapshot()
            if (s.active || s.llmActive || s.polishActive || a.running) {
                render()
                handler.postDelayed(this, 1000)
            }
        }
    }
    private val muted by lazy { ContextCompat.getColor(this, R.color.md_on_surface_variant) }
    private val onPrimary by lazy { ContextCompat.getColor(this, R.color.md_on_primary) }
    private val palette by lazy { intArrayOf(R.color.md_primary, R.color.md_tertiary, R.color.md_secondary, R.color.due_overdue).map { ContextCompat.getColor(this, it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        b = ActivityLiveBinding.inflate(layoutInflater)
        setContentView(b.root)
        val panes = b.root.findViewById<View>(R.id.panes)
        Ui.insets(b.root, top = b.appbar, bottom = panes ?: b.scroll)
        if (panes != null) Ui.column(panes, 1440) else Ui.column(b.scroll, 720)
        b.toolbar.setNavigationOnClickListener { finish() }
        // Follow the newest line only while the user is not holding or dragging the list and it already sits at the bottom.
        b.scroll.setOnScrollChangeListener { _, _, _, _, _ -> if (!touching) follow = atBottom() }
        b.scroll.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> { touching = true; follow = false }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { touching = false; follow = atBottom() }
            }
            false
        }
        b.btnTrial.setOnClickListener {
            if (TrialAsr.running) Toast.makeText(this, "已有一个试转在进行", Toast.LENGTH_SHORT).show()
            else pick.launch(arrayOf("audio/*", "video/mp4"))
        }
        // Battery optimisation off = transcription keeps running with the screen off (vendor settings may still apply).
        b.btnBattery.visibility = if (KeepAwake.unrestricted(this)) View.GONE else View.VISIBLE
        b.btnBattery.setOnClickListener {
            try { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))) }
            catch (_: Exception) { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(i: Intent?) {
        val recId = i?.getStringExtra("recordingId")
        if (!recId.isNullOrBlank()) {
            focusRecordingId = recId
            loadStoredRecording(recId)
        }
        startTrialFrom(i)
    }

    private fun loadStoredRecording(recId: String) {
        val app = applicationContext
        Thread {
            val rec = runCatching { LocalStore.of(app).get(recId) }.getOrNull()
            storedRec = rec
            runOnUiThread { render() }
        }.start()
    }

    /** "trial" extra: a file name inside files/trial/ (put there by the picker below, or by adb when testing). */
    private fun startTrialFrom(i: Intent?) {
        val name = i?.getStringExtra("trial") ?: return
        i.removeExtra("trial")
        if (!TrialAsr.start(this, name)) Toast.makeText(this, "试转没有开始（文件不在或已有任务）：$name", Toast.LENGTH_LONG).show()
    }

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread {
            try {
                val name = (contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val col = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && col >= 0) c.getString(col) else null
                } ?: "picked.audio").replace('/', '_').trimStart('.')
                val dst = File(TrialAsr.dir(this), name)
                contentResolver.openInputStream(uri)!!.use { inp -> dst.outputStream().use { inp.copyTo(it) } }
                runOnUiThread { if (!TrialAsr.start(this, name)) Toast.makeText(this, "试转没有开始（模型未就绪或已有任务）", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "读不了这个文件：${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    override fun onStart() {
        super.onStart()
        LiveAsr.addListener(listener)
        AnalysisState.addListener(listener)
        focusRecordingId?.let { loadStoredRecording(it) }
        render()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1000)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        LiveAsr.removeListener(listener)
        AnalysisState.removeListener(listener)
        super.onStop()
    }

    private fun atBottom(): Boolean = b.scroll.scrollY + b.scroll.height >= (b.scroll.getChildAt(0)?.height ?: 0) - 120

    private fun render() {
        val s = LiveAsr.snapshot()
        val a = AnalysisState.snapshot()
        val head = header(s, a)
        if (b.tvHead.text.toString() != head.toString()) b.tvHead.text = head
        val body = SpannableStringBuilder()
        val llm = llmSection(s, a)
        if (llm.isNotEmpty()) body.append(llm)
        if (s.polishTitle.isNotEmpty()) body.append(polishText(s))
        val main = if (s.final.isNotEmpty()) finalText(s) else liveText(s)
        if (main.isNotEmpty()) body.append(main)
        if (b.tvBody.text.toString() != body.toString()) b.tvBody.text = body
        if (follow && !touching) b.scroll.post { b.scroll.smoothScrollTo(0, (b.scroll.getChildAt(0)?.height ?: 0)) }
        if (s.active || s.llmActive || s.polishActive || a.running) {
            handler.removeCallbacks(ticker)
            handler.postDelayed(ticker, 1000)
        }
    }

    private fun llmSection(s: LiveAsr.Snapshot, a: AnalysisState.Snapshot): CharSequence {
        val sb = SpannableStringBuilder()
        val rec = storedRec
        val hasLiveLlm = s.llmTitle.isNotEmpty() || a.running
        if (hasLiveLlm) {
            val title = s.llmTitle.ifBlank { a.activeTitle.ifBlank { "当前记录" } }
            val model = s.llmModel.ifBlank { a.modelLabel.ifBlank { "本机/在线整理模型" } }
            val task = s.llmTask.ifBlank { "提炼纪要与待办" }
            mark(sb, "大模型总结推理（$model）", StyleSpan(Typeface.BOLD))
            val where = if (s.llmChunks > 1) "　第 ${minOf(maxOf(s.llmChunk, 1), s.llmChunks)}/${s.llmChunks} 段" else ""
            mark(sb, "\n$task · $title$where\n", ForegroundColorSpan(muted))
            val statusLine = s.llmStatus.ifBlank { a.phase.ifBlank { "正在准备整理…" } }
            val elapsedSec = if (s.llmActive && s.llmStartedAt > 0L) ((System.currentTimeMillis() - s.llmStartedAt) / 1000L).coerceAtLeast(1L)
            else if (a.running && a.startedAt > 0L) ((System.currentTimeMillis() - a.startedAt) / 1000L).coerceAtLeast(1L) else 0L
            val statusWithClock = if ((s.llmActive || a.running) && elapsedSec > 0L) "$statusLine（已用时 ${elapsedSec} 秒）" else statusLine
            if (statusWithClock.isNotEmpty()) mark(sb, "$statusWithClock\n\n", ForegroundColorSpan(palette[0]), StyleSpan(Typeface.BOLD))
            val rawStream = s.llmText.ifBlank { a.streamText }
            appendFormattedTrace(sb, rawStream, s.llmActive || a.running)
            if (s.llmFinalSummary.isNotBlank()) {
                mark(sb, "\n【最终提炼总结】\n", StyleSpan(Typeface.BOLD), ForegroundColorSpan(palette[0]))
                sb.append(s.llmFinalSummary).append("\n")
            }
            mark(sb, "\n────────\n\n", ForegroundColorSpan(muted))
        } else if (rec != null) {
            val title = rec.optString("title").ifBlank { "录音记录" }
            val model = rec.optString("analysisModel").ifBlank { "本地/在线整理模型" }
            val stats = rec.optString("analysisStats").ifBlank {
                if (rec.optString("status") == "ready") "已完成纪要与待办提炼" else "等待模型整理"
            }
            val trace = rec.optString("analysisTrace")
            val summary = rec.optString("summary")
            mark(sb, "大模型总结推理记录（$model）", StyleSpan(Typeface.BOLD))
            mark(sb, "\n$title\n", ForegroundColorSpan(muted))
            mark(sb, "$stats\n\n", ForegroundColorSpan(palette[0]), StyleSpan(Typeface.BOLD))
            if (trace.isNotBlank()) {
                appendFormattedTrace(sb, trace, false)
            } else if (summary.isNotBlank()) {
                mark(sb, "（此记录生成于旧版本，未保存原始流式 Token 轨迹，点击详情页「一键重新总结」可实时查看完整推理流。）\n", ForegroundColorSpan(muted))
            }
            if (summary.isNotBlank()) {
                mark(sb, "\n【提炼出的纪要总结】\n", StyleSpan(Typeface.BOLD), ForegroundColorSpan(palette[0]))
                sb.append(summary).append("\n")
            }
            mark(sb, "\n────────\n\n", ForegroundColorSpan(muted))
        }
        return sb
    }

    private fun appendFormattedTrace(sb: SpannableStringBuilder, raw: String, active: Boolean) {
        if (raw.isBlank()) {
            if (active) mark(sb, "正在等待模型预填充上下文并输出第一个 Token…\n", ForegroundColorSpan(muted))
            return
        }
        val thinkStart = raw.indexOf("<think>")
        val thinkEnd = raw.indexOf("</think>")
        if (thinkStart >= 0) {
            val before = raw.substring(0, thinkStart).trim()
            if (before.isNotEmpty()) sb.append(before).append("\n")
            val thinkBody = if (thinkEnd > thinkStart) raw.substring(thinkStart + 7, thinkEnd).trim() else raw.substring(thinkStart + 7).trim()
            if (thinkBody.isNotEmpty()) {
                mark(sb, "【思考推理过程】\n", StyleSpan(Typeface.BOLD), ForegroundColorSpan(muted))
                mark(sb, "$thinkBody\n\n", ForegroundColorSpan(muted))
            }
            if (thinkEnd > thinkStart) {
                val after = raw.substring(thinkEnd + 8).trim()
                if (after.isNotEmpty()) {
                    mark(sb, "【模型结构化输出】\n", StyleSpan(Typeface.BOLD))
                    sb.append(after).append("\n")
                }
            }
        } else {
            mark(sb, "【模型流式推理输出】\n", StyleSpan(Typeface.BOLD))
            sb.append(raw.trim()).append("\n")
        }
    }

    private fun polishText(s: LiveAsr.Snapshot): CharSequence {
        val sb = SpannableStringBuilder()
        mark(sb, "润色（${s.polishModel}）", StyleSpan(Typeface.BOLD))
        val where = if (s.polishChunks > 0) "　第 ${minOf(s.polishChunk + 1, s.polishChunks)}/${s.polishChunks} 段" else ""
        val elapsed = if (s.polishActive && s.polishStartedAt > 0L) "　已用时 ${((System.currentTimeMillis() - s.polishStartedAt) / 1000L).coerceAtLeast(1L)} 秒" else ""
        mark(sb, "　${s.polishTitle}$where$elapsed\n", ForegroundColorSpan(muted))
        if (s.polishStatus.isNotEmpty()) mark(sb, s.polishStatus + "\n", ForegroundColorSpan(palette[0]), StyleSpan(Typeface.BOLD))
        sb.append(s.polishText).append("\n\n")
        mark(sb, "────────\n\n", ForegroundColorSpan(muted))
        return sb
    }

    private fun header(s: LiveAsr.Snapshot, a: AnalysisState.Snapshot): CharSequence {
        val rec = storedRec
        if (s.title.isEmpty() && s.llmTitle.isEmpty() && s.polishTitle.isEmpty() && !a.running && rec == null) {
            return "还没有正在进行的转写或模型推理任务。\n开始录音转写、或在录音详情/首页/待办页点击「立即整理 / 一键重新总结」后，这里会实时显示语音转写与大模型总结推理的流式输出。"
        }
        val sb = StringBuilder()
        if (s.llmTitle.isNotEmpty() || s.polishActive || a.running) {
            val title = s.llmTitle.ifBlank { a.activeTitle.ifBlank { s.polishTitle.ifBlank { "录音整理" } } }
            val model = s.llmModel.ifBlank { a.modelLabel.ifBlank { s.polishModel.ifBlank { "整理模型" } } }
            val elapsed = when {
                s.llmActive && s.llmStartedAt > 0L -> ((System.currentTimeMillis() - s.llmStartedAt) / 1000L).coerceAtLeast(1L)
                s.polishActive && s.polishStartedAt > 0L -> ((System.currentTimeMillis() - s.polishStartedAt) / 1000L).coerceAtLeast(1L)
                a.running && a.startedAt > 0L -> ((System.currentTimeMillis() - a.startedAt) / 1000L).coerceAtLeast(1L)
                else -> 0L
            }
            sb.append("模型任务：").append(title).append('\n')
            sb.append("引擎：").append(model)
            if (elapsed > 0L) sb.append("　已用时 ").append(elapsed).append(" 秒")
            sb.append('\n')
            sb.append("状态：").append(a.phase.ifBlank { s.llmStatus.ifBlank { s.polishStatus.ifBlank { "处理中" } } })
            if (s.title.isNotEmpty()) sb.append("\n────────\n")
        } else if (rec != null && s.title.isEmpty()) {
            sb.append("记录：").append(rec.optString("title").ifBlank { "录音记录" }).append('\n')
            sb.append("模型：").append(rec.optString("analysisModel").ifBlank { "本机/在线整理模型" }).append('\n')
            sb.append("状态：").append(rec.optString("analysisStats").ifBlank { "已生成纪要与待办" })
        }
        if (s.title.isNotEmpty()) {
            val elapsed = ((if (s.active) System.currentTimeMillis() else s.startedAt + 1) - s.startedAt).coerceAtLeast(1) / 1000.0
            sb.append(if (s.files > 1) "语音转写 第 ${s.fileNo}/${s.files} 个：" else "语音转写：").append(s.title).append('\n')
            sb.append("引擎：").append(engineName(s.engine)).append("　").append(s.phase).append('\n')
            val speech = s.pieces - s.silent
            sb.append("已处理 ").append(clock(s.audioDone))
            if (s.active && s.audioDone > 0) sb.append("（%.0f 倍实时）".format(s.audioDone / elapsed))
            sb.append("　片段 ").append(s.pieces)
            if (s.silent > 0) sb.append("（静音跳过 ").append(s.silent).append("）")
            if (speech > 0) sb.append("　模型 %.0f ms/段".format(s.modelMs.toDouble() / speech))
            sb.append('\n')
            sb.append("说话人：").append(
                when {
                    !s.speakersOn -> "未开启（模型目录里没有 spk.tflite）"
                    s.speakerCount == 0 -> "归类中…"
                    else -> "${s.speakerCount} 人" + if (s.libraryNames > 0) "（声纹库 ${s.libraryNames} 人）" else "（未放声纹库，只编号）"
                })
            if (s.summary.isNotEmpty()) sb.append('\n').append(s.summary)
        }
        return sb
    }

    private fun liveText(s: LiveAsr.Snapshot): CharSequence {
        val sb = SpannableStringBuilder()
        for (r in s.rows) {
            mark(sb, clock(r.start) + "  ", ForegroundColorSpan(muted))
            if (s.speakersOn) { chip(sb, if (r.speaker > 0) " 说话人${r.speaker} " else " … ", r.speaker); sb.append(' ') }
            if (r.modelMs == 0L) mark(sb, "（静音，未送模型）", ForegroundColorSpan(muted))
            else if (r.text.isEmpty()) mark(sb, "（无文字）", ForegroundColorSpan(muted))
            else sb.append(r.text)
            if (r.modelMs > 0) mark(sb, "  ${r.modelMs} ms", ForegroundColorSpan(muted), RelativeSizeSpan(0.8f))
            sb.append("\n\n")
        }
        if (s.rows.isEmpty()) mark(sb, if (s.active) "等待第一个片段…" else "", ForegroundColorSpan(muted))
        return sb
    }

    private fun finalText(s: LiveAsr.Snapshot): CharSequence {
        val sb = SpannableStringBuilder()
        mark(sb, "最终结果（按说话人合并）\n\n", StyleSpan(Typeface.BOLD))
        val order = LinkedHashMap<String, Int>()
        for (seg in s.final) {
            val idx = order.getOrPut(seg.speaker) { order.size + 1 }
            mark(sb, clock(seg.start) + "  ", ForegroundColorSpan(muted))
            chip(sb, " ${seg.speaker} ", idx)
            sb.append(' ').append(seg.text).append("\n\n")
        }
        return sb
    }

    private fun chip(sb: SpannableStringBuilder, label: String, n: Int) {
        val c = if (n <= 0) muted else palette[(n - 1) % palette.size]
        mark(sb, label, BackgroundColorSpan(c), ForegroundColorSpan(onPrimary), StyleSpan(Typeface.BOLD))
    }

    private fun mark(sb: SpannableStringBuilder, text: String, vararg spans: Any) {
        val a = sb.length
        sb.append(text)
        for (sp in spans) sb.setSpan(sp, a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    companion object {
        fun intent(ctx: Context, recordingId: String? = null): Intent =
            Intent(ctx, TranscribeLiveActivity::class.java).apply {
                if (!recordingId.isNullOrBlank()) putExtra("recordingId", recordingId)
            }

        fun clock(s: Double): String {
            val t = s.toInt()
            return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%02d:%02d".format(t / 60, t % 60)
        }

        fun engineName(e: String): String = when (e) {
            "NEURON" -> "联发科 NPU（NeuroPilot）"
            "QNN" -> "高通 NPU（Hexagon）"
            "NNAPI" -> "系统 NNAPI"
            "CPU" -> "CPU"
            "" -> "准备中"
            else -> e
        }
    }
}
