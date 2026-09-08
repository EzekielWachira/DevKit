package io.devkit.chartkit.coordinate

import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect

/**
 * A plain rectangular plot with no axes.
 *
 * The third [CoordinateSystem], alongside [CartesianCoordinates] and
 * [PolarCoordinates], and the one the layout-driven visualisations use: a
 * treemap, a Sankey diagram, a funnel and a network graph all place their own
 * geometry inside a rectangle and have no value axis to map against.
 *
 * ### Why it exists at all
 *
 * It would have been possible to give each of those charts its own `Canvas` and
 * be done with it. The cost would have been four copies of everything above the
 * coordinate system — the layer model, the selection model, the tooltip
 * overlay, the legend, the animation clock, the theme lookup, the accessibility
 * summary, the capture modifier — and four places for them to drift apart. A
 * coordinate system that answers exactly one question, "where is the plot",
 * lets all of that stay shared.
 *
 * @param contentBounds the region the visualisation's own geometry was laid out
 *   in. Usually equal to [plotArea]; a chart that reserved a gutter for labels
 *   reports the smaller rectangle here, so hit testing and the layout agree.
 */
class PlanarCoordinates(
    override val plotArea: ChartRect,
    val contentBounds: ChartRect = plotArea,
) : CoordinateSystem {

    val isDrawable: Boolean get() = !plotArea.isEmpty

    /** True when [point] falls inside the laid-out content. */
    fun contains(point: ChartOffset): Boolean = contentBounds.contains(point)

    /** The point as a fraction of [contentBounds], for layouts stated in unit space. */
    fun fractionOf(point: ChartOffset): ChartOffset {
        val bounds = contentBounds
        if (bounds.isEmpty) return ChartOffset.Zero
        return ChartOffset(
            x = (point.x - bounds.left) / bounds.width,
            y = (point.y - bounds.top) / bounds.height,
        )
    }

    /** A unit-space position mapped into [contentBounds]. */
    fun pointAtFraction(fractionX: Float, fractionY: Float): ChartOffset = ChartOffset(
        x = contentBounds.left + fractionX * contentBounds.width,
        y = contentBounds.top + fractionY * contentBounds.height,
    )

    override fun toString(): String = "PlanarCoordinates(plot=$plotArea)"
}
