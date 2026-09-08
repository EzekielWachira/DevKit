package io.devkit.chartkit.geometry

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Where a date sits in a week-by-weekday activity grid.
 *
 * @param week the column, counting from the week containing the grid's first
 *   day.
 * @param weekday the row, `0` being the locale's own first day of the week.
 */
data class CalendarCell(val week: Int, val weekday: Int)

/** A month label and the week column it starts at. */
data class CalendarMonthLabel(val week: Int, val month: Int, val year: Int, val label: String)

/**
 * The week-and-weekday grid a calendar heatmap is laid out on.
 *
 * ### Locale, not convention
 *
 * The week does not start on Sunday, and it does not start on Monday either —
 * it starts wherever the reader's locale says it does, which
 * `java.util.Calendar` already knows for every locale Android ships. Hardcoding
 * either one puts a British reader's Sundays at the top of the grid or an
 * American reader's Mondays, and in both cases every weekday label is wrong by
 * a row.
 *
 * ### Days, not instants
 *
 * Everything here works in **epoch days** resolved in an explicit time zone.
 * A calendar heatmap is about days, and a day is a time-zone-dependent bucket
 * of instants: an event at 23:30 UTC belongs to a different day in Nairobi than
 * in New York. Converting once, here, is what stops the grid disagreeing with
 * the reader's own calendar.
 *
 * Plain Kotlin and `java.util` only — no Compose, no `java.time`, which is API
 * 26 and above ChartKit's floor.
 */
class CalendarGrid internal constructor(
    val firstEpochDay: Long,
    val lastEpochDay: Long,
    /** `0` = Monday … `6` = Sunday, resolved from the locale. */
    val firstWeekdayIndex: Int,
    private val timeZone: TimeZone,
) {
    /** The epoch day the first column begins at — the start of its week. */
    val gridStartEpochDay: Long = firstEpochDay - weekdayIndexOf(firstEpochDay)

    /** How many week columns the grid spans, at least one. */
    val weekCount: Int =
        (((lastEpochDay - gridStartEpochDay) / DAYS_PER_WEEK) + 1).toInt().coerceAtLeast(1)

    /** The cell for [epochDay], or `null` when it falls outside the grid. */
    fun cellOf(epochDay: Long): CalendarCell? {
        if (epochDay < gridStartEpochDay) return null
        val offset = epochDay - gridStartEpochDay
        val week = (offset / DAYS_PER_WEEK).toInt()
        if (week >= weekCount) return null
        return CalendarCell(week = week, weekday = (offset % DAYS_PER_WEEK).toInt())
    }

    /** The epoch day at [cell], whether or not it holds data. */
    fun epochDayAt(cell: CalendarCell): Long =
        gridStartEpochDay + cell.week.toLong() * DAYS_PER_WEEK + cell.weekday

    /** `0` = Monday … `6` = Sunday, relative to the locale's first day. */
    fun weekdayIndexOf(epochDay: Long): Int {
        // 1970-01-01 was a Thursday, which is index 3 in a Monday-first week.
        val mondayFirst = Math.floorMod(epochDay + 3L, DAYS_PER_WEEK.toLong()).toInt()
        return Math.floorMod(mondayFirst - firstWeekdayIndex, DAYS_PER_WEEK)
    }

    /**
     * The weekday row labels, in grid order, for [locale].
     *
     * Short forms — the row gutter of a contribution grid is a few characters
     * wide, and a full weekday name would either be clipped or force the grid
     * itself narrow enough to be unreadable.
     */
    fun weekdayLabels(locale: Locale = Locale.getDefault()): List<String> {
        val format = java.text.SimpleDateFormat("EEE", locale).apply { timeZone = this@CalendarGrid.timeZone }
        return List(DAYS_PER_WEEK) { row ->
            // Any epoch day whose weekday index is `row`; the grid start is
            // index 0 by construction, so adding the row reaches the rest.
            format.format(java.util.Date((gridStartEpochDay + row) * MILLIS_PER_DAY))
        }
    }

    /**
     * Where each month begins, for the labels above the grid.
     *
     * A month is labelled at the first week column whose first day falls in it,
     * which is where the reader's eye lands — labelling the week containing the
     * first of the month instead puts the label a column early whenever the
     * month starts mid-week.
     *
     * ### Month fragments are not labelled
     *
     * A grid almost always begins and ends part-way through a month, and a
     * fragment occupying one week column has nowhere to put a label: it lands
     * on top of the next month's. So a month is labelled only once it occupies
     * at least [MIN_WEEKS_FOR_LABEL] columns. The alternative — drawing both
     * and letting them collide — produces the overlapping "Dec"/"Jan" that
     * every contribution grid starts life with.
     */
    fun monthLabels(locale: Locale = Locale.getDefault()): List<CalendarMonthLabel> {
        val format = java.text.SimpleDateFormat("MMM", locale).apply { timeZone = this@CalendarGrid.timeZone }
        val calendar = Calendar.getInstance(timeZone, locale)
        val starts = ArrayList<CalendarMonthLabel>()
        var previousMonth = -1
        var previousYear = -1
        for (week in 0 until weekCount) {
            val epochDay = gridStartEpochDay + week.toLong() * DAYS_PER_WEEK
            calendar.timeInMillis = epochDay * MILLIS_PER_DAY
            val month = calendar.get(Calendar.MONTH)
            val year = calendar.get(Calendar.YEAR)
            if (month != previousMonth || year != previousYear) {
                starts += CalendarMonthLabel(
                    week = week,
                    month = month,
                    year = year,
                    label = format.format(calendar.time),
                )
                previousMonth = month
                previousYear = year
            }
        }
        return starts.filterIndexed { index, label ->
            val next = starts.getOrNull(index + 1)?.week ?: weekCount
            next - label.week >= MIN_WEEKS_FOR_LABEL
        }
    }

    internal companion object {
        const val DAYS_PER_WEEK: Int = 7
        const val MILLIS_PER_DAY: Long = 86_400_000L

        /**
         * How many week columns a month needs before it is worth labelling.
         *
         * Two: a single column is narrower than any month abbreviation, so its
         * label would sit under its neighbour's.
         */
        const val MIN_WEEKS_FOR_LABEL: Int = 2
    }
}

/** Calendar-grid construction and epoch-day arithmetic. */
object CalendarGeometry {

    /**
     * The epoch day an instant falls on, in [timeZone].
     *
     * `floorDiv`, not integer division: dates before 1970 have negative
     * milliseconds, and truncation towards zero would put 31 December 1969 and
     * 1 January 1970 on the same day.
     */
    fun epochDayOf(epochMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): Long {
        val offset = timeZone.getOffset(epochMillis).toLong()
        return Math.floorDiv(epochMillis + offset, CalendarGrid.MILLIS_PER_DAY)
    }

    /** Midday UTC-of-the-day for [epochDay], safe to format in [timeZone]. */
    fun epochMillisOf(epochDay: Long): Long = epochDay * CalendarGrid.MILLIS_PER_DAY

    /** `0` = Monday … `6` = Sunday, for the locale's first day of the week. */
    fun firstWeekdayIndex(locale: Locale = Locale.getDefault()): Int {
        // Calendar numbers Sunday as 1; a Monday-first index is what the rest
        // of the grid arithmetic uses.
        val firstDay = Calendar.getInstance(locale).firstDayOfWeek
        return Math.floorMod(firstDay - Calendar.MONDAY, CalendarGrid.DAYS_PER_WEEK)
    }

    /**
     * A grid spanning [firstEpochDay] to [lastEpochDay] inclusive.
     *
     * An inverted or empty range yields a one-day grid rather than a negative
     * week count, so a calendar with a single observation still draws.
     */
    fun grid(
        firstEpochDay: Long,
        lastEpochDay: Long,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): CalendarGrid {
        val from = minOf(firstEpochDay, lastEpochDay)
        val to = maxOf(firstEpochDay, lastEpochDay)
        return CalendarGrid(
            firstEpochDay = from,
            lastEpochDay = to,
            firstWeekdayIndex = firstWeekdayIndex(locale),
            timeZone = timeZone,
        )
    }
}
