package com.bob.recordinghelper.local

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.bob.recordinghelper.R
import com.bob.recordinghelper.databinding.ActivityVoiceLibraryBinding
import com.bob.recordinghelper.databinding.ItemSettingRowBinding
import com.bob.recordinghelper.databinding.ItemVoiceProfileBinding
import com.bob.recordinghelper.ui.Ui
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.time.Instant
import java.time.ZoneId

class VoiceLibraryActivity : AppCompatActivity() {
    private lateinit var b: ActivityVoiceLibraryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        b = ActivityVoiceLibraryBinding.inflate(layoutInflater)
        setContentView(b.root)
        Ui.insets(b.root, top = b.appbar, bottom = b.scroll)
        Ui.column(b.scroll, 720)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.btnEnroll.setOnClickListener { start("self") }
        b.btnIdentify.setOnClickListener { start("identify") }
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val profiles = try { VoiceLibrary.of(this).all() } catch (_: Exception) {
            toast("声纹库无法读取，已停止修改以保护原数据"); emptyList()
        }
        val self = profiles.any { it.self }
        b.btnEnroll.text = if (self) "更新我的声纹" else "录入我的声纹"

        b.listActions.removeAllViews()
        row(b.listActions, R.drawable.ic_people, "从对话中发现其他人的声纹", if (self) "以你的声纹为锚点，跨多段通话聚合对方的声音" else "需要先录入自己的声纹") {
            startActivity(Intent(this, VoiceRelationActivity::class.java))
        }
        row(b.listActions, R.drawable.ic_add, "添加其他说话人", "由对方本人朗读约 20 秒，须事先取得同意") { addOther() }
        row(b.listActions, R.drawable.ic_library, "比对库内声纹", "查看两两相似度，不自动合并") { compare(profiles) }

        b.headerProfiles.tvTitle.text = "已录入的声纹"
        b.headerProfiles.tvCount.text = "${profiles.size}"
        b.tvEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        b.listProfiles.removeAllViews()
        for (p in profiles.sortedByDescending { it.self }) {
            val item = ItemVoiceProfileBinding.inflate(layoutInflater, b.listProfiles, false)
            item.avatar.text = if (p.self) "我" else Ui.initials(p.name)
            item.tvName.text = p.name + if (p.self) "  ·  本人" else ""
            item.tvMeta.text = Instant.ofEpochMilli(p.created).atZone(ZoneId.systemDefault()).toLocalDate().toString() + " 录入  ·  模型更换后需重新采样"
            item.btnDelete.setOnClickListener {
                if (MicState.busy) toast("请先结束采样或录音") else MaterialAlertDialogBuilder(this).setTitle("删除「${p.name}」？")
                    .setMessage("只删除本机声纹向量，不删除已有转写，也不会改写历史说话人标签。")
                    .setPositiveButton("删除") { _, _ -> VoiceLibrary.of(this).delete(p.id); render() }.setNegativeButton("取消", null).show()
            }
            b.listProfiles.addView(item.root)
        }
    }

    private fun addOther() {
        val layout = TextInputLayout(this).apply { hint = "对方名称（不超过 40 字）"; setPadding(dp(20), dp(8), dp(20), 0) }
        val name = TextInputEditText(this).apply { setSingleLine(true) }
        layout.addView(name)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("添加声纹").setMessage("请由对方本人朗读，并事先取得其同意。不会从历史录音中擅自创建实名声纹。")
            .setView(layout).setPositiveButton("对方已同意，开始采样", null).setNegativeButton("取消", null).create()
        dialog.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val n = name.text.toString().trim()
            if (n.length !in 1..40 || n == "我的声音") layout.error = "请输入另一位说话人的名称"
            else { dialog.dismiss(); start("other", n) }
        }
    }

    private fun compare(profiles: List<VoiceLibrary.Profile>) {
        val lines = mutableListOf<String>()
        for (i in profiles.indices) for (j in i + 1 until profiles.size) {
            val a = profiles[i]; val c = profiles[j]
            lines.add("${a.name} ↔ ${c.name}\n" + if (a.model != c.model) "模型版本不同，无法比较，请重新采样" else {
                val score = VoiceMatcher.score(VoiceMatcher.center(a.vectors), VoiceMatcher.center(c.vectors))
                "相似度 %.2f  ·  %s".format(score, if (score >= VoiceMatcher.THRESHOLD) "较相似，需人工核对" else "较不相似")
            })
        }
        MaterialAlertDialogBuilder(this).setTitle("声纹相似度（不是概率）").setMessage(lines.joinToString("\n\n").ifEmpty { "请先录入两位已同意的说话人。" }).setPositiveButton("知道了", null).show()
    }

    private fun start(mode: String, name: String = "") {
        if (MicState.busy) { toast("请先结束当前录音或采样"); return }
        if (mode == "identify" && runCatching { VoiceLibrary.of(this).all().isEmpty() }.getOrDefault(true)) {
            toast("请先录入自己的声纹，或添加已同意的说话人"); return
        }
        startActivity(Intent(this, MicrophoneActivity::class.java).putExtra("startMode", mode).putExtra("name", name))
    }

    private fun row(parent: LinearLayout, icon: Int, title: String, value: String, onClick: () -> Unit) {
        val r = ItemSettingRowBinding.inflate(layoutInflater, parent, false)
        r.icon.setImageResource(icon); r.tvTitle.text = title; r.tvValue.text = value
        r.row.setOnClickListener { onClick() }
        parent.addView(r.root)
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Ui.toast(this, s)
}
