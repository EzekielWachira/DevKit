package io.devkit.chartkit.model

import io.devkit.chartkit.geometry.ChartOffset

/**
 * One line of a tooltip: a series, its value at the selected position, and the
 * caller's own object behind it.
 *
 * @param paletteIndex the series' colour slot, so a tooltip can draw the same
 *   swatch the chart did without resolving colours itself.
 * @param item the caller's data object, carried through untouched — a custom
 *   tooltip reads its fields directly, with no reflection and no reaching back
 *   into the source list by index.
 */
data class ChartTooltipEntry<out T>(
    val seriesId: String,
    val seriesName: String,
    val value: Double,
    val item: T,
    val paletteIndex: Int,
)

/**
 * Everything a tooltip needs, whatever chart produced it.
 *
 * ### One shape for every chart
 *
 * A tap on a bar, a scrub across a line, a crosshair over four series and a tap
 * on a pie slice all arrive here. That is what lets ChartKit have **one**
 * overlay positioning engine instead of one per chart type: chart-specific code
 * supplies the data and the anchor, and knows nothing about how a tooltip is
 * laid out or kept inside the plot.
 *
 * ### Single and multi-series
 *
 * [entries] holds one entry for a single-series selection and one per visible
 * series for a crosshair or a shared-x scrub, so the same custom tooltip can
 * handle both:
 *
 * ```kotlin
 * tooltip = { data ->
 *     Column {
 *         Text(data.xLabel)
 *         data.entries.forEach { Text("${it.seriesName}: ${money.format(it.value)}") }
 *     }
 * }
 * ```
 *
 * @param selection what was selected. For a multi-series tooltip this is the
 *   nearest series — the one under the pointer — while [entries] carries them
 *   all.
 * @param entries one line per series, in declaration order.
 * @param anchor where the tooltip should point, in pixels.
 * @param xLabel the domain value as text, already formatted by the chart's own
 *   axis formatter.
 */
data class ChartTooltipData<out T>(
    val selection: ChartSelection<T>,
    val entries: List<ChartTooltipEntry<T>>,
    val anchor: ChartOffset,
    val xLabel: String,
) {
    /** True when more than one series is reported at this position. */
    val isMultiSeries: Boolean get() = entries.size > 1

    /** The caller's own item behind the nearest series. */
    val item: T get() = selection.item
}

internal typealias AnyChartTooltipData = ChartTooltipData<Any?>

@Suppress("UNCHECKED_CAST")
internal fun <T> AnyChartTooltipData.typed(): ChartTooltipData<T> = this as ChartTooltipData<T>
