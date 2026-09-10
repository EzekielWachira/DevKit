package io.devkit.chartkit.three

import io.devkit.chartkit.scale.LinearScale

/**
 * The box a 3D Cartesian chart's data occupies, in world units.
 *
 * ### Why the volume starts at the origin
 *
 * The box runs `0..width`, `0..height`, `0..depth` rather than being centred,
 * for the same reason a 2D plot's coordinates start at its top-left: it makes
 * "the fraction of the axis a value sits at" and "the world coordinate it sits
 * at" the same number scaled once, with no offset to get wrong. Centring the
 * scene is the camera's job, and [Chart3DCamera] already does it — it rotates
 * about the scene's own centre unless told otherwise.
 *
 * ### And why it is measured in plot pixels
 *
 * A world unit here is one pixel of the plot the chart was given. Nothing
 * downstream requires that — the projector fits whatever it is handed — but it
 * makes every number in the pipeline comparable to every other: a marker radius
 * in pixels, a plot width in pixels and a world coordinate are all the same
 * unit, so a reader of the code can tell at a glance whether a constant is
 * plausible. See [Column3DLayoutEngine], which measures its world the same way.
 *
 * @param depth how far the volume extends away from the reader. Stated
 *   separately rather than derived from the width, because depth is the one
 *   extent no data determines: an X/Y/Z scatter's three variables are equally
 *   important and its box should be roughly cubic, while a column chart's depth
 *   is a presentational choice about how solid the columns look.
 */
class Chart3DPlotBox(
    val width: Double,
    val height: Double,
    val depth: Double,
) {
    /** The box, as bounds the frame and the projector can use directly. */
    val volume: Bounds3D = Bounds3D(
        minX = 0.0,
        maxX = if (width.isFinite() && width > 0.0) width else MIN_EXTENT,
        minY = 0.0,
        maxY = if (height.isFinite() && height > 0.0) height else MIN_EXTENT,
        minZ = 0.0,
        maxZ = if (depth.isFinite() && depth > 0.0) depth else MIN_EXTENT,
    )

    private companion object {
        /** A plot with no extent still needs a box, so the frame has somewhere to be. */
        const val MIN_EXTENT = 1.0
    }
}

/**
 * Three independent scales and the box they map into: ChartKit's true 3D
 * Cartesian coordinate system.
 *
 * ```text
 * raw (x, y, z)
 *   → xScale.fraction(x)    yScale.fraction(y)    zScale.fraction(z)
 *   → world Point3D inside the plot box
 *   → Chart3DScene → Chart3DCamera → Chart3DProjection → screen
 * ```
 *
 * ### What makes the Z here different from a 3D column's depth
 *
 * A grouped 3D column chart also has geometry at several depths, and that depth
 * is **not** a variable. It says which stack a box belongs to; the reader is
 * meant to read a *height* and use the depth only to tell two piles apart, and
 * placing anything measurable there would put a quantity in the one direction
 * perspective distorts most. See [Column3DArrangement].
 *
 * Here, `z` is a third measurement with a third domain, a third set of ticks
 * and a third formatter. `Age → x`, `Income → y`, `Satisfaction → z` are three
 * answers to the same kind of question, and the coordinate system treats them
 * identically — which is exactly why this class holds three scales of one type
 * rather than two scales and a depth setting.
 *
 * ### Independent, and each derived from its own field
 *
 * The three scales share nothing. A dataset spanning `18..80` in x,
 * `20,000..200,000` in y and `0..100` in z produces three domains of wildly
 * different magnitudes and three separate normalisations, and the box they map
 * into is the only thing they have in common. A single combined range over all
 * three fields — which is what a naive implementation reaches for — would
 * flatten the two small variables to a sliver at the bottom of the volume.
 *
 * Plain Kotlin, like everything else in this package: the whole mapping from a
 * caller's three numbers to a world point is testable on the JVM.
 */
class Cartesian3DCoordinates(
    val xScale: LinearScale,
    val yScale: LinearScale,
    val zScale: LinearScale,
    val box: Chart3DPlotBox,
) {
    /** The world box the data is mapped into. */
    val volume: Bounds3D get() = box.volume

    /**
     * The world position of an observation, or `null` when it has none.
     *
     * `null` rather than a clamped point for a missing or unrepresentable
     * value — a negative number on a log axis, a `NaN` measurement. A scatter
     * point pinned to the corner of the volume because one of its three
     * coordinates was absent is an observation the reader will measure, and
     * they would be measuring ChartKit's fallback.
     */
    fun worldOf(x: Double, y: Double, z: Double): Point3D? {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
        val fx = xScale.fraction(x)
        val fy = yScale.fraction(y)
        val fz = zScale.fraction(z)
        if (fx.isNaN() || fy.isNaN() || fz.isNaN()) return null
        return worldOfFractions(fx, fy, fz)
    }

    /**
     * The world position of three `0..1` axis positions.
     *
     * The place a tick anchor, a grid line end or a selection guide comes from,
     * so all of them land on exactly the same surfaces the data does. Fractions
     * outside `0..1` are mapped rather than clamped: a fixed domain narrower
     * than the data has points outside the box, and drawing them where they are
     * is the truth — the plot's own frame shows that they left it.
     */
    fun worldOfFractions(x: Double, y: Double, z: Double): Point3D = Point3D(
        x = volume.minX + volume.width * x,
        y = volume.minY + volume.height * y,
        z = volume.minZ + volume.depth * z,
    )

    /** Where a value sits on each axis, in `0..1`. `NaN` when unrepresentable. */
    fun xFraction(value: Double): Double = xScale.fraction(value)

    fun yFraction(value: Double): Double = yScale.fraction(value)

    fun zFraction(value: Double): Double = zScale.fraction(value)

    override fun toString(): String =
        "Cartesian3DCoordinates(x=${xScale.domain}, y=${yScale.domain}, z=${zScale.domain})"
}
