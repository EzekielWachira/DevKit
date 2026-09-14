package io.devkit.chartkit.geometry

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One cell of a hexagonal grid, in axial coordinates.
 *
 * Two integers rather than three. A hex grid is naturally described by *cube*
 * coordinates `(q, r, s)` with `q + r + s = 0`, which makes the rounding and the
 * distance arithmetic clean — but the third is always recoverable, so only two
 * are stored and [s] derives the missing one where it is needed.
 */
data class HexCell(val q: Int, val r: Int) {
    /** The third cube coordinate, which is never independent. */
    val s: Int get() = -q - r
}

/**
 * Pointy-top hexagonal grid arithmetic.
 *
 * ```text
 *    ╱‾╲ ╱‾╲       width   = √3 · radius
 *    ╲_╱ ╲_╱       height  = 2 · radius
 *    ╱‾╲ ╱‾╲       row gap = 1.5 · radius
 *    ╲_╱ ╲_╱
 * ```
 *
 * ### Why hexagons and not squares
 *
 * Every hexagon has six neighbours all at the same distance from its centre. A
 * square grid has four at one distance and four diagonals at another, which
 * means a square bin's contents are not uniformly close to its centre and
 * clusters lying on a diagonal read differently from clusters lying square.
 * Hexagons also tile without the strong horizontal and vertical banding that
 * makes a square heatmap look like a grid rather than like the data.
 *
 * ### Rounding is done in cube space
 *
 * Taking the nearest integer of each axial coordinate independently picks the
 * wrong cell near a boundary, because the axes are not orthogonal. Rounding all
 * three cube coordinates and then repairing whichever drifted furthest from its
 * original is the standard fix, and it is the only part of this arithmetic that
 * is not obvious.
 *
 * Plain Kotlin: no Compose, no `android.graphics`.
 */
object HexGrid {

    private val SQRT_3 = sqrt(3.0)

    /**
     * How far outside a hexagon still counts as inside, as a fraction of the
     * radius.
     *
     * Small enough to be invisible — a hundredth of a percent of a cell — and
     * large enough to absorb the rounding difference between binning a point
     * and testing it.
     */
    private const val EDGE_TOLERANCE = 1e-4

    /** The cell containing the point `(x, y)`, for a grid of the given radius. */
    fun cellAt(x: Double, y: Double, radius: Double): HexCell {
        if (radius <= 0.0) return HexCell(0, 0)
        val q = (SQRT_3 / 3.0 * x - y / 3.0) / radius
        val r = (2.0 / 3.0 * y) / radius
        return round(q, r)
    }

    /** The centre of [cell], for a grid of the given radius. */
    fun centerOf(cell: HexCell, radius: Double): Pair<Double, Double> {
        val x = radius * SQRT_3 * (cell.q + cell.r / 2.0)
        val y = radius * 1.5 * cell.r
        return x to y
    }

    /**
     * The six corners of a hexagon centred on `(cx, cy)`.
     *
     * Pointy-top: the first corner is directly above the centre, and they run
     * clockwise from there.
     */
    fun corners(cx: Float, cy: Float, radius: Float): List<ChartOffset> =
        (0 until 6).map { corner ->
            val angle = Math.PI / 3.0 * corner - Math.PI / 2.0
            ChartOffset(
                x = cx + (radius * kotlin.math.cos(angle)).toFloat(),
                y = cy + (radius * kotlin.math.sin(angle)).toFloat(),
            )
        }

    /**
     * True when `(x, y)` is inside the hexagon centred on `(cx, cy)`.
     *
     * Exact rather than an approximation by the inscribed or circumscribed
     * circle: a hexagon's corners reach about 15% further than its edges, so a
     * circular test either refuses taps on the corners or accepts taps in the
     * gaps between cells, and both are visible to anybody who tries them.
     *
     * ### Edges belong to both neighbours
     *
     * The comparisons are tolerant by [EDGE_TOLERANCE] of the radius, so a
     * point lying exactly on an edge is inside the cells on **both** sides of
     * it. Testing exactly instead would leave a dead hairline along every
     * boundary where a tap selects nothing and — worse — where a point that
     * [cellAt] binned into a cell is not inside that cell, because the two
     * comparisons round in opposite directions. A point landing precisely on a
     * boundary is ambiguous by nature; the tie is broken by whichever cell is
     * asked first, which is deterministic.
     */
    fun contains(cx: Float, cy: Float, radius: Float, x: Float, y: Float): Boolean {
        if (radius <= 0f) return false
        val dx = abs(x - cx) / radius
        val dy = abs(y - cy) / radius
        // Half-height 1, half-width √3/2, with the two slanted edges cutting the
        // corners off the bounding box.
        if (dy > 1.0 + EDGE_TOLERANCE) return false
        if (dx > SQRT_3 / 2.0 + EDGE_TOLERANCE) return false
        return dy <= 1.0 - dx / SQRT_3 + EDGE_TOLERANCE
    }

    /** The number of cells between two, in steps. */
    fun distance(a: HexCell, b: HexCell): Int =
        (abs(a.q - b.q) + abs(a.r - b.r) + abs(a.s - b.s)) / 2

    /**
     * Nearest cell to a fractional axial position.
     *
     * The three cube coordinates are rounded independently and then the one
     * that moved furthest is recomputed from the other two, which restores the
     * `q + r + s = 0` invariant by giving up the least reliable of the three.
     */
    private fun round(q: Double, r: Double): HexCell {
        val s = -q - r
        var roundedQ = q.roundToInt()
        var roundedR = r.roundToInt()
        val roundedS = s.roundToInt()

        val deltaQ = abs(roundedQ - q)
        val deltaR = abs(roundedR - r)
        val deltaS = abs(roundedS - s)

        if (deltaQ > deltaR && deltaQ > deltaS) {
            roundedQ = -roundedR - roundedS
        } else if (deltaR > deltaS) {
            roundedR = -roundedQ - roundedS
        }
        return HexCell(roundedQ, roundedR)
    }
}
