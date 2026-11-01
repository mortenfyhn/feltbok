package io.github.mortenfyhn.feltbok

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Test

class DatesTest {
    private val names = DateNames(
        months = listOf("jan", "feb", "mar", "apr", "mai", "jun", "jul", "aug", "sep", "okt", "nov", "des"),
        weekdays = listOf("Man", "Tir", "Ons", "Tor", "Fre", "Lør", "Søn"),
        today = "I dag",
        yesterday = "I går",
    )
    private val utc = TimeZone.UTC

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime(y, mo, d, h, mi).toInstant(utc).toEpochMilliseconds()

    @Test
    fun exportPatternsAreZeroPadded() {
        val ms = at(2026, 6, 8, 9, 5)
        assertEquals("08.06.2026", formatDateTime(ms, "dd.MM.yyyy", utc))
        assertEquals("2026-06-08", formatDateTime(ms, "yyyy-MM-dd", utc))
        assertEquals("09:05", shortTime(ms, utc))
    }

    @Test
    fun displayFormsUseTheGivenNames() {
        val start = at(2026, 6, 8, 9, 5)
        val end = at(2026, 6, 9, 0, 30)
        assertEquals("8. jun 09:05", displayTimeRange(start, null, false, names, utc))
        assertEquals("8. jun 09:05 – 9. jun 00:30", displayTimeRange(start, end, false, names, utc))
        assertEquals("8. jun – 9. jun", displayTimeRange(start, end, true, names, utc))
        assertEquals("8. jun 2026", displayDate(start, names, utc))
    }
}
