package com.bob.recordinghelper.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DueDatesTest {
    private val today = LocalDate.of(2026, 10, 11)

    @Test fun parsesCommonForms() {
        assertEquals(LocalDate.of(2026, 10, 15), DueDates.parse("2026-10-15", today))
        assertEquals(LocalDate.of(2026, 10, 15), DueDates.parse("2026/10/15 前", today))
        assertEquals(LocalDate.of(2026, 10, 15), DueDates.parse("2026年10月15日之前", today))
        assertEquals(LocalDate.of(2026, 10, 15), DueDates.parse("10月15日", today))
        assertEquals(today, DueDates.parse("今天下午三点", today))
        assertEquals(today.plusDays(1), DueDates.parse("明天", today))
        assertNull(DueDates.parse("下周三", today))
        assertNull(DueDates.parse("", today))
        assertNull(DueDates.parse(null, today))
    }

    @Test fun statesAndLabels() {
        assertEquals(DueDates.State.OVERDUE, DueDates.state("2026-10-01", today))
        assertEquals(DueDates.State.TODAY, DueDates.state("今天", today))
        assertEquals(DueDates.State.UPCOMING, DueDates.state("2026-10-12", today))
        assertEquals(DueDates.State.NONE, DueDates.state("尽快", today))
        assertEquals("逾期 10 天", DueDates.label("2026-10-01", today))
        assertEquals("今天", DueDates.label("2026-10-11", today))
        assertEquals("明天", DueDates.label("2026-10-12", today))
        assertEquals("10月20日", DueDates.label("2026-10-20", today))
        assertEquals("尽快", DueDates.label("尽快", today))
    }

    @Test fun invalidCalendarDatesAreIgnored() {
        assertNull(DueDates.parse("2026-13-45", today))
        assertNull(DueDates.parse("2月30日", today))
    }
}
