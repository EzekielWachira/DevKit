package io.devkit.chartkit.three

import kotlin.math.max
import kotlin.math.min

/**
 * A rectangular box: the first reusable 3D primitive, and the shape a column
 * segment takes.
 *
 * ### Built from chart terms, not from vertices
 *
 * The constructor takes a footprint, a value interval and a depth rather than
 * eight corners. A stacked segment is then "this footprint, from 3 to 7", which
 * is exactly what the stack engine already produced — and no caller has to get
 * a winding order right. Hand-built vertices would put that responsibility in
 * the column layer, then again in whatever draws 3D scatter, and the two would
 * disagree the first time either was fixed.
 *
 * ### Negative values
 *
 * [yStart] may be above [yEnd]. A column running from `0` down to `-4` is
 * ordinary data, and the box is normalised on construction while [isNegative]
 * records which way it was stated. Assuming a top above a bottom is how a
 * negative bar ends up drawn with inverted winding and its faces culled from
 * the outside — invisible, and impossible to see in a screenshot of the
 * positive case.
 *
 * @param x the near-left corner of the footprint on the domain axis.
 * @param width the footprint's extent along the domain axis. Never negative.
 * @param z the near edge of the footprint in depth.
 * @param depth the footprint's extent away from the reader. Never negative.
 * @param key what data this box stands for, or `null` for scene furniture such
 *   as a frame wall. Carried so selection, tooltips and animation can match a
 *   box across a data change by identity rather than by position; see
 *   [Chart3DKey].
 */
class Cuboid3D(
    x: Double,
    width: Double,
    yStart: Double,
    yEnd: Double,
    z: Double,
    depth: Double,
    val key: Chart3DKey? = null,
) {
    /** True when the box was stated top-down: a value below the baseline. */
    val isNegative: Boolean = yEnd < yStart

    val bounds: Bounds3D = Bounds3D(
        minX = min(x, x + width),
        maxX = max(x, x + width),
        minY = min(yStart, yEnd),
        maxY = max(yStart, yEnd),
        minZ = min(z, z + depth),
        maxZ = max(z, z + depth),
    )

    /** True when the box encloses no volume — a zero value, or a footprint that rounded away. */
    val isDegenerate: Boolean
        get() = !bounds.isFinite ||
            bounds.width <= 0.0 || bounds.depth <= 0.0 || bounds.height <= 0.0

    /**
     * The eight corners, in a fixed order.
     *
     * ```
     *     3---------2        y
     *    /|        /|        |
     *   7---------6 |        +---x
     *   | 0-------|-1       /
     *   |/        |/       z
     *   4---------5
     * ```
     *
     * `0..3` are the near face (small z) and `4..7` the far one, each running
     * anticlockwise as seen from outside.
     */
    val vertices: List<Point3D> = with(bounds) {
        listOf(
            Point3D(minX, minY, minZ), Point3D(maxX, minY, minZ),
            Point3D(maxX, maxY, minZ), Point3D(minX, maxY, minZ),
            Point3D(minX, minY, maxZ), Point3D(maxX, minY, maxZ),
            Point3D(maxX, maxY, maxZ), Point3D(minX, maxY, maxZ),
        )
    }

    /**
     * The six faces, each wound so its normal points out of the box.
     *
     * The orders below are not interchangeable and are asserted by test. A
     * reversed one is not a crash — it is a face that vanishes when looked at
     * and appears when looked away from, which reads as a rendering glitch
     * rather than as the winding bug it is.
     */
    val faces: List<Face3D> = with(bounds) {
        listOf(
            // Front, facing the reader: outward normal is -z.
            Face3D.of(
                listOf(
                    Point3D(minX, minY, minZ), Point3D(minX, maxY, minZ),
                    Point3D(maxX, maxY, minZ), Point3D(maxX, minY, minZ),
                ),
                FaceSide.Front,
            ),
            // Back: +z.
            Face3D.of(
                listOf(
                    Point3D(minX, minY, maxZ), Point3D(maxX, minY, maxZ),
                    Point3D(maxX, maxY, maxZ), Point3D(minX, maxY, maxZ),
                ),
                FaceSide.Back,
            ),
            // Left: -x.
            Face3D.of(
                listOf(
                    Point3D(minX, minY, minZ), Point3D(minX, minY, maxZ),
                    Point3D(minX, maxY, maxZ), Point3D(minX, maxY, minZ),
                ),
                FaceSide.Left,
            ),
            // Right: +x.
            Face3D.of(
                listOf(
                    Point3D(maxX, minY, minZ), Point3D(maxX, maxY, minZ),
                    Point3D(maxX, maxY, maxZ), Point3D(maxX, minY, maxZ),
                ),
                FaceSide.Right,
            ),
            // Top: +y.
            Face3D.of(
                listOf(
                    Point3D(minX, maxY, minZ), Point3D(minX, maxY, maxZ),
                    Point3D(maxX, maxY, maxZ), Point3D(maxX, maxY, minZ),
                ),
                FaceSide.Top,
            ),
            // Bottom: -y.
            Face3D.of(
                listOf(
                    Point3D(minX, minY, minZ), Point3D(maxX, minY, minZ),
                    Point3D(maxX, minY, maxZ), Point3D(minX, minY, maxZ),
                ),
                FaceSide.Bottom,
            ),
        )
    }

    /** The centre of the face a tooltip or a data label is anchored to. */
    fun faceCenter(side: FaceSide): Point3D = faces.first { it.side == side }.centroid

    override fun toString(): String = "Cuboid3D(bounds=$bounds, key=$key)"
}
