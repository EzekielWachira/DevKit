package io.devkit.chartkit.scale

import io.devkit.chartkit.geometry.ChartMath
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A continuous scale over instants, expressed as epoch milliseconds.
 *
 * Epoch millis rather than a date type, deliberately. ChartKit's `minSdk` is 24
 * and `java.time` is API 26; a library that referenced `Instant` in its own
 * signatures would either raise the floor or oblige every consumer to enable
 * core-library desugaring for a chart. A `Long` needs neither, works on every
 * supported release, and carries no time zone the library would have to guess
 * at. Consumers convert at the call site, where the right time zone is known.
 *
 * Positioning is proportional to elapsed time, so an irregular series — three
 * readings a minute apart and then one an hour later — is drawn with the gap
 * visible rather than evenly spaced.
 */
class TimeScale(
    domain: NumericDomain,
    override val rangeStart: Float,
    override val rangeEnd: Float,
    clamp: Boolean = false,
) : InvertibleChartScale<Long> {

    /** The underlying continuous mapping, in milliseconds. */
    val linear: LinearScale = LinearScale(domain, rangeStart, rangeEnd, clamp)

    /** The mapped interval, as epoch millis. */
    val domain: NumericDomain get() = linear.domain

    override fun scale(value: Long): Float = linear.scale(value.toDouble())

    override fun invert(position: Float): Long = linear.invert(position).toLong()

    /**
     * Tick instants at a round calendar-ish interval covering the domain.
     *
     * "Calendar-ish" is honest about the limit: the intervals are fixed
     * durations — minute, hour, day, week — chosen from [CALENDAR_STEPS], not
     * real calendar arithmetic. A month step is approximated at 30 days and a
     * year at 365. That is right for positioning a tick on a proportional axis
     * and wrong for asserting "the first of the month", which 0.1 does not
     * claim to do. A caller who needs true calendar boundaries supplies ticks
     * of their own.
     */
    fun ticks(count: Int = TickGenerator.DEFAULT_TICK_COUNT): List<Long> {
        val requested = count.coerceAtLeast(2)
        val span = domain.span
        if (span < ChartMath.EPSILON) return listOf(domain.min.toLong())

        val rough = span / requested
        val step = CALENDAR_STEPS.firstOrNull { it >= rough } ?: run {
            // Beyond a year, fall back to round numbers of years.
            val years = ceil(rough / YEAR).coerceAtLeast(1.0)
            years * YEAR
        }

        val first = floor(domain.min / step) * step
        val result = ArrayList<Long>(requested + 2)
        var index = 0
        while (index <= requested * 4) {
            val value = first + step * index
            if (value > domain.max + ChartMath.EPSILON) break
            if (value >= domain.min - ChartMath.EPSILON) result.add(value.toLong())
            index++
        }
        return result.ifEmpty { listOf(domain.min.toLong(), domain.max.toLong()) }
    }

    override fun toString(): String =
        "TimeScale(domain=[${domain.min.toLong()}, ${domain.max.toLong()}], " +
            "range=[$rangeStart, $rangeEnd])"

    private companion object {
        const val SECOND = 1_000.0
        const val MINUTE = 60 * SECOND
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val WEEK = 7 * DAY
        const val MONTH = 30 * DAY
        const val YEAR = 365 * DAY

        /** Ascending, so the first entry at or above the rough step wins. */
        val CALENDAR_STEPS = doubleArrayOf(
            SECOND, 5 * SECOND, 15 * SECOND, 30 * SECOND,
            MINUTE, 5 * MINUTE, 15 * MINUTE, 30 * MINUTE,
            HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR,
            DAY, 2 * DAY, WEEK, 2 * WEEK,
            MONTH, 3 * MONTH, 6 * MONTH, YEAR,
        )
    }
}
