package io.devkit.chartkit.geo

import kotlin.math.max
import kotlin.math.min

/**
 * A point on the globe, in degrees.
 *
 * Longitude first, matching GeoJSON — which puts `[x, y]` in every position
 * array — rather than the `latitude, longitude` order spoken aloud. Getting
 * that round the wrong way is the single most common geographic bug there is,
 * so the field names are spelled out and there is no positional constructor
 * that reads ambiguously.
 *
 * Deliberately not `android.location.Location`: this is plain Kotlin, so the
 * projection maths is testable on the JVM without Robolectric and portable to
 * Compose Multiplatform later — the same rule
 * [io.devkit.chartkit.geometry.ChartOffset] follows.
 */
data class GeoCoordinate(val longitude: Double, val latitude: Double) {

    /** True when both components are real numbers inside the earth's range. */
    val isValid: Boolean
        get() = longitude.isFinite() && latitude.isFinite() &&
            longitude >= MIN_LONGITUDE && longitude <= MAX_LONGITUDE &&
            latitude >= MIN_LATITUDE && latitude <= MAX_LATITUDE

    companion object {
        const val MIN_LONGITUDE: Double = -180.0
        const val MAX_LONGITUDE: Double = 180.0
        const val MIN_LATITUDE: Double = -90.0
        const val MAX_LATITUDE: Double = 90.0
    }
}

/**
 * A geographic bounding box, in degrees.
 *
 * Used for fitting geometry to a plot, for focusing on a region, and as the
 * cheap first pass of hit testing. Always well-ordered: the factories below
 * repair rather than propagate an inverted box, for the same reason
 * [io.devkit.chartkit.scale.NumericDomain] does.
 *
 * ### The antimeridian
 *
 * A box is *not* allowed to wrap past ±180°. A region that genuinely straddles
 * the antimeridian — Fiji, Chukotka, a Pacific-centred world map — produces a
 * box spanning nearly the whole globe here, and the map draws correspondingly
 * zoomed out. Handling the wrap properly means a second longitude convention
 * running through the projection, the fit, the viewport and the hit test, and
 * getting it half right is worse than not doing it: the region would be drawn
 * in two places. Documented in the README as a known limitation.
 */
data class GeoBounds(
    val minLongitude: Double,
    val minLatitude: Double,
    val maxLongitude: Double,
    val maxLatitude: Double,
) {
    val longitudeSpan: Double get() = maxLongitude - minLongitude
    val latitudeSpan: Double get() = maxLatitude - minLatitude

    val isFinite: Boolean
        get() = minLongitude.isFinite() && minLatitude.isFinite() &&
            maxLongitude.isFinite() && maxLatitude.isFinite()

    /** True when the box encloses no area at all. */
    val isEmpty: Boolean get() = !isFinite || longitudeSpan <= 0.0 || latitudeSpan <= 0.0

    /** The middle of the box. Where a "focus on this region" camera points. */
    val center: GeoCoordinate
        get() = GeoCoordinate(
            longitude = (minLongitude + maxLongitude) / 2.0,
            latitude = (minLatitude + maxLatitude) / 2.0,
        )

    operator fun contains(coordinate: GeoCoordinate): Boolean =
        coordinate.longitude in minLongitude..maxLongitude &&
            coordinate.latitude in minLatitude..maxLatitude

    /** The smallest box containing both. */
    fun union(other: GeoBounds): GeoBounds = GeoBounds(
        minLongitude = min(minLongitude, other.minLongitude),
        minLatitude = min(minLatitude, other.minLatitude),
        maxLongitude = max(maxLongitude, other.maxLongitude),
        maxLatitude = max(maxLatitude, other.maxLatitude),
    )

    /**
     * The box grown by [degrees] on every side, clamped to the globe.
     *
     * What "focus on this county with a little context around it" needs.
     */
    fun expanded(degrees: Double): GeoBounds = GeoBounds(
        minLongitude = (minLongitude - degrees).coerceAtLeast(GeoCoordinate.MIN_LONGITUDE),
        minLatitude = (minLatitude - degrees).coerceAtLeast(GeoCoordinate.MIN_LATITUDE),
        maxLongitude = (maxLongitude + degrees).coerceAtMost(GeoCoordinate.MAX_LONGITUDE),
        maxLatitude = (maxLatitude + degrees).coerceAtMost(GeoCoordinate.MAX_LATITUDE),
    )

    companion object {

        /** The whole globe, for a map with nothing in it yet. */
        val World: GeoBounds = GeoBounds(-180.0, -85.0, 180.0, 85.0)

        /**
         * The tightest box covering [coordinates], or `null` when none are usable.
         *
         * `null` rather than an empty box: "there is no geography here" is a
         * different answer from "the geography occupies no area", and the fit
         * has to distinguish them.
         */
        fun of(coordinates: Iterable<GeoCoordinate>): GeoBounds? {
            var minLon = Double.POSITIVE_INFINITY
            var minLat = Double.POSITIVE_INFINITY
            var maxLon = Double.NEGATIVE_INFINITY
            var maxLat = Double.NEGATIVE_INFINITY
            var seen = false
            for (point in coordinates) {
                if (!point.longitude.isFinite() || !point.latitude.isFinite()) continue
                seen = true
                if (point.longitude < minLon) minLon = point.longitude
                if (point.longitude > maxLon) maxLon = point.longitude
                if (point.latitude < minLat) minLat = point.latitude
                if (point.latitude > maxLat) maxLat = point.latitude
            }
            return if (seen) GeoBounds(minLon, minLat, maxLon, maxLat) else null
        }

        /** The union of several boxes, or `null` when there are none. */
        fun union(boxes: Iterable<GeoBounds>): GeoBounds? =
            boxes.reduceOrNull { a, b -> a.union(b) }
    }
}

/**
 * A point in projected space, before it is fitted to a plot.
 *
 * Unitless on purpose. A projection's output is whatever its own maths
 * produces — radians for Mercator, degrees for equirectangular — and the fit
 * transform normalises it. Naming the unit here would be asserting something
 * only one projection could honour.
 */
data class ProjectedPoint(val x: Double, val y: Double) {
    val isFinite: Boolean get() = x.isFinite() && y.isFinite()
}

/** A bounding box in projected space. */
data class ProjectedBounds(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY

    val isEmpty: Boolean
        get() = !minX.isFinite() || !minY.isFinite() || !maxX.isFinite() || !maxY.isFinite() ||
            width <= 0.0 || height <= 0.0

    fun union(other: ProjectedBounds): ProjectedBounds = ProjectedBounds(
        minX = min(minX, other.minX),
        minY = min(minY, other.minY),
        maxX = max(maxX, other.maxX),
        maxY = max(maxY, other.maxY),
    )

    /**
     * The same box, guaranteed to enclose some area.
     *
     * A degenerate axis is opened out symmetrically about its own centre. This
     * is not a rounding convenience — it is the difference between fitting and
     * not fitting for whole classes of real map:
     *
     * ```text
     * one city                    → a box of zero width and zero height
     * cities along one latitude   → a box of zero height
     * a single boundary segment   → a box of zero width
     * ```
     *
     * Without it, [io.devkit.chartkit.coordinate.GeoCoordinates] reports the
     * extent empty and the chart shows "no geography" over data it was given.
     * The opened extent has no natural size — there is no scale in the data to
     * borrow one from — so it takes the non-degenerate axis where there is one
     * and [FALLBACK_SPAN] where there is not, which puts the mark in the middle
     * of the plot at a sane zoom.
     */
    fun nonDegenerate(): ProjectedBounds {
        if (!minX.isFinite() || !minY.isFinite() || !maxX.isFinite() || !maxY.isFinite()) {
            return this
        }
        if (width > 0.0 && height > 0.0) return this
        val reference = maxOf(width, height).takeIf { it > 0.0 } ?: FALLBACK_SPAN
        val padX = if (width > 0.0) 0.0 else reference / 2.0
        val padY = if (height > 0.0) 0.0 else reference / 2.0
        return ProjectedBounds(minX - padX, minY - padY, maxX + padX, maxY + padY)
    }

    companion object {
        /**
         * The span given to a box with no extent at all, in projected units.
         *
         * One degree under an equirectangular projection, and of a comparable
         * order under the others. Arbitrary by necessity: a map of exactly one
         * point has no scale of its own, and any answer here is a choice rather
         * than a measurement.
         */
        const val FALLBACK_SPAN: Double = 1.0

        fun of(points: Iterable<ProjectedPoint>): ProjectedBounds? {
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            var seen = false
            for (point in points) {
                if (!point.isFinite) continue
                seen = true
                if (point.x < minX) minX = point.x
                if (point.x > maxX) maxX = point.x
                if (point.y < minY) minY = point.y
                if (point.y > maxY) maxY = point.y
            }
            return if (seen) ProjectedBounds(minX, minY, maxX, maxY) else null
        }

        fun union(boxes: Iterable<ProjectedBounds>): ProjectedBounds? =
            boxes.reduceOrNull { a, b -> a.union(b) }
    }
}
