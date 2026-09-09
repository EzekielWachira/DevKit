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
    /**
     * Which value axis this series is measured against.
     *
     * `null` on a chart whose layers produced entries before an axis was
     * resolved — never on a Cartesian chart, which fills it in for every entry.
     * What makes a multi-axis tooltip possible at all: `82` and `14.2` and
     * `1018` are three quantities, and a tooltip that could not tell them apart
     * would have to write all three the same way.
     */
    val axisId: io.devkit.chartkit.axis.ChartAxisId? = null,
    /** That axis' title, for a tooltip that groups its rows by quantity. */
    val axisTitle: String? = null,
    /** What that axis measures in. */
    val unit: io.devkit.chartkit.axis.ChartUnit = io.devkit.chartkit.axis.ChartUnit.None,
    /**
     * [value] written the way its own axis writes it, unit included.
     *
     * `82 mm`, `14.2 °C`, `1,018 hPa` — each through the formatter of the axis
     * it belongs to, so a tooltip and the axis beside it never disagree.
     */
    val formattedValue: String? = null,
    /** Where this series sits on screen at the selected position. */
    val position: ChartOffset? = null,
) {
    /** [formattedValue] when the chart supplied one, else the raw number. */
    val text: String get() = formattedValue ?: value.toString()
}

/**
 * The order a multi-series tooltip lists its rows in.
 *
 * Deterministic by construction. The rows come from a map iteration somewhere
 * upstream, and a tooltip whose lines rearranged themselves between two
 * hovers over the same point would be the kind of bug that is reported as
 * "it flickers" and never reproduced.
 */
sealed interface ChartTooltipOrder {

    fun sort(entries: List<ChartTooltipEntry<Any?>>): List<ChartTooltipEntry<Any?>>

    /** The order the layers were declared in. The default. */
    data object Declaration : ChartTooltipOrder {
        override fun sort(entries: List<ChartTooltipEntry<Any?>>) = entries
    }

    /**
     * Grouped by axis, axes in declaration order.
     *
     * For a chart where the reader is comparing quantities rather than series:
     * all the temperatures together, then all the pressures.
     */
    data class ByAxis(val axes: List<io.devkit.chartkit.axis.ChartAxisId>) : ChartTooltipOrder {
        override fun sort(entries: List<ChartTooltipEntry<Any?>>) =
            entries.sortedBy { entry ->
                axes.indexOf(entry.axisId).takeIf { it >= 0 } ?: axes.size
            }
    }

    /** Whatever the caller wants. */
    data class Custom(val comparator: Comparator<ChartTooltipEntry<Any?>>) : ChartTooltipOrder {
        override fun sort(entries: List<ChartTooltipEntry<Any?>>) = entries.sortedWith(comparator)
    }
}

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
 * @param valueFormatter how the chart's own value axis writes numbers. The
 *   default tooltip uses it when the caller supplied none, so a tooltip and the
 *   axis beside it never disagree about how a price is written — a chart whose
 *   axis reads `250` and whose tooltip reads `229.0358655001` is showing two
 *   different quantities as far as a reader is concerned.
 */
data class ChartTooltipData<out T>(
    val selection: ChartSelection<T>,
    val entries: List<ChartTooltipEntry<T>>,
    val anchor: ChartOffset,
    val xLabel: String,
    val valueFormatter: io.devkit.chartkit.formatter.ChartValueFormatter =
        io.devkit.chartkit.formatter.ChartValueFormatter.Raw,
) {
    /** True when more than one series is reported at this position. */
    val isMultiSeries: Boolean get() = entries.size > 1

    /** The caller's own item behind the nearest series. */
    val item: T get() = selection.item
}

internal typealias AnyChartTooltipData = ChartTooltipData<Any?>

@Suppress("UNCHECKED_CAST")
internal fun <T> AnyChartTooltipData.typed(): ChartTooltipData<T> = this as ChartTooltipData<T>
