package io.devkit.chartkit.geo

/**
 * A closed ring of coordinates.
 *
 * GeoJSON requires the first and last position of a ring to be identical.
 * Real files are inconsistent about it, so a ring is stored **without** the
 * repeated closing point and closed by the renderer — which means the vertex
 * count is the vertex count, and a hit test does not have to remember whether
 * the last point is a duplicate.
 *
 * @param points at least three distinct positions, or the ring encloses nothing.
 */
class GeoRing(val points: List<GeoCoordinate>) {

    /** True when the ring has enough vertices to enclose an area. */
    val isValid: Boolean get() = points.size >= MIN_RING_POINTS

    val bounds: GeoBounds? by lazy(LazyThreadSafetyMode.NONE) { GeoBounds.of(points) }

    /**
     * Twice the signed area, in degree-squared units.
     *
     * The shoelace sum. Its **sign** gives the winding direction and its
     * magnitude ranks polygons by size, which is what label placement needs;
     * it is not an area on the earth's surface and must not be used as one.
     */
    val signedDoubleArea: Double by lazy(LazyThreadSafetyMode.NONE) {
        if (points.size < MIN_RING_POINTS) {
            0.0
        } else {
            var sum = 0.0
            for (index in points.indices) {
                val current = points[index]
                val next = points[(index + 1) % points.size]
                sum += current.longitude * next.latitude - next.longitude * current.latitude
            }
            sum
        }
    }

    companion object {
        /** Fewer than three vertices is a line, not a ring. */
        const val MIN_RING_POINTS: Int = 3

        /**
         * A ring from a GeoJSON position list, dropping a repeated closing point.
         *
         * Non-finite positions are dropped rather than propagated: one `NaN`
         * anywhere in a ring poisons its bounds, its centroid and every path
         * built from it, and the failure surfaces a long way from the file that
         * caused it.
         */
        fun of(points: List<GeoCoordinate>): GeoRing {
            val usable = points.filter { it.longitude.isFinite() && it.latitude.isFinite() }
            if (usable.size < 2) return GeoRing(usable)
            val first = usable.first()
            val last = usable.last()
            val closed = first.longitude == last.longitude && first.latitude == last.latitude
            return GeoRing(if (closed) usable.dropLast(1) else usable)
        }
    }
}

/**
 * One polygon: an outer ring and any number of holes.
 *
 * ```text
 * ████████████
 * ████    ████
 * ████    ████
 * ████████████
 * ```
 *
 * The hole is genuinely unfilled — it is not painted in the background colour,
 * which would be wrong the moment anything is drawn behind the map. See
 * [io.devkit.chartkit.layer.geo.ChoroplethLayer] for how the even-odd fill
 * rule produces it.
 */
class GeoPolygon(val outer: GeoRing, val holes: List<GeoRing> = emptyList()) {

    val isValid: Boolean get() = outer.isValid

    val bounds: GeoBounds? get() = outer.bounds

    /** The rings a hit test and a path both need, outer first. */
    val rings: List<GeoRing> get() = listOf(outer) + holes
}

/**
 * A geographic shape.
 *
 * Four members, matching what a thematic map can actually draw: areas to shade,
 * and points to mark. GeoJSON's `LineString` and `MultiLineString` are absent
 * because a choropleth has nothing to do with them — a road network is a
 * different visualisation, and accepting the type here would mean pretending to
 * support something that silently renders as nothing. A file containing them is
 * reported through [GeoFeatureCollection.skipped] rather than half-drawn.
 */
sealed interface GeoGeometry {

    /** True when there is something a map could draw. */
    val isDrawable: Boolean

    /** Every coordinate in the shape, for bounds and for validation. */
    fun coordinates(): List<GeoCoordinate>

    /** The shape's bounding box, or `null` when it has no usable coordinates. */
    fun bounds(): GeoBounds? = GeoBounds.of(coordinates())

    /** A single position — a capital, a facility, a sample site. */
    @JvmInline
    value class Point(val coordinate: GeoCoordinate) : GeoGeometry {
        override val isDrawable: Boolean get() = coordinate.isValid
        override fun coordinates(): List<GeoCoordinate> = listOf(coordinate)
    }

    /** Several positions belonging to one feature. */
    @JvmInline
    value class MultiPoint(val points: List<GeoCoordinate>) : GeoGeometry {
        override val isDrawable: Boolean get() = points.any { it.isValid }
        override fun coordinates(): List<GeoCoordinate> = points
    }

    /** One area, possibly with holes. */
    @JvmInline
    value class Polygon(val polygon: GeoPolygon) : GeoGeometry {
        override val isDrawable: Boolean get() = polygon.isValid
        override fun coordinates(): List<GeoCoordinate> =
            polygon.rings.flatMap { it.points }
    }

    /**
     * Several disconnected areas belonging to **one** feature.
     *
     * Not an edge case. Islands, archipelagos, enclaves and coastal regions
     * with detached territory all arrive this way, and an implementation that
     * handled only [Polygon] would draw most real administrative datasets
     * wrongly — dropping every island, or worse, drawing each as its own
     * region.
     */
    @JvmInline
    value class MultiPolygon(val polygons: List<GeoPolygon>) : GeoGeometry {
        override val isDrawable: Boolean get() = polygons.any { it.isValid }
        override fun coordinates(): List<GeoCoordinate> =
            polygons.flatMap { polygon -> polygon.rings.flatMap { it.points } }
    }
}

/**
 * The polygons of an area geometry, or empty for a point geometry.
 *
 * The one place [GeoGeometry.Polygon] and [GeoGeometry.MultiPolygon] are
 * flattened into a common shape, so nothing downstream — the path builder, the
 * hit test, the centroid, the simplifier — has to branch on which it was given.
 */
internal fun GeoGeometry.areaPolygons(): List<GeoPolygon> = when (this) {
    is GeoGeometry.Polygon -> listOf(polygon).filter { it.isValid }
    is GeoGeometry.MultiPolygon -> polygons.filter { it.isValid }
    is GeoGeometry.Point, is GeoGeometry.MultiPoint -> emptyList()
}

/** The positions of a point geometry, or empty for an area geometry. */
internal fun GeoGeometry.markerPoints(): List<GeoCoordinate> = when (this) {
    is GeoGeometry.Point -> listOf(coordinate).filter { it.isValid }
    is GeoGeometry.MultiPoint -> points.filter { it.isValid }
    is GeoGeometry.Polygon, is GeoGeometry.MultiPolygon -> emptyList()
}
