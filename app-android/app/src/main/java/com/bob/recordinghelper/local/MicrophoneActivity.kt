package com.bob.recordinghelper.local

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.*
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bob.recordinghelper.R
import com.bob.recordinghelper.databinding.ActivityMicrophoneBinding
import com.bob.recordinghelper.databinding.ItemMicRecordingBinding
import com.bob.recordinghelper.ui.RecordingDetailActivity
import com.bob.recordinghelper.ui.Ui

class MicrophoneActivity : AppCompatActivity() {
    private lateinit var b: ActivityMicrophoneBinding
    private var pendingMode: String? = null
    private var pendingName = ""
    private var permissionInFlight = false
    private var lastBusy = false
    private var player: MediaPlayer? = null
    private var playingId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable { override fun run() { tick(); handler.postDelayed(this, 500) } }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionInFlight = false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) launchCapture()
        else { pendingMode = null; toast("未授权麦克风，没有开始录音") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) { pendingMode = intent.getStringExtra("startMode"); pendingName = intent.getStringExtra("name").orEmpty() }
        b = ActivityMicrophoneBinding.inflate(layoutInflater)
        setContentView(b.root)
        Ui.insets(b.root, top = b.appbar, bottom = b.body)
        if (b.body.orientation == android.widget.LinearLayout.HORIZONTAL) Ui.column(b.body, 1440) else { Ui.column(b.scroll, 720); Ui.column(b.panel, 720) }
        Ui.tabletop(this) { hinge -> b.body.post { fold(hinge) } }
        b.toolbar.setNavigationOnClickListener { finish() }
        b.btnStart.setOnClickListener { requestCapture("record", "") }
        b.btnStop.setOnClickListener { startService(Intent(this, MicrophoneService::class.java).setAction(MicrophoneService.STOP)) }
        MicArchive.recover(this)
        render()
    }

    override fun onResume() {
        super.onResume(); render(); handler.post(refresh)
        if (pendingMode != null && !permissionInFlight) window.decorView.post { pendingMode?.let { requestCapture(it, pendingName) } }
    }
    override fun onPause() { handler.removeCallbacks(refresh); stopPlayback(); super.onPause() }

    /** Half-opened on a table: transcript above the hinge, controls below it. Flat: controls on top, transcript scrolls under them. */
    private fun fold(hinge: android.graphics.Rect?) {
        if (b.body.orientation == android.widget.LinearLayout.HORIZONTAL) return  // landscape panes sit side by side; no tabletop split
        val body = b.body; val scroll = b.scroll; val panel = b.panel
        val tabletop = hinge != null
        if (tabletop != (body.indexOfChild(scroll) == 0)) { body.removeView(panel); body.addView(panel, if (tabletop) 1 else 0) }
        val top = IntArray(2).also { body.getLocationInWindow(it) }[1]
        val gap = (resources.displayMetrics.density * 8).toInt()
        scroll.layoutParams = (scroll.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
            if (tabletop) { height = (hinge!!.top - top).coerceAtLeast(0); weight = 0f } else { height = 0; weight = 1f }
        }
        panel.layoutParams = (panel.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
            if (tabletop) { height = 0; weight = 1f } else { height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT; weight = 0f }
        }
        panel.setPadding(panel.paddingLeft, if (tabletop) hinge!!.height() + gap else gap, panel.paddingRight, panel.paddingBottom)
        panel.gravity = if (tabletop) android.view.Gravity.CENTER_VERTICAL else android.view.Gravity.TOP
    }

    private fun render() {
        val enrollment = (if (MicState.busy) MicState.snapshot().mode else pendingMode ?: "record") != "record"
        b.toolbar.title = if (enrollment) "声纹采样" else "现场录音"
        b.tvSubtitle.text = if (enrollment) "请由同一人在安静环境自然说话，采样会自动结束。" else "麦克风只在你点击后开启，音频不上传。"
        b.tvHint.text = if (enrollment) "只保存声纹向量，采样原音处理完即删除；有效语音不足会要求重录，不会覆盖旧声纹。"
            else "约每 5 秒更新一段本地文字，不是逐字流式；模型繁忙时先保留原音、稍后追赶。单次录音上限 2 小时。"
        b.btnStart.visibility = if (enrollment) View.GONE else View.VISIBLE
        b.tvLiveTitle.text = if (enrollment) "朗读参考" else "实时文字"
        renderHistory()
        lastBusy = MicState.busy
        tick()
    }

    private fun renderHistory() {
        val rows = MicArchive.recordings(this).take(12)
        b.sectionHistory.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        b.headerHistory.tvTitle.text = "应用内录音"
        b.headerHistory.tvCount.text = "${rows.size}"
        b.listHistory.removeAllViews()
        for (o in rows) {
            val id = o.getString("id"); val f = MicArchive.audio(this, id)
            val saved = MicArchive.saved(this, id)
            val item = ItemMicRecordingBinding.inflate(layoutInflater, b.listHistory, false)
            item.tvTitle.text = o.getString("title")
            item.tvMeta.text = Ui.duration(PcmWave.count(f) / 16000) + "  ·  " + if (saved) "文字已进入资料库" else "原音已保存，可继续转写"
            item.btnAction.text = if (saved) "查看记录" else "继续转写"
            item.btnAction.setOnClickListener {
                if (saved) startActivity(RecordingDetailActivity.intent(this, "rec-" + LocalStore.hash(MicArchive.key(this, id))))
                else try { MicArchive.retry(this, id); toast("已加入本地队列，不改变自动导入范围"); render() } catch (e: Exception) { toast(e.message ?: "无法排队") }
            }
            item.btnPlay.setIconResource(if (playingId == id) R.drawable.ic_stop else R.drawable.ic_play)
            item.btnPlay.setOnClickListener {
                if (MicState.busy) { toast("录音或采样时不播放，避免回声"); return@setOnClickListener }
                if (playingId == id) { stopPlayback(); renderHistory(); return@setOnClickListener }
                stopPlayback()
                try {
                    player = MediaPlayer().apply {
                        setDataSource(f.absolutePath); prepare()
                        setOnCompletionListener { stopPlayback(); renderHistory() }; start()
                    }
                    playingId = id; renderHistory()
                } catch (_: Exception) { stopPlayback(); toast("无法播放此录音") }
            }
            b.listHistory.addView(item.root)
        }
    }

    private fun stopPlayback() { runCatching { player?.release() }; player = null; playingId = null }

    private fun tick() {
        val s = MicState.snapshot()
        if (s.busy != lastBusy) { render(); return }
        val secs = s.samples / 16000
        fun android.widget.TextView.update(value: String) { if (text.toString() != value) text = value }
        b.tvTimer.update(Ui.duration(secs))
        b.tvStatus.update(s.message)
        if (b.level.progress != s.level) b.level.progress = s.level
        b.btnStart.isEnabled = !s.busy
        b.btnStop.isEnabled = s.busy
        b.btnStop.text = if (!s.busy || s.capturing && s.mode == "record") "停止" else if (s.mode != "record") "取消" else "稍后转写"
        b.tvTranscript.update(if (s.mode != "record" && s.busy)
            "这是我的声音样本。我正在使用录音助手记录日常的想法与工作安排。今天我会整理需要完成的事情，确认每一个重要的时间和细节。说话时保持自然的语速，不需要刻意提高音量。完成这次采样之后，我还可以换一段内容验证识别效果。\n\n" +
                (if (s.mode == "identify") "约 10 秒后开始比对。" else "约 20 秒后自动建立声纹。")
            else s.text.ifBlank { "开始录音后，文字会出现在这里。声纹相似度只是提示，不用于身份认证。" })
    }

    private fun requestCapture(mode: String, name: String) {
        if (MicState.busy) { pendingMode = null; toast("已有录音或采样在进行"); return }
        pendingMode = mode; pendingName = name
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (permissionInFlight) return
            permissionInFlight = true
            val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.launch(p.toTypedArray())
        } else launchCapture()
    }

    private fun launchCapture() {
        val mode = pendingMode ?: return; pendingMode = null
        stopPlayback()
        try { startForegroundService(Intent(this, MicrophoneService::class.java).putExtra("mode", mode).putExtra("name", pendingName)) }
        catch (e: Exception) { toast(e.message ?: "请在应用可见时开始录音") }
    }

    private fun toast(s: String) = Ui.toast(this, s)
}
