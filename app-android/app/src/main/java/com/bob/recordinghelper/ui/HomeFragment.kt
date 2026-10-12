package com.bob.recordinghelper.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bob.recordinghelper.LocalAsr
import com.bob.recordinghelper.Prefs
import com.bob.recordinghelper.R
import com.bob.recordinghelper.SettingsActivity
import com.bob.recordinghelper.TranscribeLiveActivity
import com.bob.recordinghelper.databinding.FragmentHomeBinding
import com.bob.recordinghelper.databinding.ItemRecordingBinding
import com.bob.recordinghelper.databinding.ItemTodoBinding
import com.bob.recordinghelper.local.*
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
        val recent: List<JSONObject>, val recordings: Int, val todoCounts: Map<String, Int>,
        val queue: JSONObject, val autoScan: Boolean, val scope: String?, val scopeLabel: String,
        val scanStatus: String?, val importStatus: String?, val hasAccess: Boolean, val modelReady: Boolean,
    )

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
        b.btnSetup.setOnClickListener { startActivity(Intent(ctx, SettingsActivity::class.java)) }
        b.btnLive.setOnClickListener { startActivity(Intent(ctx, TranscribeLiveActivity::class.java)) }
        b.btnPause.setOnClickListener { togglePause() }
        b.btnRetry.setOnClickListener {
            RecordingQueue.of(ctx).retryErrors(); RecordingQueueWorker.schedule(ctx)
            toast(if (Prefs.autoScan(ctx)) "已重新排队" else "已重新排队；自动转写暂停时请先继续"); refresh()
        }
        b.btnAllTodos.setOnClickListener { main?.open(R.id.nav_todos) }
        b.btnAllRecordings.setOnClickListener { main?.open(R.id.nav_library) }
        b.btnPaste.setOnClickListener { TextImportSheet().show(childFragmentManager, "text-import") }
        b.btnScan.setOnClickListener { AutoImportSettings.requestScan(ctx, true); toast("正在检查录音目录，稍后刷新") }
        b.btnVoice.setOnClickListener { startActivity(Intent(ctx, VoiceLibraryActivity::class.java)) }
        b.btnAnalyze.setOnClickListener { AnalysisWorker.schedule(ctx, true); ImportWorker.schedule(ctx); toast("已排队整理待处理记录") }
        childFragmentManager.setFragmentResultListener("changed", viewLifecycleOwner) { _, _ -> refresh() }
    }

    private val liveListener = { renderLive() }
    override fun onResume() { super.onResume(); com.bob.recordinghelper.LiveAsr.addListener(liveListener); renderLive() }
    override fun onPause() { com.bob.recordinghelper.LiveAsr.removeListener(liveListener); super.onPause() }

    /** The transcription engine reports each piece as it comes back; show the current file and the last few lines. */
    private fun renderLive() {
        val binding = _b ?: return
        val s = com.bob.recordinghelper.LiveAsr.snapshot()
        if (!s.active) { binding.liveBox.visibility = View.GONE; return }
        binding.liveBox.visibility = View.VISIBLE
        binding.tvLiveFile.text = "正在转写：" + s.title + if (s.files > 1) "（第 ${s.fileNo} / ${s.files} 个）" else ""
        val lines = s.rows.filter { it.text.isNotBlank() }.takeLast(3).map { it.text }
        binding.tvLiveText.text = if (lines.isEmpty()) s.phase else lines.joinToString("\n")
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
            val prefs = LocalSettings.prefs(ctx)
            Snapshot(
                openTodos = open.size, todayTodos = today.take(3), reviewCount = open.count { !it.optBoolean("reviewed") },
                recent = recordings.sortedByDescending { it.optString("occurredAt") }.take(3), recordings = recordings.size,
                todoCounts = todos.groupingBy { it.optString("recordingId") }.eachCount(),
                queue = RecordingQueue.of(ctx).counts(), autoScan = Prefs.autoScan(ctx), scope = Prefs.scanScope(ctx),
                scopeLabel = AutoImportSettings.scopeLabel(ctx), scanStatus = prefs.getString("scanStatus", null),
                importStatus = prefs.getString("importStatus", null), hasAccess = AutoImportSettings.hasAccess(ctx), modelReady = LocalAsr.modelReady(ctx),
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
        step(b.iconStep1, b.tvStep1, s.hasAccess, "录音目录已授权", "授权录音目录")
        step(b.iconStep2, b.tvStep2, s.modelReady, "语音模型已安装", "安装语音模型")
        step(b.iconStep3, b.tvStep3, s.scope != null, "处理范围：${s.scopeLabel}", "选择要处理的录音范围")

        val q = s.queue
        val queued = q.optInt("queued"); val processing = q.optInt("processing"); val done = q.optInt("done"); val error = q.optInt("error")
        val total = queued + processing + done + error
        b.progressQueue.max = maxOf(total, 1)
        b.progressQueue.setProgressCompat(done, true)
        b.tvQueue.text = "待转写 $queued  ·  转写中 $processing  ·  已完成 $done" + if (error > 0) "  ·  需重试 $error" else ""
        b.tvQueueStatus.text = listOfNotNull(s.scanStatus, s.importStatus).joinToString("\n")
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

    private fun togglePause() {
        val ctx = requireContext()
        try { AutoImportSettings.pause(ctx, Prefs.autoScan(ctx)); toast(if (Prefs.autoScan(ctx)) "已继续自动转写" else "当前一条结束后暂停；原录音和已完成记录均保留") }
        catch (e: Exception) { toast(e.message ?: "请检查录音目录权限") }
        refresh()
    }

    private fun background(work: () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { runCatching(work) }; refresh() }
    }
}
