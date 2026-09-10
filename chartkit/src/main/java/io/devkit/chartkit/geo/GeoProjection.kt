package io.devkit.chartkit.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
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

        /**
         * Equal Earth: equal-area, and the right default for a world map.
         *
         * See [EqualEarthProjection] for why a thematic global map wants an
         * equal-area projection specifically.
         */
        val EqualEarth: GeoProjection = EqualEarthProjection()

        /**
         * The default for a **global** map: [EqualEarth].
         *
         * Separate from [Default] on purpose. A choropleth is usually of one
         * country, where equirectangular is simpler and its distortion
         * imperceptible; a world map is read by comparing the shaded areas of
         * regions on opposite sides of the planet, and that comparison is only
         * honest under an equal-area projection.
         */
        val World: GeoProjection get() = EqualEarth
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


/**
 * Equal Earth.
 *
 * ```text
 * θ = asin(√3/2 · sin φ)
 * x = λ · cos θ / (√3/2 · (A₁ + 3A₂θ² + θ⁶(7A₃ + 9A₄θ²)))
 * y = θ · (A₁ + A₂θ² + θ⁶(A₃ + A₄θ²))
 * ```
 *
 * Šavrič, Patterson and Jenny (2018). Equal-area — every region is drawn at a
 * size proportional to its true size — with a pleasant enough shape that it can
 * be used for a general world map rather than only for a statistical one.
 *
 * ### Why a world choropleth needs an equal-area projection
 *
 * A thematic map asks the reader to compare **shaded areas**. Under Mercator,
 * Greenland is drawn about fourteen times its true size relative to Africa, so
 * a colour applied to Greenland shouts and the same colour applied to Nigeria
 * whispers — the projection has silently reweighted the data. Equal Earth
 * removes that entirely: the area a colour occupies is the area it represents.
 *
 * Its predecessor for this purpose is Robinson, which is *not* equal-area and
 * merely looks reasonable, and Gall–Peters, which is equal-area and looks
 * dreadful. Equal Earth was designed to be both.
 *
 * ### What it distorts
 *
 * Shape and angle, mildly, increasingly toward the poles and the map's edges.
 * That is the unavoidable trade — no projection preserves both area and shape —
 * and for a statistical map it is the right side of it. Use
 * [MercatorProjection] when shape or basemap alignment matters more.
 *
 * ### The poles
 *
 * Ordinary points. `θ` is bounded by `asin(√3/2)` = 60°, so `y` is finite
 * everywhere and no clamp is needed — unlike Mercator, which has no value at
 * ±90° at all.
 */
class EqualEarthProjection : GeoProjection {

    override val name: String get() = "Equal Earth"

    override fun project(coordinate: GeoCoordinate): ProjectedPoint {
        if (!coordinate.longitude.isFinite() || !coordinate.latitude.isFinite()) {
            return NonFinite
        }
        val lambda = coordinate.longitude * PI / 180.0
        val phi = (coordinate.latitude * PI / 180.0).coerceIn(-PI / 2.0, PI / 2.0)

        val theta = asin(M * sin(phi))
        val theta2 = theta * theta
        val theta6 = theta2 * theta2 * theta2

        val y = theta * (A1 + A2 * theta2 + theta6 * (A3 + A4 * theta2))
        val denominator = M * (A1 + 3.0 * A2 * theta2 + theta6 * (7.0 * A3 + 9.0 * A4 * theta2))
        if (denominator == 0.0) return NonFinite
        val x = lambda * cos(theta) / denominator

        if (!x.isFinite() || !y.isFinite()) return NonFinite
        return ProjectedPoint(x, y)
    }

    /**
     * The inverse, by Newton–Raphson on `θ`.
     *
     * `y(θ)` is a strictly increasing odd polynomial over the whole range `θ`
     * can take, so the iteration converges from `θ = y` in a handful of steps
     * and cannot land on the wrong root. The published inverse uses exactly this
     * method; there is no closed form.
     */
    override fun invert(point: ProjectedPoint): GeoCoordinate? {
        if (!point.isFinite) return null

        var theta = point.y
        var iterations = 0
        while (iterations < MAX_INVERSE_ITERATIONS) {
            val theta2 = theta * theta
            val theta6 = theta2 * theta2 * theta2
            val f = theta * (A1 + A2 * theta2 + theta6 * (A3 + A4 * theta2)) - point.y
            val df = A1 + 3.0 * A2 * theta2 + theta6 * (7.0 * A3 + 9.0 * A4 * theta2)
            if (df == 0.0) return null
            val delta = f / df
            theta -= delta
            if (abs(delta) < INVERSE_TOLERANCE) break
            iterations++
        }

        // Outside the projection's own outline. `null` rather than a clamped
        // coordinate: "the reader tapped the ocean beyond the map" and "the
        // reader tapped the edge of Antarctica" are different answers.
        //
        // The test is on **theta**, not on its sine. `y(theta)` keeps rising
        // past the pole, so a point above the map converges to a perfectly real
        // root — one whose sine happens to fall back inside `[-1, 1]` and would
        // report a plausible, entirely wrong latitude. `theta` itself cannot
        // exceed `asin(sqrt(3)/2)` for any coordinate on the globe.
        if (abs(theta) > MAX_THETA + INVERSE_TOLERANCE) return null

        val sinPhi = (sin(theta) / M).coerceIn(-1.0, 1.0)

        val theta2 = theta * theta
        val theta6 = theta2 * theta2 * theta2
        val denominator = cos(theta)
        if (denominator == 0.0) return null
        val lambda = point.x * M *
            (A1 + 3.0 * A2 * theta2 + theta6 * (7.0 * A3 + 9.0 * A4 * theta2)) / denominator

        val latitude = asin(sinPhi) * 180.0 / PI
        val longitude = lambda * 180.0 / PI
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        return GeoCoordinate(longitude, latitude)
    }

    companion object {
        /** The published polynomial coefficients. */
        const val A1: Double = 1.340264
        const val A2: Double = -0.081106
        const val A3: Double = 0.000893
        const val A4: Double = 0.003796

        /** `√3/2`, the sine of the maximum parametric latitude. */
        val M: Double = sqrt(3.0) / 2.0

        /** `asin(√3/2)` = 60°: the parametric latitude of the poles. */
        val MAX_THETA: Double = asin(M)

        private const val MAX_INVERSE_ITERATIONS: Int = 12
        private const val INVERSE_TOLERANCE: Double = 1e-11
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

/**
 * The projected bounding box of a geographic box, or `null` when empty.
 *
 * ### Why the edges are sampled and not just the corners
 *
 * Corners are enough only for a projection whose `x` is monotonic in latitude,
 * which Mercator and equirectangular happen to be and [EqualEarthProjection] is
 * not: its meridians bow outward, so the widest point of a box spanning the
 * equator is on the equator, not at a corner. Taking corners alone would return
 * a box about 40% too narrow for a whole-world extent, and "focus on this
 * region" would then zoom past it.
 *
 * Sampling the four edges finds it. That is correct for any projection which is
 * monotonic *along* each edge — every projection here, and every sane one —
 * without requiring each to declare which of its arguments it is monotonic in.
 *
 * Runs once per fit or focus request, never per frame, so the extra arithmetic
 * is not on any hot path.
 */
internal fun GeoProjection.projectedBounds(bounds: GeoBounds): ProjectedBounds? {
    val samples = ArrayList<ProjectedPoint>((EDGE_SAMPLES + 1) * 4)
    for (step in 0..EDGE_SAMPLES) {
        val t = step.toDouble() / EDGE_SAMPLES
        val longitude = bounds.minLongitude + bounds.longitudeSpan * t
        val latitude = bounds.minLatitude + bounds.latitudeSpan * t
        samples += project(GeoCoordinate(longitude, bounds.minLatitude))
        samples += project(GeoCoordinate(longitude, bounds.maxLatitude))
        samples += project(GeoCoordinate(bounds.minLongitude, latitude))
        samples += project(GeoCoordinate(bounds.maxLongitude, latitude))
    }
    return ProjectedBounds.of(samples)
}

/** Samples per box edge. Enough to catch a bowed meridian, cheap enough to ignore. */
private const val EDGE_SAMPLES: Int = 16
