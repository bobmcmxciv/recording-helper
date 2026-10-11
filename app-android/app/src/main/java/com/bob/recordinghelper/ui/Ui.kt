package com.bob.recordinghelper.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.bob.recordinghelper.R
import com.google.android.material.chip.Chip
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Small view helpers shared by every screen. */
object Ui {
    /** Edge-to-edge: pad [top] by the status bar and [bottom] by the navigation bar. */
    fun insets(root: View, top: View? = null, bottom: View? = null) {
        val tp = top?.paddingTop ?: 0
        val bp = bottom?.paddingBottom ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            top?.updatePadding(top = tp + bars.top)
            bottom?.updatePadding(bottom = bp + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    fun toast(ctx: Context, message: String) = Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()

    private fun local(iso: String?): LocalDateTime? = try {
        if (iso.isNullOrBlank()) null else Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDateTime()
    } catch (_: Exception) { null }

    /** 2026-10-11 */
    fun date(iso: String?): String = local(iso)?.toLocalDate()?.toString() ?: ""

    /** 今天 14:30 · 昨天 09:12 · 10月3日 · 2025-12-24 */
    fun friendly(iso: String?): String {
        val t = local(iso) ?: return ""
        val today = LocalDate.now()
        val hm = t.format(DateTimeFormatter.ofPattern("HH:mm"))
        return when (t.toLocalDate()) {
            today -> "今天 $hm"
            today.minusDays(1) -> "昨天 $hm"
            else -> if (t.year == today.year) t.format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) else t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        }
    }

    fun duration(seconds: Long): String = if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%02d:%02d".format(seconds / 60, seconds % 60)

    fun tint(chip: Chip, bg: Int, fg: Int) {
        chip.chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(chip.context, bg))
        chip.setTextColor(ContextCompat.getColor(chip.context, fg))
        chip.chipIconTint = ColorStateList.valueOf(ContextCompat.getColor(chip.context, fg))
    }

    /** Alternating soft background for speaker rows in a transcript. */
    fun speakerBackground(ctx: Context, index: Int): GradientDrawable = GradientDrawable().apply {
        val colors = intArrayOf(R.color.speaker_a_bg, R.color.speaker_b_bg, R.color.speaker_c_bg)
        setColor(ContextCompat.getColor(ctx, colors[Math.floorMod(index, colors.size)]))
        cornerRadius = ctx.resources.displayMetrics.density * 12
    }

    /** First one or two characters of a name for an avatar circle. */
    fun initials(name: String): String {
        val clean = name.trim().removePrefix("疑似")
        if (clean.isEmpty()) return "?"
        return if (clean.first().code > 0x2E7F) clean.take(1) else clean.take(2).uppercase()
    }
}
