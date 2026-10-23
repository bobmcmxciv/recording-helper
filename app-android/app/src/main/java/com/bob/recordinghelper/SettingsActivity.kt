package com.bob.recordinghelper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.work.WorkManager
import com.bob.recordinghelper.databinding.ActivitySettingsBinding
import com.bob.recordinghelper.databinding.ItemSettingRowBinding
import com.bob.recordinghelper.databinding.SheetTextImportBinding
import com.bob.recordinghelper.local.*
import com.bob.recordinghelper.ui.Ui
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File

class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding
    private var slot = "model.gguf"
    private var scrolled = false
    private var pendingChoice: String? = null
    private var pendingDays: Int = 7

    private val mediaPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val choice = pendingChoice
            pendingChoice = null
            if (choice != null && choice != "off") {
                runCatching { AutoImportSettings.enable(this, choice, pendingDays) }
                if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 6)
                toast("已授权并开启自动转写")
            } else {
                AutoImportSettings.requestScan(this, true)
                toast("已授权，正在自动查找本机录音")
            }
        } else {
            pendingChoice = null
            toast("未授予音频权限；也可手动选择录音文件夹")
        }
        render()
    }

    private val tree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            LocalSettings.addTree(this, uri.toString())
            val choice = pendingChoice
            pendingChoice = null
            if (choice != null && choice != "off") {
                runCatching { AutoImportSettings.enable(this, choice, pendingDays) }
                toast("已授权文件夹并开启自动转写")
            } else {
                AutoImportSettings.requestScan(this, true)
            }
            render()
        } catch (e: Exception) { toast("授权失败：${e.message}") }
    }
    private val model = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ModelInstallWorker.enqueue(this, slot, uri.toString()); toast("已开始复制与校验，稍后刷新状态")
        } catch (e: Exception) { toast("导入失败：${e.message}") }
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) Thread {
            val result = runCatching { contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(LocalStore.of(this).export()) } }
            runOnUiThread { toast(if (result.isSuccess) "已导出本地数据" else "导出失败：${result.exceptionOrNull()?.message}") }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        slot = savedInstanceState?.getString("slot") ?: slot
        scrolled = savedInstanceState?.getBoolean("scrolled") ?: false
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        Ui.insets(b.root, top = b.appbar, bottom = b.scroll)
        Ui.column(b.scroll, 720)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.btnSaveSync.setOnClickListener { saveSync() }
        b.btnDisableSync.setOnClickListener {
            LocalSettings.prefs(this).edit().putBoolean("sync", false).apply()
            WorkManager.getInstance(this).cancelUniqueWork("local-sync"); toast("已关闭同步，数据保留在本机"); render()
        }
        b.btnOpenBoard.setOnClickListener {
            val base = Prefs.baseUrl(this)
            if (base.startsWith("https://")) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$base/"))) else toast("请先保存服务器地址")
        }
    }
    override fun onSaveInstanceState(out: Bundle) { out.putString("slot", slot); out.putBoolean("scrolled", scrolled); super.onSaveInstanceState(out) }
    override fun onResume() { super.onResume(); render() }

    private fun render() {
        renderImport(); renderModels(); renderSync(); renderSystem()
        if (!scrolled) {
            scrolled = true
            val target = when (intent.getStringExtra("section")) { "models" -> b.titleModels; "sync" -> b.titleSync; "import" -> b.titleImport; else -> null }
            target?.let { t -> b.scroll.post { b.scroll.smoothScrollTo(0, t.top) } }
        }
    }

    // ---------- 自动转写 ----------
    private fun renderImport() {
        val list = b.listImport; list.removeAllViews()
        val scope = Prefs.scanScope(this); val on = Prefs.autoScan(this)
        switchRow(list, R.drawable.ic_auto, "自动转写新录音", if (scope == null) "尚未选择处理范围" else AutoImportSettings.scopeLabel(this) + if (on) "" else "（已暂停）", on) { checked ->
            try {
                if (checked) { if (scope in ImportScope.ranges) AutoImportSettings.pause(this, false) else autoSetup() }
                else if (scope in ImportScope.ranges) AutoImportSettings.pause(this, true)
            } catch (e: Exception) { toast(e.message ?: "请先授权录音目录") }
            render()
        }
        row(list, R.drawable.ic_schedule, "处理范围", AutoImportSettings.scopeLabel(this)) { autoSetup() }
        val trees = LocalSettings.treeUris(this)
        row(list, R.drawable.ic_check, "读取本机录音（推荐）", if (AutoImportSettings.hasMediaAccess(this)) "已授权 · 自动发现系统录音机的文件，免找文件夹" else "未授权 · 点击一键授权，自动查找本机录音") {
            mediaPermission.launch(AutoImportSettings.mediaPermission())
        }
        row(list, R.drawable.ic_folder, "手动授权录音文件夹", if (trees.isEmpty()) "未添加 · 自动定位 ${LocalSettings.currentBrand()} 常见录音目录" else "已授权 ${trees.size} 个目录") { manageTrees() }
        row(list, R.drawable.ic_lock, "全部文件权限", if (AutoImportSettings.hasFileAccess(this)) "已授予 · 自动发现品牌录音目录" else "未授予 · 仅扫描已授权目录") {
            if (Build.VERSION.SDK_INT >= 30) startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            else ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 5)
        }
        row(list, R.drawable.ic_phone, "品牌预设路径", LocalSettings.paths.keys.joinToString(" · ")) {
            MaterialAlertDialogBuilder(this).setTitle("添加候选路径").setItems(LocalSettings.paths.keys.toTypedArray()) { _, index ->
                val merged = (Prefs.scanDirs(this) + LocalSettings.paths.values.toList()[index]).distinct()
                Prefs.save(this, Prefs.baseUrl(this), Prefs.token(this), Prefs.autoScan(this), merged.joinToString("\n"), Prefs.scanDays(this).toString())
                toast("已添加候选路径；直接扫描需要全部文件权限"); render()
            }.show()
        }
        row(list, R.drawable.ic_description, "额外扫描路径", "${Prefs.scanDirs(this).size} 条 · 每行一个相对或绝对路径") {
            val layout = TextInputLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0) }
            val field = TextInputEditText(this).apply { setText(Prefs.scanDirs(this@SettingsActivity).joinToString("\n")); maxLines = 8 }
            layout.addView(field)
            MaterialAlertDialogBuilder(this).setTitle("额外录音来源").setView(layout).setPositiveButton("保存") { _, _ ->
                Prefs.save(this, Prefs.baseUrl(this), Prefs.token(this), Prefs.autoScan(this), field.text.toString(), Prefs.scanDays(this).toString())
                AutoImportSettings.requestScan(this); render()
            }.setNegativeButton("取消", null).show()
        }
        val status = LocalSettings.prefs(this).getString("scanStatus", null)
        row(list, R.drawable.ic_refresh, "立即检查目录", status ?: "扫描已授权目录，把范围内的录音加入队列") { AutoImportSettings.requestScan(this, true); toast("正在检查，稍后回到首页查看队列") }
    }

    private fun manageTrees() {
        val uris = LocalSettings.treeUris(this).toList()
        val names = uris.map { Uri.decode(it.substringAfterLast('/')).substringAfterLast(':').ifBlank { it } }
        val builder = MaterialAlertDialogBuilder(this).setTitle("录音目录").setNeutralButton("添加目录") { _, _ -> tree.launch(LocalSettings.initialTreeUri()) }.setNegativeButton("关闭", null)
        if (uris.isEmpty()) builder.setMessage("还没有手动添加目录。系统会默认打开本机常见录音位置，直接点击底部「使用此文件夹」即可。")
        else builder.setItems(names.map { "$it（点击移除）" }.toTypedArray()) { _, index ->
            val uri = uris[index]
            try { contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            LocalSettings.prefs(this).edit().putStringSet("trees", LocalSettings.treeUris(this) - uri).apply(); render()
        }
        builder.show()
    }

    private fun autoSetup() {
        ImportChoiceDialog.show(this, Prefs.scanScope(this), Prefs.recentScanDays(this)) { choice, days ->
            if (choice != "off" && !AutoImportSettings.hasAccess(this)) {
                pendingChoice = choice
                pendingDays = days
                MaterialAlertDialogBuilder(this).setTitle("允许读取录音")
                    .setMessage("推荐使用「一键自动授权」，无需知道录音文件夹位置即可自动发现录音；也可以手动选择录音文件夹。")
                    .setPositiveButton("一键自动授权（推荐）") { _, _ -> mediaPermission.launch(AutoImportSettings.mediaPermission()) }
                    .setNeutralButton("手动选择文件夹") { _, _ -> tree.launch(LocalSettings.initialTreeUri()) }
                    .setNegativeButton("取消") { _, _ -> pendingChoice = null }.show()
                false
            } else {
                if (choice == "off") AutoImportSettings.disable(this) else AutoImportSettings.enable(this, choice, days)
                if (choice != "off" && Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 6)
                render()
                toast(if (choice == "off") "已保存：暂不开启自动转写，手动导入仍可用" else "已记住选择并开始扫描；后台执行可能受系统省电策略影响")
                true
            }
        }
    }

    // ---------- 模型中心 ----------
    private fun renderModels() {
        val list = b.listModels; list.removeAllViews()
        val asrReady = LocalAsr.modelReady(this)
        val llmReady = com.bob.recordinghelper.llm.LlmEngine.modelIn(LocalAsr.llmDir(this)) != null
        val allReady = asrReady && llmReady
        val topTitle = when {
            !asrReady -> "一键下载离线语音模型（推荐 · 约 450 MB）"
            !llmReady -> "一键补齐本地整理大模型（可选 · 约 2.4 GB）"
            else -> "全套离线模型已就绪"
        }
        val topSub = when {
            !asrReady -> "推荐先装语音识别（支持断点续传），免等 2.4 GB 大模型即可转写并快筛待办"
            !llmReady -> "语音识别已就绪 · 点击后台下载本地 Qwen 整理模型（支持断点续传）"
            else -> "语音识别与本地整理大模型均已安装 · 点击可检查或重新补齐"
        }
        row(list, R.drawable.ic_download, topTitle, topSub) {
            Thread {
                val res = runCatching {
                    val entries = ModelSource.fetch(ModelSource.manifestUrl(this))
                    val includeLlm = asrReady
                    val wanted = ModelSource.missingEntries(this, entries, includeLlm = includeLlm).ifEmpty { entries }
                    ModelSource.enqueue(this, wanted)
                    wanted.size
                }
                runOnUiThread {
                    res.onSuccess { count -> toast("已开始后台下载 $count 个模型文件（支持断点续传），完成后自动校验"); render() }
                        .onFailure { toast("无法获取推荐模型清单：${it.message}") }
                }
            }.start()
        }
        val labels = mapOf("sv.tflite" to "语音识别模型", "tokens.json" to "词表", "am.mvn" to "声学特征", "spk.tflite" to "声纹模型（可选）", "model.gguf" to "本地大模型（GGUF，可选）")
        val icons = mapOf("sv.tflite" to R.drawable.ic_memory, "tokens.json" to R.drawable.ic_text, "am.mvn" to R.drawable.ic_library, "spk.tflite" to R.drawable.ic_voice, "model.gguf" to R.drawable.ic_auto)
        for (s in ModelFiles.slots) {
            val f = if (s == "model.gguf") com.bob.recordinghelper.llm.LlmEngine.modelIn(LocalAsr.llmDir(this)) else ModelFiles.target(this, s)
            val ready = f != null && f.length() > 0
            val cached = if (ready) 0L else ModelFiles.cachedPartBytes(this, s)
            val sub = when {
                ready -> "已就绪 · ${Progress.human(f!!.length())}" + (if (s == "model.gguf") " · ${f.name}" else "")
                cached > 0L -> "未完成（已缓存 ${Progress.human(cached)} · 点击继续断点续传）"
                else -> "未安装 · 点击一键下载或从本地文件导入"
            }
            row(list, icons[s] ?: R.drawable.ic_memory, labels[s] ?: s, sub) {
                slot = s
                val actionLabel = if (cached > 0L) "继续断点续传（已缓存 ${Progress.human(cached)}）" else "从推荐来源一键下载（支持断点续传）"
                MaterialAlertDialogBuilder(this).setTitle(labels[s]).setItems(arrayOf(actionLabel, "从本地文件导入", "手动输入 HTTPS 地址下载（需 SHA-256）")) { _, which ->
                    when (which) {
                        0 -> downloadSlotFromManifest(s)
                        1 -> model.launch(arrayOf("*/*"))
                        else -> download()
                    }
                }.setNeutralButton("取消当前安装") { _, _ -> WorkManager.getInstance(this).cancelUniqueWork("model-install"); toast("已请求取消，已下载缓存与原模型均保留") }.show()
            }
        }
        row(list, R.drawable.ic_cloud, "AI 整理方式", if (com.bob.recordinghelper.llm.LlmSettings.remoteEnabled(this)) "在线 AI 服务 · " + (com.bob.recordinghelper.llm.LlmProviders.byId(com.bob.recordinghelper.llm.LlmSettings.providerId(this))?.name ?: "") else "本地模型（离线）") {
            startActivity(com.bob.recordinghelper.ui.AiSettingsActivity.intent(this))
        }
        val accelerated = LocalAsr.isAccelerated(this)
        row(list, R.drawable.ic_bolt, "转写引擎", if (accelerated) "NPU 硬件加速（已开启）" else "稳定 CPU") {
            MaterialAlertDialogBuilder(this).setTitle("转写引擎").setItems(arrayOf("稳定 CPU", "NPU 硬件加速（推荐）")) { _, which ->
                LocalSettings.prefs(this).edit().putBoolean("asrAcceleration", which == 1).apply(); render()
            }.show()
        }
        switchRow(list, R.drawable.ic_edit, "本地润色", "用本地大模型润色转写稿，较耗时；原文始终保留", Prefs.polishMode(this) == Prefs.POLISH_LOCAL) { checked ->
            Prefs.setPolishMode(this, if (checked) Prefs.POLISH_LOCAL else Prefs.POLISH_OFF)
        }
        val status = listOfNotNull(LocalSettings.prefs(this).getString("modelStatus", null), Prefs.lastAsr(this)?.let { "最近转写：$it" })
        b.tvModelStatus.text = status.joinToString("\n").ifBlank { "模型文件需与本项目运行时兼容：SenseVoice TFLite 三件套、CAM++ 声纹模型、Qwen 系列 GGUF。来源与校验值见项目说明。" }
    }

    private fun downloadSlotFromManifest(targetSlot: String) {
        Thread {
            val res = runCatching {
                val entries = ModelSource.fetch(ModelSource.manifestUrl(this))
                val entry = entries.firstOrNull { it.slot == targetSlot } ?: error("推荐清单中未找到 $targetSlot")
                ModelSource.enqueue(this, listOf(entry))
                entry.name
            }
            runOnUiThread {
                res.onSuccess { name -> toast("已加入后台下载队列：$name（支持断点续传）"); render() }
                    .onFailure { toast("无法读取推荐来源：${it.message}") }
            }
        }.start()
    }

    private fun download() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0) }
        val url = TextInputLayout(this).apply { hint = "HTTPS 直链" }.also { it.addView(TextInputEditText(this).apply { maxLines = 2 }) }
        val hash = TextInputLayout(this).apply { hint = "发布方提供的 SHA-256（64 位）" }.also { it.addView(TextInputEditText(this).apply { maxLines = 2 }) }
        box.addView(url); box.addView(hash)
        MaterialAlertDialogBuilder(this).setTitle("下载 $slot").setView(box).setPositiveButton("下载") { _, _ ->
            val u = url.editText?.text.toString().trim(); val h = hash.editText?.text.toString().trim()
            if (!u.startsWith("https://") || !Regex("[a-fA-F0-9]{64}").matches(h)) toast("需要 HTTPS 地址和有效的 SHA-256")
            else { ModelInstallWorker.enqueue(this, slot, u, h); toast("下载已排队，完成后自动校验") }
        }.setNegativeButton("取消", null).show()
    }

    // ---------- 同步 ----------
    private fun renderSync() {
        if (b.baseUrl.text.isNullOrBlank()) b.baseUrl.setText(Prefs.baseUrl(this))
        if (b.token.text.isNullOrBlank()) b.token.setText(Prefs.token(this))
        val on = LocalSettings.sync(this)
        b.tvSyncStatus.text = if (on) LocalSettings.prefs(this).getString("syncStatus", "同步已开启") else "同步默认关闭，数据只在本机"
        b.btnDisableSync.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun saveSync() {
        val base = b.baseUrl.text.toString().trim().trimEnd('/')
        val token = b.token.text.toString()
        val valid = try { val url = java.net.URL(base); url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null && url.query == null && url.ref == null } catch (_: Exception) { false }
        if (!valid || token.isBlank()) { toast("请填写有效的 HTTPS 地址和工作空间密钥"); return }
        MaterialAlertDialogBuilder(this).setTitle("把文字同步到这台服务器？").setMessage("目标：$base\n\n将发送本机转写、纪要和待办。同一密钥下的设备可以看到这些内容；关闭同步不会删除服务器上的数据。")
            .setPositiveButton("确认开启") { _, _ ->
                if (base != Prefs.baseUrl(this) || token != Prefs.token(this)) {
                    LocalStore.of(this).resetSync(); LocalSettings.prefs(this).edit().putLong("cursor", 0).apply()
                }
                Prefs.save(this, base, token, Prefs.autoScan(this), Prefs.scanDirs(this).joinToString("\n"), Prefs.scanDays(this).toString())
                LocalSettings.prefs(this).edit().putBoolean("sync", true).apply(); SyncWorker.schedule(this); toast("已开启同步"); render()
            }.setNegativeButton("取消", null).show()
    }

    // ---------- 系统与数据 ----------
    private fun renderSystem() {
        val list = b.listSystem; list.removeAllViews()
        row(list, R.drawable.ic_notifications, "处理进度通知", "转写、整理和同步的进度") {
            if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 6)
            else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        row(list, R.drawable.ic_battery, "息屏后台处理", if (KeepAwake.unrestricted(this)) "已解除电池优化限制" else "建议解除电池优化并允许自启动，否则熄屏后可能暂停") {
            try {
                startActivity(Intent(if (KeepAwake.unrestricted(this)) Settings.ACTION_APPLICATION_DETAILS_SETTINGS else Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
        row(list, R.drawable.ic_download, "导出本地数据", "转写、纪要和待办（JSON）") { export.launch("recording-helper-backup.json") }
        row(list, R.drawable.ic_visibility, "转写过程", "查看模型正在处理什么") { startActivity(Intent(this, TranscribeLiveActivity::class.java)) }
    }

    // ---------- helpers ----------
    private fun row(parent: LinearLayout, icon: Int, title: String, value: String, onClick: () -> Unit) {
        val r = ItemSettingRowBinding.inflate(layoutInflater, parent, false)
        r.icon.setImageResource(icon); r.tvTitle.text = title; r.tvValue.text = value
        r.tvValue.visibility = if (value.isBlank()) View.GONE else View.VISIBLE
        r.row.setOnClickListener { onClick() }
        parent.addView(r.root)
    }
    private fun switchRow(parent: LinearLayout, icon: Int, title: String, value: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val r = ItemSettingRowBinding.inflate(layoutInflater, parent, false)
        r.icon.setImageResource(icon); r.tvTitle.text = title; r.tvValue.text = value
        r.chevron.visibility = View.GONE; r.toggle.visibility = View.VISIBLE
        r.toggle.isChecked = checked
        r.toggle.setOnCheckedChangeListener { _, isChecked -> if (isChecked != checked) onChange(isChecked) }
        r.row.setOnClickListener { r.toggle.toggle() }
        parent.addView(r.root)
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Ui.toast(this, s)

    companion object {
        fun intent(ctx: Context, section: String? = null): Intent = Intent(ctx, SettingsActivity::class.java).putExtra("section", section)
    }
}
