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

    fun status(chip: Chip, status: String, id: String? = null) {
        val effective = when {
            id != null && com.bob.recordinghelper.local.AnalysisState.isActive(id) -> "analyzing"
            id != null && com.bob.recordinghelper.local.AnalysisState.isQueued(id) -> "queued_analysis"
            else -> status
        }
        val (label, bg, fg) = when (effective) {
            "queued_analysis" -> Triple("排队整理", R.color.status_pending_bg, R.color.status_pending_fg)
            "pending" -> Triple("等待整理", R.color.status_pending_bg, R.color.status_pending_fg)
            "analyzing" -> Triple("模型整理中", R.color.status_pending_bg, R.color.status_pending_fg)
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
        val id = rec.optString("id")
        status(b.chipStatus, rec.optString("status"), id)
        b.tvDate.text = Ui.friendly(rec.optString("occurredAt"))
        b.tvTitle.text = rec.optString("title").ifBlank { "未命名录音" }
        val text = rec.optString("text")
        val active = id.isNotBlank() && com.bob.recordinghelper.local.AnalysisState.isActive(id)
        val queued = id.isNotBlank() && com.bob.recordinghelper.local.AnalysisState.isQueued(id)
        if (active) {
            val snap = com.bob.recordinghelper.local.AnalysisState.snapshot()
            val elapsed = if (snap.startedAt > 0L) ((System.currentTimeMillis() - snap.startedAt) / 1000L).coerceAtLeast(1L) else 0L
            val stream = snap.streamText.replace('\n', ' ').trim().takeLast(120)
            val liveSnippet = if (stream.isNotBlank()) "${snap.phase}\n实时输出：$stream" else snap.phase.ifBlank { "模型正在整理此记录…" }
            b.tvSnippet.text = liveSnippet
            b.tvSnippet.visibility = View.VISIBLE
            val metaParts = buildList {
                if (snap.modelLabel.isNotBlank()) add(snap.modelLabel)
                if (elapsed > 0L) add("已用时 ${elapsed} 秒")
                add("点开查看实时过程")
            }
            b.tvMeta.text = metaParts.joinToString("  ·  ")
            b.tvMeta.visibility = View.VISIBLE
        } else {
            val snippet = rec.optString("summary").ifBlank { text }.replace('\n', ' ').trim().take(140)
            b.tvSnippet.text = snippet
            b.tvSnippet.visibility = if (snippet.isBlank()) View.GONE else View.VISIBLE
            val speakers = speakerCount(text)
            val parts = buildList {
                if (queued) {
                    val pos = (com.bob.recordinghelper.local.AnalysisState.snapshot().queuedIds.indexOf(id) + 1).coerceAtLeast(1)
                    add("排队第 $pos 位（等待前序模型任务）")
                }
                if (todoCount > 0) add("$todoCount 项待办")
                if (speakers >= 2) add("$speakers 位说话人")
                if (text.isNotBlank()) add("${text.length} 字")
            }
            b.tvMeta.text = parts.joinToString("  ·  ")
            b.tvMeta.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        }
        b.card.setOnClickListener { onClick() }
    }

    fun todo(b: ItemTodoBinding, t: JSONObject, onToggle: (Boolean) -> Unit, onOpen: () -> Unit, onSource: (() -> Unit)?, onCategory: ((String) -> Unit)? = null) {
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
        val cat = com.bob.recordinghelper.local.LocalAnalysis.category(t.optString("category"))
        b.chipCategory.text = when (cat) { "work" -> "公事"; "personal" -> "私事"; else -> "未分类" }
        Ui.tint(b.chipCategory, R.color.status_neutral_bg, R.color.status_neutral_fg)
        b.chipCategory.visibility = if (onCategory != null || cat.isNotEmpty()) View.VISIBLE else View.GONE
        b.chipCategory.setOnClickListener { onCategory?.invoke(when (cat) { "work" -> "personal"; "personal" -> ""; else -> "work" }) }
        val hasSource = t.optString("recordingId").isNotBlank() && onSource != null
        b.chipSource.visibility = if (hasSource) View.VISIBLE else View.GONE
        Ui.tint(b.chipSource, R.color.status_neutral_bg, R.color.status_neutral_fg)
        b.chipSource.setOnClickListener { onSource?.invoke() }
        b.chips.visibility = View.VISIBLE

        val evidence = t.optString("evidence").trim()
        b.tvEvidence.visibility = if (evidence.isBlank()) View.GONE else View.VISIBLE
        b.tvEvidence.text = "依据：$evidence"
        b.card.setOnClickListener { onOpen() }
    }
}
