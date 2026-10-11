package com.bob.recordinghelper.local

/** Pure range policy shared by setup and tests. "Recent" / "all" never inherit a new-only cutoff. */
data class ImportScope(val since: Long, val days: Int) {
    companion object {
        val ranges = listOf("new", "recent", "all")
        val choices = ranges + "off"

        /** A fresh install has no selection. Existing installations retain their actual range. */
        fun restore(stored: String?, legacyConfigured: Boolean, since: Long, days: Int): String? {
            if (stored != null) return stored.takeIf { it in choices }
            if (!legacyConfigured) return null
            return if (since > 0) "new" else if (days > 0) "recent" else "all"
        }

        fun select(mode: String, days: Int, now: Long, previousSince: Long = 0, preserveNewStart: Boolean = false): ImportScope {
            require(mode in ranges)
            require(mode != "recent" || days in 1..36500) { "最近天数应为 1–36500" }
            return when (mode) {
                "new" -> ImportScope(if (preserveNewStart && previousSince > 0) previousSince else now, 0)
                "recent" -> ImportScope(0, days)
                else -> ImportScope(0, 0)
            }
        }
    }
}
