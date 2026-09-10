package io.devkit.chartdemo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.accessibility.ChartDataTableView
import io.devkit.chartkit.accessibility.GeoTableOrder
import io.devkit.chartkit.accessibility.geoAccessibilitySummary
import io.devkit.chartkit.accessibility.geoDataTable
import io.devkit.chartkit.accessibility.geoPointDataTable
import io.devkit.chartkit.charts.ChoroplethMap
import io.devkit.chartkit.charts.GeoChart
import io.devkit.chartkit.charts.GeoJoinReport
import io.devkit.chartkit.charts.WorldMap
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.geo.GeoJson
import io.devkit.chartkit.geo.GeoProjection
import io.devkit.chartkit.interaction.GeoInteraction
import io.devkit.chartkit.layer.geo.GeoLabels
import io.devkit.chartkit.theme.ChartColorScales
import io.devkit.chartkit.state.rememberChartGeoViewportState
import io.devkit.chartkit.theme.ChartKitTheme
import io.devkit.chartkit.theme.materialDerivedChartColors
import java.util.Locale

/** Which world-map demo the screen is showing. Public so the sample test can walk them. */
enum class MapDemo(val label: String, val tag: String) {
    Basic("Basic map", "basic"),
    Thematic("Choropleth", "thematic"),
    Projections("Projections", "projections"),
    Points("Points", "points"),
    Bubbles("Bubbles", "bubbles"),
    Routes("Routes", "routes"),
    Layered("Layered", "layered"),
    Missing("Missing vs zero", "missing"),
    Antimeridian("Antimeridian", "antimeridian"),
    TopoJson("TopoJSON", "topojson"),
    Regional("Not the world", "regional"),
    Accessible("Accessibility", "accessible"),
}

/**
 * Geographic visualisation, one screen.
 *
 * ### Where the geometry comes from
 *
 * Natural Earth's 1:110m countries, in the public domain, held as an asset of
 * **this sample** — see [WorldDemoData]. ChartKit itself ships no geography and
 * never will; your application supplies its own, exactly as this one does.
 * Boundaries are the dataset's, not ChartKit's.
 *
 * ### What each demo is actually showing
 *
 * The shapes that are hard, rather than the shapes that are pretty. Real data
 * supplies all of them: Fiji is stored as one ring stitched across ±180° and
 * must draw as two pieces; Antarctica carries the same seam and must **not** be
 * split; Indonesia is dozens of islands and one country; South Africa encloses
 * Lesotho as a genuine hole; Kosovo, Somaliland and Northern Cyprus have no ISO
 * code, so the join finds nothing for them; and Greenland is given a real zero
 * to set against that.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun WorldMapScreen(modifier: Modifier = Modifier) {
    var demo by remember { mutableStateOf(MapDemo.Basic) }
    var dark by remember { mutableStateOf(false) }
    var projection by remember { mutableStateOf(GeoProjection.World) }
    var labels by remember { mutableStateOf(GeoLabels.None) }
    var readout by remember { mutableStateOf("Tap the map") }
    var join by remember { mutableStateOf<GeoJoinReport?>(null) }

    // Decoded once, held across every recomposition. Reparsing a boundary file
    // on recomposition is the single most expensive mistake a caller can make
    // with this API, and the one the caching below cannot save you from.
    //
    // The topology is held separately from the countries it yields, because it
    // owns the decoded arcs: asking it for a second object — `land`, say —
    // would reuse them rather than decoding 595 arcs again.
    val context = LocalContext.current
    val topology = remember(context) { WorldDemoData.topology(context) }
    val world = remember(topology) { WorldDemoData.world(topology) }
    val regions = remember { GeoJson.parse(ChartDemoData.territoriesGeoJson) }

    val camera = rememberChartGeoViewportState()
    val millions = remember { ChartNumberFormatters.decimal(1, locale = Locale.UK) }
    val counts = remember { ChartNumberFormatters.compact(locale = Locale.UK) }

    val statistics = remember(world) { WorldDemoData.statistics(world) }
    val populations = remember(statistics) { statistics.map { it.value } }
    // Not inside `remember`: the scale reads the theme's ramp, so it is a
    // composable call. It is cheap — the geometry above is the expensive part,
    // and that is the thing held across recompositions.
    val quantile = ChartColorScales.quantile(populations, groups = 5)

    fun select(label: String, value: String?) {
        readout = if (value == null) label else "$label: $value"
    }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("World maps", style = MaterialTheme.typography.titleLarge)
        Text(
            "Geographic visualisation on ChartKit's own renderer — no map SDK, no basemap, " +
                "no tiles and no network. The geometry is an asset of this sample, not of " +
                "ChartKit, which ships none.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            WorldDemoData.ATTRIBUTION,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("map-attribution"),
        )

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MapDemo.entries.forEach { option ->
                FilterChip(
                    selected = demo == option,
                    onClick = { demo = option; camera.reset() },
                    label = { Text(option.label) },
                    modifier = Modifier.testTag("map-demo-${option.tag}"),
                )
            }
        }

        if (demo == MapDemo.Projections) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "Equal Earth" to GeoProjection.EqualEarth,
                    "Mercator" to GeoProjection.Mercator,
                    "Equirectangular" to GeoProjection.Equirectangular,
                ).forEach { (name, option) ->
                    FilterChip(
                        selected = projection.name == option.name,
                        onClick = { projection = option },
                        label = { Text(name) },
                        modifier = Modifier.testTag("map-projection-${name.replace(' ', '-')}"),
                    )
                }
            }
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (demo == MapDemo.Basic || demo == MapDemo.Layered) {
                GeoLabels.entries.forEach { option ->
                    FilterChip(
                        selected = labels == option,
                        onClick = { labels = option },
                        label = { Text(option.name) },
                        modifier = Modifier.testTag("map-labels-${option.name}"),
                    )
                }
            }
            // Every demo in dark mode, not a separate one. Boundaries, the "no
            // data" fill, labels, selection, legends and tooltips all have to
            // survive the swap, and the only way to know they do is to be able
            // to flip it on whichever demo you are looking at.
            FilterChip(
                selected = dark,
                onClick = { dark = !dark },
                label = { Text("Dark") },
                modifier = Modifier.testTag("map-dark"),
            )
        }

        val chartModifier = Modifier
            .fillMaxWidth()
            .height(if (demo == MapDemo.Regional) 300.dp else 340.dp)
            .testTag("map-chart")

        // Every demo, in whichever scheme the toggle selects. Wrapping the
        // chart rather than shipping one separate dark demo is the point:
        // boundaries, the "no data" fill, labels, the selection outline, the
        // legend and the tooltip all have to survive the swap, and the only
        // way to be sure they do is to flip it on the demo in front of you.
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Surface {
                ChartKitTheme(colors = materialDerivedChartColors(isDark = dark)) {
            when (demo) {
                MapDemo.Basic -> WorldMap(
                    geometry = world,
                    labels = labels,
                    viewportState = camera,
                    onSelectionChanged = { selection ->
                        select(selection?.geo?.featureLabel ?: "Tap the map", null)
                    },
                    modifier = chartModifier,
                )

                // §136: the behaviour a basic Highcharts world map represents —
                // world geometry, country regions, thematic colouring joined from
                // the caller's own records, a tooltip, a legend, a projection and
                // selection. Written natively; nothing here wraps anything.
                MapDemo.Thematic -> ChoroplethMap(
                    geometry = world,
                    data = statistics,
                    featureKey = WorldDemoData.featureKey,
                    dataKey = { it.code },
                    value = { it.value },
                    featureLabel = WorldDemoData.featureLabel,
                    projection = GeoProjection.World,
                    scale = quantile,
                    legend = LegendPosition.Bottom,
                    legendTitle = "Index (generated)",
                    valueFormatter = millions,
                    viewportState = camera,
                    onJoin = { join = it },
                    onSelectionChanged = { selection ->
                        val geo = selection?.geo
                        readout = when {
                            geo == null -> "Tap a country"
                            !geo.hasValue -> "${geo.featureLabel}: no data"
                            else -> "${geo.featureLabel}: ${millions.format(selection.y)}"
                        }
                    },
                    modifier = chartModifier,
                )

                MapDemo.Projections -> ChoroplethMap(
                    geometry = world,
                    data = statistics,
                    featureKey = WorldDemoData.featureKey,
                    dataKey = { it.code },
                    value = { it.value },
                    featureLabel = WorldDemoData.featureLabel,
                    projection = projection,
                    scale = quantile,
                    legend = LegendPosition.None,
                    valueFormatter = millions,
                    viewportState = camera,
                    onSelectionChanged = { selection ->
                        select(selection?.geo?.featureLabel ?: "Tap a country", null)
                    },
                    modifier = chartModifier,
                )

                MapDemo.Points -> GeoChart(
                    projection = GeoProjection.World,
                    viewportState = camera,
                    onSelectionChanged = { selection ->
                        val point = selection?.geoPoint
                        readout = when {
                            point != null -> "${point.label} (${point.coordinate.longitude}, " +
                                "${point.coordinate.latitude})"
                            selection?.geo != null -> selection.geo!!.featureLabel
                            else -> "Tap a city"
                        }
                    },
                    modifier = chartModifier,
                ) {
                    map(world)
                    points(
                        data = WorldDemoData.cities,
                        longitude = { it.longitude },
                        latitude = { it.latitude },
                        label = { it.name },
                    )
                }

                MapDemo.Bubbles -> GeoChart(
                    projection = GeoProjection.World,
                    legend = LegendPosition.Bottom,
                    legendTitle = "City population (millions)",
                    valueFormatter = millions,
                    viewportState = camera,
                    onSelectionChanged = { selection ->
                        val point = selection?.geoPoint
                        readout = if (point == null) {
                            "Tap a bubble"
                        } else {
                            "${point.label}: ${millions.format(point.sizeValue ?: 0.0)} million"
                        }
                    },
                    modifier = chartModifier,
                ) {
                    map(world)
                    // Value drives **area**, not radius. A city twice the size
                    // covers twice the space, which is what a reader perceives.
                    bubbles(
                        data = WorldDemoData.cities,
                        longitude = { it.longitude },
                        latitude = { it.latitude },
                        size = { it.people },
                        color = { it.people },
                        label = { it.name },
                    )
                }

                MapDemo.Routes -> GeoChart(
                    projection = GeoProjection.World,
                    viewportState = camera,
                    onSelectionChanged = { selection ->
                        val route = selection?.geoRoute
                        readout = if (route == null) "Tap a route" else route.label
                    },
                    modifier = chartModifier,
                ) {
                    map(world)
                    // Tokyo to Lima crosses ±180°. It is drawn off one edge of the
                    // map and onto the other, rather than backwards across Asia.
                    lines(
                        data = WorldDemoData.routes,
                        path = { WorldDemoData.path(it) },
                        label = { it.name },
                        value = { it.volume },
                    )
                    points(
                        data = WorldDemoData.cities,
                        longitude = { it.longitude },
                        latitude = { it.latitude },
                        label = { it.name },
                    )
                }

                // §195: every layer kind in one geographic coordinate system.
                MapDemo.Layered -> GeoChart(
                    projection = GeoProjection.World,
                    legend = LegendPosition.Bottom,
                    legendTitle = "Index (generated)",
                    valueFormatter = millions,
                    viewportState = camera,
                    onSelectionChanged = { selection ->
                        readout = when {
                            selection?.geoPoint != null -> "City: ${selection.geoPoint!!.label}"
                            selection?.geoRoute != null -> "Route: ${selection.geoRoute!!.label}"
                            selection?.geo != null -> "Country: ${selection.geo!!.featureLabel}"
                            else -> "Tap anything"
                        }
                    },
                    modifier = chartModifier,
                ) {
                    choropleth(
                        geometry = world,
                        data = statistics,
                        featureKey = WorldDemoData.featureKey,
                        dataKey = { it.code },
                        value = { it.value },
                        featureLabel = WorldDemoData.featureLabel,
                        scale = quantile,
                        labels = labels,
                    )
                    lines(
                        data = WorldDemoData.routes,
                        path = { WorldDemoData.path(it) },
                        label = { it.name },
                    )
                    bubbles(
                        data = WorldDemoData.cities,
                        longitude = { it.longitude },
                        latitude = { it.latitude },
                        size = { it.people },
                        label = { it.name },
                    )
                }

                MapDemo.Missing -> ChoroplethMap(
                    geometry = world,
                    data = statistics,
                    featureKey = WorldDemoData.featureKey,
                    dataKey = { it.code },
                    value = { it.value },
                    featureLabel = WorldDemoData.featureLabel,
                    projection = GeoProjection.World,
                    scale = ChartColorScales.quantile(populations, groups = 5),
                    legend = LegendPosition.Bottom,
                    legendTitle = "Index (generated)",
                    missingLabel = "No data",
                    valueFormatter = millions,
                    viewportState = camera,
                    onSelectionChanged = { selection ->
                        val geo = selection?.geo
                        readout = when {
                            geo == null -> "Tap Greenland, then Iceland"
                            // The whole point of the demo, in one branch.
                            !geo.hasValue -> "${geo.featureLabel}: no data — nobody measured this"
                            selection.y == 0.0 -> "${geo.featureLabel}: 0 — measured, and it is zero"
                            else -> "${geo.featureLabel}: ${millions.format(selection.y)}"
                        }
                    },
                    modifier = chartModifier,
                )

                MapDemo.Antimeridian -> WorldMap(
                    geometry = world,
                    projection = GeoProjection.Equirectangular,
                    labels = GeoLabels.Auto,
                    viewportState = camera,
                    onSelectionChanged = { selection ->
                        select(selection?.geo?.featureLabel ?: "Tap Fiji, at either edge", null)
                    },
                    modifier = chartModifier,
                )

                MapDemo.TopoJson -> WorldMap(
                    // The very same geometry every other demo on this screen is
                    // drawing: the whole sample is TopoJSON. This tab exists to
                    // report what the topology actually contains.
                    geometry = world,
                    labels = labels,
                    viewportState = camera,
                    onSelectionChanged = { selection ->
                        select(selection?.geo?.featureLabel ?: "Tap the map", null)
                    },
                    modifier = chartModifier,
                )

                // §127: the engine is not about countries. The same code draws a
                // set of abstract sales territories with an enclave and a
                // two-component region.
                MapDemo.Regional -> ChoroplethMap(
                    geometry = regions,
                    data = ChartDemoData.territoryOrders,
                    featureKey = { it.properties.string("code") },
                    dataKey = { it.code },
                    value = { it.orders },
                    featureLabel = { it.properties.string("name").orEmpty() },
                    projection = GeoProjection.Equirectangular,
                    labels = GeoLabels.Auto,
                    legend = LegendPosition.Bottom,
                    legendTitle = "Orders",
                    valueFormatter = counts,
                    viewportState = camera,
                    onSelectionChanged = { selection ->
                        select(selection?.geo?.featureLabel ?: "Tap a territory", null)
                    },
                    modifier = chartModifier,
                )

                MapDemo.Accessible -> ChoroplethMap(
                    geometry = world,
                    data = statistics,
                    featureKey = WorldDemoData.featureKey,
                    dataKey = { it.code },
                    value = { it.value },
                    featureLabel = WorldDemoData.featureLabel,
                    projection = GeoProjection.World,
                    scale = quantile,
                    legend = LegendPosition.Bottom,
                    legendTitle = "Index (generated)",
                    valueFormatter = millions,
                    viewportState = camera,
                    // Factual, and nothing beyond. No "concentrated in Asia", no
                    // colour vocabulary — those are claims ChartKit has not
                    // computed.
                    accessibilitySummary = {
                        geoAccessibilitySummary(
                            title = "World index map",
                            regionCount = world.features.size,
                            metric = "a generated index",
                            missingCount = statistics.count { it.value == null },
                        )
                    },
                    onSelectionChanged = { selection ->
                        val geo = selection?.geo
                        readout = when {
                            geo == null -> "Tap a country"
                            !geo.hasValue -> "${geo.featureLabel}: no data"
                            else -> "${geo.featureLabel}: ${millions.format(selection.y)}"
                        }
                    },
                    modifier = chartModifier,
                )
            }
                }
            }
        }

        Text(readout, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("map-readout"))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { camera.fitToGeometry() },
                enabled = !camera.isReset,
                modifier = Modifier.testTag("map-fit"),
            ) {
                Text("Fit")
            }
            TextButton(
                onClick = { camera.zoomTo(camera.zoom * 1.6f) },
                modifier = Modifier.testTag("map-zoom-in"),
            ) {
                Text("Zoom in")
            }
            TextButton(
                onClick = {
                    camera.focusOn(
                        world.features.firstOrNull { it.id == "FJI" }?.bounds?.expanded(12.0),
                    )
                },
                modifier = Modifier.testTag("map-focus-fiji"),
            ) {
                Text("Focus Fiji")
            }
        }

        Text(
            demo.explanation,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("map-explanation"),
        )

        if (demo == MapDemo.Thematic) {
            join?.let { report ->
                HorizontalDivider()
                Text("Join", style = MaterialTheme.typography.titleSmall)
                Text(
                    buildString {
                        append("${report.matched} of ${world.features.size} regions matched.")
                        if (report.unmatchedFeatureKeys.isNotEmpty()) {
                            append(" No data for ${report.unmatchedFeatureKeys.joinToString(", ")}.")
                        }
                        if (report.unmatchedDataKeys.isNotEmpty()) {
                            append(" No region for ${report.unmatchedDataKeys.joinToString(", ")}.")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("map-join"),
                )
            }
        }

        if (demo == MapDemo.TopoJson) {
            HorizontalDivider()
            val vertices = remember(world) {
                world.features.sumOf { it.geometry.coordinates().size }
            }
            Text(
                "${topology.arcCount} arcs serve ${world.features.size} countries and " +
                    "$vertices stitched vertices, in 107 KB. Every shared border — every " +
                    "line where two countries meet — is stored once and referenced twice, " +
                    "which is both why the file is this small and why two neighbours cannot " +
                    "disagree about where their border is.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("map-topojson-sizes"),
            )
        }

        if (demo == MapDemo.Accessible) {
            HorizontalDivider()
            // For a map this is not a fallback. Shape, adjacency and colour are
            // not describable in a sentence, so for a reader who cannot see the
            // picture the table *is* the chart.
            ChartDataTableView(
                geoDataTable(
                    geometry = world,
                    data = statistics,
                    featureKey = WorldDemoData.featureKey,
                    dataKey = { it.code },
                    value = { it.value },
                    featureLabel = WorldDemoData.featureLabel,
                    category = { it.properties.string("region") },
                    valueFormatter = millions,
                    order = GeoTableOrder.ByValueDescending,
                    caption = "Index by country",
                ),
                modifier = Modifier.testTag("map-table"),
            )
        }

        if (demo == MapDemo.Bubbles) {
            HorizontalDivider()
            ChartDataTableView(
                geoPointDataTable(
                    data = WorldDemoData.cities,
                    label = { it.name },
                    value = { it.people },
                    valueFormatter = millions,
                    unit = "millions",
                    order = GeoTableOrder.ByValueDescending,
                    caption = "City population",
                ),
                modifier = Modifier.testTag("map-point-table"),
            )
        }
    }
}

/** What each demo is demonstrating, in the reader's own words. */
private val MapDemo.explanation: String
    get() = when (this) {
        MapDemo.Basic ->
            "Geography with no data on it. Pinch to zoom, drag to pan once zoomed; arrow keys, " +
                "+/- and 0 do the same from a keyboard. Labels are drawn only where they fit " +
                "inside the region they name and nothing has already claimed the space."
        MapDemo.Thematic ->
            "Your own records joined to the geometry by ISO 3166-1 numeric code, shaded " +
                "through a quantile scale. The numbers are generated, but generated *skewed*, " +
                "because real geographic statistics are — and an equal-width ramp over a " +
                "skewed distribution paints almost everything the same shade."
        MapDemo.Projections ->
            "Equal Earth keeps areas comparable, which is what a shaded world map is read for. " +
                "Mercator makes Greenland shout. Equirectangular is the simple one. Switching " +
                "reprojects the geometry; it does not reparse it."
        MapDemo.Points ->
            "Cities placed by longitude and latitude, read straight off your own records " +
                "through lambdas. Tapping a mark selects the mark; tapping between them falls " +
                "through to the country underneath."
        MapDemo.Bubbles ->
            "The same marks, with population driving the bubble's area rather than its radius. " +
                "A city twice the size covers twice the space, which is what a reader " +
                "perceives — mapping onto the radius would make it look four times as large."
        MapDemo.Routes ->
            "Links drawn as paths. Tokyo–Lima crosses ±180° and is split before projection, so " +
                "it leaves one edge of the map and arrives at the other instead of being drawn " +
                "backwards across Asia."
        MapDemo.Layered ->
            "Shading, routes and bubbles in one geographic coordinate system. Layers draw in " +
                "the order declared and are hit-tested in the reverse, so a tap selects what " +
                "you can actually see at that point."
        MapDemo.Missing ->
            "Greenland measured zero. Iceland and Fiji were never measured — and neither " +
                "were Kosovo, Somaliland or Northern Cyprus, which the geometry contains but " +
                "gives no ISO code, so the join finds nothing for them. Zero and absent are " +
                "different facts, so they get different colours, different tooltips and " +
                "different sentences."
        MapDemo.Antimeridian ->
            "Natural Earth stores Fiji as one ring stitched across the seam, with a 359° " +
                "step in it. Drawn naively that is a stripe straight across the Pacific, " +
                "through Africa and back; here it is split before projection into two " +
                "pieces, one against each edge, and tapping either selects Fiji. Russia and " +
                "Antarctica carry the same seam."
        MapDemo.TopoJson ->
            "The same territories decoded from a quantised topology. Arcs are delta-encoded " +
                "integers scaled back to degrees; nothing below the parser can tell which " +
                "format the geometry came from."
        MapDemo.Regional ->
            "Not a world map. The same engine over abstract sales territories, with an enclave " +
                "that is a genuine hole and a region made of two disconnected halves. Nothing " +
                "in ChartKit knows what a country is."
        MapDemo.Accessible ->
            "A map encodes its data in position and colour, and a reader who cannot see it " +
                "gets neither. The summary is counted rather than judged, and the table below " +
                "is not a fallback — for a non-sighted reader it is the chart."
    }
