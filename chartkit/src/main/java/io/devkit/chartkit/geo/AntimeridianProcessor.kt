package io.devkit.chartkit.geo

import kotlin.math.abs
import kotlin.math.floor

/**
 * Makes geometry safe to project across ±180°.
 *
 * ```text
 * ring crossing the antimeridian
 *          ↓  unwrap        (longitudes made continuous)
 * 170° … 185°
 *          ↓  clip per band (at every odd multiple of 180°)
 * 170°…180°   and   180°…185°
 *          ↓  shift back
 * 170°…180°   and   −180°…−175°
 * ```
 *
 * ### The bug this exists to prevent
 *
 * A projection maps longitude to x. Two consecutive vertices of Fiji at 179.9°
 * and −179.9° are half a degree apart on the ground and the entire width of the
 * map apart in x, so the renderer draws a line straight across the Pacific,
 * through Africa, and back. Every world map that has ever had this bug looks
 * the same: a country smeared horizontally across the whole picture.
 *
 * Nothing downstream can repair it. By the time the vertices are projected the
 * information that they were neighbours is gone, so the repair has to happen
 * here, in geographic space, before [GeoProjection] ever sees them.
 *
 * ### How the split works
 *
 * Longitudes are first **unwrapped**: walking the ring, a jump of more than
 * 180° between neighbours is taken as a wrap and cancelled by ±360°, so the
 * ring becomes a continuous path that may run outside `[−180, 180]`. The
 * unwrapped ring is then clipped to each 360°-wide band it touches, and each
 * piece is shifted back into range.
 *
 * Clipping uses Sutherland–Hodgman against a half-plane. A half-plane is
 * convex, which is the condition that algorithm requires of its *clip* region —
 * the subject ring may be as concave as it likes — and it closes each piece
 * along the cut automatically, which is what makes the two halves of a split
 * country look like two coastlines rather than two gashes.
 *
 * ### Straight in longitude, deliberately
 *
 * The latitude of a crossing is interpolated **linearly in longitude**, not
 * along a great circle. That is not an approximation: RFC 7946 defines a
 * GeoJSON segment as a straight line in longitude/latitude space, so linear
 * interpolation is what the file actually means. A great-circle interpolation
 * would move the cut away from where every other tool puts it.
 *
 * ### A wrap and a seam are not the same thing
 *
 * Both look like a step of more than 180°, and telling them apart is the whole
 * difference between Fiji and Antarctica:
 *
 * ```text
 * −180.0 → 179.363   a wrap:  359.363° apart on the map, 0.637° apart on the
 *                             ground. Split it.
 *  180.0 → −180.0    a seam:  exactly 360°, which is the *same meridian*. The
 *                             polygon genuinely spans the map. Leave it.
 * ```
 *
 * A step of exactly ±360° moves nothing — the two vertices name one meridian —
 * so it is the edge a polygon draws along the antimeridian, not a jump across
 * it. Natural Earth's Antarctica closes that way, running the whole width of
 * the map along its southern edge; splitting there would tear the continent in
 * half and leave a gap across the foot of every world map. Fiji, stored as one
 * ring stitched through the seam, has a 359.363° step in the middle and must be
 * split.
 *
 * ### What this does not do
 *
 * A polygon that **encloses a pole** without closing against a latitude — an
 * Antarctic coastline given as an open sweep — is not repaired. Closing one
 * correctly means inserting vertices along the pole itself and deciding which
 * side is interior, which is a different problem from this one and cannot be
 * inferred from a ring alone. Such a polygon is left as it is, and the README
 * says so. Datasets that cover Antarctica normally already close it against a
 * southern limit, in which case there is nothing to repair.
 *
 * Pure Kotlin, over [GeoCoordinate], with no Compose or `android.graphics`
 * anywhere in it — so all of the above is verified on the JVM.
 */
object AntimeridianProcessor {

    /** Half the globe. A neighbour further than this in longitude has wrapped. */
    const val WRAP_THRESHOLD: Double = 180.0

    /** A full turn. */
    const val FULL_TURN: Double = 360.0

    /**
     * Every feature, with any geometry that crosses ±180° split into pieces.
     *
     * Features whose geometry does not cross are returned **unchanged**, by
     * identity: a boundary file of one country is the common case, the scan
     * that establishes this is a subtraction per vertex, and nothing should be
     * reallocated to discover that nothing was wrong.
     */
    fun process(collection: GeoFeatureCollection): GeoFeatureCollection {
        if (collection.features.none { crosses(it.geometry) }) return collection
        return GeoFeatureCollection(
            features = collection.features.map { feature ->
                val repaired = process(feature.geometry)
                if (repaired === feature.geometry) {
                    feature
                } else {
                    GeoFeature(feature.id, feature.properties, repaired)
                }
            },
            skipped = collection.skipped,
        )
    }

    /** [geometry] with anything crossing ±180° split, or itself when nothing does. */
    fun process(geometry: GeoGeometry): GeoGeometry {
        if (!crosses(geometry)) return geometry
        return when (geometry) {
            is GeoGeometry.Point, is GeoGeometry.MultiPoint -> geometry

            is GeoGeometry.LineString -> {
                val pieces = splitLine(geometry.line.points)
                if (pieces.size == 1) {
                    GeoGeometry.LineString(GeoLine(pieces.single()))
                } else {
                    GeoGeometry.MultiLineString(pieces.map { GeoLine(it) })
                }
            }

            is GeoGeometry.MultiLineString -> GeoGeometry.MultiLineString(
                geometry.lines.flatMap { line -> splitLine(line.points).map { GeoLine(it) } },
            )

            is GeoGeometry.Polygon -> {
                val split = splitPolygon(geometry.polygon)
                if (split.size == 1) GeoGeometry.Polygon(split.single())
                else GeoGeometry.MultiPolygon(split)
            }

            is GeoGeometry.MultiPolygon ->
                GeoGeometry.MultiPolygon(geometry.polygons.flatMap { splitPolygon(it) })

            is GeoGeometry.Collection ->
                GeoGeometry.Collection(geometry.geometries.map { process(it) })
        }
    }

    /** True when any segment of [geometry] jumps more than 180° in longitude. */
    fun crosses(geometry: GeoGeometry): Boolean = when (geometry) {
        // A lone position cannot cross anything: there is no segment.
        is GeoGeometry.Point, is GeoGeometry.MultiPoint -> false
        is GeoGeometry.LineString -> crossesOpen(geometry.line.points)
        is GeoGeometry.MultiLineString -> geometry.lines.any { crossesOpen(it.points) }
        is GeoGeometry.Polygon -> geometry.polygon.rings.any { crossesClosed(it.points) }
        is GeoGeometry.MultiPolygon ->
            geometry.polygons.any { polygon -> polygon.rings.any { crossesClosed(it.points) } }
        is GeoGeometry.Collection -> geometry.geometries.any { crosses(it) }
    }

    /**
     * True when a step in longitude is a wrap across the antimeridian.
     *
     * Strictly between half a turn and a full one. Exactly a full turn is a
     * **seam** — the two vertices are the same meridian — and more than a full
     * turn is not a step any well-formed geometry contains. See the class
     * comment for why the distinction is the whole of Antarctica.
     */
    private fun isWrap(delta: Double): Boolean {
        val magnitude = abs(delta)
        return magnitude > WRAP_THRESHOLD && magnitude < FULL_TURN
    }

    /** True for a path whose ends are not joined. */
    private fun crossesOpen(points: List<GeoCoordinate>): Boolean {
        for (index in 1 until points.size) {
            if (isWrap(points[index].longitude - points[index - 1].longitude)) return true
        }
        return false
    }

    /** True for a ring, whose last vertex is a neighbour of its first. */
    private fun crossesClosed(points: List<GeoCoordinate>): Boolean {
        if (points.size < 2) return false
        if (crossesOpen(points)) return true
        return isWrap(points.first().longitude - points.last().longitude)
    }

    /**
     * A polygon split into the pieces the antimeridian leaves it in.
     *
     * Holes are split independently and then attached to whichever outer piece
     * encloses them. Matching by the hole's own position rather than by
     * construction order is what keeps an enclave with the half of its country
     * it is actually inside, instead of with whichever half came first.
     */
    fun splitPolygon(polygon: GeoPolygon): List<GeoPolygon> {
        val outers = splitRing(polygon.outer.points).filter { it.size >= GeoRing.MIN_RING_POINTS }
        if (outers.isEmpty()) return listOf(polygon)
        val holes = polygon.holes
            .flatMap { splitRing(it.points) }
            .filter { it.size >= GeoRing.MIN_RING_POINTS }

        if (outers.size == 1 && holes.size == polygon.holes.size) {
            return listOf(GeoPolygon(GeoRing(outers.single()), holes.map { GeoRing(it) }))
        }

        val boxes = outers.map { GeoBounds.of(it) }
        return outers.mapIndexed { index, ring ->
            val box = boxes[index]
            val owned = holes.filter { hole ->
                val centre = GeoBounds.of(hole)?.center
                box != null && centre != null && centre in box
            }
            GeoPolygon(GeoRing(ring), owned.map { GeoRing(it) })
        }
    }

    /**
     * A closed ring split into one piece per 360° band it spans.
     *
     * Returns a single piece — the ring itself — when it does not cross.
     */
    fun splitRing(points: List<GeoCoordinate>): List<List<GeoCoordinate>> {
        if (points.size < GeoRing.MIN_RING_POINTS) return listOf(points)
        if (!crossesClosed(points)) return listOf(points)

        val unwrapped = unwrap(points)
        val pieces = ArrayList<List<GeoCoordinate>>(2)

        forEachBand(unwrapped) { band, low, high ->
            val clipped = clip(unwrapped, low, high)
            // Enough vertices **and** some area. A ring that merely touches a
            // band boundary — Antarctica's coast reaching exactly +180 before
            // it turns back — clips to a handful of coincident points, which is
            // three vertices enclosing nothing. Emitting it would give the
            // continent a second, invisible polygon and make its component
            // count a lie.
            if (clipped.size >= GeoRing.MIN_RING_POINTS && enclosesArea(clipped)) {
                pieces += clipped.map { shift(it, -band * FULL_TURN) }
            }
        }
        return pieces.ifEmpty { listOf(points) }
    }

    /**
     * An open path split wherever it crosses ±180°.
     *
     * Each piece ends exactly **on** the meridian and the next begins on the
     * other side of it, so a route drawn across the Pacific meets both edges of
     * the map rather than stopping short of them.
     */
    fun splitLine(points: List<GeoCoordinate>): List<List<GeoCoordinate>> {
        if (points.size < GeoLine.MIN_LINE_POINTS) return listOf(points)
        if (!crossesOpen(points)) return listOf(points)

        val unwrapped = unwrap(points)
        val pieces = ArrayList<List<GeoCoordinate>>(2)
        var current = ArrayList<GeoCoordinate>()
        current += unwrapped.first()

        for (index in 1 until unwrapped.size) {
            val from = unwrapped[index - 1]
            val to = unwrapped[index]
            // Every band boundary strictly between the two, in travel order.
            val boundaries = boundariesBetween(from.longitude, to.longitude)
            boundaries.forEach { boundary ->
                val crossing = interpolate(from, to, boundary)
                current += crossing
                pieces += current
                current = ArrayList()
                current += crossing
            }
            current += to
        }
        if (current.size >= GeoLine.MIN_LINE_POINTS) pieces += current

        return pieces
            .map { piece -> piece.map { shift(it, -FULL_TURN * bandOf(midLongitude(piece))) } }
            .filter { it.size >= GeoLine.MIN_LINE_POINTS }
            .ifEmpty { listOf(points) }
    }

    /**
     * The same positions with longitudes made continuous.
     *
     * A jump of more than 180° between neighbours is a wrap, not a journey, so
     * it is cancelled by a full turn. After this the path may run to 185° or
     * −190°, which is exactly the point: it is now one continuous line that can
     * be cut cleanly.
     */
    private fun unwrap(points: List<GeoCoordinate>): List<GeoCoordinate> {
        val result = ArrayList<GeoCoordinate>(points.size)
        var offset = 0.0
        result += points.first()
        for (index in 1 until points.size) {
            val previous = points[index - 1].longitude
            val current = points[index].longitude
            val delta = current - previous
            if (isWrap(delta)) offset += if (delta > 0.0) -FULL_TURN else FULL_TURN
            result += GeoCoordinate(current + offset, points[index].latitude)
        }
        // A ring's closing segment needs no handling of its own: the clip below
        // closes each piece along the cut, which is the same edge that segment
        // would have described.
        return result
    }

    /** Calls [block] for each 360° band the unwrapped path touches. */
    private inline fun forEachBand(
        points: List<GeoCoordinate>,
        block: (band: Int, low: Double, high: Double) -> Unit,
    ) {
        var minimum = Double.POSITIVE_INFINITY
        var maximum = Double.NEGATIVE_INFINITY
        points.forEach {
            if (it.longitude < minimum) minimum = it.longitude
            if (it.longitude > maximum) maximum = it.longitude
        }
        if (!minimum.isFinite() || !maximum.isFinite()) return
        val first = bandOf(minimum)
        val last = bandOf(maximum)
        for (band in first..last) {
            block(band, band * FULL_TURN - WRAP_THRESHOLD, band * FULL_TURN + WRAP_THRESHOLD)
        }
    }

    /** Which 360°-wide band, centred on a multiple of 360°, a longitude is in. */
    private fun bandOf(longitude: Double): Int =
        floor((longitude + WRAP_THRESHOLD) / FULL_TURN).toInt()

    /** The band boundaries strictly between two longitudes, in travel order. */
    private fun boundariesBetween(from: Double, to: Double): List<Double> {
        if (from == to) return emptyList()
        val low = minOf(from, to)
        val high = maxOf(from, to)
        val firstBand = floor((low + WRAP_THRESHOLD) / FULL_TURN).toInt() + 1
        val result = ArrayList<Double>(2)
        var band = firstBand
        while (true) {
            val boundary = band * FULL_TURN - WRAP_THRESHOLD
            if (boundary >= high) break
            if (boundary > low) result += boundary
            band++
        }
        return if (to >= from) result else result.asReversed()
    }

    /** The middle longitude of a piece, for deciding which band it belongs to. */
    private fun midLongitude(points: List<GeoCoordinate>): Double {
        var minimum = Double.POSITIVE_INFINITY
        var maximum = Double.NEGATIVE_INFINITY
        points.forEach {
            if (it.longitude < minimum) minimum = it.longitude
            if (it.longitude > maximum) maximum = it.longitude
        }
        return (minimum + maximum) / 2.0
    }

    /**
     * True when the ring encloses a non-zero area.
     *
     * The shoelace sum, in degree-squared units. Its magnitude is not an area
     * on the earth's surface and must not be used as one; all that is asked
     * here is whether it is zero, which is the same question in any units.
     */
    private fun enclosesArea(ring: List<GeoCoordinate>): Boolean {
        var sum = 0.0
        for (index in ring.indices) {
            val current = ring[index]
            val next = ring[(index + 1) % ring.size]
            sum += current.longitude * next.latitude - next.longitude * current.latitude
        }
        return abs(sum) > EPSILON
    }

    private fun shift(point: GeoCoordinate, by: Double): GeoCoordinate =
        if (by == 0.0) point else GeoCoordinate(point.longitude + by, point.latitude)

    /** The position where the segment from [from] to [to] reaches [longitude]. */
    private fun interpolate(
        from: GeoCoordinate,
        to: GeoCoordinate,
        longitude: Double,
    ): GeoCoordinate {
        val span = to.longitude - from.longitude
        if (abs(span) < EPSILON) return GeoCoordinate(longitude, from.latitude)
        val t = (longitude - from.longitude) / span
        return GeoCoordinate(longitude, from.latitude + (to.latitude - from.latitude) * t)
    }

    /**
     * Sutherland–Hodgman against the slab `[low, high]`, in longitude.
     *
     * Two half-plane passes. Each keeps the vertices on the inside, and adds
     * the crossing point wherever an edge leaves or enters — which closes the
     * clipped ring along the cut without any special case for the corners.
     */
    private fun clip(
        ring: List<GeoCoordinate>,
        low: Double,
        high: Double,
    ): List<GeoCoordinate> = clipHalf(clipHalf(ring, low, keepAbove = true), high, keepAbove = false)

    private fun clipHalf(
        ring: List<GeoCoordinate>,
        boundary: Double,
        keepAbove: Boolean,
    ): List<GeoCoordinate> {
        if (ring.isEmpty()) return ring
        val inside = { point: GeoCoordinate ->
            if (keepAbove) point.longitude >= boundary else point.longitude <= boundary
        }
        val result = ArrayList<GeoCoordinate>(ring.size + 2)
        for (index in ring.indices) {
            val current = ring[index]
            val previous = ring[(index + ring.size - 1) % ring.size]
            val currentIn = inside(current)
            val previousIn = inside(previous)
            if (currentIn != previousIn) result += interpolate(previous, current, boundary)
            if (currentIn) result += current
        }
        return result
    }

    /** Below this, two longitudes are the same meridian. */
    private const val EPSILON: Double = 1e-12
}
