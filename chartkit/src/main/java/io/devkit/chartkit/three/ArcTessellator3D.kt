package io.devkit.chartkit.three

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns arcs into the flat polygons a painter's-algorithm renderer can draw.
 *
 * ### Why a curve has to become quads at all
 *
 * Every stage after the scene works on planar convex polygons: a face has one
 * normal, one centroid depth and one brightness. A cylinder wall has none of
 * those — its normal turns through the sweep, so it is lit unevenly, and its
 * near and far edges are at different depths, so a single depth for the whole
 * wall would sort it wrongly against a neighbouring slice. Cutting it into
 * angular strips gives each strip a genuine normal and a genuine depth, which
 * is what makes the shading gradient across a pie's rim correct rather than
 * painted on.
 *
 * ### Segment count is derived, not configured
 *
 * The caller states a *quality* and the tessellator works out the count from the
 * radius the arc is actually drawn at. A fixed count is wrong at both ends: 24
 * segments is visibly faceted on a tablet-sized pie and fifteen wasted faces on
 * a sparkline-sized one. See [segmentsFor].
 *
 * Plain Kotlin, like the rest of this package: the sampling, the counts and the
 * bounds are all testable on the JVM.
 */
object ArcTessellator3D {

    /** Never fewer, so even a sliver has an interior. */
    const val MIN_SEGMENTS: Int = 2

    /** Never more, whatever the tolerance asks for. */
    const val MAX_SEGMENTS: Int = 96

    /**
     * How many straight segments an arc of [sweepDegrees] at [radius] needs.
     *
     * ### The arithmetic
     *
     * A chord across an angle `d` falls short of its arc by the sagitta
     * `r(1 − cos(d/2))`. Setting that to the quality's tolerance `t` and
     * solving for `d` gives the largest step that stays within it, and the
     * segment count is the sweep divided by that step, rounded up.
     *
     * The radius is in **screen pixels**, so the answer follows the chart's
     * size: the same pie on a phone and on a tablet is tessellated differently
     * and looks equally smooth on both, which is the whole reason the count is
     * computed rather than chosen.
     *
     * @param radius the arc's radius in screen pixels. A non-positive or
     *   non-finite radius yields [MIN_SEGMENTS] rather than an error: a chart
     *   measured at zero size is a transient layout state, not a bug.
     */
    fun segmentsFor(
        sweepDegrees: Double,
        radius: Double,
        quality: Chart3DQuality = Chart3DQuality.Auto,
    ): Int {
        val sweep = abs(sweepDegrees)
        if (sweep <= 0.0 || !sweep.isFinite()) return MIN_SEGMENTS
        if (!radius.isFinite() || radius <= 0.0) return MIN_SEGMENTS
        val tolerance = quality.tolerancePx
        // A tolerance at or beyond the radius means any chord is acceptable;
        // acos would be out of domain, so the coarsest arc is the answer.
        val ratio = 1.0 - tolerance / radius
        if (ratio <= -1.0) return MIN_SEGMENTS
        val step = Math.toDegrees(2.0 * acos(ratio.coerceIn(-1.0, 1.0)))
        if (!step.isFinite() || step <= 0.0) return MAX_SEGMENTS
        val count = ceil(sweep / step).toInt()
        return count.coerceIn(MIN_SEGMENTS, MAX_SEGMENTS)
    }

    /**
     * The [segments] + 1 angles sampled across the arc, endpoints included.
     *
     * Endpoints exactly rather than approximately: a sector's radial walls are
     * built at its first and last angle, and an arc that stopped a hundredth of
     * a degree short would leave a hairline of background between the wall and
     * the rim — visible, and impossible to attribute to rounding when you are
     * looking at it.
     */
    fun anglesFor(startDegrees: Double, sweepDegrees: Double, segments: Int): DoubleArray {
        val count = segments.coerceAtLeast(1)
        val step = sweepDegrees / count
        return DoubleArray(count + 1) { index ->
            if (index == count) startDegrees + sweepDegrees else startDegrees + step * index
        }
    }

    /**
     * The point at [radius] and [angleDegrees] on the horizontal plane `y = height`.
     *
     * ### The disc is horizontal, and that is the whole design
     *
     * A radial chart's disc lies in the **x–z plane** — flat, on the same floor
     * a 3D column stands on — and is extruded **upward** along `y`. It is a
     * plate on a table, and the camera's existing meaning ("positive pitch
     * lifts the reader above the scene") then does exactly the right thing to
     * it: the disc foreshortens into an ellipse and the near edge of its rim
     * appears *below* the surface, which is what a solid disc looks like from
     * above.
     *
     * The alternative — a disc standing upright in the x–y plane, extruded away
     * from the reader — was tried first and is wrong. It foreshortens the same
     * way and looks superficially similar, but the extrusion then runs *away*
     * rather than *down*, so the rim appears **above** the surface: an
     * underside view, as though the reader were beneath the plate looking up.
     *
     * ### The angle convention, and why it is the chart's
     *
     * Zero degrees is at **twelve o'clock** and angles increase **clockwise**,
     * exactly as in [io.devkit.chartkit.geometry.PolarGeometry] — so a slice
     * that starts at 0° starts at the top of a 3D pie for the same reason it
     * does on a flat one, and the 2D slice engine's output can be handed
     * straight to this without a conversion nobody would remember to make.
     *
     * On a horizontal disc seen from in front and above, "twelve o'clock" is
     * the far side, which is `+z`; three o'clock is `+x`. So the mapping is
     * `(sin θ, height, cos θ)`, and going from 0° to 90° moves from the top of
     * the picture to its right — clockwise, as promised.
     */
    fun pointAt(radius: Double, angleDegrees: Double, height: Double): Point3D {
        val radians = Math.toRadians(angleDegrees)
        return Point3D(x = radius * sin(radians), y = height, z = radius * cos(radians))
    }

    /**
     * The largest angular step, in degrees, that stays within [quality] at
     * [radius]. Exposed for tests and for diagnostics.
     */
    internal fun stepFor(radius: Double, quality: Chart3DQuality): Double {
        if (!radius.isFinite() || radius <= 0.0) return FULL_CIRCLE
        val ratio = (1.0 - quality.tolerancePx / radius).coerceIn(-1.0, 1.0)
        return Math.toDegrees(2.0 * acos(ratio))
    }

    /**
     * How far the chord across [stepDegrees] at [radius] falls short of the arc.
     *
     * The quantity [segmentsFor] solves for, exposed so a test can assert the
     * error the chosen count actually produces rather than the count itself.
     */
    internal fun sagitta(radius: Double, stepDegrees: Double): Double =
        radius * (1.0 - cos(Math.toRadians(stepDegrees) / 2.0))

    /** Half the chord length across [stepDegrees] at [radius]. For tests. */
    internal fun halfChord(radius: Double, stepDegrees: Double): Double =
        radius * sqrt(2.0 * (1.0 - cos(Math.toRadians(stepDegrees)))) / 2.0

    private const val FULL_CIRCLE = 360.0
}
