package com.bob.recordinghelper.local

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Due strings come from the model as free text; only clearly dated ones are treated as dates. */
object DueDates {
    enum class State { NONE, OVERDUE, TODAY, UPCOMING }

    private val ymd = Regex("""(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})""")
    private val md = Regex("""(\d{1,2})月(\d{1,2})[日号]?""")

    fun parse(due: String?, today: LocalDate = LocalDate.now()): LocalDate? {
        val s = due?.trim().orEmpty()
        if (s.isEmpty()) return null
        when {
            s.startsWith("今天") || s.startsWith("今日") -> return today
            s.startsWith("明天") || s.startsWith("明日") -> return today.plusDays(1)
            s.startsWith("后天") -> return today.plusDays(2)
        }
        ymd.find(s)?.let { m ->
            return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
        }
        md.find(s)?.let { m ->
            return runCatching { LocalDate.of(today.year, m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()
        }
        return null
    }

    fun state(due: String?, today: LocalDate = LocalDate.now()): State {
        val d = parse(due, today) ?: return State.NONE
        return when {
            d.isBefore(today) -> State.OVERDUE
            d == today -> State.TODAY
            else -> State.UPCOMING
        }
    }

    /** Short chip label; falls back to the raw text when no date could be read. */
    fun label(due: String?, today: LocalDate = LocalDate.now()): String {
        val raw = due?.trim().orEmpty()
        val d = parse(raw, today) ?: return raw.take(16)
        val days = ChronoUnit.DAYS.between(today, d)
        return when {
            days < 0 -> "逾期 ${-days} 天"
            days == 0L -> "今天"
            days == 1L -> "明天"
            days == 2L -> "后天"
            days <= 7 -> "$days 天后"
            d.year == today.year -> "${d.monthValue}月${d.dayOfMonth}日"
            else -> d.toString()
        }
    }

    /** Sort key: dated items first by date, undated afterwards. */
    fun sortKey(due: String?, today: LocalDate = LocalDate.now()): Long = parse(due, today)?.toEpochDay() ?: Long.MAX_VALUE
}
