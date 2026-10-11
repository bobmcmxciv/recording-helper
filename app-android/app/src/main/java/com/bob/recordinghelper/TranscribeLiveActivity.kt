package com.bob.recordinghelper

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
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
import com.bob.recordinghelper.ui.Ui
import java.io.File

/**
 * Live view of the on-device transcription: current file, engine, every ~10 s piece with its text and model time,
 * speaker clustering, and the final speaker-merged result. Data comes from [LiveAsr].
 */
class TranscribeLiveActivity : AppCompatActivity() {
    private lateinit var b: ActivityLiveBinding
    private var follow = true
    private val listener = { render() }
    private val muted by lazy { ContextCompat.getColor(this, R.color.md_on_surface_variant) }
    private val onPrimary by lazy { ContextCompat.getColor(this, R.color.md_on_primary) }
    private val palette by lazy { intArrayOf(R.color.md_primary, R.color.md_tertiary, R.color.md_secondary, R.color.due_overdue).map { ContextCompat.getColor(this, it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        b = ActivityLiveBinding.inflate(layoutInflater)
        setContentView(b.root)
        Ui.insets(b.root, top = b.appbar, bottom = b.scroll)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.scroll.setOnScrollChangeListener { v, _, y, _, _ -> follow = y + v.height >= b.tvBody.height - 120 }
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
        startTrialFrom(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); startTrialFrom(intent) }

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

    override fun onStart() { super.onStart(); LiveAsr.addListener(listener); render() }
    override fun onStop() { LiveAsr.removeListener(listener); super.onStop() }

    private fun render() {
        val s = LiveAsr.snapshot()
        b.tvHead.text = header(s)
        val main = if (s.final.isNotEmpty()) finalText(s) else liveText(s)
        b.tvBody.text = if (s.polishTitle.isNotEmpty()) SpannableStringBuilder(polishText(s)).append(main) else main
        if (follow) b.scroll.post { b.scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun polishText(s: LiveAsr.Snapshot): CharSequence {
        val sb = SpannableStringBuilder()
        mark(sb, "润色（本地大模型 ${s.polishModel}）", StyleSpan(Typeface.BOLD))
        val where = if (s.polishChunks > 0) "　第 ${minOf(s.polishChunk + 1, s.polishChunks)}/${s.polishChunks} 段" else ""
        mark(sb, "　${s.polishTitle}$where\n", ForegroundColorSpan(muted))
        if (s.polishStatus.isNotEmpty()) mark(sb, s.polishStatus + "\n", ForegroundColorSpan(palette[0]))
        sb.append(s.polishText).append("\n\n")
        mark(sb, "────────\n\n", ForegroundColorSpan(muted))
        return sb
    }

    private fun header(s: LiveAsr.Snapshot): CharSequence {
        if (s.title.isEmpty()) return "还没有转写任务。\n开启自动转写后，扫描到新录音时这里会实时显示模型输出。"
        val elapsed = ((if (s.active) System.currentTimeMillis() else s.startedAt + 1) - s.startedAt).coerceAtLeast(1) / 1000.0
        val sb = StringBuilder()
        sb.append(if (s.files > 1) "第 ${s.fileNo}/${s.files} 个：" else "").append(s.title).append('\n')
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
