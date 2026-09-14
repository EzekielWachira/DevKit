package io.devkit.chartkit.geometry

import io.devkit.chartkit.scale.NumericDomain

/**
 * How a multi-series area chart combines its series.
 *
 * ```text
 * None      ▁▂▃▄▅  each series filled from the axis, overlapping
 * Stacked   ▄▄▄▄▄  each series sits on the one below
 * Expand    █████  the same, rescaled so every column is 100%
 * Stream    ◗◗◗◗◗  the same, centred on the axis rather than resting on it
 * ```
 *
 * ### Why a mode rather than three charts
 *
 * A stacked area, a hundred-percent area and a stream graph differ in exactly
 * one thing: where the bottom of the first series is put. The values, the
 * scales, the interpolation, the interaction and the accessibility layer are
 * identical. Three chart types would mean three copies of that, kept in
 * agreement by hand.
 */
enum class AreaStacking {

    /**
     * Each series filled from the axis independently. The default.
     *
     * The honest reading when the series are **alternatives** — revenue against
     * forecast, this quarter against last — where adding them together would
     * produce a total that means nothing.
     */
    None,

    /** Each series rests on the one below it, so the top edge is the total. */
    Stacked,

    /**
     * Stacked and rescaled so every column fills the plot.
     *
     * Composition over time, at the cost of the totals: a column whose parts
     * halved looks identical to one that did not move.
     */
    Expand,

    /**
     * Stacked and centred on the axis — the stream graph, or ThemeRiver.
     *
     * Each column is shifted so the stack straddles zero rather than resting on
     * it, which turns the hard bottom edge of a stacked area into a second
     * flowing boundary. It costs the reader the ability to judge any one
     * series' value against an axis — only the *thickness* is readable — and
     * buys legibility on many series over many columns, where a stacked area
     * degenerates into a stack of thin slivers pinned to a line.
     *
     * This is the **centred** (silhouette) baseline. The Byron–Wattenberg
     * "wiggle" baseline, which chooses the offset that minimises the total
     * slope of the boundaries rather than centring them, is not implemented;
     * see the roadmap.
     */
    Stream,
    ;

    /** True when the series are piled on one another rather than overlaid. */
    val isStacked: Boolean get() = this != None
}

/**
 * Value-space bands for a stacked area chart.
 *
 * ### It does not stack
 *
 * [BarStacking] does, and this calls it. A stacked area and a stacked bar are
 * the same arithmetic over the same values — accumulate per sign, leave a hole
 * where a series has no value — and the only thing an area adds is the option
 * to move the whole column afterwards. Reimplementing the accumulation here
 * would mean two copies that have to agree about negatives, about missing
 * values and about percentage totals, and nothing would notice when they
 * stopped agreeing.
 */
internal object AreaStackGeometry {

    /**
     * `[lower, upper]` in value space for each series and column.
     *
     * Indexed `[seriesIndex][columnIndex]`; `null` where a series has no value
     * there. A missing value leaves a hole rather than a zero-height band, so
     * the gap is visible as a break instead of being silently read as zero —
     * but the series stacked **above** it necessarily close over the space,
     * because a stack has nothing else it can do with an absent part.
     */
    fun bounds(
        values: List<List<Double?>>,
        stacking: AreaStacking,
    ): List<List<ClosedFloatingPointRange<Double>?>> {
        if (values.isEmpty() || !stacking.isStacked) return emptyList()
        val grouping = if (stacking == AreaStacking.Expand) {
            BarGrouping.StackedPercent
        } else {
            BarGrouping.Stacked
        }
        val stacked = BarStacking.bounds(values, grouping)
        return if (stacking == AreaStacking.Stream) centred(stacked) else stacked
    }

    /**
     * Every column shifted so its stack straddles zero.
     *
     * The shift is per column and is the midpoint of what that column occupies,
     * so the band *thicknesses* — the only quantity a stream graph claims to
     * show — are untouched.
     */
    private fun centred(
        bounds: List<List<ClosedFloatingPointRange<Double>?>>,
    ): List<List<ClosedFloatingPointRange<Double>?>> {
        if (bounds.isEmpty()) return bounds
        val columnCount = bounds.maxOf { it.size }
        val shift = DoubleArray(columnCount)
        for (column in 0 until columnCount) {
            var top = Double.NEGATIVE_INFINITY
            var bottom = Double.POSITIVE_INFINITY
            bounds.forEach { series ->
                val range = series.getOrNull(column) ?: return@forEach
                if (range.endInclusive > top) top = range.endInclusive
                if (range.start < bottom) bottom = range.start
            }
            // A column every series is missing from has nothing to centre, and
            // an infinite midpoint would poison the scale.
            shift[column] = if (top.isFinite() && bottom.isFinite()) (top + bottom) / 2.0 else 0.0
        }
        return bounds.map { series ->
            series.mapIndexed { column, range ->
                val offset = shift.getOrElse(column) { 0.0 }
                range?.let { (it.start - offset)..(it.endInclusive - offset) }
            }
        }
    }

    /** The interval the stacked bands occupy, for the value axis. */
    fun domainOf(bounds: List<List<ClosedFloatingPointRange<Double>?>>): NumericDomain? =
        BarStacking.domainOf(bounds)

    /**
     * The edge the series' **line** is drawn along.
     *
     * A band built from a positive value is read from its top and one built
     * from a negative value from its bottom: the line belongs on the side that
     * moved when the value changed. The decision needs the original value
     * rather than the band, because [AreaStacking.Stream] has already shifted
     * the band off zero and its sign no longer says which way the series grew —
     * in that mode every band is read from the top, which is what keeps the
     * layers looking stacked.
     */
    fun lineEdge(
        range: ClosedFloatingPointRange<Double>,
        value: Double,
        stacking: AreaStacking,
    ): Double = if (stacking == AreaStacking.Stream || value >= 0.0) {
        range.endInclusive
    } else {
        range.start
    }

    /** The edge the series' fill closes to: the other end from [lineEdge]. */
    fun fillEdge(
        range: ClosedFloatingPointRange<Double>,
        value: Double,
        stacking: AreaStacking,
    ): Double = if (stacking == AreaStacking.Stream || value >= 0.0) {
        range.start
    } else {
        range.endInclusive
    }
}
