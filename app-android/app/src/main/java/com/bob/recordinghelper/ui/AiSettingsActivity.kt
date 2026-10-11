package com.bob.recordinghelper.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bob.recordinghelper.LocalAsr
import com.bob.recordinghelper.R
import com.bob.recordinghelper.databinding.ActivityAiSettingsBinding
import com.bob.recordinghelper.llm.*
import com.bob.recordinghelper.local.AnalysisWorker
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Local model by default; the online path needs an explicit, recorded consent. */
class AiSettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivityAiSettingsBinding
    private var remoteSelected = false
    /** Consent given in this screen session; persisted by [save] so the dialog is not shown twice. */
    private var consented = false
    private var provider: LlmProvider = LlmProviders.all.first()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        b = ActivityAiSettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        Ui.insets(b.root, top = b.appbar, bottom = b.scroll)
        Ui.column(b.scroll, 720)
        b.toolbar.setNavigationOnClickListener { finish() }

        remoteSelected = LlmSettings.mode(this) == LlmSettings.MODE_REMOTE
        provider = LlmProviders.byId(LlmSettings.providerId(this)) ?: provider
        b.provider.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, LlmProviders.all.map { it.name }))
        b.provider.setText(provider.name, false)
        b.provider.setOnItemClickListener { _, _, position, _ -> selectProvider(LlmProviders.all[position], keepFields = false) }
        fill()

        b.cardLocal.setOnClickListener { remoteSelected = false; LlmSettings.useLocal(this); AnalysisWorker.schedule(this, true); render() }
        b.cardRemote.setOnClickListener { if (!remoteSelected) consent { consented = true; remoteSelected = true; render() } }
        b.btnTest.setOnClickListener { test() }
        b.btnSave.setOnClickListener { save() }
        b.btnBackToLocal.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle("改回本地模型？").setMessage("会清除保存的 API Key。已经发送过的内容无法撤回。")
                .setPositiveButton("改回本地") { _, _ -> LlmSettings.forget(this); remoteSelected = false; fill(); AnalysisWorker.schedule(this, true); render() }
                .setNegativeButton("取消", null).show()
        }
        render()
    }

    private fun fill() {
        b.baseUrl.setText(LlmSettings.baseUrl(this).ifBlank { provider.baseUrl })
        b.apiKey.setText(LlmSettings.apiKey(this))
        b.modelName.setText(LlmSettings.model(this).ifBlank { provider.defaultModel })
        b.wifiOnly.isChecked = LlmSettings.wifiOnly(this)
        b.tvProviderNote.text = provider.note
    }

    private fun selectProvider(p: LlmProvider, keepFields: Boolean) {
        provider = p
        b.tvProviderNote.text = p.note
        if (!keepFields) { b.baseUrl.setText(p.baseUrl); b.modelName.setText(p.defaultModel); b.apiKey.setText("") }
        b.apiKey.hint = p.keyHint
    }

    private fun render() {
        val remoteActive = LlmSettings.remoteEnabled(this)
        b.cardLocal.isChecked = !remoteSelected
        b.cardRemote.isChecked = remoteSelected
        b.cardLocal.strokeWidth = if (!remoteSelected) dp(2) else 0
        b.cardRemote.strokeWidth = if (remoteSelected) dp(2) else 0
        val llm = LlmEngine.modelIn(LocalAsr.llmDir(this))
        b.tvLocalStatus.text = if (llm != null) "完全离线，充电时自动整理。已安装 ${llm.name}" else "完全离线。尚未安装本地大模型，可在模型中心下载。"
        b.tvRemoteStatus.text = if (remoteActive) "已启用：${provider.name} · ${LlmSettings.model(this)}" else "需要网络和 API Key。转写文字会发送到服务商的服务器。"
        b.remoteForm.visibility = if (remoteSelected) View.VISIBLE else View.GONE
        b.btnBackToLocal.visibility = if (remoteActive) View.VISIBLE else View.GONE
        val last = LlmSettings.lastCallAt(this)
        b.tvUsage.text = if (last == 0L) "尚未调用过在线服务。" else "上次调用 " +
            Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) +
            " · 累计 ${LlmSettings.totalCalls(this)} 次 · ${LlmSettings.lastUsage(this)}"
    }

    private fun consent(onAgree: () -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        val check = MaterialCheckBox(this).apply { text = "我已了解风险，同意把转写文字发送到我选择的在线服务" }
        box.addView(check)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("使用在线 AI 服务前请确认").setMessage(LlmSettings.NOTICE).setView(box)
            .setPositiveButton("继续", null).setNegativeButton("取消", null).create()
        dialog.show()
        val ok = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE); ok.isEnabled = false
        check.setOnCheckedChangeListener { _, checked -> ok.isEnabled = checked }
        ok.setOnClickListener { if (check.isChecked) { dialog.dismiss(); onAgree() } }
    }

    private fun fields(): Triple<String, String, String> =
        Triple(b.baseUrl.text.toString().trim().trimEnd('/'), b.apiKey.text.toString().trim(), b.modelName.text.toString().trim())

    private fun test() {
        val (url, key, model) = fields()
        if (!url.startsWith("https://") || key.isBlank() || model.isBlank()) { Ui.toast(this, "请填写 HTTPS 接口地址、API Key 和模型名"); return }
        b.tvTest.text = "正在连接…"; b.btnTest.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { RemoteTextModel.test(provider.api, url, key, model) }
            b.btnTest.isEnabled = true
            b.tvTest.text = result.fold({ "连接成功，模型回复：$it" }, { "连接失败：${it.message}" })
            b.tvTest.setTextColor(ContextCompat.getColor(this@AiSettingsActivity, if (result.isSuccess) R.color.md_primary else R.color.md_error))
        }
    }

    private fun save() {
        val (url, key, model) = fields()
        try {
            if (!consented && LlmSettings.consentAt(this) == 0L) { consent { consented = true; save() }; return }
            LlmSettings.useRemote(this, provider.id, url, key, model, b.wifiOnly.isChecked)
            AnalysisWorker.schedule(this, true)
            Ui.toast(this, "已启用在线 AI 服务，待整理的记录会尽快处理")
            render()
        } catch (e: Exception) { Ui.toast(this, e.message ?: "保存失败") }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    companion object {
        fun intent(ctx: Context) = Intent(ctx, AiSettingsActivity::class.java)
    }
}
