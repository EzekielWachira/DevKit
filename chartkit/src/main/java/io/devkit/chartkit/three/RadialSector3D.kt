package io.devkit.chartkit.three

/**
 * An extruded radial sector: a pie slice with thickness, or a donut segment.
 *
 * ### One primitive, not two
 *
 * A pie slice and a donut segment differ by one number. Building `Sector3D` and
 * `AnnularSector3D` as separate types would have duplicated the cap
 * tessellation, both radial walls, the outer wall and every winding decision
 * below, and the two copies would have disagreed the first time either was
 * corrected — so there is one shape, and `innerRadius = 0` is a pie. The two
 * names still exist, as [Sector3D] and [AnnularSector3D], because they are what
 * the geometry is *called*; they are named constructors over this.
 *
 * ### A plate on a table
 *
 * The disc lies in the **x–z plane** — flat, on the same floor a 3D column
 * stands on — and is extruded **upward** along `y`. That is not a detail: it is
 * what makes the camera's existing meaning correct without a single sign flip.
 * Positive pitch lifts the reader above the scene, the disc foreshortens into
 * an ellipse, and the near edge of its rim appears *below* the surface, which
 * is what a solid disc looks like from above.
 *
 * Standing the disc upright in the x–y plane and extruding it away from the
 * reader gives a silhouette of almost the same proportions, and is wrong: the
 * extrusion then runs away rather than down, so the rim appears **above** the
 * surface and the picture reads as the underside of the plate.
 *
 * Angles follow ChartKit's own convention: zero at twelve o'clock, increasing
 * clockwise. See [ArcTessellator3D.pointAt].
 *
 * ### The faces
 *
 * ```
 *   top cap      (normal +y)     tessellated, one quad per angular step
 *   bottom cap   (normal −y)     the same, wound the other way
 *   outer wall   (radially out)  tessellated, one quad per angular step
 *   inner wall   (radially in)   the same, absent when innerRadius is zero
 *   start wall   (θ − 90°)       one quad, absent on a full circle
 *   end wall     (θ + 90°)       one quad, absent on a full circle
 * ```
 *
 * The caps are tessellated as well as the walls, which is not obvious and is
 * necessary twice over. A whole cap is not convex past a 180° sweep, and
 * [Chart3DHitTest] tests convex polygons; and a whole cap has one centroid
 * depth, so a large slice would sort as if all of it were at its middle and
 * would swap in front of a neighbour it actually passes behind.
 *
 * @param innerRadius zero for a pie, positive for a donut. In world units.
 * @param startAngle the first edge, in chart degrees.
 * @param sweepAngle how far it sweeps, clockwise. Positive; a counter-clockwise
 *   chart states the earlier edge as [startAngle] and the geometry is identical.
 * @param baseY the hidden underside of the disc. Below [topY].
 * @param topY the surface the reader looks at.
 * @param offsetX how far the whole sector is displaced along x, and [offsetZ]
 *   along z — the explode, in the disc's own plane. Applied to every vertex of
 *   every face, so a displaced slice is displaced geometry rather than a
 *   drawing offset that hit testing would miss.
 * @param segments how many angular steps the arcs are cut into. From
 *   [ArcTessellator3D.segmentsFor]; passed in rather than computed here so that
 *   an animating slice keeps a stable topology while its sweep changes.
 */
@Suppress("LongParameterList")
class RadialSector3D(
    val innerRadius: Double,
    val outerRadius: Double,
    val startAngle: Double,
    val sweepAngle: Double,
    val baseY: Double,
    val topY: Double,
    val offsetX: Double = 0.0,
    val offsetZ: Double = 0.0,
    val segments: Int = ArcTessellator3D.MIN_SEGMENTS,
    override val key: Chart3DKey? = null,
) : Chart3DGeometry {

    init {
        if (!innerRadius.isFinite() || innerRadius < 0.0) {
            throw Chart3DException(
                "A radial sector's inner radius must be a finite, non-negative number, " +
                    "was $innerRadius",
            )
        }
        if (!outerRadius.isFinite() || outerRadius < innerRadius) {
            throw Chart3DException(
                "A radial sector's outer radius ($outerRadius) must be finite and at least " +
                    "its inner radius ($innerRadius)",
            )
        }
        if (!startAngle.isFinite() || !sweepAngle.isFinite() || sweepAngle < 0.0) {
            throw Chart3DException(
                "A radial sector needs a finite start angle and a non-negative sweep, was " +
                    "start=$startAngle sweep=$sweepAngle",
            )
        }
        if (!baseY.isFinite() || !topY.isFinite() || topY < baseY) {
            throw Chart3DException(
                "A radial sector's top ($topY) must be finite and no lower than its " +
                    "base ($baseY)",
            )
        }
    }

    /** True when the sector closes on itself and has no radial walls. */
    val isFullCircle: Boolean = sweepAngle >= FULL_CIRCLE - ANGLE_EPSILON

    /** True when the shape has a hole, and therefore an inner wall. */
    val isAnnular: Boolean = innerRadius > RADIUS_EPSILON

    /** The angle halfway through the sweep: where a label or an explode points. */
    val midAngle: Double = startAngle + sweepAngle / 2.0

    /** How thick the disc is. */
    val depth: Double = topY - baseY

    override val isDegenerate: Boolean
        get() = sweepAngle <= ANGLE_EPSILON ||
            outerRadius - innerRadius <= RADIUS_EPSILON ||
            depth <= 0.0

    private val angles: DoubleArray = ArcTessellator3D.anglesFor(
        startDegrees = startAngle,
        sweepDegrees = if (isFullCircle) FULL_CIRCLE else sweepAngle,
        segments = segments.coerceAtLeast(1),
    )

    override val faces: List<Face3D> = buildFaces()

    override val bounds: Bounds3D = Bounds3D.of(faces.flatMap { it.vertices })
        ?: Bounds3D(offsetX, offsetX, baseY, topY, offsetZ, offsetZ)

    /**
     * The silhouette the scene fit should measure this sector against.
     *
     * Its own rim, at both caps — not the corners of its bounding box, which
     * are outside the disc and would leave a tilted pie fitted to a square it
     * never fills. See [Chart3DProjector.of].
     */
    fun fitPoints(): List<Point3D> {
        if (isDegenerate) return emptyList()
        val points = ArrayList<Point3D>(angles.size * 2 + 2)
        angles.forEach { angle ->
            points += outer(angle, baseY)
            points += outer(angle, topY)
        }
        // The inner edge only matters for a sector that has one and does not
        // reach the middle: on a thin ring the hole is what the fit misses.
        if (isAnnular) {
            points += inner(angles.first(), topY)
            points += inner(angles.last(), baseY)
        }
        return points
    }

    /**
     * A point on the top surface, [radiusFraction] of the way across the ring at
     * [angleDegrees].
     *
     * What a label, a leader line or a tooltip is anchored to. On the *top*
     * surface because that is what the reader is looking at; anchoring inside
     * the solid would put a label a visible distance below the face it belongs
     * to on a shallow view.
     */
    fun surfacePoint(angleDegrees: Double, radiusFraction: Double): Point3D {
        val radius = innerRadius + (outerRadius - innerRadius) * radiusFraction.coerceIn(0.0, 1.0)
        return offsetOf(ArcTessellator3D.pointAt(radius, angleDegrees, topY))
    }

    /** The centre of the top surface's arc, at the mid-angle. Used by tooltips. */
    fun anchor(): Point3D = surfacePoint(midAngle, ANCHOR_RING_FRACTION)

    /** The middle of the outer wall, where a leader line leaves the slice. */
    fun rimPoint(): Point3D = offsetOf(
        ArcTessellator3D.pointAt(outerRadius, midAngle, (baseY + topY) / 2.0),
    )

    // ---- geometry ---------------------------------------------------------

    private fun offsetOf(point: Point3D): Point3D =
        if (offsetX == 0.0 && offsetZ == 0.0) {
            point
        } else {
            Point3D(point.x + offsetX, point.y, point.z + offsetZ)
        }

    private fun outer(angle: Double, y: Double): Point3D =
        offsetOf(ArcTessellator3D.pointAt(outerRadius, angle, y))

    private fun inner(angle: Double, y: Double): Point3D =
        offsetOf(ArcTessellator3D.pointAt(innerRadius, angle, y))

    /**
     * Every face, wound outward.
     *
     * The orders below were derived from the right-hand rule against this
     * package's world convention and are asserted by test. A reversed one does
     * not crash: it produces a surface that is invisible from outside and
     * visible from inside, so a pie shows the reader the inside of its own far
     * rim — which reads as a rendering glitch rather than as a winding bug.
     */
    private fun buildFaces(): List<Face3D> {
        if (isDegenerate) return emptyList()
        val steps = angles.size - 1
        val faces = ArrayList<Face3D>(steps * FACES_PER_STEP + RADIAL_WALLS)

        for (index in 0 until steps) {
            val a = angles[index]
            val b = angles[index + 1]

            val outerTopA = outer(a, topY)
            val outerTopB = outer(b, topY)
            val outerBaseA = outer(a, baseY)
            val outerBaseB = outer(b, baseY)
            val innerTopA = inner(a, topY)
            val innerTopB = inner(b, topY)
            val innerBaseA = inner(a, baseY)
            val innerBaseB = inner(b, baseY)

            // Top cap: increasing angle along the outer edge, then back along
            // the inner one, which is the winding whose cross product points
            // up. A pie has no inner edge, so its cap step is a triangle rather
            // than a quad with two coincident vertices — those would give a
            // zero-area edge and a normal of nothing.
            val topCap = if (isAnnular) {
                listOf(outerTopA, outerTopB, innerTopB, innerTopA)
            } else {
                listOf(outerTopA, outerTopB, innerTopA)
            }
            faces += Face3D.of(topCap, FaceSide.Top)
            faces += Face3D.of(topCap.map { Point3D(it.x, baseY, it.z) }.reversed(), FaceSide.Bottom)

            faces += Face3D.of(
                listOf(outerBaseA, outerBaseB, outerTopB, outerTopA),
                FaceSide.Outer,
            )
            if (isAnnular) {
                faces += Face3D.of(
                    listOf(innerTopA, innerTopB, innerBaseB, innerBaseA),
                    FaceSide.Inner,
                )
            }
        }

        if (!isFullCircle) {
            val first = angles.first()
            val last = angles.last()
            faces += Face3D.of(
                listOf(
                    inner(first, baseY), outer(first, baseY),
                    outer(first, topY), inner(first, topY),
                ),
                FaceSide.Start,
            )
            faces += Face3D.of(
                listOf(
                    inner(last, topY), outer(last, topY),
                    outer(last, baseY), inner(last, baseY),
                ),
                FaceSide.End,
            )
        }
        return faces
    }

    override fun toString(): String =
        "RadialSector3D(r=[$innerRadius, $outerRadius], start=$startAngle, sweep=$sweepAngle, " +
            "y=[$baseY, $topY], segments=$segments, key=$key)"

    companion object {

        /** Halfway across the ring: the middle of a donut band, mid-radius on a pie. */
        const val ANCHOR_RING_FRACTION: Double = 0.5

        private const val FULL_CIRCLE = 360.0

        /** Below this a sweep has no interior. */
        private const val ANGLE_EPSILON = 1e-6

        /** Below this two radii are the same radius. */
        private const val RADIUS_EPSILON = 1e-9

        /** Top cap, bottom cap, outer wall and — on a donut — an inner wall. */
        private const val FACES_PER_STEP = 4

        private const val RADIAL_WALLS = 2
    }
}

/**
 * An extruded pie slice: a [RadialSector3D] with no hole.
 *
 * The name the geometry goes by, as a named constructor rather than as a second
 * type. See [RadialSector3D] for why there is only one shape.
 */
@Suppress("FunctionNaming", "LongParameterList")
fun Sector3D(
    outerRadius: Double,
    startAngle: Double,
    sweepAngle: Double,
    baseY: Double,
    topY: Double,
    offsetX: Double = 0.0,
    offsetZ: Double = 0.0,
    segments: Int = ArcTessellator3D.MIN_SEGMENTS,
    key: Chart3DKey? = null,
): RadialSector3D = RadialSector3D(
    innerRadius = 0.0,
    outerRadius = outerRadius,
    startAngle = startAngle,
    sweepAngle = sweepAngle,
    baseY = baseY,
    topY = topY,
    offsetX = offsetX,
    offsetZ = offsetZ,
    segments = segments,
    key = key,
)

/**
 * An extruded donut segment: a [RadialSector3D] with a hole, and therefore with
 * an inner wall.
 */
@Suppress("FunctionNaming", "LongParameterList")
fun AnnularSector3D(
    innerRadius: Double,
    outerRadius: Double,
    startAngle: Double,
    sweepAngle: Double,
    baseY: Double,
    topY: Double,
    offsetX: Double = 0.0,
    offsetZ: Double = 0.0,
    segments: Int = ArcTessellator3D.MIN_SEGMENTS,
    key: Chart3DKey? = null,
): RadialSector3D = RadialSector3D(
    innerRadius = innerRadius,
    outerRadius = outerRadius,
    startAngle = startAngle,
    sweepAngle = sweepAngle,
    baseY = baseY,
    topY = topY,
    offsetX = offsetX,
    offsetZ = offsetZ,
    segments = segments,
    key = key,
)
