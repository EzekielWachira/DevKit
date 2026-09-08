package io.devkit.chartkit.stream

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How often a stream is allowed to change what the chart draws.
 *
 * ### The problem this exists for
 *
 * A source emitting a thousand events a second must not cause a thousand chart
 * recompositions a second. A display refreshes at sixty hertz, so nine hundred
 * and forty of those updates are invisible work — and the work is not free: it
 * is a geometry rebuild, a path allocation and a recomposition each. Left
 * unthrottled, a fast feed makes a chart that cannot be scrolled, tapped or
 * zoomed, because the main thread never has a frame to spare.
 *
 * The policy is chosen rather than defaulted-away because the right answer
 * depends on what the stream is: dropping intermediate samples is correct for a
 * temperature and wrong for a count of events.
 */
sealed interface ChartUpdatePolicy {

    /**
     * Redraw on every emission.
     *
     * Correct for a slow stream — one value a second, a poll, a WebSocket
     * pushing a summary — and wrong for anything fast.
     */
    data object Immediate : ChartUpdatePolicy

    /**
     * Redraw at most once per [interval], showing the newest value.
     *
     * The default, at roughly a frame. Intermediate values are **discarded**:
     * for a measurement whose latest reading supersedes the previous one — a
     * price, a temperature, a queue depth — that is exactly right.
     */
    data class Throttle(val interval: Duration = DEFAULT_INTERVAL) : ChartUpdatePolicy {
        init {
            require(interval.isPositive()) { "A throttle interval must be positive, was $interval" }
        }
    }

    /**
     * Collect emissions for [interval], then append **all** of them at once.
     *
     * The difference from [Throttle] that matters: nothing is discarded. For a
     * stream of discrete events — trades, errors, requests — every one belongs
     * on the chart, and throwing away the ones that arrived between frames
     * would undercount.
     */
    data class Batch(val interval: Duration = DEFAULT_INTERVAL) : ChartUpdatePolicy {
        init {
            require(interval.isPositive()) { "A batch interval must be positive, was $interval" }
        }
    }

    /**
     * Collect emissions for [interval] and append one value that summarises
     * them, chosen by [aggregation].
     *
     * The middle ground for a genuinely fast sensor: an averaged sample says
     * what the period was like, and a min/max pair says how far it moved,
     * without either putting a thousand points on screen.
     */
    data class Aggregate(
        val interval: Duration = DEFAULT_INTERVAL,
        val aggregation: ChartAggregation = ChartAggregation.Average,
    ) : ChartUpdatePolicy {
        init {
            require(interval.isPositive()) { "An aggregation interval must be positive, was $interval" }
        }
    }

    companion object {

        /**
         * About one frame at 60 Hz.
         *
         * Long enough that no two updates land in one frame, short enough that
         * a chart still looks live.
         */
        val DEFAULT_INTERVAL: Duration = 16.milliseconds

        /** Throttle to roughly a frame. */
        val Default: ChartUpdatePolicy = Throttle()
    }
}

/**
 * How a batch of samples becomes the value or values the chart keeps.
 *
 * Applied only by [ChartUpdatePolicy.Aggregate].
 */
sealed interface ChartAggregation {

    /** Keep the last sample of the interval and discard the rest. */
    data object Latest : ChartAggregation

    /**
     * Keep one sample carrying the mean of the interval's values.
     *
     * Smooths a noisy sensor into something readable, and hides a spike that
     * lasted one sample — which is why [MinMax] exists.
     */
    data object Average : ChartAggregation

    /**
     * Keep two samples: the interval's minimum and its maximum, in the order
     * they occurred.
     *
     * Cannot hide a spike, because a spike is by definition an interval
     * extreme. The line looks noisier than an average, which is the point.
     */
    data object MinMax : ChartAggregation

    /**
     * Reduce the interval's samples with the caller's own function.
     *
     * For a stream whose right summary is domain-specific: a sum for counts, a
     * last-known-good for a flaky reading, a weighted mean.
     */
    data class Custom<T>(val reduce: (List<T>) -> List<T>) : ChartAggregation
}
