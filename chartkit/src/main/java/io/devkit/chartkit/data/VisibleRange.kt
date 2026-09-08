package io.devkit.chartkit.data

import io.devkit.chartkit.geometry.ChartMath

/**
 * An index range of a dataset, inclusive of both ends, or empty.
 *
 * Returned rather than a sublist so nothing is copied: locating the visible
 * window of a 500,000-point series must not allocate a 500,000-element list on
 * every pan frame, which is precisely what `filter` would do.
 */
data class IndexRange(val first: Int, val last: Int) {

    val isEmpty: Boolean get() = last < first

    val size: Int get() = if (isEmpty) 0 else last - first + 1

    fun asIntRange(): IntRange = if (isEmpty) IntRange.EMPTY else first..last

    companion object {
        val Empty: IndexRange = IndexRange(0, -1)
    }
}

/**
 * Finding the part of an ordered dataset a viewport is actually showing.
 *
 * ### Why this exists
 *
 * Zooming into a week of a five-year series leaves 99% of the points off
 * screen. Building geometry for all of them costs a path with half a million
 * segments that the plot then clips away, on every frame of a pan. The fix is
 * not to draw faster; it is to locate the visible window first — which on
 * ordered data is a binary search, and on a pan frame is fourteen comparisons
 * rather than five hundred thousand.
 *
 * Plain Kotlin, and correct at the edges, which is where a range lookup goes
 * wrong: a window entirely before the data, entirely after it, containing all
 * of it, containing none of it, or falling between two adjacent points.
 */
object VisibleRange {

    /**
     * The indices of [values] whose entries fall within `[from, to]`, extended
     * by [overscan] entries at each end.
     *
     * [values] must be non-decreasing; supplying unordered data gives a wrong
     * range rather than a crash, and callers check ordering once per data
     * change rather than per frame.
     *
     * The range is deliberately **inclusive of one point beyond each edge**
     * before any overscan is applied. A line whose first visible point is at
     * the left edge of the plot must still be drawn joined to the point off
     * screen behind it, or the line appears to begin partway into the chart.
     *
     * @param overscan extra entries kept at each end so a pan does not reveal
     *   an unrendered edge before the next layout.
     */
    fun of(
        values: DoubleArray,
        from: Double,
        to: Double,
        overscan: Int = 0,
    ): IndexRange {
        if (values.isEmpty()) return IndexRange.Empty
        if (!from.isFinite() || !to.isFinite()) return IndexRange(0, values.size - 1)
        val low = minOf(from, to)
        val high = maxOf(from, to)

        // Entirely outside the data: an empty range rather than a clamped one,
        // so the caller draws nothing instead of drawing the nearest point as
        // though it were in view.
        if (high < values.first() || low > values.last()) return IndexRange.Empty

        // One point beyond each edge, so the line enters *and leaves* the plot.
        // Symmetrically: extending only the leading edge makes a line stop at
        // the right-hand edge of the plot instead of running out of it, which
        // reads as the series ending there.
        val firstInside = lowerBound(values, low)
        val lastInside = upperBound(values, high)

        val first = (firstInside - 1 - overscan).coerceIn(0, values.size - 1)
        val last = (lastInside + 1 + overscan).coerceIn(0, values.size - 1)
        return if (last < first) IndexRange.Empty else IndexRange(first, last)
    }

    /**
     * The first index whose value is `>= target`, or `values.size` when none is.
     *
     * Plain binary search, written out rather than delegated to
     * `DoubleArray.binarySearch`, because that returns an insertion point
     * encoded as a negative number only when the target is absent — and the
     * present and absent cases need the same answer here.
     */
    fun lowerBound(values: DoubleArray, target: Double): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[mid] < target) low = mid + 1 else high = mid
        }
        return low
    }

    /** The last index whose value is `<= target`, or `-1` when none is. */
    fun upperBound(values: DoubleArray, target: Double): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[mid] <= target) low = mid + 1 else high = mid
        }
        return low - 1
    }

    /** True when [values] is non-decreasing. Computed once per data change. */
    fun isAscending(values: DoubleArray): Boolean {
        for (index in 1 until values.size) {
            if (values[index] < values[index - 1]) return false
        }
        return true
    }

    /**
     * The overscan, in entries, for a viewport showing [visibleCount] of them.
     *
     * A fraction rather than a constant: twenty extra points is generous at a
     * hundred visible and imperceptible at ten thousand.
     */
    fun overscanFor(visibleCount: Int, fraction: Float): Int {
        if (visibleCount <= 0 || fraction <= 0f || !fraction.isFinite()) return 0
        return ChartMath.clamp(visibleCount * fraction, 0f, MAX_OVERSCAN.toFloat()).toInt()
    }

    /** Beyond this, overscan costs more than the redraw it avoids. */
    private const val MAX_OVERSCAN: Int = 512
}
