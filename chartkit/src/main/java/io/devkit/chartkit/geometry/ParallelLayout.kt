package io.devkit.chartkit.geometry

import io.devkit.chartkit.scale.NumericDomain

/**
 * One dimension, before it has been measured.
 *
 * @param label the axis' name.
 * @param values one per row, `null` where the row has no value for it.
 * @param domain the interval to scale against, or `null` to take the values'
 *   own minimum and maximum.
 */
class ParallelAxisSpec(
    val label: String,
    val values: List<Double?>,
    val domain: NumericDomain? = null,
)

/**
 * One placed axis.
 *
 * @param x the pixel column it stands on.
 * @param domain the interval it maps, which is **its own** — see
 *   [ParallelLayout].
 */
class ParallelAxis internal constructor(
    val index: Int,
    val label: String,
    val x: Float,
    val top: Float,
    val bottom: Float,
    val domain: NumericDomain,
) {
    /** The pixel row [value] sits at, or `null` when it is not finite. */
    fun positionOf(value: Double): Float? {
        if (!value.isFinite()) return null
        val span = domain.max - domain.min
        // A dimension whose values are all identical has no interval to scale
        // against. Drawn down the middle rather than at an edge, which is the
        // truthful picture: every row agrees, so no row is higher than another.
        val fraction = if (span <= 0.0) 0.5 else (value - domain.min) / span
        return bottom - (fraction * (bottom - top)).toFloat()
    }

    /** The value at pixel row [y], for reading a brush back out. */
    fun valueAt(y: Float): Double {
        val height = bottom - top
        if (height <= 0f) return domain.min
        val fraction = ((bottom - y) / height).coerceIn(0f, 1f)
        return domain.min + fraction * (domain.max - domain.min)
    }
}

/**
 * One row's path across the axes.
 *
 * @param points one per axis, `null` where the row has no value for that
 *   dimension — which breaks the line rather than closing over the gap.
 */
class ParallelPolyline internal constructor(
    val rowIndex: Int,
    val groupIndex: Int,
    val points: List<ChartOffset?>,
) {
    /** Runs of consecutive present points, which is what actually gets stroked. */
    val segments: List<List<ChartOffset>> by lazy {
        val runs = ArrayList<List<ChartOffset>>()
        var current = ArrayList<ChartOffset>()
        points.forEach { point ->
            if (point == null) {
                if (current.size > 1) runs += current
                current = ArrayList()
            } else {
                current += point
            }
        }
        if (current.size > 1) runs += current
        runs
    }
}

/** Where every axis and every row sits, for one frame. */
class ParallelGeometry internal constructor(
    val axes: List<ParallelAxis>,
    val polylines: List<ParallelPolyline>,
)

/**
 * Many rows compared across many measures.
 *
 * ```text
 *  price   mpg    hp    weight
 *    │      │      │      │
 *    ├──────┼──╲   │   ╱──┤      one polyline = one row
 *    │   ╲  │   ╲──┼──╱   │
 *    ├────╲─┼──────┼──────┤
 *    │      │      │      │
 * ```
 *
 * ### Every axis has its own domain
 *
 * That is the whole reason the chart works. The dimensions are measured in
 * different units — a price, a fuel economy, a weight — and forcing them onto
 * one scale would flatten every axis but the largest into a line at the bottom.
 * Each axis is scaled to its own interval, and the consequence is stated rather
 * than hidden: **vertical position is only comparable within an axis**. A line
 * that is high on two axes is high on each of them separately; it is not
 * "higher overall", because there is no overall.
 *
 * ### What it is for, and what a radar is for
 *
 * [io.devkit.chartkit.charts.RadarChart] is the other multivariate view, and it
 * degrades past six or eight metrics — the spokes crowd and the polygon becomes
 * a shape rather than a reading. Parallel coordinates take ten or twenty
 * dimensions and hundreds of rows, and trade the radar's single readable
 * silhouette for the ability to see *groups* of rows behaving alike.
 *
 * Plain Kotlin: no Compose, no `android.graphics`.
 */
object ParallelLayout {

    /**
     * Places every axis and every row inside [bounds].
     *
     * @param groupOf the palette group each row belongs to, or `0` throughout.
     * @param inset pixels left above and below the axes, for their labels.
     */
    fun layout(
        specs: List<ParallelAxisSpec>,
        bounds: ChartRect,
        groupOf: (Int) -> Int = { 0 },
        inset: Float = 0f,
    ): ParallelGeometry {
        val axes = axes(specs, bounds, inset)
        if (axes.isEmpty()) return ParallelGeometry(emptyList(), emptyList())

        val rowCount = specs.maxOf { it.values.size }
        val polylines = (0 until rowCount).map { row ->
            ParallelPolyline(
                rowIndex = row,
                groupIndex = groupOf(row),
                points = axes.map { axis ->
                    val value = specs[axis.index].values.getOrNull(row)
                    val y = value?.let { axis.positionOf(it) }
                    y?.let { ChartOffset(axis.x, it) }
                },
            )
        }
        return ParallelGeometry(axes, polylines)
    }

    /**
     * Just the axes, without walking the rows.
     *
     * Shared with the gesture handler, which has to know where the axes stand
     * to decide which one a finger came down on and cannot afford to lay out
     * every row to find out. Two implementations of the same spacing would
     * eventually disagree, and the symptom would be a brush that filters the
     * axis next to the one being dragged.
     */
    fun axes(
        specs: List<ParallelAxisSpec>,
        bounds: ChartRect,
        inset: Float = 0f,
    ): List<ParallelAxis> {
        if (bounds.isEmpty || specs.size < 2) return emptyList()
        val top = bounds.top + inset
        val bottom = bounds.bottom - inset
        if (bottom <= top) return emptyList()
        val gaps = (specs.size - 1).coerceAtLeast(1)
        return specs.mapIndexed { index, spec ->
            ParallelAxis(
                index = index,
                label = spec.label,
                x = bounds.left + bounds.width * index / gaps,
                top = top,
                bottom = bottom,
                domain = spec.domain ?: domainOf(spec.values),
            )
        }
    }

    /**
     * The interval a dimension occupies.
     *
     * Its own minimum and maximum rather than zero to the maximum: a parallel
     * axis is read for *where a row sits among the others*, and padding the
     * bottom out to a zero nobody measured compresses every real difference
     * into the top of the axis.
     */
    private fun domainOf(values: List<Double?>): NumericDomain =
        NumericDomain.of(values.filterNotNull().filter { it.isFinite() }) ?: NumericDomain.Default

    /**
     * The squared distance from [point] to the nearest of [polyline]'s segments.
     *
     * Squared, because it is only ever compared with another one and the square
     * root would be taken once per row per hit test for no change in the answer.
     */
    fun distanceSquaredTo(polyline: ParallelPolyline, point: ChartOffset): Float {
        var best = Float.MAX_VALUE
        polyline.segments.forEach { run ->
            for (index in 0 until run.size - 1) {
                val distance = segmentDistanceSquared(run[index], run[index + 1], point)
                if (distance < best) best = distance
            }
        }
        return best
    }

    private fun segmentDistanceSquared(a: ChartOffset, b: ChartOffset, p: ChartOffset): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 0f) {
            val ox = p.x - a.x
            val oy = p.y - a.y
            return ox * ox + oy * oy
        }
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        val nx = a.x + t * dx
        val ny = a.y + t * dy
        val ox = p.x - nx
        val oy = p.y - ny
        return ox * ox + oy * oy
    }
}
