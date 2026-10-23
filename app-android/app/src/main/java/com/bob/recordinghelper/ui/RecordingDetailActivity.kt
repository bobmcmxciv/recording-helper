package com.bob.recordinghelper.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bob.recordinghelper.LocalAsr
import com.bob.recordinghelper.Progress
import com.bob.recordinghelper.R
import com.bob.recordinghelper.SettingsActivity
import com.bob.recordinghelper.databinding.ActivityRecordingDetailBinding
import com.bob.recordinghelper.databinding.ItemTodoBinding
import com.bob.recordinghelper.llm.LlmSettings
import com.bob.recordinghelper.local.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class RecordingDetailActivity : AppCompatActivity() {
    private lateinit var b: ActivityRecordingDetailBinding
    private val id: String by lazy { intent.getStringExtra("id").orEmpty() }
    private var rec: JSONObject? = null
    private var todos: List<JSONObject> = emptyList()
    private var audio: String? = null
    private var player: MediaPlayer? = null
    private var transcriptPane: android.widget.TextView? = null
    private var wasWorkingOnThis = false
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable { override fun run() { tick(); handler.postDelayed(this, 500) } }
    private val statusTicker = object : Runnable {
        override fun run() {
            if (AnalysisState.isQueuedOrActive(id) || rec?.optString("status") == "analyzing") {
                bindHeader()
                render()
                handler.postDelayed(this, 1000)
            }
        }
    }
    private val analysisListener: () -> Unit = {
        val working = AnalysisState.isQueuedOrActive(id)
        if (working) {
            wasWorkingOnThis = true
            handler.removeCallbacks(statusTicker)
            handler.post(statusTicker)
            bindHeader()
            render()
        } else if (wasWorkingOnThis) {
            wasWorkingOnThis = false
            handler.removeCallbacks(statusTicker)
            load()
        } else {
            bindHeader()
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        b = ActivityRecordingDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        val panes = b.root.findViewById<View>(R.id.panes)
        Ui.insets(b.root, top = b.appbar, bottom = panes ?: b.scroll)
        if (panes != null) Ui.column(panes, 1440) else Ui.column(b.scroll, 720)
        transcriptPane = b.root.findViewById(R.id.tvTranscript)
        if (transcriptPane != null) b.secText.visibility = View.GONE  // landscape: the transcript has its own pane
        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_share -> share()
                R.id.action_copy -> copy()
                R.id.action_reanalyze -> reanalyze()
                R.id.action_live_trace -> startActivity(com.bob.recordinghelper.TranscribeLiveActivity.intent(this, id))
                R.id.action_delete -> delete()
            }
            true
        }
        b.sections.addOnButtonCheckedListener { _, _, _ -> render() }
        b.btnPlay.setOnClickListener { togglePlay() }
    }

    override fun onResume() {
        super.onResume()
        wasWorkingOnThis = AnalysisState.isQueuedOrActive(id)
        AnalysisState.addListener(analysisListener)
        load()
        if (wasWorkingOnThis) {
            handler.removeCallbacks(statusTicker)
            handler.postDelayed(statusTicker, 1000)
        }
    }

    override fun onPause() {
        AnalysisState.removeListener(analysisListener)
        handler.removeCallbacks(statusTicker)
        stopPlayer()
        super.onPause()
    }

    private fun load() {
        val ctx = applicationContext
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val store = LocalStore.of(ctx)
                val r = store.get(id)
                val mic = MicArchive.recordings(ctx).firstOrNull { "rec-" + LocalStore.hash(MicArchive.key(ctx, it.getString("id"))) == id }
                    ?.let { MicArchive.audio(ctx, it.getString("id")).absolutePath }
                Triple(r, store.todosFor(id), mic ?: RecordingQueue.of(ctx).sourceFor(ctx, id))
            }
            rec = result.first; todos = result.second; audio = result.third
            if (rec == null || rec!!.optBoolean("deleted")) { Ui.toast(this@RecordingDetailActivity, "记录不存在或已删除"); finish(); return@launch }
            bindHeader(); render()
        }
    }

    private fun bindHeader() {
        val r = rec ?: return
        b.tvTitle.text = r.optString("title").ifBlank { "未命名录音" }
        Binders.status(b.chipStatus, r.optString("status"), id)
        b.chipDate.text = Ui.friendly(r.optString("occurredAt")).ifBlank { Ui.date(r.optString("occurredAt")) }
        Ui.tint(b.chipDate, R.color.status_neutral_bg, R.color.status_neutral_fg)
        val speakers = Binders.speakerCount(r.optString("text"))
        val chars = r.optString("text").length
        b.chipMeta.text = buildList { if (speakers >= 2) add("$speakers 位说话人"); if (chars > 0) add("$chars 字") }.joinToString(" · ").ifBlank { "文字记录" }
        Ui.tint(b.chipMeta, R.color.status_neutral_bg, R.color.status_neutral_fg)
        val error = r.optString("error")
        b.tvError.visibility = if (error.isBlank()) View.GONE else View.VISIBLE
        b.tvError.text = "处理提示：$error"
        val playable = audio?.let { it.startsWith("content://") || File(it).isFile } == true
        b.cardPlayer.visibility = if (playable) View.VISIBLE else View.GONE
        transcriptPane?.text = transcript(r.optString("text"))
    }

    private fun render() {
        val r = rec ?: return
        b.listTodos.removeAllViews()
        b.btnAction.visibility = View.GONE
        b.btnTrace.visibility = View.GONE
        b.tvBody.visibility = View.VISIBLE
        val summary = r.optString("summary")
        val status = r.optString("status")
        val snap = AnalysisState.snapshot()
        val active = snap.activeId == id || status == "analyzing"
        val queued = id in snap.queuedIds
        val elapsed = if (snap.startedAt > 0L && (active || queued)) ((System.currentTimeMillis() - snap.startedAt) / 1000L).coerceAtLeast(1L) else 0L
        val downloadingLlm = !AnalysisWorker.canAnalyze(this) && AnalysisWorker.isDownloadingLlm(this)

        when (b.sections.checkedButtonId) {
            R.id.secTodos -> {
                b.tvBody.visibility = if (todos.isEmpty()) View.VISIBLE else View.GONE
                b.tvBody.text = when {
                    active || queued -> "正在整理本条录音（已用时 ${elapsed} 秒），完成后提取的待办会自动显示在这里。"
                    status == "ready" -> "这段录音没有提取出待办。"
                    else -> "整理完成后，提取的待办会出现在这里。"
                }
                for (t in todos) {
                    val row = ItemTodoBinding.inflate(layoutInflater, b.listTodos, false)
                    val tid = t.getString("id")
                    Binders.todo(row, t, onToggle = { checked -> lifecycleScope.launch { withContext(Dispatchers.IO) { LocalStore.of(applicationContext).setDone(tid, checked); SyncWorker.schedule(applicationContext) } } },
                        onOpen = { TodoEditSheet.newInstance(tid).show(supportFragmentManager, "todo") }, onSource = null)
                    b.listTodos.addView(row.root)
                }
                supportFragmentManager.setFragmentResultListener("changed", this) { _, _ -> load() }
                bindSummaryActions(active, queued, downloadingLlm, summary, status)
            }
            R.id.secText -> b.tvBody.text = transcript(r.optString("text"))
            R.id.secPolished -> {
                val polished = r.optString("polished")
                val hasPolished = polished.isNotBlank() && polished != r.optString("text")
                b.tvBody.text = when {
                    active && snap.phase.contains("润色") -> {
                        val stream = if (snap.activeId == id && snap.streamText.isNotBlank()) "\n\n【实时润色输出】\n${snap.streamText.takeLast(800)}" else ""
                        "${snap.phase}\n已用时 ${elapsed} 秒$stream"
                    }
                    polished.isNotBlank() -> polished
                    else -> "没有润色稿。可点击下方「一键本地润色并总结」生成校对后的文稿。"
                }
                if (status != "silent") {
                    b.btnAction.visibility = View.VISIBLE
                    if (active || queued) {
                        b.btnAction.text = if (active) "取消整理" else "取消排队"
                        b.btnAction.setOnClickListener { cancelAnalyze() }
                    } else {
                        b.btnAction.text = if (hasPolished) "一键重新润色并总结" else "一键本地润色并总结"
                        b.btnAction.setOnClickListener { reanalyze(forcePolish = true) }
                    }
                }
            }
            else -> {
                val metaLine = listOf(r.optString("analysisModel"), r.optString("analysisStats"))
                    .filter { it.isNotBlank() }.joinToString(" · ")
                b.tvBody.text = when {
                    active -> {
                        val phase = snap.phase.ifBlank { "本机模型正在整理纪要与待办…" }
                        val stream = if (snap.activeId == id && snap.streamText.isNotBlank()) "\n\n【模型实时推理输出】\n${snap.streamText.takeLast(800)}" else ""
                        val prev = if (summary.isNotBlank()) "\n\n────────\n【原纪要（整理完成后自动更新）】\n$summary" else ""
                        "$phase\n已用时 ${elapsed} 秒 · 完成后此页面会自动刷新，也可随时取消。$stream$prev"
                    }
                    queued -> {
                        val pos = (snap.queuedIds.indexOf(id) + 1).coerceAtLeast(1)
                        val waitHint = if (snap.activeId != null) "正在等待当前记录整理完成（优先队列第 $pos 位）…" else snap.phase.ifBlank { "已加入优先队列，正在启动整理模型…" }
                        val prev = if (summary.isNotBlank()) "\n\n────────\n【原纪要（整理完成后自动更新）】\n$summary" else ""
                        "$waitHint\n已等待 ${elapsed} 秒 · 整理完成后此页面会自动刷新。$prev"
                    }
                    summary.isNotBlank() -> if (metaLine.isNotBlank()) "$summary\n\n— $metaLine" else summary
                    downloadingLlm -> "本地整理模型正在后台下载，下载完成后会自动整理并刷新此页面。"
                    status == "error" -> "上次整理未完成。可以点击下方按钮重试，原文始终保留。"
                    status == "silent" -> "这段录音没有识别到语音。"
                    else -> "等待本机模型整理纪要与待办。点击「立即整理」将优先处理本条录音。"
                }
                bindSummaryActions(active, queued, downloadingLlm, summary, status)
            }
        }
    }

    private fun bindSummaryActions(active: Boolean, queued: Boolean, downloadingLlm: Boolean, summary: String, status: String) {
        val r = rec ?: return
        when {
            active || queued -> {
                b.btnAction.visibility = View.VISIBLE
                b.btnAction.text = if (active) "取消整理" else "取消排队"
                b.btnAction.setOnClickListener { cancelAnalyze() }
            }
            downloadingLlm && summary.isBlank() && status != "silent" -> {
                b.btnAction.visibility = View.VISIBLE
                b.btnAction.text = "查看模型下载进度"
                b.btnAction.setOnClickListener { startActivity(SettingsActivity.intent(this, "models")) }
            }
            summary.isNotBlank() && status != "silent" -> {
                b.btnAction.visibility = View.VISIBLE
                val isQuick = r.optBoolean("quickExtracted") && !AnalysisWorker.canAnalyze(this)
                b.btnAction.text = if (isQuick) "使用 AI 大模型深度整理" else "一键重新总结"
                b.btnAction.setOnClickListener { reanalyze() }
            }
            summary.isBlank() && status != "silent" -> {
                b.btnAction.visibility = View.VISIBLE
                b.btnAction.text = if (status == "error") "重试整理" else "立即整理"
                b.btnAction.setOnClickListener { reanalyze() }
            }
        }
        if (active || queued || summary.isNotBlank() || r.optString("analysisTrace").isNotBlank()) {
            b.btnTrace.visibility = View.VISIBLE
            b.btnTrace.text = if (active) "全屏查看实时推理过程" else "查看模型总结推理过程"
            b.btnTrace.setOnClickListener {
                startActivity(com.bob.recordinghelper.TranscribeLiveActivity.intent(this, id))
            }
        }
    }

    /** Speaker-labelled lines become coloured labels; everything else stays as paragraphs. */
    private fun transcript(text: String): CharSequence {
        if (text.isBlank()) return "没有转写文字。"
        val sb = SpannableStringBuilder()
        val palette = intArrayOf(R.color.md_primary, R.color.md_tertiary, R.color.md_secondary, R.color.due_overdue)
        val speakers = LinkedHashMap<String, Int>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val m = Binders.SPEAKER_LINE.find(line)
            if (m != null) {
                val name = m.groupValues[1].trim()
                val idx = speakers.getOrPut(name) { speakers.size }
                val start = sb.length
                sb.append(name)
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, palette[idx % palette.size])), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append("\n").append(m.groupValues[2].trim()).append("\n\n")
            } else sb.append(line).append("\n\n")
        }
        return sb.trimEnd()
    }

    private fun currentText(): String {
        val r = rec ?: return ""
        return when (b.sections.checkedButtonId) {
            R.id.secText -> r.optString("text")
            R.id.secPolished -> r.optString("polished")
            R.id.secTodos -> todos.joinToString("\n") { "- " + it.optString("title") + it.optString("due").let { d -> if (d.isBlank()) "" else "（$d）" } }
            else -> r.optString("summary")
        }
    }

    private fun copy() {
        val text = currentText().ifBlank { rec?.optString("text").orEmpty() }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("录音助手", text))
        Ui.toast(this, "已复制")
    }

    private fun share() {
        val r = rec ?: return
        val body = "【${r.optString("title")}】\n\n纪要：\n${r.optString("summary").ifBlank { "（未整理）" }}\n\n待办：\n" +
            todos.joinToString("\n") { "- " + it.optString("title") }.ifBlank { "（无）" } + "\n\n原文：\n${r.optString("text")}"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, r.optString("title")).putExtra(Intent.EXTRA_TEXT, body), "分享"))
    }

    private fun reanalyze(forcePolish: Boolean = false) {
        if (AnalysisState.isQueuedOrActive(id)) {
            Ui.toast(this, "正在整理本条录音，请稍候")
            bindHeader()
            render()
            return
        }
        if (!AnalysisWorker.canAnalyze(this)) {
            if (AnalysisWorker.isDownloadingLlm(this)) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { LocalStore.of(applicationContext).status(id, "pending") }
                    Ui.toast(this@RecordingDetailActivity, "本地整理模型正在后台下载，完成后会自动整理")
                    load()
                }
                return
            }
            promptInstallLlm()
            return
        }
        wasWorkingOnThis = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { AnalysisWorker.requestNow(applicationContext, id, forcePolish = forcePolish) }
            handler.removeCallbacks(statusTicker)
            handler.post(statusTicker)
            Ui.toast(this@RecordingDetailActivity, if (forcePolish) "已开始润色并重新总结，完成后自动刷新" else "已优先开始重新总结，完成后自动刷新")
            load()
        }
    }

    private fun cancelAnalyze() {
        wasWorkingOnThis = false
        handler.removeCallbacks(statusTicker)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { AnalysisWorker.cancelActive(applicationContext, id) }
            Ui.toast(this@RecordingDetailActivity, "已取消整理")
            load()
        }
    }

    private fun promptInstallLlm() {
        val cached = ModelFiles.cachedPartBytes(this, "model.gguf")
        val dlBtn = if (cached > 0L) "继续续传（已缓存 ${Progress.human(cached)}）" else "后台下载本地模型"
        MaterialAlertDialogBuilder(this)
            .setTitle("选择深度整理方式")
            .setMessage("当前已通过本机规则生成初筛摘要与待办。如需 AI 深度语义整理，推荐使用在线 AI 服务（0 下载 · 秒出纪要），也可在后台下载本地大模型（约 2.4 GB，支持断点续传，下载完成后自动重整）。")
            .setPositiveButton("使用在线 AI（0 下载）") { _, _ -> startActivity(AiSettingsActivity.intent(this)) }
            .setNeutralButton(dlBtn) { _, _ -> downloadLlmInBackground() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun downloadLlmInBackground() {
        val start = {
            lifecycleScope.launch {
                val res = withContext(Dispatchers.IO) {
                    LocalStore.of(applicationContext).status(id, "pending")
                    runCatching { ModelSource.fetch(ModelSource.manifestUrl(applicationContext)) }
                }
                res.onSuccess { entries ->
                    val wanted = ModelSource.missingEntries(applicationContext, entries, includeLlm = true)
                    if (wanted.isEmpty()) {
                        AnalysisWorker.requestNow(applicationContext, id)
                        load()
                        return@onSuccess
                    }
                    val free = (LocalAsr.modelDir(applicationContext).parentFile ?: filesDir).usableSpace
                    val need = wanted.sumOf { it.size }
                    if (need > 0 && free < need + 200L * 1024 * 1024) {
                        Ui.toast(this@RecordingDetailActivity, "可用空间不足（需约 ${Progress.human(need)}）")
                        return@onSuccess
                    }
                    ModelSource.enqueue(applicationContext, wanted)
                    Ui.toast(this@RecordingDetailActivity, "已开始后台下载，安装完成后自动整理")
                    load()
                }.onFailure {
                    Ui.toast(this@RecordingDetailActivity, "无法获取模型清单：${it.message}")
                }
            }
        }
        if (!LlmSettings.onUnmeteredNetwork(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("当前不是 Wi-Fi")
                .setMessage("本地整理模型约 2.4 GB，可能消耗较多移动流量。是否继续后台下载？")
                .setPositiveButton("继续下载") { _, _ -> start() }
                .setNegativeButton("等 Wi-Fi", null)
                .show()
        } else start()
    }

    private fun delete() {
        MaterialAlertDialogBuilder(this).setTitle("删除这条记录？").setMessage("转写、纪要和关联的待办都会被删除；原始录音文件不受影响。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch { withContext(Dispatchers.IO) { LocalStore.of(applicationContext).delete(id); SyncWorker.schedule(applicationContext) }; finish() }
            }.setNegativeButton("取消", null).show()
    }

    private fun togglePlay() {
        val src = audio ?: return
        val p = player
        if (p != null) {
            if (p.isPlaying) { p.pause(); b.btnPlay.setIconResource(R.drawable.ic_play) } else { p.start(); b.btnPlay.setIconResource(R.drawable.ic_pause) }
            return
        }
        try {
            val media = MediaPlayer()
            if (src.startsWith("content://")) media.setDataSource(this, Uri.parse(src)) else media.setDataSource(src)
            media.setOnCompletionListener { b.btnPlay.setIconResource(R.drawable.ic_play); b.playProgress.progress = 0 }
            media.setOnErrorListener { _, _, _ -> stopPlayer(); Ui.toast(this, "无法播放原录音"); true }
            media.prepare(); media.start()
            player = media
            b.playProgress.max = maxOf(media.duration, 1)
            b.btnPlay.setIconResource(R.drawable.ic_pause)
            handler.post(ticker)
        } catch (_: Exception) { stopPlayer(); Ui.toast(this, "无法播放：原文件可能已移动或授权失效") }
    }

    private fun tick() {
        val p = player ?: return
        runCatching {
            b.playProgress.progress = p.currentPosition
            b.tvPlayTime.text = "${Ui.duration(p.currentPosition / 1000L)} / ${Ui.duration(p.duration / 1000L)}"
        }
    }

    private fun stopPlayer() {
        handler.removeCallbacks(ticker)
        runCatching { player?.release() }
        player = null
        if (::b.isInitialized) b.btnPlay.setIconResource(R.drawable.ic_play)
    }

    companion object {
        fun intent(ctx: Context, id: String) = Intent(ctx, RecordingDetailActivity::class.java).putExtra("id", id)
    }
}
