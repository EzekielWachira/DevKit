package io.devkit.chartkit.model

import io.devkit.chartkit.geometry.ChartOffset

/**
 * What the user just selected, in enough detail to render a tooltip and act on.
 *
 * One type for every chart. A tap on a bar, a tap on a line point and a scrub
 * across an area all produce this, which is what lets a combined chart
 * coordinate selection across its layers instead of each one keeping a private
 * notion of "the selected thing".
 *
 * [item] is the caller's own object, carried through untouched — a custom
 * tooltip reads `selection.item.customerName` directly rather than reaching
 * back into the source list by index and hoping the two still line up.
 *
 * @param seriesId the [ChartSeries.id] the selection belongs to.
 * @param seriesName the series' human label.
 * @param seriesIndex the series' position among the *visible* series. Not the
 *   palette slot: colours are assigned in declaration order so that hiding a
 *   series never recolours the rest.
 * @param pointIndex the index within `ChartSeries.data`.
 * @param x the resolved horizontal domain value.
 * @param y the value.
 * @param item the caller's data object at [pointIndex].
 * @param position where the selection landed in the plot, in pixels, for
 *   anchoring a tooltip or a marker. The **common anchor**: a bar's top edge, a
 *   line vertex, a pie slice's mid-ring point and a crosshair's intersection
 *   all arrive here, which is what lets one overlay engine position a tooltip
 *   for any of them.
 * @param details geometry specific to the coordinate system the selection came
 *   from. Cartesian selections carry nothing extra; a polar selection carries
 *   its share of the whole and its arc, which a pie tooltip needs and a line
 *   tooltip has no meaning for.
 */
data class ChartSelection<out T>(
    val seriesId: String,
    val seriesName: String,
    val seriesIndex: Int,
    val pointIndex: Int,
    val x: ChartX,
    val y: Double,
    val item: T,
    val position: ChartOffset,
    val details: ChartSelectionDetails = ChartSelectionDetails.Cartesian,
) {
    /** The polar geometry of this selection, or `null` for a Cartesian one. */
    val polar: ChartSelectionDetails.Polar?
        get() = details as? ChartSelectionDetails.Polar

    /** The geographic detail of this selection, or `null` for any other chart. */
    val geo: ChartSelectionDetails.Geo?
        get() = details as? ChartSelectionDetails.Geo

    /** The x value as a label, for tooltips and accessibility text. */
    val xLabel: String
        get() = when (val value = x) {
            is ChartX.Category -> value.label
            is ChartX.Numeric -> value.value.toString()
            is ChartX.Time -> value.epochMillis.toString()
        }
}

/**
 * Coordinate-specific detail hung off a [ChartSelection].
 *
 * A sealed hierarchy rather than a bag of nullable fields, and an *addition*
 * rather than a split of [ChartSelection] into `CartesianSelection` and
 * `PolarSelection`. The common shape — which series, which item, where on
 * screen — is genuinely common: the tooltip overlay, the accessibility layer,
 * the selection callbacks and the hoisted state all work on it without knowing
 * which coordinate system produced it. Only the extra geometry differs, and
 * only the code that needs it looks.
 */
sealed interface ChartSelectionDetails {

    /** A point, a bar or a crosshair intersection. Nothing to add. */
    data object Cartesian : ChartSelectionDetails

    /**
     * A shaded region on a thematic map.
     *
     * Everything a caller needs about *where* the selection is, alongside the
     * `item` every selection already carries — which for a choropleth is the
     * caller's own joined statistic, so a tooltip reads
     * `selection.item.bookings` directly.
     *
     * @param featureId the GeoJSON feature id, when the file had one.
     * @param featureKey the value the join matched on — the county code, the
     *   ISO code. Always present, because a feature that produced no key could
     *   not have been joined.
     * @param featureLabel the region's name, as the caller's `featureLabel`
     *   lambda resolved it.
     * @param properties the feature's own GeoJSON properties, untouched. A
     *   drill-down reads a parent id out of here; a tooltip reads a population.
     * @param hasValue whether the join found a record. `false` means the region
     *   was drawn in the theme's "no data" colour, and [ChartSelection.y] is
     *   not a measurement.
     */
    data class Geo(
        val featureId: String?,
        val featureKey: String,
        val featureLabel: String,
        val properties: io.devkit.chartkit.geo.GeoProperties,
        val hasValue: Boolean,
        val bounds: io.devkit.chartkit.geo.GeoBounds?,
    ) : ChartSelectionDetails

    /**
     * A pie or donut slice, or a radial bar.
     *
     * @param fraction the value's share of the total, in `0..1`. For a radial
     *   bar this is its progress between the configured minimum and maximum.
     * @param label the slice's own label, which on a polar chart is the
     *   identity a reader has rather than a series name.
     * @param startAngle where the arc begins, in ChartKit's convention — zero
     *   at the top, increasing clockwise.
     * @param sweepAngle how far it sweeps.
     */
    data class Polar(
        val fraction: Double,
        val label: String,
        val startAngle: Float,
        val sweepAngle: Float,
    ) : ChartSelectionDetails
}

/**
 * The engine's own view of a selection, before the caller's type is restored.
 *
 * Internal because layers are type-erased: a combined chart holds layers over
 * different `T`s, so hit testing cannot be generic. The high-level charts
 * recover `T` on the way out, which is safe because they are also what put it
 * in — the item at `pointIndex` came from their own `List<T>`.
 */
internal typealias AnyChartSelection = ChartSelection<Any?>

@Suppress("UNCHECKED_CAST")
internal fun <T> AnyChartSelection.typed(): ChartSelection<T> = this as ChartSelection<T>
