package io.devkit.chartkit.geometry

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** The shape a scatter marker is drawn as. */
enum class ScatterShape {

    /** The default, and the only shape whose area a reader judges reliably. */
    Circle,

    /**
     * A square of equal area to the circle of the same radius.
     *
     * Equal area, not equal side: a square whose side is the circle's diameter
     * looks noticeably heavier, and a chart mixing the two shapes would appear
     * to be encoding a difference in magnitude that is not there.
     */
    Square,

    /** A square rotated 45°, again matched by area. */
    Diamond,
}

/**
 * One plotted observation: where it is, how big it is, and where it came from.
 *
 * @param radius the marker's radius in pixels, already resolved through a size
 *   scale for a bubble chart and constant for a plain scatter.
 */
internal data class ScatterPoint(
    val position: ChartOffset,
    val sourceIndex: Int,
    val radius: Float,
)

/**
 * A uniform grid over the plot, for finding the nearest scatter point.
 *
 * ### Why not a linear scan
 *
 * Scatter data has no order to binary-search: a line chart's points ascend in
 * x, and a scatter's do not. Scanning is therefore `O(n)` per pointer move,
 * which at fifty thousand points is fifty thousand distance computations on
 * every frame of a drag.
 *
 * A uniform grid gets that down to the handful of points in the cells around
 * the pointer. It is not a k-d tree and does not need to be: the query is
 * always "nearest within a small radius", the points are bounded by the plot,
 * and a grid is built in one pass with no rebalancing. The pathological case —
 * every point in one cell — is a dataset where every observation is at the same
 * position, which no index helps with and which is not a chart.
 *
 * Built once per layout, alongside the geometry it indexes.
 */
internal class ScatterIndex(
    private val points: List<ScatterPoint>,
    private val bounds: ChartRect,
) {
    private val columns: Int
    private val rows: Int
    private val cellWidth: Float
    private val cellHeight: Float
    private val cells: Array<MutableList<Int>?>

    init {
        // Roughly one point per cell: fewer cells means longer scans, more
        // means a larger array to walk when the query radius spans several.
        val target = max(1, ceil(sqrt(points.size.toDouble())).toInt())
        columns = target.coerceIn(1, MAX_AXIS_CELLS)
        rows = target.coerceIn(1, MAX_AXIS_CELLS)
        cellWidth = if (bounds.width > 0f) bounds.width / columns else 1f
        cellHeight = if (bounds.height > 0f) bounds.height / rows else 1f
        cells = arrayOfNulls(columns * rows)

        points.forEachIndexed { index, point ->
            val cell = cellIndexOf(point.position) ?: return@forEachIndexed
            val bucket = cells[cell] ?: ArrayList<Int>(4).also { cells[cell] = it }
            bucket += index
        }
    }

    /**
     * The index of the point nearest [query], or `-1` when none is within
     * [maxDistance].
     *
     * The search widens ring by ring from the pointer's own cell and stops as
     * soon as the next ring cannot contain anything closer than the best found
     * — so a hit in the first cell costs one cell's worth of comparisons, and
     * a miss over empty space costs the rings covering [maxDistance].
     */
    fun nearest(query: ChartOffset, maxDistance: Float): Int {
        if (points.isEmpty() || !query.isFinite) return -1

        var best = -1
        var bestDistanceSquared = maxDistance * maxDistance

        val centreColumn = columnOf(query.x)
        val centreRow = rowOf(query.y)
        val maxRing = max(columns, rows)

        for (ring in 0..maxRing) {
            // Everything in this ring is at least this far away. Once that
            // exceeds the best found, no further ring can improve on it.
            val ringDistance = (ring - 1) * minOf(cellWidth, cellHeight)
            if (ring > 0 && ringDistance > 0f && ringDistance * ringDistance > bestDistanceSquared) break

            var examined = false
            for (column in (centreColumn - ring)..(centreColumn + ring)) {
                for (row in (centreRow - ring)..(centreRow + ring)) {
                    // Only the perimeter of the ring; the interior was covered
                    // by the previous iterations.
                    val onPerimeter = ring == 0 ||
                        column == centreColumn - ring || column == centreColumn + ring ||
                        row == centreRow - ring || row == centreRow + ring
                    if (!onPerimeter) continue
                    if (column < 0 || column >= columns || row < 0 || row >= rows) continue
                    examined = true
                    val bucket = cells[row * columns + column] ?: continue
                    for (index in bucket) {
                        val point = points[index]
                        val dx = point.position.x - query.x
                        val dy = point.position.y - query.y
                        val distanceSquared = dx * dx + dy * dy
                        if (distanceSquared < bestDistanceSquared) {
                            bestDistanceSquared = distanceSquared
                            best = index
                        }
                    }
                }
            }
            // Past the grid entirely and still nothing: no larger ring exists.
            if (!examined && ring > 0 && ring > maxRing) break
        }
        return best
    }

    private fun columnOf(x: Float): Int =
        floor((x - bounds.left) / cellWidth).toInt().coerceIn(-1, columns)

    private fun rowOf(y: Float): Int =
        floor((y - bounds.top) / cellHeight).toInt().coerceIn(-1, rows)

    private fun cellIndexOf(position: ChartOffset): Int? {
        if (!position.isFinite) return null
        val column = floor((position.x - bounds.left) / cellWidth).toInt()
        val row = floor((position.y - bounds.top) / cellHeight).toInt()
        if (column < 0 || column >= columns || row < 0 || row >= rows) return null
        return row * columns + column
    }

    private companion object {
        /** A 256×256 grid indexes 65,536 cells, which is past the useful point. */
        const val MAX_AXIS_CELLS = 256
    }
}
