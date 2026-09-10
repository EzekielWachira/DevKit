package io.devkit.chartkit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.charts.GeoChart
import io.devkit.chartkit.charts.WorldMap
import io.devkit.chartkit.geo.GeoCoordinate
import io.devkit.chartkit.geo.GeoJson
import io.devkit.chartkit.geo.GeoProjection
import io.devkit.chartkit.geo.TopoJson
import io.devkit.chartkit.interaction.GeoInteraction
import io.devkit.chartkit.layer.geo.GeoLabels
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.state.rememberChartGeoViewportState
import org.junit.Rule
import org.junit.Test

private class GeoCity(val name: String, val longitude: Double, val latitude: Double, val people: Double)

private class GeoLink(val name: String, val path: List<GeoCoordinate>)

/**
 * The composed geographic chart on a device: layers draw, layers select, and
 * the one on top wins.
 *
 * ### The fixture
 *
 * A 2×1 grid of square regions spanning `0..2` by `0..1`, with a mark at the
 * centre of each. Deterministic squares rather than real geography, for the
 * same reason [ChartGeoTest] uses them: these tests are about the machinery,
 * not about the shape of anything.
 *
 * ```text
 * ┌────────┬────────┐
 * │   ●    │   ●    │   marks at (0.5, 0.5) and (1.5, 0.5)
 * │  west  │  east  │
 * └────────┴────────┘
 * ```
 */
class ChartGeoLayersTest {

    @get:Rule
    val rule = createComposeRule()

    private val geoJson = """
        { "type": "FeatureCollection", "features": [
          { "type": "Feature", "id": "west",
            "properties": { "code": "west", "name": "West" },
            "geometry": { "type": "Polygon",
              "coordinates": [[[0,0],[1,0],[1,1],[0,1],[0,0]]] } },
          { "type": "Feature", "id": "east",
            "properties": { "code": "east", "name": "East" },
            "geometry": { "type": "Polygon",
              "coordinates": [[[1,0],[2,0],[2,1],[1,1],[1,0]]] } }
        ]}
    """.trimIndent()

    private val cities = listOf(
        GeoCity("Westville", 0.5, 0.5, 10.0),
        GeoCity("Eastville", 1.5, 0.5, 40.0),
    )

    private val links = listOf(
        GeoLink("West to East", listOf(GeoCoordinate(0.5, 0.5), GeoCoordinate(1.5, 0.5))),
    )

    private val mapSize = Modifier.fillMaxWidth().height(220.dp)

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        MaterialTheme { Surface { Column { content() } } }
    }

    // ---- the composed chart ----------------------------------------------

    @Test
    fun aComposedChartDrawsEveryLayerItIsGiven() {
        rule.setContent {
            Host {
                val world = remember { GeoJson.parse(geoJson) }
                GeoChart(
                    projection = GeoProjection.Equirectangular,
                    modifier = mapSize.testTag("chart"),
                ) {
                    map(world)
                    lines(links, path = { it.path }, label = { it.name })
                    bubbles(
                        cities,
                        longitude = { it.longitude },
                        latitude = { it.latitude },
                        size = { it.people },
                        label = { it.name },
                    )
                }
            }
        }

        rule.onNodeWithTag("chart").assertIsDisplayed()
    }

    @Test
    fun aTapOnAMarkSelectsTheMarkAndNotTheRegionUnderneath() {
        var selection by mutableStateOf<AnyChartSelection?>(null)

        rule.setContent {
            Host {
                val world = remember { GeoJson.parse(geoJson) }
                GeoChart(
                    projection = GeoProjection.Equirectangular,
                    onSelectionChanged = { selection = it },
                    modifier = mapSize.testTag("chart"),
                ) {
                    map(world)
                    points(
                        cities,
                        longitude = { it.longitude },
                        latitude = { it.latitude },
                        label = { it.name },
                    )
                }
                Text(
                    selection?.geoPoint?.label
                        ?: selection?.geo?.featureLabel
                        ?: "nothing",
                    modifier = Modifier.testTag("readout"),
                )
            }
        }

        // The centre of the left region, which is also exactly where its mark
        // is. Both layers claim the point; the mark was drawn last, so the mark
        // is what a tap must select.
        rule.onNodeWithTag("chart").performTouchInput {
            click(Offset(width * 0.25f, height * 0.5f))
        }
        rule.waitForIdle()

        rule.onNodeWithTag("readout").assertTextEquals("Westville")
    }

    @Test
    fun aTapAwayFromEveryMarkFallsThroughToTheRegion() {
        var selection by mutableStateOf<AnyChartSelection?>(null)

        rule.setContent {
            Host {
                val world = remember { GeoJson.parse(geoJson) }
                GeoChart(
                    projection = GeoProjection.Equirectangular,
                    onSelectionChanged = { selection = it },
                    modifier = mapSize.testTag("chart"),
                ) {
                    map(world)
                    points(
                        cities,
                        longitude = { it.longitude },
                        latitude = { it.latitude },
                        label = { it.name },
                    )
                }
                Text(
                    selection?.geoPoint?.label
                        ?: selection?.geo?.featureLabel
                        ?: "nothing",
                    modifier = Modifier.testTag("readout"),
                )
            }
        }

        // Near the left region's own corner, well clear of the mark at its
        // centre. Falling through is what makes a bubble map still a map.
        rule.onNodeWithTag("chart").performTouchInput {
            click(Offset(width * 0.08f, height * 0.5f))
        }
        rule.waitForIdle()

        rule.onNodeWithTag("readout").assertTextEquals("West")
    }

    @Test
    fun aRouteIsSelectableWhereItIsDrawn() {
        var selection by mutableStateOf<AnyChartSelection?>(null)

        rule.setContent {
            Host {
                val world = remember { GeoJson.parse(geoJson) }
                GeoChart(
                    projection = GeoProjection.Equirectangular,
                    onSelectionChanged = { selection = it },
                    modifier = mapSize.testTag("chart"),
                ) {
                    map(world)
                    lines(links, path = { it.path }, label = { it.name })
                }
                Text(
                    selection?.geoRoute?.label ?: selection?.geo?.featureLabel ?: "nothing",
                    modifier = Modifier.testTag("readout"),
                )
            }
        }

        // The middle of the map, where the route runs. A line has no interior,
        // so this is proximity rather than containment — and it must beat the
        // region the route is drawn over.
        rule.onNodeWithTag("chart").performTouchInput {
            click(Offset(width * 0.5f, height * 0.5f))
        }
        rule.waitForIdle()

        rule.onNodeWithTag("readout").assertTextEquals("West to East")
    }

    @Test
    fun aSelectedRouteHandsBackTheCoordinatesItWasGiven() {
        var selection by mutableStateOf<AnyChartSelection?>(null)

        rule.setContent {
            Host {
                val world = remember { GeoJson.parse(geoJson) }
                GeoChart(
                    projection = GeoProjection.Equirectangular,
                    onSelectionChanged = { selection = it },
                    modifier = mapSize.testTag("chart"),
                ) {
                    map(world)
                    lines(links, path = { it.path }, label = { it.name })
                }
                Text(
                    selection?.geoRoute?.coordinates?.size?.toString() ?: "0",
                    modifier = Modifier.testTag("readout"),
                )
            }
        }

        rule.onNodeWithTag("chart").performTouchInput {
            click(Offset(width * 0.5f, height * 0.5f))
        }
        rule.waitForIdle()

        // Two, as supplied — not the pieces the antimeridian repair or the
        // projection might have produced. A caller reading this back gets what
        // they gave.
        rule.onNodeWithTag("readout").assertTextEquals("2")
    }

    @Test
    fun aMarkReportsItsOwnCoordinateAndItsSizeValue() {
        var selection by mutableStateOf<AnyChartSelection?>(null)

        rule.setContent {
            Host {
                GeoChart(
                    projection = GeoProjection.Equirectangular,
                    onSelectionChanged = { selection = it },
                    // Square, and a fixed size, so where the marks land is
                    // arithmetic rather than a property of the test device.
                    // The two marks share a latitude, which makes the extent
                    // degenerate in y until it is opened out — so this also
                    // covers a map that would otherwise report itself empty.
                    modifier = Modifier.size(220.dp).testTag("chart"),
                ) {
                    bubbles(
                        cities,
                        longitude = { it.longitude },
                        latitude = { it.latitude },
                        size = { it.people },
                        label = { it.name },
                    )
                }
                Text(
                    selection?.geoPoint?.let { "${it.coordinate.longitude}/${it.sizeValue}" }
                        ?: "nothing",
                    modifier = Modifier.testTag("readout"),
                )
            }
        }

        // A chart of nothing but marks still fits them. The extent is one
        // degree square after being opened out, so it fills the padded plot and
        // the right-hand mark sits close to its right edge.
        rule.onNodeWithTag("chart").performTouchInput {
            click(Offset(width * 0.93f, height * 0.5f))
        }
        rule.waitForIdle()

        rule.onNodeWithTag("readout").assertTextEquals("1.5/40.0")
    }

    // ---- WorldMap ---------------------------------------------------------

    @Test
    fun aWorldMapDrawsAndSelectsWithNoDataAtAll() {
        var label by mutableStateOf("nothing")

        rule.setContent {
            Host {
                val world = remember { GeoJson.parse(geoJson) }
                WorldMap(
                    geometry = world,
                    projection = GeoProjection.Equirectangular,
                    onSelectionChanged = { label = it?.geo?.featureLabel ?: "nothing" },
                    modifier = mapSize.testTag("chart"),
                )
                Text(label, modifier = Modifier.testTag("readout"))
            }
        }

        rule.onNodeWithTag("chart").performTouchInput {
            click(Offset(width * 0.75f, height * 0.5f))
        }
        rule.waitForIdle()

        rule.onNodeWithTag("readout").assertTextEquals("East")
    }

    @Test
    fun aBaseMapRegionReportsNoValueRatherThanZero() {
        var text by mutableStateOf("nothing")

        rule.setContent {
            Host {
                val world = remember { GeoJson.parse(geoJson) }
                WorldMap(
                    geometry = world,
                    projection = GeoProjection.Equirectangular,
                    onSelectionChanged = { selection ->
                        val geo = selection?.geo
                        text = when {
                            geo == null -> "nothing"
                            geo.hasValue -> "measured"
                            else -> "unmeasured"
                        }
                    },
                    modifier = mapSize.testTag("chart"),
                )
                Text(text, modifier = Modifier.testTag("readout"))
            }
        }

        rule.onNodeWithTag("chart").performTouchInput {
            click(Offset(width * 0.25f, height * 0.5f))
        }
        rule.waitForIdle()

        // An outline map is not missing a measurement — it never claimed one.
        // `hasValue` says so, which is what stops a tooltip printing "0".
        rule.onNodeWithTag("readout").assertTextEquals("unmeasured")
    }

    @Test
    fun labelsAreDrawnWithoutCrashingOnEveryPolicy() {
        var policy by mutableStateOf(GeoLabels.None)

        rule.setContent {
            Host {
                val world = remember { GeoJson.parse(geoJson) }
                WorldMap(
                    geometry = world,
                    projection = GeoProjection.Equirectangular,
                    labels = policy,
                    modifier = mapSize.testTag("chart"),
                )
                Text(policy.name, modifier = Modifier.testTag("readout"))
            }
        }

        GeoLabels.entries.forEach { next ->
            policy = next
            rule.waitForIdle()
            rule.onNodeWithTag("chart").assertIsDisplayed()
            rule.onNodeWithTag("readout").assertTextEquals(next.name)
        }
    }

    // ---- formats ----------------------------------------------------------

    @Test
    fun aTopoJsonMapBehavesExactlyLikeAGeoJsonOne() {
        val topoJson = """
            { "type": "Topology",
              "transform": { "scale": [0.5, 0.5], "translate": [0, 0] },
              "objects": { "regions": { "type": "GeometryCollection", "geometries": [
                { "type": "Polygon", "id": "west", "arcs": [[0, 1]],
                  "properties": { "name": "West" } },
                { "type": "Polygon", "id": "east", "arcs": [[-1, 2]],
                  "properties": { "name": "East" } }
              ] } },
              "arcs": [
                [[2, 0], [0, 2]],
                [[2, 2], [-2, 0], [0, -2], [2, 0]],
                [[2, 0], [2, 0], [0, 2], [-2, 0]]
              ] }
        """.trimIndent()
        var label by mutableStateOf("nothing")

        rule.setContent {
            Host {
                val world = remember { TopoJson.parse(topoJson, "regions") }
                WorldMap(
                    geometry = world,
                    projection = GeoProjection.Equirectangular,
                    onSelectionChanged = { label = it?.geo?.featureLabel ?: "nothing" },
                    modifier = mapSize.testTag("chart"),
                )
                Text(label, modifier = Modifier.testTag("readout"))
            }
        }

        rule.onNodeWithTag("chart").performTouchInput {
            click(Offset(width * 0.75f, height * 0.5f))
        }
        rule.waitForIdle()

        // The same geometry as the GeoJSON fixture above, and the same answer
        // to the same tap. Nothing below the parser can tell them apart.
        rule.onNodeWithTag("readout").assertTextEquals("East")
    }

    @Test
    fun aRegionStraddlingTheAntimeridianIsSelectableFromEitherEdge() {
        val json = """
            { "type": "FeatureCollection", "features": [
              { "type": "Feature", "id": "fiji", "properties": { "name": "Fiji" },
                "geometry": { "type": "Polygon", "coordinates":
                  [[[170,-10],[-170,-10],[-170,10],[170,10],[170,-10]]] } }
            ]}
        """.trimIndent()
        var label by mutableStateOf("nothing")

        rule.setContent {
            Host {
                val world = remember { GeoJson.parse(json) }
                WorldMap(
                    geometry = world,
                    projection = GeoProjection.Equirectangular,
                    onSelectionChanged = { label = it?.geo?.featureLabel ?: "nothing" },
                    modifier = mapSize.testTag("chart"),
                )
                Text(label, modifier = Modifier.testTag("readout"))
            }
        }

        // The far left of the plot, which is the −180° side of the split.
        rule.onNodeWithTag("chart").performTouchInput {
            click(Offset(width * 0.03f, height * 0.5f))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("readout").assertTextEquals("Fiji")

        // And the far right, which is the +180° side. One feature, two pieces,
        // one answer.
        rule.onNodeWithTag("chart").performTouchInput {
            click(Offset(width * 0.97f, height * 0.5f))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("readout").assertTextEquals("Fiji")
    }

    // ---- projections and the camera ---------------------------------------

    @Test
    fun everyProjectionDrawsTheSameGeometry() {
        val projections = listOf(
            GeoProjection.EqualEarth,
            GeoProjection.Mercator,
            GeoProjection.Equirectangular,
        )
        // Driven by state rather than by calling `setContent` per projection:
        // the rule allows one content tree per test, and switching in place is
        // closer to what a caller does anyway.
        var index by mutableStateOf(0)

        rule.setContent {
            Host {
                val world = remember { GeoJson.parse(geoJson) }
                WorldMap(
                    geometry = world,
                    projection = projections[index],
                    modifier = mapSize.testTag("chart"),
                )
                Text(projections[index].name, modifier = Modifier.testTag("readout"))
            }
        }

        projections.indices.forEach { next ->
            index = next
            rule.waitForIdle()
            rule.onNodeWithTag("chart").assertIsDisplayed()
            rule.onNodeWithTag("readout").assertTextEquals(projections[next].name)
        }
    }

    @Test
    fun theCameraZoomsAndFitsWithoutRebuildingTheChart() {
        rule.setContent {
            Host {
                val world = remember { GeoJson.parse(geoJson) }
                val camera = rememberChartGeoViewportState()
                WorldMap(
                    geometry = world,
                    projection = GeoProjection.Equirectangular,
                    viewportState = camera,
                    modifier = mapSize.testTag("chart"),
                )
                Text(
                    if (camera.isReset) "fitted" else "zoomed",
                    modifier = Modifier.testTag("readout"),
                )
                // Driven from the state object rather than from a gesture: the
                // point here is that the chart follows the camera, not that
                // pinch works.
                LaunchedZoom(camera)
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag("readout").assertTextEquals("zoomed")
    }

    @Composable
    private fun LaunchedZoom(camera: io.devkit.chartkit.state.ChartGeoViewportState) {
        androidx.compose.runtime.LaunchedEffect(camera) { camera.zoomTo(3f) }
    }

    @Test
    fun aStaticMapIgnoresEveryGesture() {
        var label by mutableStateOf("nothing")

        rule.setContent {
            Host {
                val world = remember { GeoJson.parse(geoJson) }
                WorldMap(
                    geometry = world,
                    projection = GeoProjection.Equirectangular,
                    interaction = GeoInteraction.None,
                    onSelectionChanged = { label = it?.geo?.featureLabel ?: "nothing" },
                    modifier = mapSize.testTag("chart"),
                )
                Text(label, modifier = Modifier.testTag("readout"))
            }
        }

        rule.onNodeWithTag("chart").performTouchInput {
            click(Offset(width * 0.25f, height * 0.5f))
        }
        rule.waitForIdle()

        rule.onNodeWithTag("readout").assertTextEquals("nothing")
    }

    @Test
    fun anEmptyGeometryShowsTheEmptyStateRatherThanABlankBox() {
        rule.setContent {
            Host {
                WorldMap(
                    geometry = io.devkit.chartkit.geo.GeoFeatureCollection.Empty,
                    emptyContent = { Text("No geography", Modifier.testTag("empty")) },
                    modifier = mapSize.testTag("chart"),
                )
            }
        }

        rule.onNodeWithTag("empty").assertIsDisplayed()
    }
}
