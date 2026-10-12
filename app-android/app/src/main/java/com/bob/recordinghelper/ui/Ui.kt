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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
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


    /** Material window width classes: compact phones, medium (unfolded small foldables, portrait tablets), expanded (large foldables, tablets). */
    enum class Width { COMPACT, MEDIUM, EXPANDED, WIDE }

    fun width(ctx: Context): Width {
        val w = ctx.resources.configuration.screenWidthDp
        return if (w >= 1200) Width.WIDE else if (w >= 840) Width.EXPANDED else if (w >= 600) Width.MEDIUM else Width.COMPACT
    }

    /** Card columns for the library and the board: one on phones, two from 840dp, three from 1200dp. */
    fun gridColumns(ctx: Context): Int = when (width(ctx)) { Width.WIDE -> 3; Width.EXPANDED -> 2; else -> 1 }

    /** Widest content column for grid pages: wide windows get 1440dp, expanded 1080dp, the rest 720dp. */
    fun gridMaxDp(ctx: Context): Int = when (width(ctx)) { Width.WIDE -> 1440; Width.EXPANDED -> 1080; else -> 720 }

    /** Dashboard pages (home, me) stay a 720dp reading column until the wide tier, where they split into two columns. */
    fun dashboardMaxDp(ctx: Context): Int = if (width(ctx) == Width.WIDE) 1440 else 720

    /** Keeps a reading column on wide windows: width beyond [maxDp] becomes symmetric horizontal padding. */
    fun column(view: View, maxDp: Int = 640) {
        val baseLeft = view.paddingLeft
        val baseRight = view.paddingRight
        val max = (maxDp * view.resources.displayMetrics.density).toInt()
        (view as? android.view.ViewGroup)?.clipToPadding = false
        // Applied at pre-draw rather than inside the layout pass: a requestLayout issued during the first layout is dropped on some devices.
        fun fit(): Boolean {
            val width = view.width
            if (width <= 0) return false
            val extra = ((width - max) / 2).coerceAtLeast(0)
            if (view.paddingLeft == baseLeft + extra && view.paddingRight == baseRight + extra) return false
            view.updatePadding(left = baseLeft + extra, right = baseRight + extra)
            return true
        }
        view.viewTreeObserver.addOnPreDrawListener { !fit() }
    }

    /** Navigation rail layout: the rail takes the start inset, the content the end inset, both take top and bottom. */
    fun railInsets(root: View, rail: View, content: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            rail.updatePadding(left = bars.left, top = bars.top, bottom = bars.bottom)
            content.updatePadding(right = bars.right, top = bars.top, bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** Reports the hinge bounds (window coordinates) while a foldable rests half-opened like a laptop; null when flat or absent. */
    fun tabletop(activity: androidx.appcompat.app.AppCompatActivity, onChange: (android.graphics.Rect?) -> Unit) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                androidx.window.layout.WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
                    val fold = info.displayFeatures.filterIsInstance<androidx.window.layout.FoldingFeature>().firstOrNull {
                        it.state == androidx.window.layout.FoldingFeature.State.HALF_OPENED && it.orientation == androidx.window.layout.FoldingFeature.Orientation.HORIZONTAL
                    }
                    onChange(fold?.bounds)
                }
            }
        }
    }

    fun toast(ctx: Context, message: String) = Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()

    private fun local(iso: String?): LocalDateTime? = try {
        if (iso.isNullOrBlank()) null else Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDateTime()
    } catch (_: Exception) { null }

    /** 2026-10-11 */
    fun date(iso: String?): String = local(iso)?.toLocalDate()?.toString() ?: ""
    fun localDate(iso: String?): LocalDate? = local(iso)?.toLocalDate()

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
