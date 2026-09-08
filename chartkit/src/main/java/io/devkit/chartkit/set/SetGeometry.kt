package io.devkit.chartkit.set

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The shape a set is drawn as, in the layout's own unit space.
 *
 * ```text
 * SetShape
 * ├── Circle    every Venn up to three sets, and every Euler
 * └── Ellipse   four-set Venn, where circles cannot produce all fifteen regions
 * ```
 *
 * A sealed hierarchy rather than "a circle with an optional second radius",
 * because the two differ in more than a field: an ellipse has an orientation, a
 * different containment test and a different area. Everything downstream —
 * rendering, hit testing, region sampling — asks the shape rather than switching
 * on a flag, which is what leaves room for a path-based shape later without
 * touching any of them.
 *
 * ### Unit space
 *
 * Positions are in the layout's own coordinates, with no relation to pixels. The
 * chart fits the whole arrangement into its plot area afterwards with a single
 * uniform scale — see [SetLayout.fitInto] — so the geometry here is resolution
 * independent, testable on the JVM, and never stretched to fill a container.
 */
sealed interface SetShape {

    val centerX: Double
    val centerY: Double

    /** The area enclosed. */
    val area: Double

    /** True when the point is inside the shape. Boundary counts as inside. */
    fun contains(x: Double, y: Double): Boolean

    /** The axis-aligned box, as `minX, minY, maxX, maxY`. */
    fun bounds(): DoubleArray

    /** The same shape moved and scaled about the origin. */
    fun transformed(scale: Double, offsetX: Double, offsetY: Double): SetShape

    /** A circle. */
    data class Circle(
        override val centerX: Double,
        override val centerY: Double,
        val radius: Double,
    ) : SetShape {
        override val area: Double get() = PI * radius * radius

        override fun contains(x: Double, y: Double): Boolean {
            val dx = x - centerX
            val dy = y - centerY
            return dx * dx + dy * dy <= radius * radius
        }

        override fun bounds(): DoubleArray = doubleArrayOf(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius,
        )

        override fun transformed(scale: Double, offsetX: Double, offsetY: Double): SetShape =
            Circle(centerX * scale + offsetX, centerY * scale + offsetY, radius * scale)
    }

    /**
     * An ellipse, rotated by [rotation] radians about its centre.
     *
     * Needed for a four-set Venn: no arrangement of four circles produces all
     * fifteen regions, and four congruent ellipses do. See [VennLayoutEngine].
     */
    data class Ellipse(
        override val centerX: Double,
        override val centerY: Double,
        val radiusX: Double,
        val radiusY: Double,
        val rotation: Double = 0.0,
    ) : SetShape {
        override val area: Double get() = PI * radiusX * radiusY

        override fun contains(x: Double, y: Double): Boolean {
            if (radiusX <= 0.0 || radiusY <= 0.0) return false
            // Rotate the *point* into the ellipse's own frame rather than
            // rotating the ellipse: one point, two multiplications, and the
            // test becomes the unit-circle test.
            val cosR = cos(-rotation)
            val sinR = sin(-rotation)
            val dx = x - centerX
            val dy = y - centerY
            val localX = dx * cosR - dy * sinR
            val localY = dx * sinR + dy * cosR
            val nx = localX / radiusX
            val ny = localY / radiusY
            return nx * nx + ny * ny <= 1.0
        }

        override fun bounds(): DoubleArray {
            // The exact extent of a rotated ellipse, not the box of its
            // unrotated form: half-width is sqrt((rx·cos)² + (ry·sin)²).
            val cosR = cos(rotation)
            val sinR = sin(rotation)
            val halfWidth = sqrt(radiusX * radiusX * cosR * cosR + radiusY * radiusY * sinR * sinR)
            val halfHeight = sqrt(radiusX * radiusX * sinR * sinR + radiusY * radiusY * cosR * cosR)
            return doubleArrayOf(
                centerX - halfWidth,
                centerY - halfHeight,
                centerX + halfWidth,
                centerY + halfHeight,
            )
        }

        override fun transformed(scale: Double, offsetX: Double, offsetY: Double): SetShape =
            Ellipse(
                centerX = centerX * scale + offsetX,
                centerY = centerY * scale + offsetY,
                radiusX = radiusX * scale,
                radiusY = radiusY * scale,
                rotation = rotation,
            )
    }
}

/**
 * Circle and ellipse arithmetic, in pure Kotlin.
 *
 * Every function here is a closed form or a bounded numeric solve, and every one
 * is tested against geometry whose answer is known independently. That matters
 * more here than almost anywhere else in ChartKit: a layout solver is only as
 * good as the areas it is minimising error against, and an intersection-area
 * function that is subtly wrong produces a diagram that looks plausible and is
 * not.
 */
object SetGeometryUtils {

    /** Below this, two lengths are the same length. */
    const val EPSILON: Double = 1e-9

    /** The straight-line distance between two centres. */
    fun distance(a: SetShape, b: SetShape): Double =
        hypot(a.centerX - b.centerX, a.centerY - b.centerY)

    /**
     * The area shared by two circles.
     *
     * The circular-lens formula: each circle contributes a circular segment cut
     * by the radical line between them.
     *
     * ```text
     * d ≥ r₁ + r₂          → 0            (disjoint, or tangent)
     * d ≤ |r₁ − r₂|        → π·min(r)²    (one inside the other)
     * otherwise            → the lens
     * ```
     *
     * The two degenerate branches are not an optimisation — they are what stops
     * `acos` receiving an argument outside `−1..1` and returning `NaN`, which is
     * how a tangent pair of circles takes down a whole frame.
     */
    fun circleIntersectionArea(
        x1: Double, y1: Double, r1: Double,
        x2: Double, y2: Double, r2: Double,
    ): Double {
        if (r1 <= 0.0 || r2 <= 0.0) return 0.0
        val d = hypot(x1 - x2, y1 - y2)
        if (d >= r1 + r2 - EPSILON) return 0.0
        if (d <= abs(r1 - r2) + EPSILON) {
            val smaller = min(r1, r2)
            return PI * smaller * smaller
        }
        val d2 = d * d
        val a1 = (d2 + r1 * r1 - r2 * r2) / (2 * d * r1)
        val a2 = (d2 + r2 * r2 - r1 * r1) / (2 * d * r2)
        val angle1 = acos(a1.coerceIn(-1.0, 1.0))
        val angle2 = acos(a2.coerceIn(-1.0, 1.0))
        val area = r1 * r1 * (angle1 - sin(2 * angle1) / 2) +
            r2 * r2 * (angle2 - sin(2 * angle2) / 2)
        return if (area.isFinite()) max(0.0, area) else 0.0
    }

    /** [circleIntersectionArea] for two shapes, when both are circles. */
    fun intersectionArea(a: SetShape, b: SetShape): Double = when {
        a is SetShape.Circle && b is SetShape.Circle -> circleIntersectionArea(
            a.centerX, a.centerY, a.radius,
            b.centerX, b.centerY, b.radius,
        )
        // No closed form exists for two rotated ellipses, and the numerical
        // ones are fiddly enough to be a source of quiet error. Sampling is
        // slower and obviously correct, and it is only ever used by the
        // four-set ellipse layout, which does not run a solver.
        else -> sampledIntersectionArea(listOf(a, b))
    }

    /**
     * The area covered by **every** shape in [shapes], by sampling.
     *
     * A Monte Carlo estimate would need a random source and would return a
     * different answer each call; this walks a deterministic grid over the
     * bounding box of the smallest shape, so the same shapes always produce the
     * same number. That is worth more than the last decimal place: a layout
     * whose objective function is noisy cannot converge.
     *
     * Used for regions no closed form covers — three or more circles, and
     * anything involving an ellipse.
     */
    fun sampledIntersectionArea(shapes: List<SetShape>, resolution: Int = 96): Double {
        if (shapes.isEmpty()) return 0.0
        if (shapes.size == 1) return shapes.first().area
        val smallest = shapes.minByOrNull { it.area } ?: return 0.0
        val box = smallest.bounds()
        val width = box[2] - box[0]
        val height = box[3] - box[1]
        if (width <= 0.0 || height <= 0.0) return 0.0

        val cellArea = (width / resolution) * (height / resolution)
        var inside = 0
        for (row in 0 until resolution) {
            val y = box[1] + (row + 0.5) * height / resolution
            for (column in 0 until resolution) {
                val x = box[0] + (column + 0.5) * width / resolution
                if (shapes.all { it.contains(x, y) }) inside++
            }
        }
        return inside * cellArea
    }

    /** The radius of a circle with this area. */
    fun radiusForArea(area: Double): Double =
        if (area <= 0.0) 0.0 else sqrt(area / PI)

    /**
     * How far apart two circles must be for their overlap to have [target] area.
     *
     * There is no closed form for this — the lens area is a transcendental
     * function of the distance — so it is solved by bisection on a monotonically
     * decreasing function, which is exactly the case bisection is best at.
     *
     * ```text
     * d = 0        → overlap is π·min(r)²   (maximum)
     * d = r₁ + r₂  → overlap is 0           (minimum)
     * ```
     *
     * Bounded at [MAX_BISECTIONS], which for a bracket that halves each step is
     * accuracy far below anything a screen can show. Unbounded root-finding in a
     * layout is how a bad dataset freezes a UI thread.
     */
    fun distanceForOverlap(r1: Double, r2: Double, target: Double): Double {
        if (r1 <= 0.0 || r2 <= 0.0) return r1 + r2
        val maximum = PI * min(r1, r2) * min(r1, r2)
        if (target <= 0.0) return r1 + r2
        if (target >= maximum) return abs(r1 - r2)

        var low = abs(r1 - r2)
        var high = r1 + r2
        repeat(MAX_BISECTIONS) {
            val middle = (low + high) / 2
            val area = circleIntersectionArea(0.0, 0.0, r1, middle, 0.0, r2)
            if (area > target) low = middle else high = middle
            if (high - low < EPSILON) return middle
        }
        return (low + high) / 2
    }

    /**
     * The union of every shape's bounding box, as `minX, minY, maxX, maxY`.
     *
     * `null` when there is nothing to bound, which is a different answer from a
     * box of zero size and one the fit has to distinguish.
     */
    fun boundsOf(shapes: Collection<SetShape>): DoubleArray? {
        if (shapes.isEmpty()) return null
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        shapes.forEach { shape ->
            val box = shape.bounds()
            if (box.any { !it.isFinite() }) return@forEach
            minX = min(minX, box[0])
            minY = min(minY, box[1])
            maxX = max(maxX, box[2])
            maxY = max(maxY, box[3])
        }
        return if (minX.isFinite() && maxX > minX) {
            doubleArrayOf(minX, minY, maxX, maxY)
        } else {
            null
        }
    }

    /** How many times [distanceForOverlap] may halve its bracket. */
    private const val MAX_BISECTIONS: Int = 64
}
