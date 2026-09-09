package io.devkit.chartkit.three

/**
 * How camera-space geometry becomes a flat picture.
 *
 * ### An abstraction, not a flag
 *
 * The column layer never divides by a depth. It hands points to whichever
 * projection the chart was configured with and receives 2D positions back,
 * which is what makes orthographic a configuration rather than a second
 * renderer — and what will make a 3D scatter or a 3D surface get both for free.
 *
 * ### Camera space
 *
 * Both implementations expect points already transformed by
 * [Chart3DCamera.viewMatrix]: the camera sits at the origin looking along `+z`,
 * so a point's `z` **is** its distance in front of the camera and a point at
 * `z = distance` sits on the plane through the scene's centre.
 */
sealed interface Chart3DProjection {

    /**
     * The projected position of a camera-space point, or `null` when the point
     * cannot be projected.
     *
     * `null` means "behind the camera or too close to it", which has no finite
     * projection. Returning a very large number instead is how a chart ends up
     * drawing a column several screens wide when a reader pushes the camera
     * distance down; the projector drops the face instead.
     *
     * @param distance the camera distance the scene was built at.
     */
    fun project(point: Point3D, distance: Double): Projected2D?

    /**
     * True perspective: things further away are smaller.
     *
     * ```
     *   scale = distance / z          (z is the point's depth from the camera)
     * ```
     *
     * A real divide, not a skew. Offsetting `x` and `y` by a multiple of `z` —
     * the "2.5D" shortcut — produces parallel edges that never converge, so the
     * far row of a chart is exactly as wide as the near one while pretending to
     * be behind it. That is not a cheaper perspective; it is orthographic with
     * the depth cue removed, and it makes stacked columns of equal height look
     * unequal at the edges of the plot.
     *
     * @param nearPlane the fraction of the camera distance in front of which
     *   geometry is dropped. Small, but never zero: `z` approaching zero sends
     *   the scale to infinity, and one vertex doing that takes a whole face
     *   with it.
     */
    data class Perspective(val nearPlane: Double = DEFAULT_NEAR_PLANE) : Chart3DProjection {

        init {
            require(nearPlane.isFinite() && nearPlane > 0.0 && nearPlane < 1.0) {
                "The near plane must be a fraction of the camera distance in (0, 1), " +
                    "was $nearPlane"
            }
        }

        override fun project(point: Point3D, distance: Double): Projected2D? {
            if (!point.isFinite || !distance.isFinite() || distance <= 0.0) return null
            val near = distance * nearPlane
            if (point.z <= near) return null
            val scale = distance / point.z
            val x = point.x * scale
            val y = point.y * scale
            if (!x.isFinite() || !y.isFinite()) return null
            return Projected2D(x, y)
        }
    }

    /**
     * Parallel projection: depth changes position but never size.
     *
     * The analytically honest option, and the reason it is a first-class mode
     * rather than a debugging aid. Under perspective, two columns of the same
     * height at different depths are drawn at different heights, so a reader
     * comparing them across the depth axis is reading a distortion. Under
     * orthographic they are drawn identically, and depth carries only grouping.
     *
     * The cost is that the picture reads as flatter, and that two columns
     * exactly in line can coincide. Depth ordering still resolves which is in
     * front; see [SceneProjector].
     */
    data object Orthographic : Chart3DProjection {
        override fun project(point: Point3D, distance: Double): Projected2D? {
            if (!point.isFinite) return null
            return Projected2D(point.x, point.y)
        }
    }

    companion object {
        /** Perspective, at the default near plane. */
        val Default: Chart3DProjection = Perspective()

        /** Far enough in front of the camera to survive an edge-on view. */
        const val DEFAULT_NEAR_PLANE: Double = 0.05
    }
}
