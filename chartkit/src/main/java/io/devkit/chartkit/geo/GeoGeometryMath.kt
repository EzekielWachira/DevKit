package io.devkit.chartkit.geo

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Point-in-polygon, centroids and simplification, in projected space.
 *
 * All pure Kotlin over [ProjectedPoint], not over [GeoCoordinate]. Hit testing
 * happens where the reader is pointing — on the projected plane — and a test
 * done in degrees would disagree with the picture wherever the projection is
 * non-linear, which for Mercator is everywhere away from the equator.
 *
 * Nothing here touches Compose or `android.graphics`, so every rule below is
 * verified on the JVM.
 */
internal object GeoGeometryMath {

    /**
     * True when [point] lies inside the ring.
     *
     * The even–odd ray-casting rule: count how many edges a ray cast from the
     * point crosses, and an odd count means inside. Chosen over the winding
     * rule because it needs no orientation, and real boundary files are
     * inconsistent about ring winding — a hit test that assumed counter-
     * clockwise outer rings would fail on about half the datasets in the wild.
     *
     * ### The boundary
     *
     * A point exactly on an edge is not guaranteed either answer; the
     * comparison is deliberately asymmetric (`>` on one end, `<=` on the other)
     * so that a point on a shared edge between two adjacent regions belongs to
     * exactly **one** of them. Two counties that both claimed a border pixel
     * would make selection flicker along every boundary.
     */
    fun ringContains(ring: List<ProjectedPoint>, x: Double, y: Double): Boolean {
        if (ring.size < GeoRing.MIN_RING_POINTS) return false
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            // The half-open comparison: an edge is considered to span
            // [min, max) in y, so a vertex is counted exactly once rather than
            // twice or not at all.
            if ((a.y > y) != (b.y > y)) {
                val t = (y - a.y) / (b.y - a.y)
                if (x < a.x + t * (b.x - a.x)) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * True when [point] is inside the polygon's outer ring and in none of its
     * holes.
     *
     * The hole test is what makes an enclave work: a point inside Lesotho is
     * inside South Africa's outer ring, and South Africa must not be selected
     * for it.
     */
    fun polygonContains(polygon: ProjectedPolygon, x: Double, y: Double): Boolean {
        if (!polygon.bounds.contains(x, y)) return false
        if (!ringContains(polygon.outer, x, y)) return false
        return polygon.holes.none { ringContains(it, x, y) }
    }

    /**
     * Twice the signed area of a ring.
     *
     * The shoelace sum. Used to rank a multi-polygon's components by size and
     * to weight a centroid; the sign is the winding direction.
     */
    fun signedDoubleArea(ring: List<ProjectedPoint>): Double {
        if (ring.size < GeoRing.MIN_RING_POINTS) return 0.0
        var sum = 0.0
        for (index in ring.indices) {
            val current = ring[index]
            val next = ring[(index + 1) % ring.size]
            sum += current.x * next.y - next.x * current.y
        }
        return sum
    }

    /**
     * The area centroid of a ring, or `null` for a degenerate one.
     *
     * The true polygon centroid — the area-weighted mean — not the mean of the
     * vertices, which is pulled toward whichever part of the boundary happens
     * to be described in most detail. A coastline with ten thousand points and
     * a straight inland border would otherwise place its label in the sea.
     *
     * ### It can fall outside the polygon
     *
     * For a crescent, a horseshoe or a strongly concave county, the area
     * centroid is not inside the shape. That is a property of the centroid, not
     * a defect here — and it is why [labelPoint] tests the centroid before
     * using it and falls back to [interiorPoint] when it lands outside.
     */
    fun centroid(ring: List<ProjectedPoint>): ProjectedPoint? {
        if (ring.size < GeoRing.MIN_RING_POINTS) return null
        var area = 0.0
        var x = 0.0
        var y = 0.0
        for (index in ring.indices) {
            val current = ring[index]
            val next = ring[(index + 1) % ring.size]
            val cross = current.x * next.y - next.x * current.y
            area += cross
            x += (current.x + next.x) * cross
            y += (current.y + next.y) * cross
        }
        if (abs(area) < EPSILON) {
            // A zero-area ring — every vertex collinear, or all identical. The
            // vertex mean is the only defensible answer, and it is at least
            // inside the ring's own bounding box.
            val meanX = ring.sumOf { it.x } / ring.size
            val meanY = ring.sumOf { it.y } / ring.size
            return ProjectedPoint(meanX, meanY)
        }
        val factor = 1.0 / (3.0 * area)
        val point = ProjectedPoint(x * factor, y * factor)
        return if (point.isFinite) point else null
    }

    /**
     * Where a feature's label belongs.
     *
     * For a multi-polygon, the centroid of the **largest** component. Labelling
     * the centroid of all components together would put a country's name in the
     * ocean between its mainland and its islands, which is the single most
     * visible way a map label goes wrong.
     */
    fun labelPoint(polygons: List<ProjectedPolygon>): ProjectedPoint? {
        val largest = polygons.maxByOrNull { abs(signedDoubleArea(it.outer)) } ?: return null
        val centroid = centroid(largest.outer)
        // The centroid is right for the overwhelming majority of regions and
        // costs one pass, so it is tried first and only replaced when it is
        // demonstrably wrong — inside a hole, or outside a crescent altogether.
        if (centroid != null && polygonContains(largest, centroid.x, centroid.y)) return centroid
        return interiorPoint(largest) ?: centroid
    }

    /**
     * A point inside the polygon, as far from its boundary as this can find.
     *
     * The pole of inaccessibility: for Norway it is inland rather than in a
     * fjord, for a horseshoe-shaped county it is in one of the arms rather than
     * in the gap, and for a doughnut it is in the ring rather than in the hole.
     * That is the difference between a label that names a region and a label
     * that appears to name its neighbour.
     *
     * ### How
     *
     * A coarse grid over the bounding box scored by [signedDistance], then a
     * fixed number of halving refinements around the best cell. This is the
     * shape of Mapbox's polylabel without its priority queue: the queue buys a
     * guaranteed-optimal answer, and a label does not need the optimum — it
     * needs a point comfortably inside, found in bounded time.
     *
     * Bounded work by construction: [GRID] × [GRID] samples plus
     * [REFINEMENTS] × 8, whatever the vertex count. Computed once per feature,
     * lazily, and never during a draw pass.
     *
     * `null` when no sample lands inside at all, which happens for a polygon
     * thinner than the grid can resolve — a river drawn as an area, say. The
     * caller falls back to the centroid.
     */
    fun interiorPoint(polygon: ProjectedPolygon): ProjectedPoint? {
        val box = polygon.bounds
        if (box.isEmpty) return null
        val width = box.maxX - box.minX
        val height = box.maxY - box.minY
        if (width <= 0.0 || height <= 0.0) return null

        var bestX = Double.NaN
        var bestY = Double.NaN
        var bestDistance = 0.0

        for (row in 0 until GRID) {
            for (column in 0 until GRID) {
                val x = box.minX + width * (column + 0.5) / GRID
                val y = box.minY + height * (row + 0.5) / GRID
                val distance = signedDistance(polygon, x, y)
                if (distance > bestDistance) {
                    bestDistance = distance
                    bestX = x
                    bestY = y
                }
            }
        }
        if (bestX.isNaN()) return null

        var step = maxOf(width, height) / GRID
        repeat(REFINEMENTS) {
            step /= 2.0
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val x = bestX + dx * step
                    val y = bestY + dy * step
                    val distance = signedDistance(polygon, x, y)
                    if (distance > bestDistance) {
                        bestDistance = distance
                        bestX = x
                        bestY = y
                    }
                }
            }
        }
        return ProjectedPoint(bestX, bestY)
    }

    /**
     * Distance from the point to the polygon's boundary, positive inside.
     *
     * The sign comes from [polygonContains] — which already handles holes — and
     * the magnitude from the nearest edge of any ring, holes included. A point
     * near the rim of a hole is therefore *close* to the boundary even though
     * it is inside the outer ring, which is what stops a label being placed on
     * the edge of an enclave.
     */
    fun signedDistance(polygon: ProjectedPolygon, x: Double, y: Double): Double {
        var nearest = Double.POSITIVE_INFINITY
        val rings = ArrayList<List<ProjectedPoint>>(1 + polygon.holes.size)
        rings += polygon.outer
        rings += polygon.holes
        rings.forEach { ring ->
            for (index in ring.indices) {
                val current = ring[index]
                val next = ring[(index + 1) % ring.size]
                val distance = squaredDistanceToSegment(ProjectedPoint(x, y), current, next)
                if (distance < nearest) nearest = distance
            }
        }
        if (!nearest.isFinite()) return 0.0
        val magnitude = sqrt(nearest)
        return if (polygonContains(polygon, x, y)) magnitude else -magnitude
    }

    /**
     * The distance from a point to an **open** path, or infinity for an empty one.
     *
     * What selecting a route needs: a line has no interior, so the only
     * meaningful question is how near the pointer came to it.
     */
    fun distanceToPath(points: List<ProjectedPoint>, x: Double, y: Double): Double {
        if (points.isEmpty()) return Double.POSITIVE_INFINITY
        if (points.size == 1) {
            val dx = points[0].x - x
            val dy = points[0].y - y
            return sqrt(dx * dx + dy * dy)
        }
        val point = ProjectedPoint(x, y)
        var nearest = Double.POSITIVE_INFINITY
        for (index in 0 until points.size - 1) {
            val distance = squaredDistanceToSegment(point, points[index], points[index + 1])
            if (distance < nearest) nearest = distance
        }
        return sqrt(nearest)
    }

    /**
     * [points] with the vertices that contribute less than [tolerance] removed.
     *
     * Ramer–Douglas–Peucker: keep the two endpoints, find the vertex furthest
     * from the line between them, and recurse on both halves if that distance
     * exceeds the tolerance. It preserves the shape's silhouette — corners
     * survive, straight runs collapse — which is exactly the property a
     * boundary needs.
     *
     * ### When this is worth doing
     *
     * A national boundary file at full resolution can carry tens of thousands
     * of vertices per feature, most of them describing a coastline at a
     * precision no phone screen can show. Simplifying at a tolerance derived
     * from the plot size discards what cannot be seen and nothing else.
     *
     * It is **off by default**: silently discarding a caller's geographic
     * fidelity is not a decision a chart should make for them. See
     * [io.devkit.chartkit.charts.ChoroplethMap]'s `simplification` parameter.
     *
     * Iterative, over an explicit stack: a pathological ring can recurse as
     * deeply as it has vertices, and a hundred thousand frames is a crash.
     */
    fun simplify(points: List<ProjectedPoint>, tolerance: Double): List<ProjectedPoint> {
        if (tolerance <= 0.0 || points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true

        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        val toleranceSquared = tolerance * tolerance

        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            if (last <= first + 1) continue
            var furthest = -1
            var furthestDistance = 0.0
            for (index in first + 1 until last) {
                val distance = squaredDistanceToSegment(points[index], points[first], points[last])
                if (distance > furthestDistance) {
                    furthestDistance = distance
                    furthest = index
                }
            }
            if (furthestDistance > toleranceSquared && furthest > 0) {
                keep[furthest] = true
                stack.addLast(first to furthest)
                stack.addLast(furthest to last)
            }
        }

        val result = ArrayList<ProjectedPoint>(points.size)
        points.forEachIndexed { index, point -> if (keep[index]) result += point }
        // A ring simplified below three vertices no longer encloses anything,
        // so the original is kept rather than a shape that cannot be drawn.
        return if (result.size >= GeoRing.MIN_RING_POINTS) result else points
    }

    /** The squared perpendicular distance from a point to a segment. */
    private fun squaredDistanceToSegment(
        point: ProjectedPoint,
        from: ProjectedPoint,
        to: ProjectedPoint,
    ): Double {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared < EPSILON) {
            val px = point.x - from.x
            val py = point.y - from.y
            return px * px + py * py
        }
        val t = (((point.x - from.x) * dx + (point.y - from.y) * dy) / lengthSquared)
            .coerceIn(0.0, 1.0)
        val cx = from.x + t * dx
        val cy = from.y + t * dy
        val ex = point.x - cx
        val ey = point.y - cy
        return ex * ex + ey * ey
    }

    /** Below this, two projected coordinates are the same point. */
    const val EPSILON: Double = 1e-12

    /** Samples per axis in [interiorPoint]'s first pass. */
    private const val GRID: Int = 12

    /** Halving steps [interiorPoint] takes around its best sample. */
    private const val REFINEMENTS: Int = 10
}

/** A polygon in projected space: an outer ring and its holes. */
internal class ProjectedPolygon(
    val outer: List<ProjectedPoint>,
    val holes: List<List<ProjectedPoint>>,
) {
    val bounds: ProjectedBox = ProjectedBox.of(outer)

    val isValid: Boolean get() = outer.size >= GeoRing.MIN_RING_POINTS

    val vertexCount: Int get() = outer.size + holes.sumOf { it.size }
}

/**
 * An axis-aligned box in projected space.
 *
 * A plain class with primitive fields rather than [ProjectedBounds], because
 * this one is allocated per polygon and tested per pointer event — the
 * difference between four `Double` fields and a data class with nullable
 * factories shows up when a thousand-feature map is being scrubbed.
 */
internal class ProjectedBox(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
) {
    val isEmpty: Boolean get() = maxX < minX || maxY < minY

    fun contains(x: Double, y: Double): Boolean =
        x >= minX && x <= maxX && y >= minY && y <= maxY

    fun union(other: ProjectedBox): ProjectedBox = ProjectedBox(
        minX = kotlin.math.min(minX, other.minX),
        minY = kotlin.math.min(minY, other.minY),
        maxX = max(maxX, other.maxX),
        maxY = max(maxY, other.maxY),
    )

    companion object {
        val Empty: ProjectedBox = ProjectedBox(
            Double.POSITIVE_INFINITY,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
        )

        fun of(points: List<ProjectedPoint>): ProjectedBox {
            if (points.isEmpty()) return Empty
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            points.forEach { point ->
                if (point.x < minX) minX = point.x
                if (point.x > maxX) maxX = point.x
                if (point.y < minY) minY = point.y
                if (point.y > maxY) maxY = point.y
            }
            return ProjectedBox(minX, minY, maxX, maxY)
        }

        fun union(boxes: List<ProjectedBox>): ProjectedBox =
            boxes.filterNot { it.isEmpty }.reduceOrNull { a, b -> a.union(b) } ?: Empty
    }
}
