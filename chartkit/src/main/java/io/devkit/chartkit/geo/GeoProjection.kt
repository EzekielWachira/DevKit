package io.devkit.chartkit.geo

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Flattens the globe onto a plane.
 *
 * ```text
 * longitude / latitude
 *         ↓  project
 * projected x / y
 * ```
 *
 * The one thing every map projection is, and the extension point for every one
 * ChartKit does not ship. A caller wanting Albers, Equal Earth, Lambert or an
 * orthographic globe implements this and passes it — nothing in
 * [io.devkit.chartkit.charts.ChoroplethMap], the fit, the viewport, the hit
 * test or the layer knows which projection it was given.
 *
 * ### Units
 *
 * Deliberately unspecified. Equirectangular emits degrees, Mercator emits
 * radians, and the fit transform normalises whatever comes out — so an
 * implementation is free to use whatever its own maths is natural in.
 *
 * ### Requirements on an implementation
 *
 * - [project] must be **continuous** over the region it is used for. A
 *   projection that jumps produces polygons drawn across the whole map.
 * - It must return a non-finite point rather than throwing for a coordinate it
 *   cannot represent. The renderer drops those; an exception from inside a
 *   draw pass takes the whole frame down.
 * - `y` must **increase northward**. The fit flips it once for the screen; an
 *   implementation that pre-flipped would produce an upside-down map.
 */
interface GeoProjection {

    /** A short name, for diagnostics and for the accessibility summary. */
    val name: String

    /** [coordinate] on the projection plane. */
    fun project(coordinate: GeoCoordinate): ProjectedPoint

    /**
     * The inverse, or `null` when the point is off the projection.
     *
     * Optional in the sense that a projection with no closed-form inverse may
     * return `null` throughout. Nothing in the choropleth requires it — hit
     * testing is done in projected space, not by unprojecting — but a caller
     * converting a tap into a coordinate needs it, and every projection here
     * provides it.
     */
    fun invert(point: ProjectedPoint): GeoCoordinate?

    companion object {
        /**
         * The default: simple, undistorted near the equator, and correct for the
         * regional maps a thematic chart is usually of.
         *
         * See [Equirectangular] for when it is the wrong choice.
         */
        val Default: GeoProjection get() = Equirectangular

        /** Longitude and latitude straight onto x and y. */
        val Equirectangular: GeoProjection = EquirectangularProjection()

        /** Web Mercator, as used by every slippy map. */
        val Mercator: GeoProjection = MercatorProjection()
    }
}

/**
 * Longitude and latitude used directly as x and y.
 *
 * ```text
 * x = longitude
 * y = latitude
 * ```
 *
 * The plate carrée. Nothing could be simpler, it inverts exactly, and it has no
 * singularities — the poles are ordinary points.
 *
 * ### What it distorts
 *
 * East–west distances, increasingly with latitude. At the equator the
 * distortion is none; at 60° north a degree of longitude is half a degree of
 * latitude on the ground but the same width on the map, so Scandinavia and
 * Canada look twice as wide as they are.
 *
 * That is acceptable — and often preferable — for a **thematic** map, which is
 * read for the colour of a region rather than for its area or its bearing. It
 * is the wrong choice for a world map where relative shape matters, and it is
 * not a navigational projection at all.
 *
 * ### Why it is the default
 *
 * A choropleth is nearly always of one country, state or set of counties. Over
 * a few degrees of latitude the equirectangular distortion is imperceptible,
 * and the projection has no pole behaviour to go wrong. Reach for
 * [MercatorProjection] when the map is global or must match a tiled basemap.
 */
class EquirectangularProjection(
    /**
     * The latitude at which the scale is true.
     *
     * Zero gives the plate carrée. Setting it to the middle of the mapped
     * region gives the equidistant conic's simpler cousin, which corrects most
     * of the east–west stretch for a high-latitude country at no cost.
     */
    val standardParallel: Double = 0.0,
) : GeoProjection {

    override val name: String get() = "Equirectangular"

    private val cosStandard: Double =
        kotlin.math.cos(standardParallel.coerceIn(-89.0, 89.0) * PI / 180.0)

    override fun project(coordinate: GeoCoordinate): ProjectedPoint {
        if (!coordinate.longitude.isFinite() || !coordinate.latitude.isFinite()) {
            return NonFinite
        }
        return ProjectedPoint(coordinate.longitude * cosStandard, coordinate.latitude)
    }

    override fun invert(point: ProjectedPoint): GeoCoordinate? {
        if (!point.isFinite || cosStandard == 0.0) return null
        return GeoCoordinate(point.x / cosStandard, point.y)
    }
}

/**
 * Web Mercator.
 *
 * ```text
 * x = longitude in radians
 * y = ln(tan(π/4 + latitude/2))
 * ```
 *
 * Conformal: shapes are locally correct, which is why every tiled basemap uses
 * it and why a choropleth meant to overlay one should too.
 *
 * ### The poles
 *
 * `y` goes to infinity as latitude approaches ±90°, so the projection has no
 * value there at all. Latitude is therefore **clamped** to
 * [MAX_MERCATOR_LATITUDE] — the same ±85.0511° every web map uses, which is the
 * latitude at which the projected world becomes square. Without the clamp a
 * single Antarctic vertex would produce an infinite bound, and the fit would
 * collapse the entire map to a point.
 *
 * Clamping is not silent about what it costs: a polygon crossing the clamp is
 * drawn with a flat edge along it. Antarctica on a Mercator map is that edge.
 *
 * ### What it distorts
 *
 * Area, severely and increasingly with latitude. Greenland is drawn about
 * fourteen times its true size relative to Africa. For a choropleth this
 * matters more than for a basemap: the reader is comparing coloured *areas*,
 * and Mercator makes high-latitude regions shout. Prefer
 * [EquirectangularProjection] or an equal-area projection of your own for a
 * global thematic map.
 */
class MercatorProjection : GeoProjection {

    override val name: String get() = "Mercator"

    override fun project(coordinate: GeoCoordinate): ProjectedPoint {
        if (!coordinate.longitude.isFinite() || !coordinate.latitude.isFinite()) {
            return NonFinite
        }
        val latitude = coordinate.latitude.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE)
        val radians = latitude * PI / 180.0
        val y = ln(tan(PI / 4.0 + radians / 2.0))
        if (!y.isFinite()) return NonFinite
        return ProjectedPoint(coordinate.longitude * PI / 180.0, y)
    }

    override fun invert(point: ProjectedPoint): GeoCoordinate? {
        if (!point.isFinite) return null
        val latitude = atan(sinh(point.y)) * 180.0 / PI
        val longitude = point.x * 180.0 / PI
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        return GeoCoordinate(longitude, latitude)
    }

    companion object {
        /**
         * The latitude Web Mercator is cut off at, in degrees.
         *
         * `atan(sinh(π))`: the point at which the projected world is exactly as
         * tall as it is wide, which is what makes a square tile pyramid
         * possible. Every slippy map uses it.
         */
        val MAX_MERCATOR_LATITUDE: Double = atan(sinh(PI)) * 180.0 / PI
    }
}

/** What a projection returns for a coordinate it cannot place. */
private val NonFinite = ProjectedPoint(Double.NaN, Double.NaN)

/** Projects a whole ring, dropping the vertices the projection could not place. */
internal fun GeoProjection.projectRing(ring: GeoRing): List<ProjectedPoint> {
    val result = ArrayList<ProjectedPoint>(ring.points.size)
    ring.points.forEach { point ->
        val projected = project(point)
        if (projected.isFinite) result += projected
    }
    return result
}

/** The projected bounding box of a whole collection, or `null` when empty. */
internal fun GeoProjection.projectedBounds(bounds: GeoBounds): ProjectedBounds? {
    // Corners are not enough in general — Mercator's y is non-linear in
    // latitude — but it *is* monotonic in both arguments for both projections
    // here, so the extremes of the box are the extremes of its corners. A
    // projection that broke that would need to override this; it is internal
    // precisely so it can be changed when one does.
    val corners = listOf(
        GeoCoordinate(bounds.minLongitude, bounds.minLatitude),
        GeoCoordinate(bounds.maxLongitude, bounds.minLatitude),
        GeoCoordinate(bounds.minLongitude, bounds.maxLatitude),
        GeoCoordinate(bounds.maxLongitude, bounds.maxLatitude),
    )
    return ProjectedBounds.of(corners.map { project(it) })
}
