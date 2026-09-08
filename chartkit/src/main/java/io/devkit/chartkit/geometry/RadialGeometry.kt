package io.devkit.chartkit.geometry

import kotlin.math.abs

/**
 * The arithmetic behind concentric radial bars, separated from their drawing.
 *
 * Extracted for the same reason bar stacking is: deciding which ring a finger
 * landed on, and how far a value sweeps, is arithmetic — and arithmetic tested
 * against a canvas is arithmetic tested badly. Everything here is plain Kotlin
 * and runs on the JVM.
 */
object RadialGeometry {

    /**
     * A value's progress through `[minValue, maxValue]`, in `0..1`.
     *
     * Clamped: a bar cannot sweep past its own track, so the choice for an
     * out-of-range value is between a full ring and no ring, and a full ring at
     * least says "at or above the maximum". A caller who would rather be told
     * uses [RadialRangePolicy.Reject].
     *
     * A range of zero width yields `0` rather than dividing by it.
     */
    fun progress(
        value: Double?,
        minValue: Double,
        maxValue: Double,
        policy: RadialRangePolicy = RadialRangePolicy.Clamp,
    ): Double {
        if (value == null || !value.isFinite()) return 0.0
        if (policy == RadialRangePolicy.Reject && (value < minValue || value > maxValue)) {
            throw IllegalArgumentException(
                "$value is outside the radial bar range [$minValue, $maxValue]. Widen the " +
                    "range, or use RadialRangePolicy.Clamp.",
            )
        }
        val span = maxValue - minValue
        if (span <= ChartMath.EPSILON) return 0.0
        return ChartMath.clamp((value - minValue) / span, 0.0, 1.0)
    }

    /**
     * The centre-line radius of track [index], laid out from the outside in.
     *
     * Outermost first, because that is the reading order of a set of nested
     * rings: the first metric in the list is the one the eye lands on.
     */
    fun trackRadius(
        index: Int,
        outerRadius: Float,
        thickness: Float,
        spacing: Float,
    ): Float = outerRadius - thickness / 2f - index * (thickness + spacing)

    /**
     * Which track a point at [radius] falls on, or `-1` between tracks.
     *
     * The gap between two rings belongs to neither. Snapping it to the nearer
     * one would make a tap in the visible space between bars select something,
     * which is exactly the behaviour that makes a chart feel imprecise.
     */
    fun trackAt(
        radius: Float,
        trackCount: Int,
        outerRadius: Float,
        thickness: Float,
        spacing: Float,
    ): Int {
        for (index in 0 until trackCount) {
            val centre = trackRadius(index, outerRadius, thickness, spacing)
            if (centre <= 0f) continue
            if (abs(radius - centre) <= thickness / 2f) return index
        }
        return -1
    }

    /**
     * How many tracks fit before their radii run past the centre.
     *
     * A chart given more metrics than it has room for draws the ones that fit
     * rather than drawing arcs through its own middle.
     */
    fun visibleTrackCount(
        trackCount: Int,
        outerRadius: Float,
        thickness: Float,
        spacing: Float,
    ): Int {
        var visible = 0
        for (index in 0 until trackCount) {
            if (trackRadius(index, outerRadius, thickness, spacing) <= thickness / 2f) break
            visible++
        }
        return visible
    }
}

/** What a radial bar does with a value outside its configured range. */
enum class RadialRangePolicy {

    /** Draw it at the nearest end of the range. The default. */
    Clamp,

    /** Throw, for a caller who would rather find out at the call site. */
    Reject,
}
