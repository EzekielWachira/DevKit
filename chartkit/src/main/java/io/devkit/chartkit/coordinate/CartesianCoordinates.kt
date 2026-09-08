package io.devkit.chartkit.coordinate

import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.scale.CategoryScale
import io.devkit.chartkit.scale.InvertibleChartScale
import io.devkit.chartkit.scale.LinearScale

/**
 * How a chart turns data into positions.
 *
 * Deliberately an abstraction over "Cartesian", not a synonym for it. ChartKit
 * has two implementations — [CartesianCoordinates] for line, area and bar
 * charts, and [PolarCoordinates] for pie, donut and radial bar — and everything
 * above this interface is shared between them: the layer model, the interaction
 * model, the animation clock, the theme, the overlay and the accessibility
 * layer. Nothing above this interface names an x or a y.
 */
interface CoordinateSystem {

    /** The region data is drawn inside. */
    val plotArea: ChartRect
}

/**
 * The axis that carries the domain — categories, numbers or instants.
 *
 * Two shapes rather than one because they answer different questions. A
 * continuous axis can be inverted: "which value is at this pixel", which is
 * what scrubbing needs. A category axis cannot, in general — the gap between
 * two bands belongs to no category — so it answers "which band is nearest"
 * instead. Papering over the difference with a single interface would mean one
 * of the two lying.
 */
sealed interface DomainAxis {

    /** The pixel coordinate the axis starts at. */
    val start: Float

    /** The pixel coordinate the axis ends at. */
    val end: Float

    /** Discrete bands, positioned by index. */
    class Categories(val scale: CategoryScale) : DomainAxis {
        override val start: Float get() = scale.rangeStart
        override val end: Float get() = scale.rangeEnd
    }

    /** A continuous run of numbers or instants, in domain units. */
    class Continuous(val scale: LinearScale) : DomainAxis {
        override val start: Float get() = scale.rangeStart
        override val end: Float get() = scale.rangeEnd
    }
}

/**
 * A Cartesian plot: one domain axis, one value axis, and an orientation that
 * says which is drawn horizontally.
 *
 * Every layer works in *domain* and *value* terms and never in x and y, which
 * is what makes a horizontal bar chart the same code as a vertical one with the
 * orientation flipped. [pointAt] is the single place the two are turned back
 * into screen coordinates.
 */
class CartesianCoordinates(
    override val plotArea: ChartRect,
    val domainAxis: DomainAxis,
    val valueScale: LinearScale,
    val orientation: ChartOrientation,
) : CoordinateSystem {

    /** The screen position of a point at [domainPosition] and [valuePosition]. */
    fun pointAt(domainPosition: Float, valuePosition: Float): ChartOffset =
        if (orientation.isVertical) {
            ChartOffset(domainPosition, valuePosition)
        } else {
            ChartOffset(valuePosition, domainPosition)
        }

    /** The domain-axis component of a screen position. */
    fun domainOf(point: ChartOffset): Float =
        if (orientation.isVertical) point.x else point.y

    /** The value-axis component of a screen position. */
    fun valueOf(point: ChartOffset): Float =
        if (orientation.isVertical) point.y else point.x

    /** The pixel position of [value] on the value axis. */
    fun positionOfValue(value: Double): Float = valueScale.scale(value)

    /** The value at pixel [position] on the value axis. */
    fun valueAt(position: Float): Double = valueScale.invert(position)

    /** The pixel position of a continuous domain value, or `null` on a band axis. */
    fun positionOfDomain(value: Double): Float? =
        (domainAxis as? DomainAxis.Continuous)?.scale?.scale(value)

    /** The continuous scale, when there is one — scrubbing needs to invert it. */
    val continuousDomain: InvertibleChartScale<Double>?
        get() = (domainAxis as? DomainAxis.Continuous)?.scale

    /** The category scale, when the domain is banded. */
    val categories: CategoryScale?
        get() = (domainAxis as? DomainAxis.Categories)?.scale

    /** The value-axis pixel position of zero — every bar's baseline. */
    val baseline: Float get() = valueScale.scale(0.0)
}
