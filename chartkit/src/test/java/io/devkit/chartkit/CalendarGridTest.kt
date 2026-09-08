package io.devkit.chartkit

import io.devkit.chartkit.geometry.CalendarGeometry
import io.devkit.chartkit.geometry.CalendarGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * The week-and-weekday grid a calendar heatmap is laid out on.
 *
 * Locale is the point: the week does not start on Sunday, and it does not start
 * on Monday either — it starts where the reader's locale says it does.
 */
class CalendarGridTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** 1 January 2024 was a Monday. */
    private val jan1_2024 = CalendarGeometry.epochDayOf(1_704_067_200_000L, utc)

    @Test
    fun `epoch day conversion respects the time zone`() {
        // 2024-01-01T23:30Z is still 1 January in UTC and already the 2nd in
        // Auckland, which is exactly the case a naive conversion gets wrong.
        val lateUtc = 1_704_153_000_000L // 2024-01-01T23:50:00Z
        val utcDay = CalendarGeometry.epochDayOf(lateUtc, utc)
        val nzDay = CalendarGeometry.epochDayOf(lateUtc, TimeZone.getTimeZone("Pacific/Auckland"))
        assertEquals(1L, nzDay - utcDay)
    }

    @Test
    fun `dates before 1970 do not collapse onto the epoch`() {
        val newYearsEve1969 = -3_600_000L // 1969-12-31T23:00:00Z
        assertEquals(-1L, CalendarGeometry.epochDayOf(newYearsEve1969, utc))
    }

    @Test
    fun `a Monday-first locale puts Monday in row zero`() {
        val grid = CalendarGeometry.grid(jan1_2024, jan1_2024 + 30, Locale.UK, utc)
        assertEquals(0, grid.firstWeekdayIndex)
        assertEquals(0, grid.weekdayIndexOf(jan1_2024))
        assertEquals(6, grid.weekdayIndexOf(jan1_2024 + 6))
    }

    @Test
    fun `a Sunday-first locale shifts every weekday by one`() {
        val grid = CalendarGeometry.grid(jan1_2024, jan1_2024 + 30, Locale.US, utc)
        assertEquals(6, grid.firstWeekdayIndex)
        // Monday is the second row when the week starts on Sunday.
        assertEquals(1, grid.weekdayIndexOf(jan1_2024))
        assertEquals(0, grid.weekdayIndexOf(jan1_2024 - 1))
    }

    @Test
    fun `the grid starts at the beginning of the first day's week`() {
        val midWeek = jan1_2024 + 3 // Thursday
        val grid = CalendarGeometry.grid(midWeek, midWeek + 20, Locale.UK, utc)
        assertEquals(jan1_2024, grid.gridStartEpochDay)
        assertEquals(3, grid.cellOf(midWeek)!!.weekday)
        assertEquals(0, grid.cellOf(midWeek)!!.week)
    }

    @Test
    fun `days advance by column once a week is complete`() {
        val grid = CalendarGeometry.grid(jan1_2024, jan1_2024 + 20, Locale.UK, utc)
        assertEquals(0, grid.cellOf(jan1_2024 + 6)!!.week)
        assertEquals(1, grid.cellOf(jan1_2024 + 7)!!.week)
        assertEquals(0, grid.cellOf(jan1_2024 + 7)!!.weekday)
    }

    @Test
    fun `a date outside the grid has no cell`() {
        val grid = CalendarGeometry.grid(jan1_2024, jan1_2024 + 6, Locale.UK, utc)
        assertNull(grid.cellOf(jan1_2024 - 1))
        assertNull(grid.cellOf(jan1_2024 + 100))
    }

    @Test
    fun `a cell and its date round-trip`() {
        val grid = CalendarGeometry.grid(jan1_2024, jan1_2024 + 60, Locale.UK, utc)
        val day = jan1_2024 + 37
        assertEquals(day, grid.epochDayAt(grid.cellOf(day)!!))
    }

    @Test
    fun `the week count covers the whole range`() {
        val grid = CalendarGeometry.grid(jan1_2024, jan1_2024 + 364, Locale.UK, utc)
        assertEquals(53, grid.weekCount)
        assertTrue(grid.cellOf(jan1_2024 + 364) != null)
    }

    @Test
    fun `a single-day range still produces one column`() {
        val grid = CalendarGeometry.grid(jan1_2024, jan1_2024, Locale.UK, utc)
        assertEquals(1, grid.weekCount)
    }

    @Test
    fun `an inverted range is read in either order`() {
        val a = CalendarGeometry.grid(jan1_2024, jan1_2024 + 30, Locale.UK, utc)
        val b = CalendarGeometry.grid(jan1_2024 + 30, jan1_2024, Locale.UK, utc)
        assertEquals(a.weekCount, b.weekCount)
        assertEquals(a.gridStartEpochDay, b.gridStartEpochDay)
    }

    @Test
    fun `month labels land on the week each month begins in`() {
        val grid = CalendarGeometry.grid(jan1_2024, jan1_2024 + 200, Locale.UK, utc)
        val labels = grid.monthLabels(Locale.UK)
        // Roughly one per month across seven months, and strictly ascending.
        assertTrue(labels.size in 6..8)
        assertTrue(labels.map { it.week }.zipWithNext().all { (a, b) -> b > a })
        assertEquals(0, labels.first().week)
        assertEquals(java.util.Calendar.JANUARY, labels.first().month)
    }

    @Test
    fun `a one-column month fragment is not labelled`() {
        // A grid starting on 31 December leaves December one week column wide,
        // which is narrower than the word "Dec" — so it is left unlabelled
        // rather than drawn on top of January's.
        val newYearsEve = jan1_2024 - 1
        val grid = CalendarGeometry.grid(newYearsEve, newYearsEve + 200, Locale.US, utc)
        val labels = grid.monthLabels(Locale.UK)
        assertTrue(labels.none { it.month == java.util.Calendar.DECEMBER })
        assertEquals(java.util.Calendar.JANUARY, labels.first().month)
    }

    @Test
    fun `labels are at least two columns apart`() {
        val grid = CalendarGeometry.grid(jan1_2024 - 3, jan1_2024 + 400, Locale.US, utc)
        val weeks = grid.monthLabels(Locale.UK).map { it.week }
        assertTrue(weeks.zipWithNext().all { (a, b) -> b - a >= 2 })
    }

    @Test
    fun `weekday labels come back in grid order`() {
        val uk = CalendarGeometry.grid(jan1_2024, jan1_2024 + 10, Locale.UK, utc)
        val us = CalendarGeometry.grid(jan1_2024, jan1_2024 + 10, Locale.US, utc)
        assertEquals(CalendarGrid.DAYS_PER_WEEK, uk.weekdayLabels(Locale.UK).size)
        // The two locales list the same seven names, starting one apart.
        assertEquals(
            uk.weekdayLabels(Locale.UK).toSet(),
            us.weekdayLabels(Locale.UK).toSet(),
        )
        assertTrue(uk.weekdayLabels(Locale.UK) != us.weekdayLabels(Locale.UK))
    }
}
