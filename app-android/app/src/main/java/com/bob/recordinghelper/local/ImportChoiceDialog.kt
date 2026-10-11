package com.bob.recordinghelper.local

import android.app.Activity
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.bob.recordinghelper.databinding.DialogImportChoiceBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** No implicit range or number of days. Dismissing the dialog never saves anything. */
object ImportChoiceDialog {
    fun show(activity: Activity, initialChoice: String?, rememberedDays: Int,
             save: (String, Int) -> Boolean): AlertDialog {
        val b = DialogImportChoiceBinding.inflate(activity.layoutInflater)
        val ids = listOf(b.optNew.id, b.optRecent.id, b.optAll.id, b.optOff.id) // same order as ImportScope.choices
        b.days.setText(rememberedDays.takeIf { it in 1..36500 }?.toString().orEmpty())
        var choice = initialChoice?.takeIf { it in ImportScope.choices }
        choice?.let { b.options.check(ids[ImportScope.choices.indexOf(it)]) }
        val dialog = MaterialAlertDialogBuilder(activity).setTitle("默认如何处理录音")
            .setView(b.root)
            .setPositiveButton("保存并开启", null).setNegativeButton("取消", null).create()
        fun refresh() {
            val recent = choice == "recent"
            b.daysLayout.visibility = if (recent) View.VISIBLE else View.GONE
            b.days.visibility = if (recent) View.VISIBLE else View.GONE
            b.note.text = when (choice) {
                "new" -> "不处理开启前的历史录音。暂停后继续会沿用原开启时间，不漏掉暂停期间的新增录音。"
                "recent", "all" -> "历史录音按新到旧排队，每批最多 3 条，可随时暂停。缺少语音模型时等待安装，不上传音频。切换范围只重建未处理队列，已完成记录保留。"
                "off" -> "不自动扫描和转写；当前一条完成后停止。原录音、模型和已完成记录保留，可随时重新选择。"
                else -> "请先选择一种处理方式；未选择或取消都不会开启自动转写。"
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                text = if (choice == "off") "保存选择" else "保存并开启"
                isEnabled = choice != null && (choice != "recent" || b.days.text.toString().toIntOrNull() in 1..36500)
            }
        }
        b.options.setOnCheckedChangeListener { _, id -> choice = ids.indexOf(id).takeIf { it >= 0 }?.let { ImportScope.choices[it] }; refresh() }
        b.days.doAfterTextChanged { refresh() }
        dialog.show()
        // Initialize synchronously, before the first frame; OnShowListener is dispatched later.
        refresh()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val selected = choice ?: return@setOnClickListener
            try {
                val n = if (selected == "recent") b.days.text.toString().toInt() else 0
                if (selected != "off") ImportScope.select(selected, n, System.currentTimeMillis())
                if (save(selected, n)) dialog.dismiss()
            } catch (e: Exception) { Toast.makeText(activity, e.message ?: "保存失败，请重试", Toast.LENGTH_LONG).show() }
        }
        return dialog
    }
}
