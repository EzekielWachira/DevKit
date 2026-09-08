package io.devkit.chartkit.coordinate

import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.geometry.PolarDirection
import io.devkit.chartkit.geometry.PolarGeometry

/**
 * A polar plot: a centre, a ring between two radii, and a sweep.
 *
 * The sibling of [CartesianCoordinates], and the reason [CoordinateSystem]
 * exists at all. Pie, donut and radial bar charts are built on this; the layer
 * model, the interaction model, the animation clock, the theme, the overlay and
 * the accessibility layer are shared with the Cartesian charts unchanged.
 *
 * ### Angles
 *
 * Zero degrees is at **twelve o'clock** and angles increase **clockwise** by
 * default. See [PolarGeometry] for why, and for the one place that convention
 * is translated into the canvas'.
 *
 * ### Radii
 *
 * [innerRadius] is what separates a pie from a donut: zero gives a pie, and
 * anything larger leaves a hole that belongs to no slice. A radial bar chart
 * uses the pair as the band its concentric tracks are laid out within.
 *
 * @param plotArea the square region the circle is inscribed in.
 * @param center the centre of the circle, in pixels.
 * @param startAngle where the first slice begins.
 * @param sweepAngle how far the whole chart covers. `360` for a full circle;
 *   less for a gauge or a half-donut.
 */
class PolarCoordinates(
    override val plotArea: ChartRect,
    val center: ChartOffset,
    val innerRadius: Float,
    val outerRadius: Float,
    val startAngle: Float = 0f,
    val sweepAngle: Float = PolarGeometry.FULL_CIRCLE,
    val direction: PolarDirection = PolarDirection.Clockwise,
) : CoordinateSystem {

    init {
        require(innerRadius >= 0f) { "Inner radius cannot be negative, was $innerRadius" }
        require(outerRadius >= innerRadius) {
            "Outer radius ($outerRadius) must be at least the inner radius ($innerRadius)"
        }
    }

    /** True when there is a ring to draw in. */
    val isDrawable: Boolean
        get() = !plotArea.isEmpty && outerRadius > 0f && outerRadius > innerRadius

    /** The width of the ring: the whole radius for a pie, the band for a donut. */
    val ringThickness: Float get() = outerRadius - innerRadius

    /** The screen position at [angleDegrees] and [radius] from the centre. */
    fun pointAt(angleDegrees: Float, radius: Float): ChartOffset =
        PolarGeometry.pointOnCircle(center, radius, angleDegrees)

    /**
     * The screen position at [angleDegrees], [fraction] of the way from the
     * inner radius to the outer one.
     *
     * `0.5` is the middle of the ring — where a slice label or a tooltip anchor
     * belongs.
     */
    fun pointAtFraction(angleDegrees: Float, fraction: Float): ChartOffset =
        pointAt(angleDegrees, innerRadius + ringThickness * fraction.coerceIn(0f, 1f))

    /** The angle from the centre to [point], in the chart convention. */
    fun angleOf(point: ChartOffset): Float = PolarGeometry.angleOf(center, point)

    /** The distance from the centre to [point]. */
    fun radiusOf(point: ChartOffset): Float = PolarGeometry.radiusOf(center, point)

    /**
     * True when [point] falls inside the ring.
     *
     * False inside a donut's hole, which is what lets centre content receive
     * its own taps without a slice stealing them.
     */
    fun containsInRing(point: ChartOffset): Boolean {
        val radius = radiusOf(point)
        return radius >= innerRadius && radius <= outerRadius
    }

    override fun toString(): String =
        "PolarCoordinates(center=$center, radii=[$innerRadius, $outerRadius], " +
            "start=$startAngle, sweep=$sweepAngle, $direction)"
}
