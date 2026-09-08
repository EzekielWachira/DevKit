package io.devkit.chartkit.data

import kotlin.math.abs
import kotlin.math.floor

/**
 * Reduces a dataset to a number of points a screen can actually distinguish.
 *
 * ### The contract
 *
 * A downsampler returns **indices into the original data**, never a new list of
 * points. Two consequences, both deliberate:
 *
 * - The caller's list is never copied, reordered or mutated. ChartKit does not
 *   own the developer's data and does not touch it.
 * - Everything downstream — a tooltip, a selection, an accessibility
 *   announcement — still refers to the original observation, because the index
 *   *is* the original observation's position. Downsampling changes what is
 *   drawn; it does not change what exists.
 *
 * Indices come back ascending, and the first and last are always present. An
 * algorithm that dropped the endpoints would shorten the visible series and
 * make the chart disagree with its own axis.
 */
fun interface ChartDownsampler {

    /**
     * The indices of the points to draw, ascending.
     *
     * @param x the domain positions, ascending. Required to be ordered:
     *   downsampling an unordered series is not meaningful, and callers check
     *   ordering once per data change.
     * @param y the values, parallel to [x]. A `NaN` marks a missing value; an
     *   implementation must not treat it as a number.
     * @param targetCount how many points are wanted. An implementation may
     *   return slightly more or fewer — the count is a budget, not a promise —
     *   but never more than the input holds.
     */
    fun sample(x: DoubleArray, y: DoubleArray, targetCount: Int): IntArray
}

/**
 * Which downsampler a chart uses, and how large a budget it gets.
 *
 * ```kotlin
 * performance = ChartPerformance(downsampling = ChartDownsampling.Lttb())
 * ```
 */
sealed interface ChartDownsampling {

    /**
     * Draw every point.
     *
     * The right choice when the dataset is small, and the honest choice when
     * every individual observation matters — a scatter of forty measurements
     * should not have four of them silently removed.
     */
    data object None : ChartDownsampling

    /**
     * Sample only when the data is denser than the plot can show, choosing the
     * budget from the plot's own width. The default.
     *
     * @param pointsPerPixel how many drawn points each pixel of plot width is
     *   allowed. Two, by default: enough for a line to show a vertical extent
     *   within one pixel column, which is what makes a noisy series still look
     *   noisy. More than that is invisible.
     * @param minimumTarget a floor, so a narrow chart still draws a
     *   recognisable shape.
     */
    data class Auto(
        val pointsPerPixel: Float = 2f,
        val minimumTarget: Int = 256,
    ) : ChartDownsampling {
        init {
            require(pointsPerPixel > 0f && pointsPerPixel.isFinite()) {
                "pointsPerPixel must be a finite value > 0, was $pointsPerPixel"
            }
            require(minimumTarget >= 2) { "minimumTarget must be at least 2, was $minimumTarget" }
        }
    }

    /**
     * Keep the extremes of each bucket.
     *
     * The right choice when the **envelope** is the information: a sensor trace
     * whose spikes are the point, a price series whose intraday range matters.
     * It cannot hide a spike, because a spike is by definition a bucket
     * extreme. It produces a visibly saw-toothed line at low budgets, which is
     * the price of that guarantee.
     */
    data class MinMax(val targetCount: Int = DEFAULT_TARGET) : ChartDownsampling {
        init {
            require(targetCount >= 2) { "targetCount must be at least 2, was $targetCount" }
        }
    }

    /**
     * Largest-Triangle-Three-Buckets.
     *
     * The right choice when the **shape** is the information. It preserves the
     * visual character of a line far better than picking every *n*-th point,
     * and unlike [MinMax] it does not exaggerate noise into a band. It can, in
     * principle, miss a single-sample spike that a min/max pass would keep —
     * which is exactly why both are offered rather than one being declared the
     * winner.
     */
    data class Lttb(val targetCount: Int = DEFAULT_TARGET) : ChartDownsampling {
        init {
            require(targetCount >= 3) { "LTTB needs a target of at least 3, was $targetCount" }
        }
    }

    companion object {
        /** Roughly two points per pixel across a phone in landscape. */
        const val DEFAULT_TARGET: Int = 1_000

        /** Sample only when the data outruns the display. */
        val Default: ChartDownsampling = Auto()
    }
}

/**
 * Keeps the first, the last, and the minimum and maximum of each bucket.
 *
 * ```text
 * bucket:  ▁▃█▂▁      →  keeps ▁ and █
 * ```
 *
 * Two points per bucket, emitted in the order they occur in the data so the
 * line does not zig backwards. The envelope of the series is preserved exactly:
 * no drawn line ever falls short of a peak the data reached.
 */
object MinMaxDownsampler : ChartDownsampler {

    override fun sample(x: DoubleArray, y: DoubleArray, targetCount: Int): IntArray {
        val n = minOf(x.size, y.size)
        if (n == 0) return IntArray(0)
        if (targetCount >= n || targetCount < 2) return IntArray(n) { it }

        // Two indices per bucket, plus the two endpoints.
        val buckets = ((targetCount - 2) / 2).coerceAtLeast(1)
        val result = ArrayList<Int>(targetCount + 2)
        result += 0

        val step = (n - 2).toDouble() / buckets
        for (bucket in 0 until buckets) {
            val from = (1 + floor(bucket * step)).toInt().coerceIn(1, n - 2)
            val to = (1 + floor((bucket + 1) * step)).toInt().coerceIn(from, n - 1)
            if (to <= from) continue

            var minIndex = -1
            var maxIndex = -1
            var minimum = Double.POSITIVE_INFINITY
            var maximum = Double.NEGATIVE_INFINITY
            for (index in from until to) {
                val value = y[index]
                if (value.isNaN()) continue
                if (value < minimum) {
                    minimum = value
                    minIndex = index
                }
                if (value > maximum) {
                    maximum = value
                    maxIndex = index
                }
            }
            when {
                minIndex < 0 && maxIndex < 0 -> {
                    // Every value in the bucket was missing. Keeping one of
                    // them preserves the gap in the line; dropping the bucket
                    // entirely would close it.
                    result += from
                }
                minIndex == maxIndex -> result += minIndex
                // In data order, so the drawn line advances monotonically.
                minIndex < maxIndex -> {
                    result += minIndex
                    result += maxIndex
                }
                else -> {
                    result += maxIndex
                    result += minIndex
                }
            }
        }

        if (result.last() != n - 1) result += n - 1
        return result.toIntArray()
    }
}

/**
 * Largest-Triangle-Three-Buckets (Steinarsson, 2013).
 *
 * The data is split into `targetCount - 2` buckets between the fixed first and
 * last points. From each bucket the point is chosen that forms the **largest
 * triangle** with the previously kept point and the average of the next bucket
 * — a direct measure of how much visual information that point carries.
 *
 * ```text
 *            ● candidate
 *           ╱ ╲
 * kept ●───     ───● next bucket's average
 *      the bigger this triangle, the more the candidate matters
 * ```
 *
 * Missing values are not smoothed over: a `NaN` bucket average would poison
 * every triangle it took part in, so buckets are averaged over their present
 * values only, and a bucket with none keeps its first index to preserve the
 * gap.
 */
object LttbDownsampler : ChartDownsampler {

    override fun sample(x: DoubleArray, y: DoubleArray, targetCount: Int): IntArray {
        val n = minOf(x.size, y.size)
        if (n == 0) return IntArray(0)
        if (targetCount >= n || targetCount < 3) return IntArray(n) { it }

        val result = IntArray(targetCount)
        result[0] = 0
        result[targetCount - 1] = n - 1

        // Buckets span the interior; the endpoints are fixed.
        val bucketSize = (n - 2).toDouble() / (targetCount - 2)
        var previous = 0

        for (bucket in 0 until targetCount - 2) {
            val from = (floor(bucket * bucketSize) + 1).toInt().coerceIn(1, n - 1)
            val to = (floor((bucket + 1) * bucketSize) + 1).toInt().coerceIn(from, n - 1)

            // The average of the *next* bucket forms the triangle's third
            // vertex. Looking ahead is what makes the choice about shape rather
            // than about local extremes.
            val nextFrom = to
            val nextTo = (floor((bucket + 2) * bucketSize) + 1).toInt().coerceIn(nextFrom, n)
            var avgX = 0.0
            var avgY = 0.0
            var counted = 0
            for (index in nextFrom until nextTo) {
                val value = y[index]
                if (value.isNaN()) continue
                avgX += x[index]
                avgY += value
                counted++
            }
            if (counted == 0) {
                // No usable point ahead: fall back to the last point, which is
                // the only vertex guaranteed to exist.
                avgX = x[n - 1]
                avgY = if (y[n - 1].isNaN()) 0.0 else y[n - 1]
            } else {
                avgX /= counted
                avgY /= counted
            }

            val anchorX = x[previous]
            val anchorY = if (y[previous].isNaN()) 0.0 else y[previous]

            var best = from
            var bestArea = -1.0
            for (index in from until to) {
                val value = y[index]
                if (value.isNaN()) {
                    // A gap is information. If nothing in the bucket is
                    // present, the first index is kept below and the line
                    // breaks where it should.
                    continue
                }
                // Twice the triangle's area; the factor of a half is constant
                // and would only cost a multiplication per candidate.
                val area = abs(
                    (anchorX - avgX) * (value - anchorY) - (anchorX - x[index]) * (avgY - anchorY),
                )
                if (area > bestArea) {
                    bestArea = area
                    best = index
                }
            }

            result[bucket + 1] = best
            previous = best
        }

        return result
    }
}

/**
 * The downsampler and budget [downsampling] resolves to for a given dataset and
 * plot width.
 *
 * Returns `null` when nothing should be sampled, which is both the common case
 * and the cheap one — a two-hundred-point chart does no work here at all.
 */
internal fun ChartDownsampling.resolve(
    pointCount: Int,
    plotWidthPx: Float,
): Pair<ChartDownsampler, Int>? = when (this) {
    ChartDownsampling.None -> null

    is ChartDownsampling.Auto -> {
        val budget = if (plotWidthPx.isFinite() && plotWidthPx > 0f) {
            (plotWidthPx * pointsPerPixel).toInt().coerceAtLeast(minimumTarget)
        } else {
            minimumTarget
        }
        // LTTB for the automatic case: it is the better default for the line
        // and area charts that dominate large-dataset use, and it does not
        // exaggerate noise the way a min/max envelope does.
        if (pointCount > budget) LttbDownsampler to budget else null
    }

    is ChartDownsampling.MinMax ->
        if (pointCount > targetCount) MinMaxDownsampler to targetCount else null

    is ChartDownsampling.Lttb ->
        if (pointCount > targetCount) LttbDownsampler to targetCount else null
}
