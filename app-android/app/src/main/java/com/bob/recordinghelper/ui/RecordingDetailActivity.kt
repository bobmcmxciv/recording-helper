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
import com.bob.recordinghelper.R
import com.bob.recordinghelper.databinding.ActivityRecordingDetailBinding
import com.bob.recordinghelper.databinding.ItemTodoBinding
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
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable { override fun run() { tick(); handler.postDelayed(this, 500) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        b = ActivityRecordingDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        Ui.insets(b.root, top = b.appbar, bottom = b.scroll)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_share -> share()
                R.id.action_copy -> copy()
                R.id.action_reanalyze -> reanalyze()
                R.id.action_delete -> delete()
            }
            true
        }
        b.sections.addOnButtonCheckedListener { _, _, _ -> render() }
        b.btnPlay.setOnClickListener { togglePlay() }
    }

    override fun onResume() { super.onResume(); load() }
    override fun onPause() { stopPlayer(); super.onPause() }

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
        Binders.status(b.chipStatus, r.optString("status"))
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
    }

    private fun render() {
        val r = rec ?: return
        b.listTodos.removeAllViews()
        b.btnAction.visibility = View.GONE
        b.tvBody.visibility = View.VISIBLE
        when (b.sections.checkedButtonId) {
            R.id.secTodos -> {
                b.tvBody.visibility = if (todos.isEmpty()) View.VISIBLE else View.GONE
                b.tvBody.text = if (r.optString("status") == "ready") "这段录音没有提取出待办。" else "整理完成后，提取的待办会出现在这里。"
                for (t in todos) {
                    val row = ItemTodoBinding.inflate(layoutInflater, b.listTodos, false)
                    val tid = t.getString("id")
                    Binders.todo(row, t, onToggle = { checked -> lifecycleScope.launch { withContext(Dispatchers.IO) { LocalStore.of(applicationContext).setDone(tid, checked); SyncWorker.schedule(applicationContext) } } },
                        onOpen = { TodoEditSheet.newInstance(tid).show(supportFragmentManager, "todo") }, onSource = null)
                    b.listTodos.addView(row.root)
                }
                supportFragmentManager.setFragmentResultListener("changed", this) { _, _ -> load() }
            }
            R.id.secText -> b.tvBody.text = transcript(r.optString("text"))
            R.id.secPolished -> b.tvBody.text = r.optString("polished").ifBlank { "没有润色稿。可在设置中开启本地润色，或点击「重新整理」。" }
            else -> {
                val summary = r.optString("summary")
                val status = r.optString("status")
                b.tvBody.text = summary.ifBlank {
                    when (status) {
                        "analyzing" -> "本机模型正在整理，请稍候。"
                        "error" -> "上次整理失败。可以重试，原文始终保留。"
                        "silent" -> "这段录音没有识别到语音。"
                        else -> "等待本机模型整理。连接电源时会自动进行，也可以立即整理。"
                    }
                }
                if (summary.isBlank() && status != "analyzing" && status != "silent") {
                    b.btnAction.visibility = View.VISIBLE
                    b.btnAction.text = if (status == "error") "重试整理" else "立即整理"
                    b.btnAction.setOnClickListener { reanalyze() }
                }
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

    private fun reanalyze() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { LocalStore.of(applicationContext).status(id, "pending"); AnalysisWorker.schedule(applicationContext, true) }
            Ui.toast(this@RecordingDetailActivity, "已排队，本机模型整理后自动更新"); load()
        }
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
