package io.devkit.chartkit.geometry

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * How consecutive points are joined.
 *
 * Every option here is one ChartKit actually draws. There is no `Spline` or
 * `Bezier` entry that silently falls back to straight lines — an enum constant
 * that does not do what it says is worse than not offering it.
 */
enum class LineInterpolation {

    /** Straight segments. Reads the data exactly and adds nothing. */
    Linear,

    /**
     * A monotone cubic curve.
     *
     * Monotone, specifically, rather than a plain Catmull–Rom or natural cubic
     * spline. Those overshoot: three points at `10, 90, 10` produce a curve
     * that rises above 90 and dips below 10, inventing values the data never
     * contained — on a chart of anything bounded, like a percentage, the curve
     * leaves the possible range. The Fritsch–Carlson construction used here
     * cannot overshoot between two points, so the curve stays within the data
     * it interpolates. That is the only smoothing a data-visualisation library
     * has any business shipping.
     */
    Smooth,

    /** Holds each value until the next x, then steps. Right for sampled state. */
    Step,
}

/** A point already mapped to pixels, with the source index kept for hit testing. */
internal data class LinePoint(
    val position: ChartOffset,
    val sourceIndex: Int,
    val value: Double,
)

/**
 * A run of consecutive present points.
 *
 * Missing values split a series into several of these under
 * [io.devkit.chartkit.model.MissingValuePolicy.Break], which is what puts a gap
 * in the line rather than a straight edge across the absence.
 */
internal data class LineSegment(val points: List<LinePoint>)

/** Splits a series' points into runs separated by gaps. */
internal fun segmentLine(points: List<LinePoint?>): List<LineSegment> {
    val segments = ArrayList<LineSegment>()
    var current = ArrayList<LinePoint>()
    for (point in points) {
        if (point == null) {
            if (current.isNotEmpty()) {
                segments += LineSegment(current)
                current = ArrayList()
            }
        } else {
            current += point
        }
    }
    if (current.isNotEmpty()) segments += LineSegment(current)
    return segments
}

/**
 * The two Bézier control points joining `points[index]` to `points[index + 1]`.
 *
 * Fritsch–Carlson: tangents are the slopes of neighbouring secants, then
 * limited so no tangent exceeds three times the smaller adjacent secant slope.
 * That limit is what makes the curve monotone on each interval, and therefore
 * what stops it overshooting the data.
 *
 * Returns `null` when the pair cannot produce a finite curve, and the caller
 * falls back to a straight segment — a curve is a presentation choice, and
 * losing it is always better than emitting a `NaN` into a path.
 */
internal fun monotoneControlPoints(
    points: List<LinePoint>,
    index: Int,
): Pair<ChartOffset, ChartOffset>? {
    if (index < 0 || index >= points.size - 1) return null
    val p0 = points[index].position
    val p1 = points[index + 1].position
    val dx = p1.x - p0.x
    if (!dx.isFinite() || abs(dx) < 1e-6f) return null

    fun secant(a: ChartOffset, b: ChartOffset): Float {
        val run = b.x - a.x
        return if (abs(run) < 1e-6f) 0f else (b.y - a.y) / run
    }

    val slope = secant(p0, p1)
    val previousSlope = if (index > 0) secant(points[index - 1].position, p0) else slope
    val nextSlope =
        if (index + 2 < points.size) secant(p1, points[index + 2].position) else slope

    // A tangent of zero at a local extremum is what pins the curve to the data
    // point instead of letting it arc past.
    fun tangent(before: Float, after: Float): Float = when {
        sign(before) != sign(after) || before == 0f || after == 0f -> 0f
        else -> {
            val average = (before + after) / 2f
            val limit = 3f * min(abs(before), abs(after))
            if (abs(average) > limit) sign(average) * limit else average
        }
    }

    val m0 = if (index == 0) slope else tangent(previousSlope, slope)
    val m1 = if (index + 2 >= points.size) slope else tangent(slope, nextSlope)

    val third = dx / 3f
    val c0 = ChartOffset(p0.x + third, p0.y + m0 * third)
    val c1 = ChartOffset(p1.x - third, p1.y - m1 * third)
    return if (c0.isFinite && c1.isFinite) c0 to c1 else null
}

/**
 * The index in [points] whose x is nearest [x].
 *
 * Binary search when the points are known to be x-ordered, which they are for
 * every chart built from a numeric or time axis, and a linear scan otherwise.
 * The distinction matters at the sizes ChartKit claims to handle: scrubbing a
 * 10,000-point series calls this on every pointer move, and a scan there is
 * 10,000 comparisons per frame against fourteen.
 *
 * @param sorted whether [points] is non-decreasing in x. Wrong-but-true gives
 *   a wrong nearest point, never a crash.
 */
internal fun nearestPointIndex(
    points: List<LinePoint>,
    x: Float,
    sorted: Boolean,
): Int {
    if (points.isEmpty()) return -1
    if (points.size == 1) return 0

    if (!sorted) {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        points.forEachIndexed { index, point ->
            val distance = abs(point.position.x - x)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    var low = 0
    var high = points.size - 1
    while (low < high) {
        val mid = (low + high) / 2
        if (points[mid].position.x < x) low = mid + 1 else high = mid
    }
    val candidate = low
    val previous = (candidate - 1).coerceAtLeast(0)
    return if (
        abs(points[previous].position.x - x) <= abs(points[candidate].position.x - x)
    ) {
        previous
    } else {
        candidate
    }
}

/** True when [points] is non-decreasing in x. Computed once per data change. */
internal fun isXOrdered(points: List<LinePoint>): Boolean {
    for (index in 1 until points.size) {
        if (points[index].position.x < points[index - 1].position.x) return false
    }
    return true
}
