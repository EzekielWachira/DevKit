package io.devkit.chartkit.geo

import io.devkit.chartkit.geometry.HexCell
import io.devkit.chartkit.geometry.HexGrid

/**
 * How the records falling in one bin become the number the bin is coloured by.
 */
enum class HexAggregate {

    /** How many records fell in the bin. The default, and the only one that
     * needs no value accessor. */
    Count,

    /** The total of their values. */
    Sum,

    /** Their mean. Bins with no usable value report nothing rather than zero. */
    Mean,

    /** The largest of their values. */
    Max,
}

/**
 * One bin: where it sits, what fell in it, and what that comes to.
 *
 * Named `GeoBin` rather than `GeoHexBin` because the object that builds these
 * is `GeoHexbin`, and two names differing only in the case of one letter
 * produce class files that collide on a case-insensitive filesystem — which
 * macOS is by default. It compiles on CI and not on half the machines that
 * would review it.
 *
 * @param center the bin's centre in **projected** space, not on screen.
 * @param count how many records fell in it, whatever the aggregate.
 * @param value the aggregated number, or `null` when the aggregate had nothing
 *   usable to work with — a bin of records whose values are all missing is not
 *   a bin whose total is zero.
 * @param indices the records' positions in the caller's own list.
 */
class GeoBin internal constructor(
    val cell: HexCell,
    val center: ProjectedPoint,
    val count: Int,
    val value: Double?,
    val indices: List<Int>,
)

/**
 * Hexagonal binning of projected points.
 *
 * ### Binned in projected space, not on screen
 *
 * d3-hexbin and most web implementations bin in screen coordinates, which
 * rebins on every zoom so that the hexagons stay a constant size under the
 * reader's eye. That is a reasonable choice and it has a cost: the numbers
 * change as you pan. A bin that said "14" says "9" after a nudge, because its
 * boundaries moved.
 *
 * These bins are fixed in projected space. The aggregation is a property of the
 * data rather than of the view, so panning changes nothing and zooming
 * magnifies the same bins instead of recomputing them — which also keeps the
 * work off the gesture loop, the same reason a choropleth's vertices are
 * projected once. The trade is that zooming far in produces large hexagons
 * rather than finer ones.
 *
 * ### Why bin at all
 *
 * A point map of any density stops being a map of points and becomes a blob:
 * the marks overlap, the overlaps are opaque, and the reader cannot tell two
 * records from two hundred. Binning replaces "where is every record" — which
 * the picture could not answer anyway — with "how many are near here", which it
 * can.
 *
 * Plain Kotlin: no Compose, no `android.graphics`.
 */
object GeoHexbin {

    /**
     * Bins [points] on a grid of [radius] projected units.
     *
     * @param values one per point, for the aggregates that need them. `null`
     *   entries are records with no usable measurement and are counted but not
     *   totalled.
     */
    fun bin(
        points: List<ProjectedPoint>,
        radius: Double,
        aggregate: HexAggregate = HexAggregate.Count,
        values: List<Double?>? = null,
    ): List<GeoBin> {
        if (points.isEmpty() || radius <= 0.0 || !radius.isFinite()) return emptyList()

        // Insertion-ordered, so two runs over the same data produce the bins in
        // the same order — which is what makes the draw order, and therefore
        // the overlap of any partially transparent fill, reproducible.
        val buckets = LinkedHashMap<HexCell, MutableList<Int>>()
        points.forEachIndexed { index, point ->
            if (!point.isFinite) return@forEachIndexed
            val cell = HexGrid.cellAt(point.x, point.y, radius)
            buckets.getOrPut(cell) { ArrayList() } += index
        }

        return buckets.map { (cell, indices) ->
            val (x, y) = HexGrid.centerOf(cell, radius)
            GeoBin(
                cell = cell,
                center = ProjectedPoint(x, y),
                count = indices.size,
                value = aggregateOf(aggregate, indices, values),
                indices = indices,
            )
        }
    }

    private fun aggregateOf(
        aggregate: HexAggregate,
        indices: List<Int>,
        values: List<Double?>?,
    ): Double? {
        if (aggregate == HexAggregate.Count) return indices.size.toDouble()
        val usable = indices.mapNotNull { values?.getOrNull(it) }.filter { it.isFinite() }
        // A bin whose records all lack a measurement has no total, no mean and
        // no maximum. Reporting zero would put it at the bottom of the colour
        // scale beside the genuinely empty places.
        if (usable.isEmpty()) return null
        return when (aggregate) {
            HexAggregate.Count -> indices.size.toDouble()
            HexAggregate.Sum -> usable.sum()
            HexAggregate.Mean -> usable.average()
            HexAggregate.Max -> usable.max()
        }
    }
}
