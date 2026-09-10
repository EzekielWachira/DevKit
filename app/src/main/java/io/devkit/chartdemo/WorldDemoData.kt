package io.devkit.chartdemo

import android.content.Context
import io.devkit.chartkit.geo.GeoCoordinate
import io.devkit.chartkit.geo.GeoFeature
import io.devkit.chartkit.geo.GeoFeatureCollection
import io.devkit.chartkit.geo.TopoJson
import io.devkit.chartkit.geo.TopoJsonTopology
import kotlin.math.exp

/**
 * The sample's world geography, and some invented numbers to paint onto it.
 *
 * ### Where the geometry comes from
 *
 * `app/src/main/assets/world-110m.topojson` — Natural Earth's 1:110m admin-0
 * countries, in the public domain, packaged by the TopoJSON project's
 * `world-atlas`. 107 KB, 177 countries, 595 arcs.
 *
 * It lives in the **sample**, not in ChartKit. The library ships no geography at
 * all and that is deliberate: a usable world file is this big at its coarsest
 * and tens of megabytes at survey resolution, and putting one in the library
 * would put it in every consumer's release build including everyone who only
 * wanted a bar chart. Your application supplies its own, exactly as this one
 * does.
 *
 * Boundaries are Natural Earth's. ChartKit renders the geometry it is given and
 * takes no position on any of them.
 *
 * ### Why 1:110m
 *
 * The coarsest resolution Natural Earth publishes, and the right one for a map
 * drawn a few hundred pixels wide: 10,587 vertices for the whole planet, which
 * is fewer than a single county carries at survey resolution. A real
 * application picks the resolution its zoom range justifies — see the README's
 * note on there being no zoom-dependent level of detail.
 *
 * ### The numbers are invented
 *
 * Every statistic below is generated deterministically from the country's own
 * ISO code. They are shaped like a real geographic statistic — heavily skewed,
 * which is what makes the default quantile colour scale the right default — and
 * they are not measurements of anything. The city coordinates are real; the city
 * populations are rounded real figures.
 */
object WorldDemoData {

    /** The asset the sample reads its geography from. */
    const val ASSET: String = "world-110m.topojson"

    /** Shown in the sample, because attribution is not optional. */
    const val ATTRIBUTION: String =
        "Geometry: Natural Earth 1:110m admin-0 countries (public domain), " +
            "packaged by world-atlas. Statistics are generated, not measured."

    /** The topology, decoded once. Hold it: decoding is not free. */
    fun topology(context: Context): TopoJsonTopology =
        TopoJson.topology(context.assets.open(ASSET).readBytes().decodeToString())

    /** The countries. */
    fun world(topology: TopoJsonTopology): GeoFeatureCollection = topology.feature("countries")

    /**
     * One country's invented statistic.
     *
     * @param value `null` for a country the sample pretends was never measured.
     */
    class Statistic(val code: String, val name: String, val value: Double?)

    /**
     * Countries with no record at all, so the map can show what that looks like
     * beside a genuine zero.
     *
     * Iceland and Fiji by name; Kosovo, Somaliland and Northern Cyprus arrive
     * unmeasured on their own, because Natural Earth gives them no ISO code and
     * so `featureKey` returns `null` for them. That second case is the more
     * instructive one: it is what a real join looks like when the geometry and
     * the data disagree about what exists.
     */
    private val UNMEASURED = setOf("Iceland", "Fiji")

    /** A country the sample gives a real, measured zero. */
    private const val ZERO = "Greenland"

    /**
     * A statistic per country, keyed by ISO 3166-1 numeric code.
     *
     * Deterministic: the same country always gets the same number, on every run
     * and every device, so the sample's screenshots and tests do not drift.
     */
    fun statistics(world: GeoFeatureCollection): List<Statistic> =
        world.features.mapNotNull { feature ->
            val code = feature.properties.string("iso_n3") ?: feature.id ?: return@mapNotNull null
            val name = feature.properties.string("name").orEmpty()
            val value = when {
                name in UNMEASURED -> null
                name == ZERO -> 0.0
                else -> skewed(code)
            }
            Statistic(code, name, value)
        }

    /**
     * A skewed pseudo-random value in roughly `0.3 .. 400`.
     *
     * Exponential rather than uniform, on purpose. Real geographic statistics —
     * population, GDP, incidence — are heavily skewed, and an evenly distributed
     * demo would make an equal-width colour ramp look perfectly adequate and
     * quietly misrepresent why ChartKit defaults to a quantile scale.
     */
    private fun skewed(code: String): Double {
        // A small deterministic hash of the code, then one LCG step, so
        // neighbouring codes do not produce neighbouring values.
        var state = 2166136261L
        code.forEach { character ->
            state = (state xor character.code.toLong()) * 16777619L and 0xFFFFFFFFL
        }
        state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
        val unit = state.toDouble() / 0xFFFFFFFFL.toDouble()
        return (0.3 * exp(unit * 7.0) * 100.0).toInt() / 100.0
    }

    /** A place on the map. Capital coordinates, rounded; populations in millions. */
    class Place(val name: String, val longitude: Double, val latitude: Double, val people: Double)

    val cities: List<Place> = listOf(
        Place("Tokyo", 139.7, 35.7, 37.2),
        Place("Delhi", 77.2, 28.6, 32.9),
        Place("Shanghai", 121.5, 31.2, 29.2),
        Place("São Paulo", -46.6, -23.5, 22.6),
        Place("Mexico City", -99.1, 19.4, 22.3),
        Place("Cairo", 31.2, 30.0, 22.2),
        Place("Mumbai", 72.9, 19.1, 21.3),
        Place("Beijing", 116.4, 39.9, 21.8),
        Place("Lagos", 3.4, 6.5, 15.4),
        Place("Buenos Aires", -58.4, -34.6, 15.4),
        Place("Moscow", 37.6, 55.8, 12.7),
        Place("Paris", 2.4, 48.9, 11.2),
        Place("Jakarta", 106.8, -6.2, 11.0),
        Place("Lima", -77.0, -12.0, 11.2),
        Place("London", -0.1, 51.5, 9.6),
        Place("New York", -74.0, 40.7, 8.3),
        Place("Toronto", -79.4, 43.7, 6.4),
        Place("Johannesburg", 28.0, -26.2, 6.0),
        Place("Nairobi", 36.8, -1.3, 5.3),
        Place("Sydney", 151.2, -33.9, 5.3),
        Place("Berlin", 13.4, 52.5, 3.6),
        Place("Algiers", 3.1, 36.8, 3.4),
        Place("Madrid", -3.7, 40.4, 3.3),
        Place("Oslo", 10.7, 59.9, 1.1),
        Place("Reykjavík", -21.9, 64.1, 0.2),
        Place("Suva", 178.4, -18.1, 0.1),
        Place("Anchorage", -149.9, 61.2, 0.3),
    )

    /** A link between two places. */
    class Route(val name: String, val from: String, val to: String, val volume: Double)

    /**
     * Illustrative links, two of which cross the antimeridian.
     *
     * Tokyo–Lima and Sydney–Anchorage both run the long way across the Pacific,
     * which is exactly the case a naive renderer draws backwards across Asia and
     * Europe instead.
     */
    val routes: List<Route> = listOf(
        Route("Tokyo – Lima", "Tokyo", "Lima", 3.0),
        Route("Sydney – Anchorage", "Sydney", "Anchorage", 1.1),
        Route("Sydney – Suva", "Sydney", "Suva", 1.4),
        Route("London – New York", "London", "New York", 6.2),
        Route("Nairobi – Mumbai", "Nairobi", "Mumbai", 2.1),
        Route("São Paulo – Lagos", "São Paulo", "Lagos", 1.8),
        Route("Moscow – Beijing", "Moscow", "Beijing", 2.6),
        Route("Cairo – Paris", "Cairo", "Paris", 3.4),
    )

    private val cityByName: Map<String, Place> = cities.associateBy { it.name }

    /** A route's endpoints as coordinates, or empty when a place is unknown. */
    fun path(route: Route): List<GeoCoordinate> {
        val from = cityByName[route.from] ?: return emptyList()
        val to = cityByName[route.to] ?: return emptyList()
        return listOf(
            GeoCoordinate(from.longitude, from.latitude),
            GeoCoordinate(to.longitude, to.latitude),
        )
    }

    /** The join key for a feature: its ISO 3166-1 numeric code, or `null`. */
    val featureKey: (GeoFeature) -> String? = { it.properties.string("iso_n3") ?: it.id }

    /** A feature's display name. */
    val featureLabel: (GeoFeature) -> String = { it.properties.string("name").orEmpty() }
}
