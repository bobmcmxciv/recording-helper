package com.bob.recordinghelper.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.bob.recordinghelper.LocalAsr
import com.bob.recordinghelper.Prefs
import com.bob.recordinghelper.R
import com.bob.recordinghelper.SettingsActivity
import com.bob.recordinghelper.TranscribeLiveActivity
import com.bob.recordinghelper.databinding.FragmentMeBinding
import com.bob.recordinghelper.databinding.ItemSettingRowBinding
import com.bob.recordinghelper.llm.LlmEngine
import com.bob.recordinghelper.local.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MeFragment : TabFragment() {
    private var _b: FragmentMeBinding? = null
    private val b get() = _b!!

    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val ctx = requireContext().applicationContext
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    ctx.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(LocalStore.of(ctx).export()) }
                } }
                toast(if (result.isSuccess) "已导出本地数据" else "导出失败：${result.exceptionOrNull()?.message}")
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentMeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.btnSyncSettings.setOnClickListener { startActivity(SettingsActivity.intent(requireContext(), "sync")) }
        b.btnBoard.setOnClickListener {
            val base = Prefs.baseUrl(requireContext())
            if (base.startsWith("https://")) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$base/"))) else toast("请先在同步设置中填写服务器地址")
        }
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }

    override fun refresh() {
        if (_b == null) return
        val ctx = requireContext()
        val sync = LocalSettings.sync(ctx)
        b.iconMode.setImageResource(if (sync) R.drawable.ic_cloud else R.drawable.ic_lock)
        b.tvMode.text = if (sync) "多设备同步已开启" else "纯本地模式"
        b.tvModeSub.text = if (sync) LocalSettings.prefs(ctx).getString("syncStatus", "等待同步") else "无需账号、无需服务器，音频不离开手机。"

        b.listSetup.removeAllViews()
        val asr = LocalAsr.modelReady(ctx)
        val llm = LlmEngine.modelIn(LocalAsr.llmDir(ctx)) != null
        val spk = File(LocalAsr.modelDir(ctx), "spk.tflite").length() > 0
        row(b.listSetup, R.drawable.ic_memory, "语音模型", if (asr) "已就绪 · 本机转写" else "未安装 · 到模型中心导入") { startActivity(SettingsActivity.intent(ctx, "models")) }
        row(b.listSetup, R.drawable.ic_auto, "本地大模型", if (llm) "已就绪 · 自动生成纪要和待办" else "未安装 · 只保留转写原文") { startActivity(SettingsActivity.intent(ctx, "models")) }
        row(b.listSetup, R.drawable.ic_voice, "声纹模型", if (spk) "已就绪 · 可区分说话人" else "未安装 · 可选") { startActivity(SettingsActivity.intent(ctx, "models")) }
        row(b.listSetup, R.drawable.ic_folder, "自动转写", AutoImportSettings.scopeLabel(ctx) + if (Prefs.autoScan(ctx)) "" else "（未开启）") { startActivity(SettingsActivity.intent(ctx, "import")) }

        b.listTools.removeAllViews()
        row(b.listTools, R.drawable.ic_mic, "现场录音", "录音并实时转写") { startActivity(Intent(ctx, MicrophoneActivity::class.java)) }
        row(b.listTools, R.drawable.ic_voice, "声纹库", "认出自己和熟悉的声音") { startActivity(Intent(ctx, VoiceLibraryActivity::class.java)) }
        row(b.listTools, R.drawable.ic_visibility, "转写过程", "查看模型正在处理什么") { startActivity(Intent(ctx, TranscribeLiveActivity::class.java)) }
        row(b.listTools, R.drawable.ic_download, "导出数据", "转写、纪要和待办（JSON）") { export.launch("recording-helper-backup.json") }
        row(b.listTools, R.drawable.ic_settings, "全部设置", "模型、录音来源、同步、系统") { startActivity(Intent(ctx, SettingsActivity::class.java)) }

        b.listAbout.removeAllViews()
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
        row(b.listAbout, R.drawable.ic_info, "版本", version, chevron = false) { }
        row(b.listAbout, R.drawable.ic_lock, "隐私与数据", "全部在本机，卸载即清除") {
            MaterialAlertDialogBuilder(ctx).setTitle("隐私与数据").setMessage(
                "录音、转写、纪要、待办和声纹都保存在手机的应用私有目录中，不会上传。\n\n" +
                "只有在你明确开启多设备同步后，转写文字、纪要和待办才会发送到你自己的服务器；音频、声纹和模型永远不会发送。\n\n" +
                "AI 生成的纪要和待办是建议，执行前请核对原文。卸载应用会删除本机数据和应用专用模型。").setPositiveButton("知道了", null).show()
        }
        row(b.listAbout, R.drawable.ic_description, "开源许可", "Apache-2.0 · 第三方组件说明") {
            MaterialAlertDialogBuilder(ctx).setTitle("开源许可").setMessage(
                "Recording Helper 以 Apache License 2.0 发布。\n\n" +
                "包含的第三方组件：llama.cpp（MIT）、minimp3（CC0）、opencore-amr（Apache-2.0）、fdk-aac（FDK 许可）、LiteRT（Apache-2.0）、Qualcomm QNN 运行库（Qualcomm 许可）、Material Components（Apache-2.0）。\n\n" +
                "语音、声纹和语言模型由用户自行获取并导入，须遵守各模型的许可条款。完整条款见项目仓库。").setPositiveButton("知道了", null).show()
        }
    }

    private fun row(parent: LinearLayout, icon: Int, title: String, value: String, chevron: Boolean = true, onClick: () -> Unit) {
        val r = ItemSettingRowBinding.inflate(layoutInflater, parent, false)
        r.icon.setImageResource(icon)
        r.tvTitle.text = title
        r.tvValue.text = value
        r.tvValue.visibility = if (value.isBlank()) View.GONE else View.VISIBLE
        r.chevron.visibility = if (chevron) View.VISIBLE else View.INVISIBLE
        r.row.setOnClickListener { onClick() }
        parent.addView(r.root)
    }
}
