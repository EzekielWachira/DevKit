package io.devkit.chartkit.layout

import io.devkit.chartkit.axis.AxisPosition
import io.devkit.chartkit.axis.ChartAxisId
import io.devkit.chartkit.geometry.ChartInsets
import io.devkit.chartkit.geometry.ChartMath
import io.devkit.chartkit.geometry.ChartRect
import kotlin.math.max

/**
 * How much room one axis needs, after its labels have been measured.
 *
 * Measured, not assumed. A chart that reserved a fixed `48.dp` gutter would
 * clip `1,250,000` and waste half the width on `0..5` — and every chart type
 * would carry its own copy of the guess.
 *
 * @param labelExtent the largest label's extent across the axis: its width for
 *   a vertical axis, its height for a horizontal one.
 * @param titleExtent the axis title's extent across the axis, or `0`.
 */
internal data class AxisMetrics(
    val position: AxisPosition,
    val visible: Boolean,
    val labelExtent: Float,
    val tickLength: Float,
    val labelPadding: Float,
    val titleExtent: Float,
    /** Which axis this is, when the chart has more than one per side. */
    val id: ChartAxisId? = null,
    /**
     * An explicit distance from the plot edge, overriding the measured one.
     *
     * The advanced override from [io.devkit.chartkit.axis.ChartAxisSpec.offset].
     * It moves the axis without changing what it reserves, so a caller who
     * pushes one axis outward is responsible for the gap they leave.
     */
    val offsetOverride: Float? = null,
) {
    /** The total gutter this axis takes out of the chart's bounds. */
    val gutter: Float
        get() = if (!visible) {
            0f
        } else {
            max(
                0f,
                ChartMath.finiteOr(labelExtent, 0f) +
                    ChartMath.finiteOr(tickLength, 0f) +
                    ChartMath.finiteOr(labelPadding, 0f) +
                    ChartMath.finiteOr(titleExtent, 0f),
            )
        }
}

/**
 * The regions a chart is divided into, in pixels.
 *
 * The same type for both coordinate systems. A Cartesian chart's plot area is
 * whatever is left after the axis gutters; a polar chart's is the largest
 * square that fits, because an elliptical pie misreports every angle as an
 * area. Both are produced here, so no chart carves its own region out of its
 * bounds and no two charts disagree about what "the plot" means.
 *
 * @param bounds everything the chart composable was given.
 * @param plotArea the data region. Every layer draws relative to this and none
 *   of them computes padding of its own, which is what keeps a line layer and a
 *   bar layer in the same combined chart agreeing about where `40` is.
 */
internal data class ChartLayout(
    val bounds: ChartRect,
    val plotArea: ChartRect,
    /**
     * How far each axis sits outside the plot edge it is drawn against.
     *
     * Zero for the axis nearest the plot; for the next one out, the whole
     * gutter the first reserved. Measured rather than configured, which is what
     * the [AxisMetrics] doc is about: a chart with a `0..5` axis and a
     * `0..1,250,000` axis on the same side needs two different offsets, and no
     * constant is right for both.
     */
    val axisOffsets: Map<ChartAxisId, Float> = emptyMap(),
) {
    val isDrawable: Boolean get() = !plotArea.isEmpty

    /** How far axis [id] sits outside its plot edge. */
    fun offsetOf(id: ChartAxisId?): Float = id?.let { axisOffsets[it] } ?: 0f

    companion object {
        val Empty: ChartLayout = ChartLayout(ChartRect.Zero, ChartRect.Zero)
    }
}

/**
 * Carves the plot area out of the chart's bounds.
 *
 * Order matters: content padding first, then axis gutters, then the overhang
 * that half of the first and last horizontal labels stick out by. Applying the
 * overhang before the gutters would double-count it against the vertical axis
 * that already reserved space for its own labels.
 *
 * @param labelOverhang half the width of the widest horizontal-axis label. The
 *   first label is centred on the plot's left edge, so half of it falls outside
 *   the plot; without this the leftmost tick label is clipped by the composable.
 */
internal fun computeChartLayout(
    bounds: ChartRect,
    contentPadding: ChartInsets = ChartInsets.Zero,
    axes: List<AxisMetrics>,
    labelOverhang: Float = 0f,
): ChartLayout {
    if (bounds.isEmpty) return ChartLayout(bounds, ChartRect.Zero)

    var insets = contentPadding
    // Axes on one edge stack outward in declaration order: the first sits on
    // the plot, and each later one starts where the previous one's gutter
    // ended. Accumulating per side rather than globally is the whole of what
    // "multiple axes per side" needs from the layout engine.
    val consumed = HashMap<AxisPosition, Float>(4)
    val offsets = LinkedHashMap<ChartAxisId, Float>(axes.size)
    for (axis in axes) {
        val gutter = axis.gutter
        val already = consumed[axis.position] ?: 0f
        axis.id?.let { offsets[it] = axis.offsetOverride ?: already }
        if (gutter <= 0f) continue
        consumed[axis.position] = already + gutter
        insets += when (axis.position) {
            AxisPosition.Bottom -> ChartInsets(bottom = gutter)
            AxisPosition.Top -> ChartInsets(top = gutter)
            AxisPosition.Start -> ChartInsets(left = gutter)
            AxisPosition.End -> ChartInsets(right = gutter)
        }
    }

    val overhang = ChartMath.finiteOr(labelOverhang, 0f).coerceAtLeast(0f)
    if (overhang > 0f) {
        // Only where a vertical axis has not already reserved room.
        val startNeeds = max(0f, overhang - insets.left)
        val endNeeds = max(0f, overhang - insets.right)
        insets += ChartInsets(left = startNeeds, right = endNeeds)
    }

    val plot = bounds.inset(insets)
    return ChartLayout(
        bounds = bounds,
        // A plot that has been squeezed to nothing by its own axes is reported
        // as empty rather than as a rectangle with a negative width, so layers
        // skip drawing instead of dividing by it.
        plotArea = if (plot.isEmpty) ChartRect.Zero else plot,
        axisOffsets = offsets,
    )
}

/**
 * The largest centred square inside [bounds], after [contentPadding].
 *
 * Polar charts are square by necessity rather than by preference: a circle
 * stretched to fill a wide plot becomes an ellipse, and on an ellipse a 90°
 * slice no longer occupies a quarter of the area — the chart would misreport
 * every share it draws. The leftover width is given up instead.
 *
 * Returned as a [ChartLayout] like every other layout, so the overlay,
 * accessibility and legend machinery need no notion of which coordinate system
 * produced the plot.
 */
internal fun computePolarLayout(
    bounds: ChartRect,
    contentPadding: ChartInsets = ChartInsets.Zero,
): ChartLayout {
    if (bounds.isEmpty) return ChartLayout(bounds, ChartRect.Zero)
    val padded = bounds.inset(contentPadding)
    if (padded.isEmpty) return ChartLayout(bounds, ChartRect.Zero)

    val side = kotlin.math.min(padded.width, padded.height)
    if (side <= 0f) return ChartLayout(bounds, ChartRect.Zero)

    val left = padded.left + (padded.width - side) / 2f
    val top = padded.top + (padded.height - side) / 2f
    return ChartLayout(
        bounds = bounds,
        plotArea = ChartRect(left, top, left + side, top + side),
    )
}
