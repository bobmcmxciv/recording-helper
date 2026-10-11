package com.bob.recordinghelper.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.bob.recordinghelper.*
import com.bob.recordinghelper.databinding.ActivityOnboardingBinding
import com.bob.recordinghelper.databinding.ItemSettingRowBinding
import com.bob.recordinghelper.llm.LlmEngine
import com.bob.recordinghelper.llm.LlmSettings
import com.bob.recordinghelper.local.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** First-run wizard: model → recording folders → range → live progress → done. Progress survives leaving the app. */
class OnboardingActivity : AppCompatActivity() {
    private lateinit var b: ActivityOnboardingBinding
    private var step = 1
    private var manifest: List<ModelSource.Entry> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable { override fun run() { if (step == 5) renderProcessing(); if (step == 2) renderModels(); handler.postDelayed(this, 1000) } }
    private val liveListener = { if (step == 5) renderProcessing() }

    private val mediaPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { AutoImportSettings.requestScan(this, true); Ui.toast(this, "已授权，正在查找录音") } else Ui.toast(this, "未授权；也可以手动选择录音目录")
        renderSources()
    }
    private val tree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            LocalSettings.addTree(this, uri.toString()); AutoImportSettings.requestScan(this, true); renderSources()
        } catch (e: Exception) { Ui.toast(this, "授权失败：${e.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        b = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(b.root)
        Ui.insets(b.root, top = b.toolbar, bottom = b.footer)
        Ui.column(b.scroll, 640); Ui.column(b.footer, 640)
        b.toolbar.setNavigationOnClickListener { exitDialog() }
        step = (savedInstanceState?.getInt("step") ?: Onboarding.step(this)).coerceIn(1, 6).let { if (it == 0) 1 else it }
        if (step == 0) step = 1

        b.btnAsrDownload.setOnClickListener { download(required = true) }
        b.btnAsrImport.setOnClickListener { startActivity(SettingsActivity.intent(this, "models")) }
        b.btnLlmDownload.setOnClickListener { download(required = false) }
        b.btnLlmOnline.setOnClickListener { startActivity(AiSettingsActivity.intent(this)) }
        b.btnLlmSkip.setOnClickListener { LlmSettings.useLocal(this); Ui.toast(this, "先转写，之后可以在设置里补充整理方式"); go(3) }
        b.btnMediaPermission.setOnClickListener { mediaPermission.launch(AutoImportSettings.mediaPermission()) }
        b.btnPickFolder.setOnClickListener { tree.launch(null) }
        b.btnAllFiles.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 30) startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            else ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 5)
        }
        b.rangeGroup.setOnCheckedChangeListener { _, id -> b.daysGroup.visibility = if (id == R.id.rangeRecent) View.VISIBLE else View.GONE }
        b.btnProcPause.setOnClickListener {
            try { AutoImportSettings.pause(this, Prefs.autoScan(this)); renderProcessing() } catch (e: Exception) { Ui.toast(this, e.message ?: "请检查权限") }
        }
        b.btnPrimary.setOnClickListener { primary() }
        b.btnSecondary.setOnClickListener { secondary() }
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("model-install").observe(this) { renderModels(it) }
        show(step)
    }

    override fun onSaveInstanceState(out: Bundle) { out.putInt("step", step); super.onSaveInstanceState(out) }
    override fun onResume() { super.onResume(); show(step); handler.post(ticker); LiveAsr.addListener(liveListener) }
    override fun onPause() { handler.removeCallbacks(ticker); LiveAsr.removeListener(liveListener); Onboarding.setStep(this, step); super.onPause() }

    private fun go(n: Int) { step = n.coerceIn(1, 6); Onboarding.setStep(this, step); show(step) }

    private fun show(n: Int) {
        listOf(b.step1, b.step2, b.step3, b.step4, b.step5, b.step6).forEachIndexed { i, v -> v.visibility = if (i + 1 == n) View.VISIBLE else View.GONE }
        b.tvStep.text = if (n == 6) "首次设置 · 完成" else "首次设置 · $n / 5"
        when (n) {
            1 -> { b.toolbar.title = "欢迎"; b.tvTitle.text = "让录音变成可以推进的事"; b.tvSubtitle.text = ""; b.btnPrimary.text = "开始"; b.btnSecondary.text = "稍后再说" }
            2 -> { b.toolbar.title = "准备模型"; b.tvTitle.text = "先准备识别能力"; b.tvSubtitle.text = "语音模型是转写必需项，整理方式可以稍后选择。"; b.btnPrimary.text = "下一步"; b.btnSecondary.text = "上一步"; renderModels() }
            3 -> { b.toolbar.title = "录音来源"; b.tvTitle.text = "录音在哪里？"; b.tvSubtitle.text = "已检查常见录音目录，授权后才能读取文件。"; b.btnPrimary.text = "下一步"; b.btnSecondary.text = "上一步"; renderSources() }
            4 -> { b.toolbar.title = "处理范围"; b.tvTitle.text = "从哪些录音开始？"; b.tvSubtitle.text = "范围可随时在自动转写设置中修改。"; b.btnPrimary.text = "开始处理"; b.btnSecondary.text = "上一步"; b.daysGroup.visibility = if (b.rangeGroup.checkedRadioButtonId == R.id.rangeRecent) View.VISIBLE else View.GONE }
            5 -> { b.toolbar.title = "处理进度"; b.tvTitle.text = "正在把录音变成文字"; b.tvSubtitle.text = "锁屏或离开此页，也可以继续处理。"; b.btnPrimary.text = "后台继续"; b.btnSecondary.text = "查看过程"; renderProcessing() }
            6 -> { b.toolbar.title = "完成"; b.tvTitle.text = "第一批录音已整理好"; b.tvSubtitle.text = ""; b.btnPrimary.text = "进入概览"; b.btnSecondary.text = "查看待办"; renderDone() }
        }
    }

    private fun primary() {
        when (step) {
            1 -> go(2)
            2 -> if (!LocalAsr.modelReady(this)) MaterialAlertDialogBuilder(this).setTitle("还需要语音模型").setMessage("准备好语音模型后，才能在本机转写。可以先去下一步选择录音目录，开始处理前会再次检查。")
                    .setPositiveButton("先选目录") { _, _ -> go(3) }.setNegativeButton("留在这里", null).show() else go(3)
            3 -> if (!AutoImportSettings.hasAccess(this)) MaterialAlertDialogBuilder(this).setTitle("先授权录音目录").setMessage("授权后才能读取并处理录音。也可以先跳过，之后用手动导入。")
                    .setPositiveButton("去授权") { _, _ -> tree.launch(null) }.setNeutralButton("跳过") { _, _ -> go(4) }.setNegativeButton("取消", null).show() else go(4)
            4 -> startProcessing()
            5 -> { Onboarding.finish(this); startActivity(Intent(this, MainActivity::class.java)); finish() }
            6 -> { Onboarding.finish(this); startActivity(Intent(this, MainActivity::class.java)); finish() }
        }
    }

    private fun secondary() {
        when (step) {
            1 -> { Onboarding.finish(this); Ui.toast(this, "可以在「我的 → 首次设置」里随时继续"); startActivity(Intent(this, MainActivity::class.java)); finish() }
            2, 3, 4 -> go(step - 1)
            5 -> startActivity(Intent(this, TranscribeLiveActivity::class.java))
            6 -> { Onboarding.finish(this); startActivity(Intent(this, MainActivity::class.java).putExtra("tab", R.id.nav_todos)); finish() }
        }
    }

    private fun exitDialog() {
        if (step >= 5) { primary(); return }
        MaterialAlertDialogBuilder(this).setTitle("下次接着设置").setMessage("已保存当前步骤。下次打开会从这里继续。")
            .setPositiveButton("保存并退出") { _, _ -> Onboarding.setStep(this, step); startActivity(Intent(this, MainActivity::class.java)); finish() }
            .setNegativeButton("继续设置", null).show()
    }

    // ---------- step 2 ----------
    private fun download(required: Boolean) {
        val proceed = {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { ModelSource.fetch(ModelSource.manifestUrl(this@OnboardingActivity)) } }
                result.onSuccess { entries ->
                    manifest = entries
                    val wanted = entries.filter { if (required) it.slot != "model.gguf" else it.slot == "model.gguf" }
                    if (wanted.isEmpty()) { Ui.toast(this@OnboardingActivity, "清单里没有需要的文件"); return@onSuccess }
                    val free = (LocalAsr.modelDir(this@OnboardingActivity).parentFile ?: filesDir).usableSpace
                    val need = wanted.sumOf { it.size }
                    if (need > 0 && free < need + 200L * 1024 * 1024) {
                        MaterialAlertDialogBuilder(this@OnboardingActivity).setTitle("可用空间不足").setMessage("需要约 ${Progress.human(need)}，当前可用 ${Progress.human(free)}。清理空间后重试，或从本地文件导入。").setPositiveButton("知道了", null).show()
                        return@onSuccess
                    }
                    ModelSource.enqueue(this@OnboardingActivity, wanted)
                    Ui.toast(this@OnboardingActivity, "开始下载，完成后自动校验")
                    renderModels()
                }.onFailure { Ui.toast(this@OnboardingActivity, "无法读取推荐来源：${it.message}") }
            }
        }
        if (!LlmSettings.onUnmeteredNetwork(this)) MaterialAlertDialogBuilder(this).setTitle("当前不是 Wi-Fi").setMessage(if (required) "语音模型约 450 MB，可能消耗移动流量。" else "本地大模型约 2.4 GB，可能消耗大量移动流量。")
            .setPositiveButton("仍然下载") { _, _ -> proceed() }.setNegativeButton("等 Wi-Fi", null).show() else proceed()
    }

    private fun renderModels(infos: List<WorkInfo>? = null) {
        val asrReady = LocalAsr.modelReady(this)
        val llm = LlmEngine.modelIn(LocalAsr.llmDir(this))
        b.chipAsr.text = if (asrReady) "已就绪" else "必需"
        if (asrReady) Ui.tint(b.chipAsr, R.color.status_ready_bg, R.color.status_ready_fg) else Ui.tint(b.chipAsr, R.color.status_pending_bg, R.color.status_pending_fg)
        b.btnAsrDownload.text = if (asrReady) "重新下载" else "从推荐来源下载"
        b.tvLlmInfo.text = when {
            LlmSettings.remoteEnabled(this) -> "已选择在线 AI 服务整理。"
            llm != null -> "本地模型已就绪：${llm.name}"
            else -> "本地 Qwen 模型约 2.4 GB，离线整理；也可以使用在线 AI 服务，或暂时只转写。"
        }
        b.btnLlmDownload.text = if (llm != null) "本地模型已就绪" else "下载本地模型（约 2.4 GB）"
        val running = (infos ?: WorkManager.getInstance(this).getWorkInfosForUniqueWork("model-install").get()).filter { !it.state.isFinished }
        val status = LocalSettings.prefs(this).getString("modelStatus", null)
        for ((bar, text, isLlm) in listOf(Triple(b.progressAsr, b.tvAsrProgress, false), Triple(b.progressLlm, b.tvLlmProgress, true))) {
            val mine = running.filter { (it.progress.getString("slot") == "model.gguf") == isLlm }
            if (mine.isEmpty()) {
                bar.visibility = View.GONE
                text.visibility = if (status != null && status.startsWith("安装失败")) View.VISIBLE else View.GONE
                text.text = status.orEmpty()
                continue
            }
            val info = mine.first()
            val slot = info.progress.getString("slot") ?: "…"
            val bytes = info.progress.getLong("bytes", 0); val total = info.progress.getLong("total", -1)
            bar.visibility = View.VISIBLE; text.visibility = View.VISIBLE
            if (total > 0) { bar.isIndeterminate = false; bar.max = 1000; bar.setProgressCompat((bytes * 1000 / total).toInt(), true) } else bar.isIndeterminate = true
            text.text = if (info.state == WorkInfo.State.RUNNING) "正在下载 $slot · ${Progress.human(bytes)}" + (if (total > 0) " / ${Progress.human(total)}" else "") + if (mine.size > 1) "（还有 ${mine.size - 1} 个文件排队）" else ""
                else "排队中 · ${mine.size} 个文件"
        }
    }

    // ---------- step 3 ----------
    private fun renderSources() {
        val ctx = this
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val access = AutoImportSettings.hasAccess(ctx)
                val files = if (access) RecordingSources.collect(ctx).files else emptyList()
                val primary = android.os.Environment.getExternalStorageDirectory().canonicalPath.lowercase().trimEnd('/')
                LocalSettings.paths.map { (brand, dirs) ->
                    val count = if (!access) -1 else files.count { f -> val k = f.key.lowercase(); dirs.any { rel -> k.startsWith("$primary/${rel.lowercase()}/") } }
                    Triple(brand, dirs.joinToString(" · "), count)
                } to (if (access) files.size else -1)
            }
            b.listSources.removeAllViews()
            for ((brand, dirs, count) in rows.first) {
                val r = ItemSettingRowBinding.inflate(layoutInflater, b.listSources, false)
                r.icon.setImageResource(R.drawable.ic_phone); r.tvTitle.text = brand; r.tvValue.text = dirs
                r.chevron.visibility = View.GONE
                r.toggle.visibility = View.GONE
                val tag = android.widget.TextView(ctx).apply { text = when { count < 0 -> "待授权"; count == 0 -> "未找到"; else -> "找到 $count 个" }; setTextColor(androidx.core.content.ContextCompat.getColor(ctx, if (count > 0) R.color.md_primary else R.color.md_on_surface_variant)) }
                (r.root as android.widget.LinearLayout).addView(tag)
                r.row.isClickable = false
                b.listSources.addView(r.root)
            }
            val trees = LocalSettings.treeUris(ctx).size
            val total = rows.second
            b.btnMediaPermission.visibility = if (AutoImportSettings.hasMediaAccess(ctx) || AutoImportSettings.hasFileAccess(ctx)) View.GONE else View.VISIBLE
            b.tvSourcesHint.text = when {
                total > 0 -> "✓ 已授权 · 当前可读取 $total 条录音" + if (trees > 0) "（已选 $trees 个目录）" else ""
                AutoImportSettings.hasAccess(ctx) -> "已授权，但还没有找到录音。添加其他目录，或录一段声音再回来。"
                else -> "先检查常见的录音目录；授权后才会读取文件。"
            }
        }
    }

    // ---------- step 4 / 5 ----------
    private fun startProcessing() {
        val choice = when (b.rangeGroup.checkedRadioButtonId) { R.id.rangeNew -> "new"; R.id.rangeAll -> "all"; R.id.rangeOff -> "off"; else -> "recent" }
        val days = when (b.daysGroup.checkedChipId) { R.id.days14 -> 14; R.id.days30 -> 30; R.id.days90 -> 90; else -> 7 }
        if (choice == "off") { AutoImportSettings.disable(this); Onboarding.finish(this); Ui.toast(this, "设置已保存，自动转写未开启"); startActivity(Intent(this, MainActivity::class.java)); finish(); return }
        if (!LocalAsr.modelReady(this)) { MaterialAlertDialogBuilder(this).setTitle("还需要语音模型").setMessage("准备好语音模型后，就能在本机转写。").setPositiveButton("去准备模型") { _, _ -> go(2) }.setNegativeButton("取消", null).show(); return }
        if (!AutoImportSettings.hasAccess(this)) { MaterialAlertDialogBuilder(this).setTitle("先授权录音目录").setMessage("授权后才能读取并处理录音。").setPositiveButton("去授权") { _, _ -> go(3) }.setNegativeButton("取消", null).show(); return }
        try { AutoImportSettings.enable(this, choice, days) } catch (e: Exception) { Ui.toast(this, e.message ?: "无法开启"); return }
        if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 6)
        go(5)
    }

    private fun renderProcessing() {
        val q = RecordingQueue.of(this).counts()
        val queued = q.optInt("queued"); val processing = q.optInt("processing"); val done = q.optInt("done"); val error = q.optInt("error")
        val total = queued + processing + done + error
        val paused = !Prefs.autoScan(this)
        b.tvProcState.text = if (paused) "已暂停" else if (total == 0) "正在检查录音目录" else "正在处理"
        b.chipProc.text = "$done / $total"
        val scan = LocalSettings.prefs(this).getString("scanStatus", null)
        b.tvProcFound.text = scan ?: "正在检查录音目录…"
        b.progressProc.max = maxOf(total, 1); b.progressProc.setProgressCompat(done, true)
        val live = LiveAsr.snapshot()
        b.tvProcFile.text = if (live.active) "正在转写：${live.title}" else if (processing > 0) "正在准备下一条…" else ""
        val lines = live.rows.filter { it.text.isNotBlank() }.takeLast(3).map { it.text }
        b.tvProcText.text = when {
            live.active && lines.isNotEmpty() -> lines.joinToString("\n")
            live.active -> live.phase
            paused -> "已暂停，点击继续后接着处理。"
            else -> "正在识别，文字将在这里逐段出现…"
        }
        b.btnProcPause.text = if (paused) "继续" else "暂停"
        b.btnProcPause.setIconResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        if (total > 0 && queued == 0 && processing == 0 && !live.active) go(6)
        else if (total == 0 && scan != null && scan.contains("范围内 0 条")) {
            b.tvProcState.text = "已开启，等待新录音"; b.tvProcText.text = "范围内没有已有录音。新录音保存到授权目录后，会自动开始转写。"
        }
    }

    private fun renderDone() {
        val recs = LocalStore.of(this).all("recording")
        val todos = LocalStore.of(this).all("todo")
        val transcribed = RecordingQueue.of(this).counts().optInt("done")
        b.tvDoneTitle.text = "已转写 $transcribed 条"
        val llm = LlmEngine.modelIn(LocalAsr.llmDir(this)) != null || LlmSettings.remoteEnabled(this)
        b.tvDoneSub.text = if (!llm) "文字已保存在本机。整理方式尚未就绪，可在设置里补充后生成待办。"
            else "生成 ${todos.size} 条待办 · ${recs.count { it.optString("status") == "pending" }} 条仍在整理。文字已保存在本机。"
    }

    companion object {
        fun intent(ctx: Context) = Intent(ctx, OnboardingActivity::class.java)
    }
}
