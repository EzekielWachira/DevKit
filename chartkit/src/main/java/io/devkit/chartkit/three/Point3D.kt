package io.devkit.chartkit.three

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A position in ChartKit's 3D world space.
 *
 * ### The convention, stated once
 *
 * ```
 *        +y  (values grow upward)
 *         |
 *         |
 *         +-------- +x  (the domain axis runs right)
 *        /
 *      +z  (depth runs *away* from the reader)
 * ```
 *
 * Larger `z` is further from the camera. That is the one fact the whole
 * pipeline depends on — culling asks which way a face points, the depth sorter
 * asks which face is further, and both would be inverted by the opposite
 * choice — so it is fixed here and never re-decided downstream.
 *
 * `Double`, not `Float`. A projection divides by a camera distance and a fit
 * divides by a projected span; at `Float` precision an edge-on view accumulates
 * enough error to make two coplanar faces disagree about their depth, which is
 * visible as flicker. The conversion to `Float` happens once, at the Canvas
 * boundary. See [io.devkit.chartkit.geometry.ChartOffset] for the same argument
 * made about the 2D engine.
 *
 * Plain Kotlin, with no Android or Compose types anywhere in this package, so
 * every part of the 3D pipeline that can be wrong — the matrix maths, the
 * projection, the winding, the culling, the sort, the lighting — is testable on
 * the JVM without a device.
 */
data class Point3D(val x: Double, val y: Double, val z: Double) {

    /** True when every component is a real number a projection can use. */
    val isFinite: Boolean get() = x.isFinite() && y.isFinite() && z.isFinite()

    operator fun plus(other: Vector3D): Point3D = Point3D(x + other.x, y + other.y, z + other.z)

    /** The vector from [other] to this point. */
    operator fun minus(other: Point3D): Vector3D = Vector3D(x - other.x, y - other.y, z - other.z)

    companion object {
        val Origin: Point3D = Point3D(0.0, 0.0, 0.0)
    }
}

/**
 * A direction and a magnitude in world space.
 *
 * Separate from [Point3D] on purpose: a normal is not a position, and a
 * transform treats the two differently — a point is translated and a direction
 * is not. Collapsing them into one type is how a rotated scene ends up with
 * normals that have been moved as well as turned, and the symptom is faces
 * culled at the wrong angles rather than an exception.
 */
data class Vector3D(val x: Double, val y: Double, val z: Double) {

    operator fun plus(other: Vector3D): Vector3D = Vector3D(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vector3D): Vector3D = Vector3D(x - other.x, y - other.y, z - other.z)

    operator fun times(scalar: Double): Vector3D = Vector3D(x * scalar, y * scalar, z * scalar)

    operator fun unaryMinus(): Vector3D = Vector3D(-x, -y, -z)

    infix fun dot(other: Vector3D): Double = x * other.x + y * other.y + z * other.z

    /**
     * The vector perpendicular to both, by the right-hand rule.
     *
     * What turns three vertices into a face normal, which is what decides
     * whether the face is drawn at all. The vertex order of every face in
     * [Cuboid3D] is chosen so this points *outwards*; reversing one would make
     * that face invisible from outside and visible from inside, which looks
     * like a hole in the column rather than like a winding bug.
     */
    infix fun cross(other: Vector3D): Vector3D = Vector3D(
        x = y * other.z - z * other.y,
        y = z * other.x - x * other.z,
        z = x * other.y - y * other.x,
    )

    val length: Double get() = sqrt(x * x + y * y + z * z)

    /**
     * The same direction at length one, or [Zero] when there is no direction.
     *
     * A degenerate face — a column of zero height, a cuboid whose depth
     * rounded away — produces a zero-length cross product, and normalising it
     * without this guard yields `NaN` in all three components. That `NaN` then
     * reaches a dot product, makes every comparison false, and the face is
     * neither culled nor drawn. Returning [Zero] instead makes it fail one
     * check, in one place, deterministically.
     */
    fun normalized(): Vector3D {
        val magnitude = length
        if (!magnitude.isFinite() || magnitude < EPSILON) return Zero
        return Vector3D(x / magnitude, y / magnitude, z / magnitude)
    }

    /** True when this is the zero vector, within [EPSILON]. */
    val isZero: Boolean get() = abs(x) < EPSILON && abs(y) < EPSILON && abs(z) < EPSILON

    val isFinite: Boolean get() = x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val Zero: Vector3D = Vector3D(0.0, 0.0, 0.0)
        val UnitX: Vector3D = Vector3D(1.0, 0.0, 0.0)
        val UnitY: Vector3D = Vector3D(0.0, 1.0, 0.0)
        val UnitZ: Vector3D = Vector3D(0.0, 0.0, 1.0)

        /** Below this a vector has no usable direction. */
        const val EPSILON: Double = 1e-12
    }
}

/**
 * The axis-aligned box a set of points occupies.
 *
 * Used to frame a scene, to fit it into a plot, and to draw the walls of a
 * chart frame around it. Deliberately allows a zero-thickness box — a chart of
 * one category has no width in `x`, and refusing to represent that would push
 * the special case into every caller — so consumers read [isEmpty] rather than
 * assuming a span.
 */
data class Bounds3D(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
    val minZ: Double,
    val maxZ: Double,
) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY
    val depth: Double get() = maxZ - minZ

    val center: Point3D
        get() = Point3D((minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0)

    val isFinite: Boolean
        get() = minX.isFinite() && maxX.isFinite() && minY.isFinite() &&
            maxY.isFinite() && minZ.isFinite() && maxZ.isFinite()

    /** True when the box encloses no volume at all in any direction. */
    val isEmpty: Boolean get() = !isFinite || (width <= 0.0 && height <= 0.0 && depth <= 0.0)

    /** The eight corners, for framing and for fitting a projected scene. */
    fun corners(): List<Point3D> = listOf(
        Point3D(minX, minY, minZ), Point3D(maxX, minY, minZ),
        Point3D(maxX, maxY, minZ), Point3D(minX, maxY, minZ),
        Point3D(minX, minY, maxZ), Point3D(maxX, minY, maxZ),
        Point3D(maxX, maxY, maxZ), Point3D(minX, maxY, maxZ),
    )

    /** The smallest box containing both. */
    fun union(other: Bounds3D): Bounds3D = Bounds3D(
        minX = min(minX, other.minX), maxX = max(maxX, other.maxX),
        minY = min(minY, other.minY), maxY = max(maxY, other.maxY),
        minZ = min(minZ, other.minZ), maxZ = max(maxZ, other.maxZ),
    )

    companion object {

        /** The box enclosing [points], or `null` when there are none. */
        fun of(points: Iterable<Point3D>): Bounds3D? {
            var minX = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            var minZ = Double.POSITIVE_INFINITY
            var maxZ = Double.NEGATIVE_INFINITY
            var seen = false
            for (point in points) {
                if (!point.isFinite) continue
                seen = true
                minX = min(minX, point.x); maxX = max(maxX, point.x)
                minY = min(minY, point.y); maxY = max(maxY, point.y)
                minZ = min(minZ, point.z); maxZ = max(maxZ, point.z)
            }
            return if (seen) Bounds3D(minX, maxX, minY, maxY, minZ, maxZ) else null
        }
    }
}
