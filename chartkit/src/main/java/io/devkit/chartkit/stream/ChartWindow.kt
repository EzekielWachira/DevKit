package io.devkit.chartkit.stream

import kotlin.time.Duration

/**
 * How much of a live stream a chart keeps.
 *
 * A realtime chart cannot keep everything: a sensor at 50 Hz produces four
 * million samples a day, and a chart holding all of them is a memory leak with
 * an axis. The window is therefore mandatory rather than optional, and stating
 * it is stating what the chart is *about* — the last minute, the last thousand
 * readings.
 */
sealed interface ChartWindow {

    /**
     * Keep the most recent [size] values.
     *
     * The right choice when the samples are regular, or when the chart is about
     * "the last *n* events" regardless of when they happened.
     */
    data class Count(val size: Int) : ChartWindow {
        init {
            require(size >= 1) { "A count window needs room for at least one value, was $size" }
        }
    }

    /**
     * Keep values newer than `now - duration`.
     *
     * The right choice when the axis is time and the chart is about a period:
     * "the last minute" stays the last minute whether that held ten samples or
     * ten thousand.
     *
     * Requires a timestamp for each value. It is read from the value itself
     * through the caller's own lambda and never inferred from arrival order —
     * an event that arrived late is still an event that happened when it
     * happened, and a chart that timestamped it on arrival would draw it in the
     * wrong place.
     */
    data class Duration(val duration: kotlin.time.Duration) : ChartWindow {
        init {
            require(duration.isPositive()) { "A duration window must be positive, was $duration" }
        }
    }

    /**
     * Keep everything.
     *
     * For a bounded stream — a replay, a finite job's progress — where the end
     * is known and the whole of it is the chart. Not for an open-ended feed.
     */
    data object Unbounded : ChartWindow

    companion object {
        /** Enough to fill a phone-width plot at roughly two points per pixel. */
        val Default: ChartWindow = Count(600)
    }
}

/** Duration window, in the units [ChartWindow.Duration] stores. */
fun chartWindowOf(duration: Duration): ChartWindow = ChartWindow.Duration(duration)
