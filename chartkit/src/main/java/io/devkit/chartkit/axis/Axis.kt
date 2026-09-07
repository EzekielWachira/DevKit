package io.devkit.chartkit.axis

import io.devkit.chartkit.formatter.ChartTimeFormatter
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.scale.TickGenerator

/** Which edge of the plot an axis is drawn against. */
enum class AxisPosition {
    Bottom,
    Top,
    Start,
    End,
    ;

    /** True for [Bottom] and [Top]: the axis runs left to right. */
    val isHorizontal: Boolean get() = this == Bottom || this == Top
}

/**
 * What to do when axis labels do not all fit.
 *
 * Drawing them anyway produces overlapping text, and truncating each one
 * produces a row of ellipses; neither is a strategy. ChartKit measures the
 * labels and then applies one of these.
 */
enum class AxisLabelOverflow {

    /**
     * Show every *n*-th label, keeping the first and last. The default.
     *
     * Loses information about which categories exist but keeps every visible
     * label readable, and the bars or points themselves still show the count.
     */
    Skip,

    /**
     * Rotate the labels 45° so more of them fit before skipping starts.
     *
     * Better for long category names; harder to read at a glance, so it is not
     * the default.
     */
    Rotate,

    /** Draw them all and let them collide. Only useful for debugging a layout. */
    None,
}

/**
 * One axis of a Cartesian chart.
 *
 * Immutable and passed as a parameter, in Compose's own idiom, rather than
 * configured through a builder:
 *
 * ```kotlin
 * LineChart(
 *     data = revenue,
 *     x = { it.month },
 *     y = { it.amount },
 *     yAxis = ChartAxis(title = "Revenue", tickCount = 4),
 * )
 * ```
 *
 * @param visible whether the axis is drawn at all. A hidden axis still occupies
 *   no space, so hiding one widens the plot.
 * @param position the edge it is drawn against. Validated against the axis'
 *   role: an x axis cannot sit at [AxisPosition.Start].
 * @param title an optional label for the axis itself, drawn outside the ticks.
 * @param showLine whether the axis line is drawn.
 * @param showTicks whether tick marks are drawn.
 * @param showLabels whether tick labels are drawn.
 * @param tickCount the *approximate* number of ticks wanted on a continuous
 *   axis. Approximate on purpose — see [TickGenerator]. Ignored on a category
 *   axis, where the categories are the ticks.
 * @param maxLabels a hard cap on drawn labels, applied after measurement. `null`
 *   lets the measured width decide.
 * @param labelOverflow what to do when labels do not fit.
 * @param valueFormatter formats numeric tick values. `null` derives a formatter
 *   from the tick values themselves, which is usually what you want.
 * @param timeFormatter formats time tick values. `null` picks a pattern from
 *   the span the axis covers.
 * @param categoryFormatter transforms category labels, e.g. to abbreviate them.
 * @param domain overrides how the axis chooses its interval. `null` takes the
 *   chart's default, which differs between bars and lines for reasons set out
 *   in [DomainPolicy].
 */
data class ChartAxis(
    val visible: Boolean = true,
    val position: AxisPosition? = null,
    val title: String? = null,
    val showLine: Boolean = true,
    val showTicks: Boolean = true,
    val showLabels: Boolean = true,
    val tickCount: Int = TickGenerator.DEFAULT_TICK_COUNT,
    val maxLabels: Int? = null,
    val labelOverflow: AxisLabelOverflow = AxisLabelOverflow.Skip,
    val valueFormatter: ChartValueFormatter? = null,
    val timeFormatter: ChartTimeFormatter? = null,
    val categoryFormatter: ((String) -> String)? = null,
    val domain: DomainPolicy? = null,
) {
    init {
        require(tickCount >= 2) {
            "An axis needs at least 2 ticks to describe an interval, was $tickCount"
        }
        require(maxLabels == null || maxLabels >= 1) {
            "maxLabels must be at least 1 when set, was $maxLabels"
        }
    }

    /** The position, or [fallback] when the caller did not state one. */
    internal fun positionOr(fallback: AxisPosition): AxisPosition = position ?: fallback

    companion object {

        /** Line, ticks and labels, at the axis' natural edge. */
        val Default: ChartAxis = ChartAxis()

        /** Drawn nowhere and taking no space. */
        val Hidden: ChartAxis = ChartAxis(visible = false)

        /** Labels but no line or ticks — a quieter axis for dense dashboards. */
        val LabelsOnly: ChartAxis = ChartAxis(showLine = false, showTicks = false)
    }
}

/** One tick: its domain value, its pixel position and the text drawn for it. */
data class AxisTick(
    val value: Double,
    val position: Float,
    val label: String,
)

/** Which grid lines are drawn behind the plot. */
enum class ChartGrid {
    None,

    /** Lines across the plot at the value axis' ticks. The usual choice. */
    Horizontal,

    /** Lines down the plot at the category or x axis' ticks. */
    Vertical,

    Both,
    ;

    internal val hasHorizontal: Boolean get() = this == Horizontal || this == Both
    internal val hasVertical: Boolean get() = this == Vertical || this == Both
}

/**
 * Chooses which of [count] labels to draw, given the space each one needs.
 *
 * Returns indices, always including the first and — where the stride allows —
 * the last, because the ends of an axis are the two labels a reader orients
 * from. The stride is uniform: dropping labels unevenly makes the axis look
 * like it has irregular intervals.
 *
 * @param available the pixel extent the labels share.
 * @param labelExtent the space one label needs, measured, including its gutter.
 */
internal fun selectLabelIndices(
    count: Int,
    available: Float,
    labelExtent: Float,
    maxLabels: Int? = null,
): List<Int> {
    if (count <= 0) return emptyList()
    if (count == 1) return listOf(0)

    val fits = when {
        !available.isFinite() || available <= 0f -> 1
        !labelExtent.isFinite() || labelExtent <= 0f -> count
        else -> (available / labelExtent).toInt().coerceAtLeast(1)
    }
    val capacity = minOf(fits, maxLabels ?: count, count)
    if (capacity >= count) return (0 until count).toList()
    if (capacity <= 1) return listOf(0)

    // Ceiling division: a stride that rounds down would select more labels than
    // fit, which is the failure this whole function exists to avoid.
    val stride = ((count - 1) + (capacity - 1) - 1) / (capacity - 1)
    val indices = ArrayList<Int>(capacity)
    var index = 0
    while (index < count) {
        indices += index
        index += stride
    }
    // Keep the last label when doing so does not crowd its neighbour.
    val last = count - 1
    if (indices.last() != last) {
        if (last - indices.last() >= stride / 2) indices += last else indices[indices.size - 1] = last
    }
    return indices
}
