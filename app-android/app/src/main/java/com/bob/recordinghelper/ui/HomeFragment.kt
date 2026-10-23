package com.bob.recordinghelper.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.bob.recordinghelper.LocalAsr
import com.bob.recordinghelper.Prefs
import com.bob.recordinghelper.Progress
import com.bob.recordinghelper.R
import com.bob.recordinghelper.SettingsActivity
import com.bob.recordinghelper.TranscribeLiveActivity
import com.bob.recordinghelper.databinding.FragmentHomeBinding
import com.bob.recordinghelper.databinding.ItemRecordingBinding
import com.bob.recordinghelper.databinding.ItemTodoBinding
import com.bob.recordinghelper.llm.LlmEngine
import com.bob.recordinghelper.llm.LlmSettings
import com.bob.recordinghelper.local.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class HomeFragment : TabFragment() {
    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!

    private class Snapshot(
        val openTodos: Int, val todayTodos: List<JSONObject>, val reviewCount: Int,
        val recent: List<JSONObject>, val recordings: Int, val pendingAnalysisCount: Int, val todoCounts: Map<String, Int>,
        val queue: JSONObject, val autoScan: Boolean, val scope: String?, val scopeLabel: String,
        val scanStatus: String?, val importStatus: String?, val hasAccess: Boolean, val modelReady: Boolean,
        val llmReady: Boolean,
    )

    private var modelDownloadLine: String? = null

    private val quickSetupPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val ctx = context ?: return@registerForActivityResult
        if (granted || AutoImportSettings.hasAccess(ctx)) {
            completeQuickSetup(ctx)
        } else {
            enqueueSuiteInBackground(ctx.applicationContext)
            toast("模型已开始后台下载；可在设置中选择录音文件夹")
            startActivity(Intent(ctx, SettingsActivity::class.java))
        }
    }

    private val importAudio = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val ctx = requireContext().applicationContext
            lifecycleScope.launch {
                val message = withContext(Dispatchers.IO) { Imports.audio(ctx, uris) }
                toast(message); refresh()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentHomeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        Ui.column(b.root, if (Ui.sidePane(b.root)) 1440 else Ui.dashboardMaxDp(ctx))
        b.btnRecord.setOnClickListener { startActivity(Intent(ctx, MicrophoneActivity::class.java)) }
        b.btnImport.setOnClickListener { importAudio.launch(Imports.AUDIO_TYPES) }
        b.btnSetup.setOnClickListener { showQuickSetupDialog() }
        b.btnLive.setOnClickListener { startActivity(Intent(ctx, TranscribeLiveActivity::class.java)) }
        b.liveBox.setOnClickListener { startActivity(Intent(ctx, TranscribeLiveActivity::class.java)) }
        b.btnPause.setOnClickListener { togglePause() }
        b.btnRetry.setOnClickListener {
            RecordingQueue.of(ctx).retryErrors(); AutoImportSettings.requestScan(ctx, true); RecordingQueueWorker.schedule(ctx)
            toast(if (Prefs.autoScan(ctx)) "已重新排队并校验录音来源" else "已重新排队；自动转写暂停时请先继续"); refresh()
        }
        b.btnAllTodos.setOnClickListener { main?.open(R.id.nav_todos) }
        b.btnAllRecordings.setOnClickListener { main?.open(R.id.nav_library) }
        b.btnPaste.setOnClickListener { TextImportSheet().show(childFragmentManager, "text-import") }
        b.btnScan.setOnClickListener { AutoImportSettings.requestScan(ctx, true); toast("正在检查录音目录，稍后刷新") }
        b.btnVoice.setOnClickListener { startActivity(Intent(ctx, VoiceLibraryActivity::class.java)) }
        b.btnAnalyze.setOnClickListener { onAnalyzeClicked() }
        WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkLiveData("model-install").observe(viewLifecycleOwner) { infos ->
            val running = infos.orEmpty().filter { !it.state.isFinished }
            modelDownloadLine = if (running.isEmpty()) null else {
                val info = running.first()
                val label = ModelFiles.label(info.progress.getString("slot"))
                val bytes = info.progress.getLong("bytes", 0L)
                val total = info.progress.getLong("total", -1L)
                val resumed = info.progress.getBoolean("resumed", false)
                val pct = if (total > 0) " (${(bytes * 100 / total).coerceIn(0, 100)}%)" else ""
                val resumeTag = if (resumed) " · 断点续传" else ""
                if (info.state == WorkInfo.State.RUNNING) {
                    "正在后台下载 $label$resumeTag · ${Progress.human(bytes)}" + (if (total > 0) " / ${Progress.human(total)}$pct" else "")
                } else {
                    "后台排队下载离线模型（共 ${running.size} 项 · 支持断点续传）…"
                }
            }
            refresh()
        }
        childFragmentManager.setFragmentResultListener("changed", viewLifecycleOwner) { _, _ -> refresh() }
    }

    private var lastLiveKey = ""
    private val liveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val liveTicker = object : Runnable {
        override fun run() {
            val s = com.bob.recordinghelper.LiveAsr.snapshot()
            val a = AnalysisState.snapshot()
            if (_b != null && (s.active || s.llmActive || s.polishActive || a.running)) {
                renderLive()
                liveHandler.postDelayed(this, 1000)
            }
        }
    }
    private val liveListener = { renderLive() }
    private val analysisListener = { renderLive(); refresh() }
    override fun onResume() {
        super.onResume()
        com.bob.recordinghelper.LiveAsr.addListener(liveListener)
        AnalysisState.addListener(analysisListener)
        renderLive()
    }
    override fun onPause() {
        liveHandler.removeCallbacks(liveTicker)
        com.bob.recordinghelper.LiveAsr.removeListener(liveListener)
        AnalysisState.removeListener(analysisListener)
        super.onPause()
    }

    /** Shows either live speech transcription pieces OR live LLM summarization/reasoning/polishing tokens. */
    private fun renderLive() {
        val binding = _b ?: return
        val s = com.bob.recordinghelper.LiveAsr.snapshot()
        val a = AnalysisState.snapshot()
        val liveKey = "${s.active}|${s.title}|${s.fileNo}|${s.llmActive}|${s.polishActive}|${a.running}|${a.activeId}"
        if (liveKey != lastLiveKey) {
            val shouldRefresh = lastLiveKey.isNotEmpty()
            lastLiveKey = liveKey
            if (shouldRefresh) refresh()
        }
        when {
            s.active -> {
                binding.liveBox.visibility = View.VISIBLE
                val elapsed = if (s.startedAt > 0L) ((System.currentTimeMillis() - s.startedAt) / 1000L).coerceAtLeast(1L) else 0L
                val clock = if (elapsed > 0L) " · 已用时 ${elapsed} 秒" else ""
                binding.tvLiveFile.text = "正在转写：" + s.title + (if (s.files > 1) "（第 ${s.fileNo} / ${s.files} 个）" else "") + clock
                val lines = s.rows.filter { it.text.isNotBlank() }.takeLast(3).map { it.text }
                binding.tvLiveText.text = if (lines.isEmpty()) s.phase else lines.joinToString("\n")
                liveHandler.removeCallbacks(liveTicker)
                liveHandler.postDelayed(liveTicker, 1000)
            }
            s.llmActive || s.polishActive || a.running -> {
                binding.liveBox.visibility = View.VISIBLE
                val title = s.llmTitle.ifBlank { a.activeTitle.ifBlank { s.polishTitle.ifBlank { "录音纪要与待办" } } }
                val model = s.llmModel.ifBlank { a.modelLabel.ifBlank { s.polishModel.ifBlank { "整理模型" } } }
                val startMs = when {
                    s.llmActive && s.llmStartedAt > 0L -> s.llmStartedAt
                    s.polishActive && s.polishStartedAt > 0L -> s.polishStartedAt
                    a.startedAt > 0L -> a.startedAt
                    else -> 0L
                }
                val elapsed = if (startMs > 0L) ((System.currentTimeMillis() - startMs) / 1000L).coerceAtLeast(1L) else 0L
                val clock = if (elapsed > 0L) " · 已用时 ${elapsed} 秒" else ""
                binding.tvLiveFile.text = "$model 正在工作：$title$clock（点击查看全过程）"
                val phase = AnalysisState.statusLine() ?: s.llmStatus.ifBlank { s.polishStatus.ifBlank { "正在准备整理模型…" } }
                val stream = s.llmText.ifBlank { a.streamText.ifBlank { s.polishText } }.trim()
                binding.tvLiveText.text = if (stream.isNotBlank()) "$phase\n${stream.takeLast(220)}" else phase
                liveHandler.removeCallbacks(liveTicker)
                liveHandler.postDelayed(liveTicker, 1000)
            }
            else -> {
                binding.liveBox.visibility = View.GONE
                liveHandler.removeCallbacks(liveTicker)
            }
        }
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }

    override fun refresh() {
        if (_b == null) return
        val ctx = requireContext().applicationContext
        load({
            val store = LocalStore.of(ctx)
            val todos = store.all("todo")
            val open = todos.filter { !it.optBoolean("done") }
            val today = open.filter { DueDates.state(it.optString("due")) != DueDates.State.UPCOMING && DueDates.state(it.optString("due")) != DueDates.State.NONE }
                .sortedBy { DueDates.sortKey(it.optString("due")) }
            val recordings = store.all("recording")
            val pendingAnalysisCount = recordings.count { it.optString("status") in listOf("pending", "analyzing", "error") && it.optString("text").isNotBlank() }
            val prefs = LocalSettings.prefs(ctx)
            Snapshot(
                openTodos = open.size, todayTodos = today.take(3), reviewCount = open.count { !it.optBoolean("reviewed") },
                recent = recordings.sortedByDescending { it.optString("occurredAt") }.take(3), recordings = recordings.size,
                pendingAnalysisCount = pendingAnalysisCount,
                todoCounts = todos.groupingBy { it.optString("recordingId") }.eachCount(),
                queue = RecordingQueue.of(ctx).counts(), autoScan = Prefs.autoScan(ctx), scope = Prefs.scanScope(ctx),
                scopeLabel = AutoImportSettings.scopeLabel(ctx), scanStatus = prefs.getString("scanStatus", null),
                importStatus = prefs.getString("importStatus", null), hasAccess = AutoImportSettings.hasAccess(ctx), modelReady = LocalAsr.modelReady(ctx),
                llmReady = AnalysisWorker.canAnalyze(ctx),
            )
        }) { render(it) }
    }

    private fun render(s: Snapshot) {
        val ctx = requireContext()
        b.tvGreeting.text = LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINESE))
        b.tvSummary.text = if (s.recordings == 0) "录音、转写、纪要和待办都在这台手机上完成" else "${s.recordings} 份录音记录  ·  全部在本机处理"
        b.tvHeroCount.text = if (s.openTodos == 0) "没有待推进的事" else "${s.openTodos} 件事，等待推进"
        b.tvHeroSub.text = "今日必办 ${s.todayTodos.size}  ·  待核对 ${s.reviewCount}"

        val setupDone = s.hasAccess && s.modelReady && s.scope != null
        b.cardSetup.visibility = if (setupDone) View.GONE else View.VISIBLE
        b.btnSetup.text = "一键傻瓜设置"
        step(b.iconStep1, b.tvStep1, s.hasAccess, "本机录音已授权自动读取", "授权读取本机录音（免找文件夹）")
        step(b.iconStep2, b.tvStep2, s.modelReady, if (s.llmReady) "语音与整理模型已就绪" else "语音模型已安装（整理模型可后台补齐）", modelDownloadLine ?: "后台下载全套离线模型")
        step(b.iconStep3, b.tvStep3, s.scope != null, "处理范围：${s.scopeLabel}", "默认处理最近 7 天及新增录音")

        val q = s.queue
        val queued = q.optInt("queued"); val processing = q.optInt("processing"); val done = q.optInt("done"); val error = q.optInt("error")
        val total = queued + processing + done + error
        b.progressQueue.max = maxOf(total, 1)
        b.progressQueue.setProgressCompat(done, true)
        b.tvQueue.text = "待转写 $queued  ·  转写中 $processing  ·  已完成 $done" + if (error > 0) "  ·  需重试 $error" else ""
        val analysisSnap = AnalysisState.snapshot()
        val analysisBusy = analysisSnap.running || analysisSnap.queuedIds.isNotEmpty()
        b.btnAnalyze.text = when {
            analysisBusy -> "取消整理"
            s.pendingAnalysisCount > 0 -> "立即整理 (${s.pendingAnalysisCount})"
            s.recordings > 0 -> "一键重新总结"
            else -> "立即整理"
        }
        b.tvQueueStatus.text = listOfNotNull(modelDownloadLine, AnalysisState.statusLine(), s.scanStatus, s.importStatus).joinToString("\n")
        b.tvQueueStatus.visibility = if (b.tvQueueStatus.text.isBlank()) View.GONE else View.VISIBLE
        b.chipScope.text = if (s.scope == null) "未设置" else if (s.autoScan) s.scopeLabel else "已暂停"
        if (s.autoScan) Ui.tint(b.chipScope, R.color.status_ready_bg, R.color.status_ready_fg) else Ui.tint(b.chipScope, R.color.status_neutral_bg, R.color.status_neutral_fg)
        val hasRange = s.scope in ImportScope.ranges
        b.btnPause.visibility = if (hasRange) View.VISIBLE else View.GONE
        b.btnPause.text = if (s.autoScan) "暂停" else "继续"
        b.btnPause.setIconResource(if (s.autoScan) R.drawable.ic_pause else R.drawable.ic_play)
        b.btnRetry.visibility = if (error > 0) View.VISIBLE else View.GONE

        b.sectionToday.visibility = if (s.todayTodos.isEmpty()) View.GONE else View.VISIBLE
        b.headerToday.tvTitle.text = "今日必办"
        b.headerToday.tvCount.text = "${s.todayTodos.size}"
        b.listToday.removeAllViews()
        for (t in s.todayTodos) {
            val row = ItemTodoBinding.inflate(layoutInflater, b.listToday, false)
            val id = t.getString("id")
            Binders.todo(row, t, onToggle = { checked -> background { LocalStore.of(ctx).setDone(id, checked); SyncWorker.schedule(ctx) } },
                onOpen = { TodoEditSheet.newInstance(id).show(childFragmentManager, "todo") },
                onSource = { startActivity(RecordingDetailActivity.intent(ctx, t.optString("recordingId"))) })
            b.listToday.addView(row.root)
        }

        b.sectionRecent.visibility = if (s.recent.isEmpty()) View.GONE else View.VISIBLE
        b.headerRecent.tvTitle.text = "最近录音"
        b.headerRecent.tvCount.text = "${s.recordings}"
        b.listRecent.removeAllViews()
        for (r in s.recent) {
            val row = ItemRecordingBinding.inflate(layoutInflater, b.listRecent, false)
            Binders.recording(row, r, s.todoCounts[r.getString("id")] ?: 0) { startActivity(RecordingDetailActivity.intent(ctx, r.getString("id"))) }
            b.listRecent.addView(row.root)
        }
    }

    private fun step(icon: android.widget.ImageView, text: android.widget.TextView, ok: Boolean, doneLabel: String, todoLabel: String) {
        icon.setImageResource(if (ok) R.drawable.ic_check_circle else R.drawable.ic_radio_off)
        icon.imageTintList = ContextCompat.getColorStateList(requireContext(), if (ok) R.color.md_primary else R.color.md_on_surface_variant)
        text.text = if (ok) doneLabel else todoLabel
    }

    private fun onAnalyzeClicked() {
        val ctx = requireContext()
        val snap = AnalysisState.snapshot()
        if (snap.running || snap.queuedIds.isNotEmpty()) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { AnalysisWorker.cancelActive(ctx.applicationContext) }
                toast("已取消后台整理")
                refresh()
            }
            return
        }
        ImportWorker.schedule(ctx)
        if (!AnalysisWorker.canAnalyze(ctx)) {
            if (AnalysisWorker.isDownloadingLlm(ctx)) {
                toast("本地整理模型正在后台下载，完成后会自动整理")
                return
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle("需要整理模型")
                .setMessage("提炼纪要和待办需要本地整理大模型（约 2.4 GB，后台下载完成后自动开始整理），也可以改用在线 AI 服务。")
                .setPositiveButton("一键后台下载") { _, _ ->
                    enqueueSuiteInBackground(ctx.applicationContext)
                    toast("已开始后台下载模型，完成后将自动整理")
                }
                .setNeutralButton("使用在线 AI") { _, _ -> startActivity(AiSettingsActivity.intent(ctx)) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        lifecycleScope.launch {
            val (pendingIds, allReady) = withContext(Dispatchers.IO) {
                val store = LocalStore.of(ctx.applicationContext)
                val all = store.all("recording").filter { it.optString("status") != "silent" && it.optString("text").isNotBlank() }
                    .sortedByDescending { it.optString("occurredAt") }
                val need = all.filter { it.optString("status") in listOf("pending", "analyzing", "error") }
                need.forEach { if (it.optString("status") == "error") store.status(it.getString("id"), "pending") }
                need.map { it.getString("id") } to all
            }
            if (pendingIds.isNotEmpty()) {
                withContext(Dispatchers.IO) { AnalysisWorker.requestNow(ctx.applicationContext, pendingIds.first()) }
                toast("已优先开始整理 ${pendingIds.size} 条待处理记录，点击「查看过程」可看实时推理")
                renderLive()
                refresh()
            } else if (allReady.isEmpty()) {
                AutoImportSettings.requestScan(ctx, true)
                toast("当前没有可整理的文字记录，已开始扫描录音目录")
                refresh()
            } else {
                val latest = allReady.first()
                val latestId = latest.getString("id")
                val latestTitle = latest.optString("title").ifBlank { "最新录音" }
                val batch = allReady.take(8)
                val items = arrayOf(
                    "一键重新总结最近一条（$latestTitle）",
                    "一键重新总结近期全部（共 ${batch.size} 条）",
                    "查看最近一条的总结与详情",
                    "查看模型转写与推理过程",
                )
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("总结查看与一键重新总结")
                    .setItems(items) { _, which ->
                        when (which) {
                            0 -> lifecycleScope.launch {
                                withContext(Dispatchers.IO) { AnalysisWorker.requestNow(ctx.applicationContext, latestId) }
                                toast("已开始重新总结「$latestTitle」")
                                renderLive()
                                refresh()
                            }
                            1 -> lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    val store = LocalStore.of(ctx.applicationContext)
                                    batch.forEach { store.status(it.getString("id"), "pending") }
                                    AnalysisWorker.requestNow(ctx.applicationContext, batch.first().getString("id"))
                                }
                                toast("已开始重新总结近期 ${batch.size} 条录音")
                                renderLive()
                                refresh()
                            }
                            2 -> startActivity(RecordingDetailActivity.intent(ctx, latestId))
                            3 -> startActivity(TranscribeLiveActivity.intent(ctx, latestId))
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun showQuickSetupDialog() {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("一键傻瓜设置（推荐）")
            .setMessage("自动授权读取本机录音（免找录音文件夹），默认处理最近 7 天及新增录音，并在后台仅下载语音转写模型（约 450 MB，支持断点续传），免等 2.4 GB 大模型即可转写并快筛待办。")
            .setPositiveButton("一键开启") { _, _ ->
                if (AutoImportSettings.hasAccess(ctx)) completeQuickSetup(ctx)
                else quickSetupPermission.launch(AutoImportSettings.mediaPermission())
            }
            .setNeutralButton("高级设置") { _, _ -> startActivity(Intent(ctx, SettingsActivity::class.java)) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun completeQuickSetup(ctx: android.content.Context) {
        val app = ctx.applicationContext
        if (Prefs.scanScope(app) !in ImportScope.ranges) {
            runCatching { AutoImportSettings.enable(app, "recent", 7) }
        } else if (!Prefs.autoScan(app)) {
            runCatching { AutoImportSettings.pause(app, false) }
        } else {
            AutoImportSettings.requestScan(app, true)
        }
        enqueueSuiteInBackground(app)
        Onboarding.finish(app)
        toast("傻瓜模式已开启：后台正在自动下载语音模型（支持断点续传）并识别近 7 天录音")
        refresh()
    }

    private fun enqueueSuiteInBackground(app: android.content.Context) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val entries = ModelSource.fetch(ModelSource.manifestUrl(app))
                val wanted = ModelSource.missingEntries(app, entries, includeLlm = false)
                if (wanted.isNotEmpty()) ModelSource.enqueue(app, wanted)
            }
        }
    }

    private fun togglePause() {
        val ctx = requireContext()
        try {
            AutoImportSettings.pause(ctx, Prefs.autoScan(ctx))
            toast(if (Prefs.autoScan(ctx)) "已继续自动转写与整理" else "已暂停后台转写与模型整理；原录音和已完成记录均保留")
        } catch (e: Exception) { toast(e.message ?: "请检查录音目录权限") }
        refresh()
    }

    private fun background(work: () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { runCatching(work) }; refresh() }
    }
}
