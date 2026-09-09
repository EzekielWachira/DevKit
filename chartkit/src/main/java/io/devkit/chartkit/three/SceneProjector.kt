package io.devkit.chartkit.three

import io.devkit.chartkit.geometry.ChartRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What one projection pass did, for development and profiling.
 *
 * Debug-only by intent: nothing in ChartKit's own UI reads these, and no chart
 * draws them. They exist because 3D failures are silent — a face wound the
 * wrong way, a scene that fitted to nothing, a sort that put the back wall in
 * front — and counting is how you find out which, without a debugger attached
 * to a device.
 */
data class Chart3DDiagnostics(
    val objectCount: Int = 0,
    val faceCount: Int = 0,
    val culledFaces: Int = 0,
    val degenerateFaces: Int = 0,
    val clippedFaces: Int = 0,
    val renderedFaces: Int = 0,
    /** The uniform scale the fit chose, in screen pixels per scene unit. */
    val fitScale: Double = 0.0,
    /**
     * How many angular segments a curved surface was approximated with, summed
     * over the scene.
     *
     * Zero for a scene of flat-sided shapes, which is every 3D column chart.
     * Filled in by whichever layer did the tessellating, because the projector
     * cannot tell an intrinsically flat quad from one segment of an arc — and
     * this is the number to look at first when a pie is either visibly faceted
     * or unexpectedly slow.
     */
    val tessellationSegments: Int = 0,
)

/**
 * The world → camera → projection → screen pipeline, applied once per frame.
 *
 * ### The stages, and why they are stages
 *
 * ```
 * world geometry
 *   → camera transform      (Matrix4, composed once)
 *   → projection            (Chart3DProjection: perspective or parallel)
 *   → uniform fit           (one scale and one offset for the whole scene)
 *   → back-face culling     (normals, in camera space)
 *   → depth sort            (camera-space centroid, far to near)
 *   → lighting              (per face, from its camera-space normal)
 * ```
 *
 * Each stage is a function of the one before it, and each is separately
 * wrong-able. Collapsed into a single draw loop — the shape this class exists
 * to avoid — a chart whose far columns are drawn in front of its near ones
 * gives no clue whether the camera, the sort or the winding is at fault, and
 * none of the three can be tested without a screen.
 *
 * ### Reuse across a rotation
 *
 * The projector holds the composed matrix and the fit and nothing else, so a
 * camera change rebuilds *this* and leaves the scene's world geometry — which
 * came from the stack engine and the scales — completely untouched.
 */
class Chart3DProjector private constructor(
    private val view: Matrix4,
    private val projection: Chart3DProjection,
    private val distance: Double,
    private val lighting: Chart3DLighting,
    /** Screen pixels per projected scene unit. */
    val scale: Double,
    private val originX: Double,
    private val originY: Double,
) {

    /** True when every viewing ray is parallel, which changes the culling test. */
    private val parallel: Boolean = projection is Chart3DProjection.Orthographic

    /** [point] in camera space: the eye at the origin, looking along `+z`. */
    fun toCamera(point: Point3D): Point3D = view.transformPoint(point)

    /** A world direction — a face normal — in camera space. */
    fun directionToCamera(vector: Vector3D): Vector3D = view.transformVector(vector)

    /**
     * [point] as a screen position in the plot's pixel space, or `null` when it
     * cannot be projected.
     *
     * The y flip happens here and nowhere else: world `y` grows upward because
     * values do, and screen `y` grows downward because canvases do. One
     * conversion point means a chart cannot be half-flipped.
     */
    fun toScreen(point: Point3D): Projected2D? {
        val camera = toCamera(point)
        val projected = projection.project(camera, distance) ?: return null
        val x = originX + projected.x * scale
        val y = originY - projected.y * scale
        if (!x.isFinite() || !y.isFinite()) return null
        return Projected2D(x, y)
    }

    /**
     * Every visible face of [scene], back to front, lit and ready to draw.
     *
     * The returned order **is** the draw order. A caller that reorders it — to
     * group by colour, say — reintroduces the overlap bug the sort exists to
     * prevent.
     */
    fun project(scene: Chart3DScene): Chart3DProjectionResult {
        val faces = ArrayList<ProjectedFace>(scene.objects.size * TYPICAL_FACES_PER_OBJECT)
        var total = 0
        var culled = 0
        var degenerate = 0
        var clipped = 0

        scene.objects.forEachIndexed { objectIndex, obj ->
            obj.geometry.faces.forEachIndexed { faceIndex, face ->
                total++
                if (face.normal.isZero) {
                    degenerate++
                    return@forEachIndexed
                }
                val cameraNormal = directionToCamera(face.normal)
                val cameraCentroid = toCamera(face.centroid)
                if (!isFrontFacing(cameraNormal, cameraCentroid, parallel)) {
                    culled++
                    return@forEachIndexed
                }
                val points = ArrayList<Projected2D>(face.vertices.size)
                var projectable = true
                for (vertex in face.vertices) {
                    val screen = toScreen(vertex)
                    if (screen == null) {
                        projectable = false
                        break
                    }
                    points += screen
                }
                if (!projectable) {
                    clipped++
                    return@forEachIndexed
                }
                val projectedFace = ProjectedFace(
                    points = points,
                    depth = cameraCentroid.z,
                    brightness = lighting.brightnessOf(cameraNormal),
                    side = face.side,
                    objectIndex = objectIndex,
                    faceIndex = faceIndex,
                    key = obj.key,
                )
                // A face seen exactly edge-on projects to a sliver of no area.
                // Drawing it costs a path and paints a hairline across the
                // chart where a surface should have disappeared.
                if (abs(projectedFace.doubleSignedArea()) < MIN_DOUBLE_AREA) {
                    degenerate++
                    return@forEachIndexed
                }
                faces += projectedFace
            }
        }

        val sorted = faces.sortedWith(DEPTH_ORDER)
        return Chart3DProjectionResult(
            faces = sorted,
            diagnostics = Chart3DDiagnostics(
                objectCount = scene.objects.size,
                faceCount = total,
                culledFaces = culled,
                degenerateFaces = degenerate,
                clippedFaces = clipped,
                renderedFaces = sorted.size,
                fitScale = scale,
            ),
        )
    }

    companion object {

        /** A cuboid has six faces; a tessellated sector has more. Only a capacity hint. */
        private const val TYPICAL_FACES_PER_OBJECT = 6

        /** Twice the area of the smallest polygon worth painting, in square pixels. */
        private const val MIN_DOUBLE_AREA = 0.25

        /**
         * Back to front, then deterministic.
         *
         * The tie-breakers are the point. Two coplanar faces — the floor of a
         * frame and the bottom of a column standing on it — have identical
         * centroid depths, and a sort that left their order to the comparator's
         * whim would swap them between frames as the camera moved by a
         * thousandth of a degree. The visible result is a flickering seam,
         * which reads as a rendering fault rather than as an unstable sort.
         */
        private val DEPTH_ORDER: Comparator<ProjectedFace> =
            compareByDescending<ProjectedFace> { it.depth }
                .thenBy { it.objectIndex }
                .thenBy { it.faceIndex }

        /**
         * Whether a face points toward the camera.
         *
         * The view direction is not one vector. Under perspective the eye is a
         * point, so the direction differs per face and is taken from the eye to
         * the face's own centroid; under a parallel projection every ray is
         * `+z` and using the centroid instead would cull the wrong faces at the
         * edges of a wide plot, where the centroid direction diverges most from
         * the ray direction. One test, two view vectors, chosen by which
         * projection is in use.
         */
        internal fun isFrontFacing(
            normal: Vector3D,
            centroid: Point3D,
            parallel: Boolean,
        ): Boolean {
            val view = if (parallel) {
                Vector3D.UnitZ
            } else {
                Vector3D(centroid.x, centroid.y, centroid.z).normalized()
            }
            if (view.isZero) return true
            return (normal dot view) < 0.0
        }

        /**
         * A projector that fits [scene] into [bounds].
         *
         * Two passes, and they cannot be merged: the scale depends on the
         * projected extent, and the projected extent under perspective depends
         * on nothing but the camera — so the corners are projected once at unit
         * scale, measured, and the whole scene is then placed with a single
         * uniform scale and offset.
         *
         * **Uniform**, not per-axis. Fitting width and height independently
         * would stretch the scene, and a stretched cube is no longer a cube:
         * the reader's depth cue — parallel edges converging at a consistent
         * rate — is exactly what non-uniform scaling destroys.
         *
         * @param reserve pixels held back on every side, for the axis labels a
         *   3D chart writes outside its own frame.
         * @param fitTo the points the fit is measured against, or `null` for the
         *   scene bounds' eight corners.
         *
         *   The corners are right for a scene of boxes, whose geometry reaches
         *   them. They are wrong for a scene of round things: the corners of the
         *   box around a disc are outside the disc, so fitting to them leaves a
         *   pie noticeably smaller than the space it was given, and more so the
         *   further it is tilted. A caller that knows its own silhouette passes
         *   it, and the fit is then exact rather than conservative.
         */
        fun of(
            scene: Chart3DScene,
            camera: Chart3DCamera,
            projection: Chart3DProjection,
            bounds: ChartRect,
            reserve: Chart3DReserve = Chart3DReserve.None,
            fitTo: List<Point3D>? = null,
        ): Chart3DProjector? {
            val sceneBounds = scene.bounds ?: return null
            if (bounds.isEmpty) return null
            // The camera's distance is stated in scene widths, so it is
            // resolved against the largest span the scene actually occupies.
            // Anything else makes the perspective depend on the chart's size in
            // pixels, and the same configuration looks different on a phone and
            // on a tablet.
            val sceneUnit = maxOf(
                sceneBounds.width,
                sceneBounds.height,
                sceneBounds.depth,
            ).takeIf { it.isFinite() && it > 0.0 } ?: 1.0
            val distance = camera.distanceIn(sceneUnit)
            val view = camera.viewMatrix(sceneBounds.center, sceneUnit)

            val target = ChartRect(
                left = bounds.left + reserve.left,
                top = bounds.top + reserve.top,
                right = bounds.right - reserve.right,
                bottom = bounds.bottom - reserve.bottom,
            )
            if (target.isEmpty) return null

            var minX = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            var seen = false
            for (corner in fitTo ?: sceneBounds.corners()) {
                val projected = projection.project(view.transformPoint(corner), distance)
                    ?: continue
                if (!projected.isFinite) continue
                seen = true
                minX = min(minX, projected.x); maxX = max(maxX, projected.x)
                minY = min(minY, projected.y); maxY = max(maxY, projected.y)
            }
            if (!seen) return null

            val spanX = maxX - minX
            val spanY = maxY - minY
            // An edge-on view collapses one span to nothing. Falling back to
            // the other keeps the chart at a sane size instead of scaling by
            // infinity; a scene with neither span is not drawable at all.
            val scaleX = if (spanX > SPAN_EPSILON) target.width / spanX else Double.MAX_VALUE
            val scaleY = if (spanY > SPAN_EPSILON) target.height / spanY else Double.MAX_VALUE
            val scale = min(scaleX, scaleY)
            if (!scale.isFinite() || scale <= 0.0) return null

            val projectedCenterX = (minX + maxX) / 2.0
            val projectedCenterY = (minY + maxY) / 2.0
            return Chart3DProjector(
                view = view,
                projection = projection,
                distance = distance,
                lighting = scene.lighting,
                scale = scale,
                originX = target.centerX - projectedCenterX * scale,
                // Plus, not minus: the y flip in `toScreen` means a larger
                // world y must produce a smaller screen y, and the origin has
                // to move the other way to compensate.
                originY = target.centerY + projectedCenterY * scale,
            )
        }

        private const val SPAN_EPSILON = 1e-9
    }
}

/** Room held back on each side of the plot for the labels a 3D chart writes. */
data class Chart3DReserve(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    companion object {
        val None: Chart3DReserve = Chart3DReserve()
    }
}

/** The faces to draw, and what the pass cost. */
data class Chart3DProjectionResult(
    val faces: List<ProjectedFace>,
    val diagnostics: Chart3DDiagnostics,
)
