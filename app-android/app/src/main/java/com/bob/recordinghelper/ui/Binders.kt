package com.bob.recordinghelper.ui

import android.graphics.Paint
import android.view.View
import com.bob.recordinghelper.R
import com.bob.recordinghelper.databinding.ItemRecordingBinding
import com.bob.recordinghelper.databinding.ItemTodoBinding
import com.bob.recordinghelper.local.DueDates
import com.google.android.material.chip.Chip
import org.json.JSONObject

/** Binds recording / todo JSON rows into the shared list item layouts. */
object Binders {
    /** "名字：文字" lines produced by on-device speaker separation. */
    val SPEAKER_LINE = Regex("^([^：:\\n]{1,20})[：:](.*)$")

    fun status(chip: Chip, status: String) {
        val (label, bg, fg) = when (status) {
            "pending" -> Triple("等待整理", R.color.status_pending_bg, R.color.status_pending_fg)
            "analyzing" -> Triple("整理中", R.color.status_pending_bg, R.color.status_pending_fg)
            "ready" -> Triple("已生成纪要", R.color.status_ready_bg, R.color.status_ready_fg)
            "error" -> Triple("需重试", R.color.status_error_bg, R.color.status_error_fg)
            "silent" -> Triple("未识别到语音", R.color.status_neutral_bg, R.color.status_neutral_fg)
            else -> Triple("已保存", R.color.status_neutral_bg, R.color.status_neutral_fg)
        }
        chip.text = label
        Ui.tint(chip, bg, fg)
    }

    fun speakerCount(text: String): Int =
        text.lineSequence().mapNotNull { SPEAKER_LINE.find(it.trim())?.groupValues?.get(1) }.distinct().count()

    fun recording(b: ItemRecordingBinding, rec: JSONObject, todoCount: Int, onClick: () -> Unit) {
        status(b.chipStatus, rec.optString("status"))
        b.tvDate.text = Ui.friendly(rec.optString("occurredAt"))
        b.tvTitle.text = rec.optString("title").ifBlank { "未命名录音" }
        val text = rec.optString("text")
        val snippet = rec.optString("summary").ifBlank { text }.replace('\n', ' ').trim().take(140)
        b.tvSnippet.text = snippet
        b.tvSnippet.visibility = if (snippet.isBlank()) View.GONE else View.VISIBLE
        val speakers = speakerCount(text)
        val parts = buildList {
            if (todoCount > 0) add("$todoCount 项待办")
            if (speakers >= 2) add("$speakers 位说话人")
            if (text.isNotBlank()) add("${text.length} 字")
        }
        b.tvMeta.text = parts.joinToString("  ·  ")
        b.tvMeta.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        b.card.setOnClickListener { onClick() }
    }

    fun todo(b: ItemTodoBinding, t: JSONObject, onToggle: (Boolean) -> Unit, onOpen: () -> Unit, onSource: (() -> Unit)?) {
        val done = t.optBoolean("done")
        b.check.setOnCheckedChangeListener(null)
        b.check.isChecked = done
        b.check.setOnCheckedChangeListener { _, checked -> onToggle(checked) }
        b.tvTitle.text = t.optString("title")
        b.tvTitle.paintFlags = if (done) b.tvTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else b.tvTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        b.tvTitle.alpha = if (done) 0.55f else 1f

        val due = t.optString("due").trim()
        if (due.isBlank()) b.chipDue.visibility = View.GONE else {
            b.chipDue.visibility = View.VISIBLE
            b.chipDue.text = DueDates.label(due)
            when (DueDates.state(due)) {
                DueDates.State.OVERDUE -> Ui.tint(b.chipDue, R.color.status_error_bg, R.color.status_error_fg)
                DueDates.State.TODAY -> Ui.tint(b.chipDue, R.color.status_pending_bg, R.color.status_pending_fg)
                else -> Ui.tint(b.chipDue, R.color.status_neutral_bg, R.color.status_neutral_fg)
            }
        }
        val unreviewed = !t.optBoolean("reviewed") && !done
        b.chipReview.visibility = if (unreviewed) View.VISIBLE else View.GONE
        Ui.tint(b.chipReview, R.color.status_ready_bg, R.color.status_ready_fg)
        val hasSource = t.optString("recordingId").isNotBlank() && onSource != null
        b.chipSource.visibility = if (hasSource) View.VISIBLE else View.GONE
        Ui.tint(b.chipSource, R.color.status_neutral_bg, R.color.status_neutral_fg)
        b.chipSource.setOnClickListener { onSource?.invoke() }
        b.chips.visibility = if (due.isBlank() && !unreviewed && !hasSource) View.GONE else View.VISIBLE

        val evidence = t.optString("evidence").trim()
        b.tvEvidence.visibility = if (evidence.isBlank()) View.GONE else View.VISIBLE
        b.tvEvidence.text = "依据：$evidence"
        b.card.setOnClickListener { onOpen() }
    }
}
