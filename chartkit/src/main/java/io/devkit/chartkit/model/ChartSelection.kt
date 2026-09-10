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

    /** The geographic mark of this selection, or `null` for any other chart. */
    val geoPoint: ChartSelectionDetails.GeoPoint?
        get() = details as? ChartSelectionDetails.GeoPoint

    /** The route of this selection, or `null` for any other chart. */
    val geoRoute: ChartSelectionDetails.GeoRoute?
        get() = details as? ChartSelectionDetails.GeoRoute

    /** The logical region of this selection, or `null` for any other chart. */
    val set: ChartSelectionDetails.Set?
        get() = details as? ChartSelectionDetails.Set

    /** The X/Y/Z coordinates of this selection, or `null` for any other chart. */
    val cartesian3D: ChartSelectionDetails.Cartesian3D?
        get() = details as? ChartSelectionDetails.Cartesian3D

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
     * A mark on a map that is **not** one of its regions.
     *
     * A city on a world map, a store in a sales territory, a sensor in a
     * catchment. Its identity is a position rather than a boundary, so it
     * carries a coordinate where [Geo] carries a feature — and it has no
     * properties bag, because the caller's own record is right there in
     * [ChartSelection.item].
     *
     * @param coordinate where the mark is, in degrees. What a "copy
     *   coordinates" action or a hand-off to a mapping app needs.
     * @param label the mark's own name, as the chart's `label` lambda gave it.
     * @param sizeValue the number behind the mark's radius, when a size
     *   encoding is in use.
     * @param colorValue the number behind the mark's colour, when a colour
     *   encoding is in use. Both are absent when the encoding is not used, and
     *   both may be absent on a mark whose record had no value for them —
     *   which is not the same as zero.
     */
    data class GeoPoint(
        val coordinate: io.devkit.chartkit.geo.GeoCoordinate,
        val label: String,
        val sizeValue: Double? = null,
        val colorValue: Double? = null,
    ) : ChartSelectionDetails

    /**
     * A route drawn on a map: a path rather than a place.
     *
     * @param label the route's own name.
     * @param coordinates the path as supplied, before projection or any
     *   antimeridian repair — so a caller reading it back gets what they gave.
     * @param value the number behind the route's width or colour, when either
     *   is encoded.
     */
    data class GeoRoute(
        val label: String,
        val coordinates: List<io.devkit.chartkit.geo.GeoCoordinate>,
        val value: Double? = null,
    ) : ChartSelectionDetails

    /**
     * One logical region of a Venn or Euler diagram.
     *
     * The unit of selection in a set diagram is a **region**, not a set. Tapping
     * where two circles cross selects "A and B, and nothing else" — which has
     * its own value, its own colour and its own place in the accessibility
     * table — rather than selecting whichever circle happened to be drawn last.
     *
     * @param memberships the set ids an item must be in to be in this region,
     *   and only these. Order-independent.
     * @param value how many items are in exactly this combination. This is the
     *   number a tooltip must show, and it is not the intersection cardinality
     *   the caller stated unless this is the largest combination in the diagram.
     * @param totalValue how many are in *at least* this combination — the
     *   number the caller supplied for a pairwise intersection.
     * @param label the region's name, as the diagram's own formatter rendered
     *   it. Localisable: see `regionName` on the chart.
     * @param sets the definitions behind [memberships], so a tooltip can read a
     *   set's metadata without looking anything up.
     * @param isTheoretical true when Venn semantics drew this region even though
     *   the data puts nothing in it.
     */
    data class Set(
        val memberships: kotlin.collections.Set<String>,
        val kind: io.devkit.chartkit.set.SetRegionKind,
        val value: Double,
        val totalValue: Double,
        val label: String,
        val sets: List<io.devkit.chartkit.set.SetDefinition>,
        val isTheoretical: Boolean,
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

    /**
     * One observation in a true X/Y/Z Cartesian 3D chart.
     *
     * ### Three values, and no camera
     *
     * The three coordinates are the caller's own numbers, each formatted by its
     * own axis. Nothing here says where the point ended up on screen, how deep
     * it was, or which other points were in front of it: a camera position is
     * presentation, and a selection that reported it would be handing an
     * application a fact about ChartKit's drawing rather than about the data.
     * [ChartSelection.position] already carries the one screen quantity a
     * caller legitimately needs — where to anchor a tooltip.
     *
     * [ChartSelection.y] carries the same number as [y] here, so a chart-level
     * tooltip that knows nothing about three dimensions still shows something
     * true rather than nothing. The extra two are what make a 3D tooltip
     * possible without a second selection type.
     *
     * @param xTitle, [yTitle] and [zTitle] the axis names, so a tooltip can
     *   write `Age: 34` rather than `X: 34` without resolving the axes again.
     * @param formattedX, [formattedY] and [formattedZ] each value through its
     *   own axis formatter and unit — the same text the axis labels use, so a
     *   tooltip cannot round differently from the ticks beside it.
     * @param size the value the marker's size encodes, or `null` when size is
     *   not encoding anything.
     * @param colorValue the value the marker's colour encodes, or `null`.
     */
    data class Cartesian3D(
        val x: Double,
        val y: Double,
        val z: Double,
        val xTitle: String?,
        val yTitle: String?,
        val zTitle: String?,
        val formattedX: String,
        val formattedY: String,
        val formattedZ: String,
        val size: Double? = null,
        val colorValue: Double? = null,
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
