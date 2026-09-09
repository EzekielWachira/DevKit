package io.devkit.chartkit.three

/**
 * How far a 3D shape extends away from the reader.
 *
 * ### Relative by default, and to the shape's own size
 *
 * A depth of 24dp is half the chart on a phone and a sliver on a tablet, and
 * the cue a reader actually uses is the *ratio* of depth to width rather than
 * either alone. So the ordinary way to state a depth is as a fraction, and what
 * it is a fraction *of* is whatever the shape's natural unit is: a column's
 * footprint width, a pie's outer radius. Each consumer says so where it
 * resolves [Auto].
 *
 * The same type serves columns and radial charts because the decision is the
 * same one — the shapes differ, the question does not — and a second copy would
 * have been the first place the two drifted apart.
 *
 * What each case *means* is resolved by whichever engine is drawing, and
 * deliberately not by a method here. [Absolute] is stated in plot pixels; a
 * column's world is measured in plot pixels and takes it as it stands, while a
 * radial chart's world is measured in radii and has to divide it by the radius
 * it will be drawn at. One shared resolver would have had to be right for both
 * and could only be right for one.
 */
sealed interface Chart3DDepth {

    /** Derived from the shape's own size. The default. */
    data object Auto : Chart3DDepth

    /** A multiple of the shape's natural unit. */
    data class Relative(val fraction: Double) : Chart3DDepth {
        init {
            if (!fraction.isFinite() || fraction <= 0.0) {
                throw Chart3DException(
                    "Relative depth must be a positive, finite fraction of the shape's own " +
                        "size, was $fraction",
                )
            }
        }
    }

    /** An explicit extent in plot pixels, for a chart that must match another exactly. */
    data class Absolute(val pixels: Float) : Chart3DDepth {
        init {
            if (!pixels.isFinite() || pixels <= 0f) {
                throw Chart3DException(
                    "Absolute depth must be a positive, finite number of pixels, was $pixels",
                )
            }
        }
    }
}
