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
 *   anchoring a tooltip or a marker.
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
) {
    /** The x value as a label, for tooltips and accessibility text. */
    val xLabel: String
        get() = when (val value = x) {
            is ChartX.Category -> value.label
            is ChartX.Numeric -> value.value.toString()
            is ChartX.Time -> value.epochMillis.toString()
        }
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
