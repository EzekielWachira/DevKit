package io.devkit.chartkit.scale

/**
 * Maps a data value onto a pixel position.
 *
 * The one abstraction every layer shares. A line layer, a bar layer and an axis
 * renderer all ask the same two scales where a value belongs, which is what
 * makes a combined chart possible at all: layers that each computed their own
 * pixel positions could never agree on where `40` is.
 *
 * ### Range direction
 *
 * [rangeStart] and [rangeEnd] are pixel coordinates and may be given in either
 * order. A vertical value axis is constructed *inverted* — `rangeStart` at the
 * bottom of the plot, `rangeEnd` at the top — because screen y grows downward
 * while values grow upward. Nothing else in the engine has to know that, and
 * horizontal bar charts get their orientation by swapping which scale is built
 * inverted rather than by a second set of geometry.
 */
interface ChartScale<T> {

    /** The pixel coordinate the low end of the domain maps to. */
    val rangeStart: Float

    /** The pixel coordinate the high end of the domain maps to. */
    val rangeEnd: Float

    /** The pixel position of [value]. */
    fun scale(value: T): Float
}

/**
 * A scale whose mapping can be run backwards.
 *
 * Continuous scales — numeric and time — can answer "which value is under this
 * pixel", which is what scrub selection and crosshair positioning need. A
 * category scale cannot, in general, because the space between two bands
 * belongs to no category; it exposes a nearest-band lookup instead.
 */
interface InvertibleChartScale<T> : ChartScale<T> {

    /** The value at pixel position [position]. */
    fun invert(position: Float): T
}
