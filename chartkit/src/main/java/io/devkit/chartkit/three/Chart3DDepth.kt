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

/**
 * How deep the whole 3D plot volume is.
 *
 * ### Not the same thing as [Chart3DDepth], and the distinction is the point
 *
 * Scene depth is the *room* — the extent of the box the chart is drawn inside,
 * what the frame's floor and walls enclose, and what the camera is fitted to.
 * [Chart3DDepth] is the *thickness of one shape* standing in that room. They
 * are related — a column cannot be deeper than the volume it stands in — and
 * they are not the same number, which is why §10 asks for both and why
 * collapsing them into one `depth` property is the mistake to avoid.
 *
 * A concrete reading: a grouped 3D column chart with two depth rows has a scene
 * a little over twice a column's depth. Doubling the *column* depth makes each
 * box thicker and the rows thicker with them. Doubling the *scene* depth leaves
 * the boxes exactly as they were and pushes the back row further away, which
 * strengthens the perspective cue without changing what any column looks like.
 *
 * Stated in plot pixels for [Absolute], on the same rule the rest of the 3D
 * column world uses: a column's world is measured in plot pixels, so a depth in
 * plot pixels needs no conversion and cannot disagree with the width beside it.
 */
sealed interface Chart3DSceneDepth {

    /**
     * Exactly the depth the content occupies. The default.
     *
     * A chart nobody configured therefore looks identical to one from before
     * scene depth existed, which is the property that made this addition safe.
     */
    data object Auto : Chart3DSceneDepth

    /** A multiple of the depth the content occupies. */
    data class Relative(val fraction: Double) : Chart3DSceneDepth {
        init {
            if (!fraction.isFinite() || fraction <= 0.0) {
                throw Chart3DException(
                    "Relative scene depth must be a positive, finite multiple of the depth " +
                        "the content occupies, was $fraction",
                )
            }
        }
    }

    /** An explicit volume depth in plot pixels. */
    data class Absolute(val pixels: Float) : Chart3DSceneDepth {
        init {
            if (!pixels.isFinite() || pixels <= 0f) {
                throw Chart3DException(
                    "Absolute scene depth must be a positive, finite number of pixels, " +
                        "was $pixels",
                )
            }
        }
    }

    companion object {

        /**
         * The volume depth this policy asks for.
         *
         * @param reference what [Auto] means and what [Relative] is a multiple
         *   of — the depth the content naturally occupies.
         * @param minimum the shallowest volume that is still legal.
         *
         *   The two differ, and which is passed is a real distinction between
         *   the chart types. A **column** chart's minimum *is* its content: a
         *   volume shallower than the columns standing in it would push the
         *   back row through the back wall, which reads as a rendering fault
         *   rather than as a configuration one, so columns leave this at the
         *   reference. A **scatter**'s content is a cloud of points with no
         *   thickness of its own — every point is inside the box by
         *   construction, because the box is what the Z scale maps into — so a
         *   caller asking for a flatter volume is asking for a legitimate view
         *   of the same data and gets it.
         */
        fun resolve(
            policy: Chart3DSceneDepth,
            reference: Double,
            minimum: Double = reference,
        ): Double {
            val base = if (reference.isFinite() && reference > 0.0) reference else 1.0
            val floor = if (minimum.isFinite() && minimum > 0.0) minimum else MIN_DEPTH
            val asked = when (policy) {
                Auto -> base
                is Relative -> base * policy.fraction
                is Absolute -> policy.pixels.toDouble()
            }
            return if (asked.isFinite() && asked > floor) asked else floor
        }

        /** A volume with no depth at all is a flat picture, not a 3D one. */
        private const val MIN_DEPTH = 1.0
    }
}
