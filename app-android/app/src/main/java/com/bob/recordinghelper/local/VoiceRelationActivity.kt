package com.bob.recordinghelper.local

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bob.recordinghelper.databinding.ActivityVoiceRelationBinding
import com.bob.recordinghelper.databinding.ItemCandidateBinding
import com.bob.recordinghelper.databinding.ItemCandidateSourceBinding
import com.bob.recordinghelper.databinding.ItemEvidenceBinding
import com.bob.recordinghelper.databinding.ItemSettingRowBinding
import com.bob.recordinghelper.R
import com.bob.recordinghelper.ui.Imports
import com.bob.recordinghelper.ui.Ui
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class VoiceRelationActivity : AppCompatActivity() {
    private lateinit var b: ActivityVoiceRelationBinding
    private var lastBusy = false
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable { override fun run() {
        if (lastBusy != VoiceRelationState.busy) render()
        val message = VoiceRelationState.snapshot.message
        if (b.tvStatus.text.toString() != message) b.tvStatus.text = message
        handler.postDelayed(this, 500)
    } }
    private val choose = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        val uris = selected.distinct()
        if (uris.isNotEmpty()) try {
            require(!VoiceRelationState.busy && !MicState.busy) { "请先结束当前录音或分析" }
            require(uris.size <= 8) { "每次最多选择 8 段" }
            uris.forEach { uri -> runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            stopPlaying()
            startForegroundService(Intent(this, VoiceRelationService::class.java).putStringArrayListExtra("uris", ArrayList(uris.map { it.toString() })))
        } catch (e: Exception) { toast(e.message ?: "无法开始分析") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        b = ActivityVoiceRelationBinding.inflate(layoutInflater)
        setContentView(b.root)
        Ui.insets(b.root, top = b.appbar, bottom = b.scroll)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.btnSelect.setOnClickListener { selectDialog() }
        b.btnEnrollSelf.setOnClickListener {
            if (MicState.busy || VoiceRelationState.busy) toast("请先结束当前任务")
            else startActivity(Intent(this, MicrophoneActivity::class.java).putExtra("startMode", "self"))
        }
        b.btnCancel.setOnClickListener { startService(Intent(this, VoiceRelationService::class.java).setAction(VoiceRelationService.STOP)) }
        b.btnClear.setOnClickListener {
            if (!idle()) return@setOnClickListener
            MaterialAlertDialogBuilder(this).setTitle("清空本机候选依据？").setMessage("不删除原录音、已确认声纹或转写。下次选择录音可重新分析。")
                .setPositiveButton("清空") { _, _ -> VoiceRelationStore.of(this).clear(); render() }.setNegativeButton("取消", null).show()
        }
    }
    override fun onResume() { super.onResume(); render(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacksAndMessages(null); stopPlaying(); super.onPause() }

    private fun render() {
        stopPlaying()
        val self = runCatching { VoiceRelationStore.currentSelf(this) }.getOrNull()
        val report = try { VoiceRelationStore.of(this).read() } catch (_: Exception) { toast("旧依据无法读取，已停止修改以保护原数据"); null }
        val compatible = report != null && self != null && report.model == self.model && report.anchor == VoiceRelationStore.anchor(self)
        val busy = VoiceRelationState.busy

        // Steps
        while (b.steps.childCount > 1) b.steps.removeViewAt(1)
        step(1, "录入自己的声纹", self != null)
        step(2, "选择 2–8 段由你参与的双人通话", report != null && report.entries.isNotEmpty())
        step(3, "试听核对，确认后命名入库", false)

        b.tvStatus.text = VoiceRelationState.snapshot.message
        b.btnSelect.isEnabled = !busy && !MicState.busy && self != null
        b.btnEnrollSelf.visibility = if (self == null) View.VISIBLE else View.GONE
        b.btnCancel.visibility = if (busy) View.VISIBLE else View.GONE
        b.tvIncompatible.visibility = if (report != null && !compatible) View.VISIBLE else View.GONE

        b.listCandidates.removeAllViews(); b.listEvidence.removeAllViews()
        b.headerCandidates.tvTitle.text = "候选说话人"
        b.headerEvidence.tvTitle.text = "分析依据"
        val candidates = if (report == null) emptyList() else VoiceRelations.candidates(report.entries.mapNotNull { it.evidence })
        b.headerCandidates.tvCount.text = "${candidates.size}"
        b.headerEvidence.tvCount.text = "${report?.entries?.size ?: 0}"
        b.tvNoCandidates.visibility = if (candidates.isEmpty()) View.VISIBLE else View.GONE
        b.headerEvidence.root.visibility = if (report == null) View.GONE else View.VISIBLE
        b.btnClear.visibility = if (report == null) View.GONE else View.VISIBLE
        if (report == null) { lastBusy = busy; return }

        val known = runCatching { VoiceLibrary.of(this).all().filter { !it.self && it.model == report.model }.associate { it.name to it.vectors } }.getOrDefault(emptyMap())
        for ((i, candidate) in candidates.withIndex()) {
            val card = ItemCandidateBinding.inflate(layoutInflater, b.listCandidates, false)
            val match = VoiceMatcher.match(candidate.vector, known)
            card.avatar.text = ('A' + (i % 26)).toString()
            card.tvTitle.text = "候选 ${i + 1}" + (match.name?.let { "  ·  疑似$it" } ?: "  ·  待命名")
            card.tvMeta.text = "来自 ${candidate.calls.size} 段录音  ·  对方约 %.0f 秒有效语音".format(candidate.calls.sumOf { it.otherSeconds }) +
                if (candidate.calls.size > 1) "  ·  跨录音最低相似度 %.2f".format(candidate.minimumSimilarity) else ""
            for (call in candidate.calls) {
                val source = report.entries.first { it.id == call.sourceId }
                val row = ItemCandidateSourceBinding.inflate(layoutInflater, card.listSources, false)
                row.tvTitle.text = source.title
                row.tvMeta.text = "本人相似度 %.2f  ·  对方片段 %02d:%02d 起，最多 6 秒".format(call.selfScore, (call.previewStart / 60).toInt(), (call.previewStart % 60).toInt())
                row.btnPlay.setOnClickListener { play(source.uri, call.previewStart, call.previewSeconds) }
                card.listSources.addView(row.root)
            }
            card.tvVerdict.text = when {
                candidate.ambiguous -> "与其他候选过于接近，暂不入库；请换更多清晰的录音。"
                !candidate.ready -> "证据暂不足，请继续添加不同的录音。"
                match.name != null -> "已有疑似对应的声纹，保留独立依据，不重复建档。"
                else -> "请试听至少两段确认是同一位，并已获其同意，再命名入库。"
            }
            card.btnConfirm.visibility = if (candidate.ready && match.name == null) View.VISIBLE else View.GONE
            card.btnConfirm.isEnabled = compatible && !busy && !MicState.busy
            card.btnConfirm.setOnClickListener { confirm(report, candidate) }
            b.listCandidates.addView(card.root)
        }
        for (e in report.entries) {
            val row = ItemEvidenceBinding.inflate(layoutInflater, b.listEvidence, false)
            row.tvTitle.text = e.title
            row.tvReason.text = e.reason + if (e.limited) "（仅前 10 分钟）" else ""
            row.btnRemove.setOnClickListener {
                if (!idle()) return@setOnClickListener
                MaterialAlertDialogBuilder(this).setTitle("移除这段依据？").setMessage("只移除候选证据，不删除原录音、已有声纹或转写。")
                    .setPositiveButton("移除") { _, _ ->
                        runCatching { val store = VoiceRelationStore.of(this); store.read()?.let { store.write(it.copy(entries = it.entries.filterNot { x -> x.id == e.id })) } }
                            .onFailure { toast("无法修改旧依据，原文件保持不变") }
                        render()
                    }.setNegativeButton("取消", null).show()
            }
            b.listEvidence.addView(row.root)
        }
        lastBusy = busy
    }

    private fun step(n: Int, text: String, done: Boolean) {
        val r = ItemSettingRowBinding.inflate(layoutInflater, b.steps, false)
        r.icon.setImageResource(if (done) R.drawable.ic_check else R.drawable.ic_radio_off)
        r.tvTitle.text = "$n. $text"; r.tvValue.visibility = View.GONE; r.chevron.visibility = View.GONE
        r.row.isClickable = false; r.row.background = null; r.row.minimumHeight = 0
        b.steps.addView(r.root)
    }

    private fun idle(): Boolean {
        if (VoiceRelationState.busy || MicState.busy) { toast("请先结束当前录音或分析"); return false }
        return true
    }

    private fun selectDialog() {
        if (!idle()) return
        try { VoiceRelationStore.currentSelf(this) } catch (e: Exception) { toast(e.message ?: "请先录入本人声纹"); return }
        val consent = MaterialCheckBox(this).apply { text = "所选录音由我参与，我已取得其他说话人用于声纹分析的同意" }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0); addView(consent) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("选择参与分析的录音").setMessage("只分析这次选择的音频，不扫描全部历史，不读取通讯录，不上传，不自动实名建档。")
            .setView(box).setPositiveButton("选择录音", null).setNegativeButton("取消", null).create()
        dialog.show()
        val button = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE); button.isEnabled = false
        consent.setOnCheckedChangeListener { _, checked -> button.isEnabled = checked }
        button.setOnClickListener { if (consent.isChecked) { dialog.dismiss(); choose.launch(Imports.AUDIO_TYPES) } }
    }

    private fun confirm(report: VoiceRelationStore.Report, candidate: VoiceRelations.Candidate) {
        if (!idle()) return
        val layout = TextInputLayout(this).apply { hint = "对方名称（不超过 40 字）"; setPadding(dp(20), dp(8), dp(20), 0) }
        val name = TextInputEditText(this).apply { setSingleLine(true) }
        layout.addView(name)
        val checked = MaterialCheckBox(this).apply { text = "已核对不同录音中的声音是同一位，并已获其同意建立声纹" }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(layout); addView(checked.apply { setPadding(dp(16), 0, dp(16), 0) }) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("确认并保存本机声纹").setMessage("系统只提出候选，不能替你确认身份。不会更新本人声纹或改写历史标签。")
            .setView(box).setPositiveButton("保存此声纹", null).setNegativeButton("取消", null).create()
        dialog.show()
        val button = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE); button.isEnabled = false
        checked.setOnCheckedChangeListener { _, value -> button.isEnabled = value }
        button.setOnClickListener {
            if (!checked.isChecked || !idle()) return@setOnClickListener
            try {
                val latest = VoiceRelationStore.of(this).read() ?: error("依据已清空，请刷新")
                require(latest.created == report.created && latest.entries.map { it.id } == report.entries.map { it.id }) { "依据已改变，请刷新后重新核对" }
                require(VoiceRelationStore.anchor(VoiceRelationStore.currentSelf(this)) == report.anchor) { "本人声纹已变更，请重新分析" }
                require(candidate.ready)
                VoiceLibrary.of(this).put(name.text.toString(), false, report.model, candidate.samples)
                dialog.dismiss(); toast("已保存本机声纹，历史标签未改写"); render()
            } catch (e: Exception) { layout.error = e.message ?: "保存失败，旧声纹未更改" }
        }
    }

    private fun play(source: String, from: Double, seconds: Double) {
        if (!idle()) return
        stopPlaying()
        try {
            val media = MediaPlayer(); player = media
            media.setDataSource(this, Uri.parse(source))
            media.setOnErrorListener { _, _, _ -> stopPlaying(); toast("原录音不可用，请重新选择分析"); true }
            media.setOnPreparedListener { if (player === it) it.seekTo((from * 1000).toLong(), MediaPlayer.SEEK_CLOSEST) }
            media.setOnSeekCompleteListener { p -> if (player === p) {
                p.start(); handler.postDelayed({ if (player === p) stopPlaying() }, (seconds.coerceIn(1.5, 6.0) * 1000).toLong())
            } }
            media.setOnCompletionListener { if (player === it) stopPlaying() }
            media.prepareAsync()
        } catch (_: Exception) { stopPlaying(); toast("无法试听，原文件可能移动或授权失效，请重新选择分析") }
    }
    private fun stopPlaying() { runCatching { player?.release() }; player = null }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun toast(text: String) = Ui.toast(this, text)
}
