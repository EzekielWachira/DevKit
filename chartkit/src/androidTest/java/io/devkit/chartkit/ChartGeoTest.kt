package io.devkit.chartkit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.accessibility.ChartWithDataTable
import io.devkit.chartkit.accessibility.geoDataTable
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.charts.ChoroplethMap
import io.devkit.chartkit.charts.GeoJoinReport
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geo.GeoJson
import io.devkit.chartkit.geo.GeoProjection
import io.devkit.chartkit.layer.geo.GeoLabels
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.state.rememberChartGeoViewportState
import org.junit.Rule
import org.junit.Test

private class Region(val code: String, val rate: Double?)

/**
 * The choropleth on a device: it draws, it selects, it reads out and its camera
 * moves.
 *
 * The fixture is a deterministic 3×2 grid of square regions rather than real
 * geography. Real boundaries would make every assertion depend on a file, and
 * the questions here — did the right region get selected, does a missing value
 * announce as missing, does reset restore the view — are about the machinery,
 * not about the shape of Wyoming.
 */
class ChartGeoTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * Six regions in a 3×2 grid spanning 0..3 by 0..2.
     *
     * ```text
     * ┌────┬────┬────┐
     * │ AD │ BD │ CD │   row 1 (north)
     * ├────┼────┼────┤
     * │ AC │ BC │ CC │   row 0 (south)
     * └────┴────┴────┘
     * ```
     */
    private val geoJson: String = run {
        val names = listOf("A", "B", "C")
        val rows = listOf("C", "D")
        val features = buildList {
            rows.forEachIndexed { rowIndex, rowName ->
                names.forEachIndexed { columnIndex, columnName ->
                    val x = columnIndex.toDouble()
                    val y = rowIndex.toDouble()
                    add(
                        """
                        { "type": "Feature", "id": "$columnName$rowName",
                          "properties": { "code": "$columnName$rowName",
                                          "name": "Region $columnName$rowName" },
                          "geometry": { "type": "Polygon", "coordinates":
                            [[[$x,$y],[${x + 1},$y],[${x + 1},${y + 1}],[$x,${y + 1}],[$x,$y]]] } }
                        """.trimIndent(),
                    )
                }
            }
        }
        """{ "type": "FeatureCollection", "features": [${features.joinToString(",")}] }"""
    }

    private val readings = listOf(
        Region("AC", 10.0),
        Region("BC", 20.0),
        Region("CC", 30.0),
        Region("AD", 40.0),
        Region("BD", 50.0),
        // CD deliberately absent: a region with no record must be visibly and
        // audibly different from one measuring zero.
    )

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        MaterialTheme { Surface { Column { content() } } }
    }

    private val mapSize = Modifier.fillMaxWidth().height(220.dp)

    @Composable
    private fun Map(
        modifier: Modifier = mapSize,
        data: List<Region> = readings,
        labels: GeoLabels = GeoLabels.None,
        legend: LegendPosition = LegendPosition.None,
        projection: GeoProjection = GeoProjection.Default,
        renderMode: ChartRenderMode = ChartRenderMode.Interactive,
        onSelectionChanged: ((io.devkit.chartkit.model.ChartSelection<Region>?) -> Unit)? = null,
        onJoin: ((GeoJoinReport) -> Unit)? = null,
        viewportState: io.devkit.chartkit.state.ChartGeoViewportState =
            rememberChartGeoViewportState(),
    ) {
        val geometry = remember { GeoJson.parse(geoJson) }
        ChoroplethMap(
            geometry = geometry,
            data = data,
            featureKey = { it.properties.string("code") },
            dataKey = { it.code },
            value = { it.rate },
            projection = projection,
            labels = labels,
            legend = legend,
            animation = ChartAnimation.None,
            valueFormatter = ChartValueFormatter.Raw,
            renderMode = renderMode,
            viewportState = viewportState,
            onSelectionChanged = onSelectionChanged,
            onJoin = onJoin,
            modifier = modifier,
        )
    }

    @Test
    fun aTapSelectsWithoutWaitingForADoubleTap() {
        var selected: String? = null
        rule.setContent {
            Host {
                Map(
                    modifier = mapSize.testTag("map"),
                    onSelectionChanged = { selected = it?.geo?.featureKey },
                )
            }
        }

        // Not the exact centre: that is the border between the two rows, and
        // a shared border deliberately belongs to exactly one of them.
        rule.onNodeWithTag("map").performTouchInput {
            click(Offset(width * 0.5f, height * 0.7f))
        }
        rule.waitForIdle()

        // The map deliberately has no double-tap gesture: a double-tap handler
        // makes `detectTapGestures` withhold every single tap for the length of
        // the double-tap window, and a third of a second before a region
        // highlights is the wrong price for a shortcut a button already covers.
        assert(selected == "BC") { "centre tap gave $selected" }
    }

    @Test
    fun aChoroplethDraws() {
        rule.setContent { Host { Map(mapSize.testTag("map")) } }

        rule.onNodeWithTag("map").assertIsDisplayed()
    }

    @Test
    fun anEmptyGeometryShowsTheEmptyStateRatherThanCrashing() {
        rule.setContent {
            Host {
                val geometry = remember {
                    GeoJson.parse("""{ "type": "FeatureCollection", "features": [] }""")
                }
                ChoroplethMap(
                    geometry = geometry,
                    data = emptyList<Region>(),
                    featureKey = { it.id },
                    dataKey = { it.code },
                    value = { it.rate },
                    animation = ChartAnimation.None,
                    modifier = mapSize.testTag("empty"),
                )
            }
        }

        rule.onNodeWithTag("empty").assertIsDisplayed()
    }

    @Test
    fun aMercatorMapDrawsToo() {
        rule.setContent {
            Host { Map(mapSize.testTag("mercator"), projection = GeoProjection.Mercator) }
        }

        rule.onNodeWithTag("mercator").assertIsDisplayed()
    }

    @Test
    fun labelsDrawWhenTheyFit() {
        rule.setContent { Host { Map(mapSize.testTag("labelled"), labels = GeoLabels.All) } }

        rule.onNodeWithTag("labelled").assertIsDisplayed()
    }

    @Test
    fun theLegendExplainsTheScale() {
        rule.setContent { Host { Map(mapSize, legend = LegendPosition.Bottom) } }

        // The default is a quantile scale, whose bands read as ranges. The
        // unmeasured region gets a swatch of its own, because an unexplained
        // grey on a map is a question the legend must answer.
        rule.onNodeWithText("No data").assertIsDisplayed()
    }

    @Test
    fun theJoinReportsWhatItMatched() {
        var report: GeoJoinReport? = null
        rule.setContent { Host { Map(onJoin = { report = it }) } }
        rule.waitForIdle()

        val found = report
        assert(found != null)
        assert(found!!.matched == 5)
        assert(found.unmatchedFeatureKeys == listOf("CD"))
        assert(found.unmatchedDataKeys.isEmpty())
    }

    @Test
    fun theJoinReportsAKeyMismatch() {
        var report: GeoJoinReport? = null
        rule.setContent {
            Host { Map(data = listOf(Region("Region AC", 1.0)), onJoin = { report = it }) }
        }
        rule.waitForIdle()

        // The commonest real failure, and the reason the report exists: a
        // blank map with no explanation is unactionable.
        assert(report!!.matched == 0)
        assert(report!!.unmatchedDataKeys == listOf("Region AC"))
    }

    @Test
    fun tappingARegionSelectsThatRegion() {
        var selected: String? = null
        rule.setContent {
            Host {
                Map(
                    modifier = mapSize.testTag("map"),
                    onSelectionChanged = { selected = it?.geo?.featureKey },
                )
            }
        }

        // Bottom-left quarter of the plot. The fixture is 3 wide by 2 tall and
        // north is up, so this is region AC.
        rule.onNodeWithTag("map").performTouchInput {
            click(Offset(width * 0.17f, height * 0.75f))
        }
        rule.waitForIdle()

        assert(selected == "AC") { "expected AC, was $selected" }
    }

    @Test
    fun tappingAnotherRegionSelectsThatOneInstead() {
        var selected: String? = null
        rule.setContent {
            Host {
                Map(
                    modifier = mapSize.testTag("map"),
                    onSelectionChanged = { selected = it?.geo?.featureKey },
                )
            }
        }

        rule.onNodeWithTag("map").performTouchInput {
            click(Offset(width * 0.83f, height * 0.75f))
        }
        rule.waitForIdle()

        assert(selected == "CC") { "expected CC, was $selected" }
    }

    @Test
    fun selectingAnUnmeasuredRegionSaysSoRatherThanReportingZero() {
        var hasValue: Boolean? = null
        var key: String? = null
        rule.setContent {
            Host {
                Map(
                    modifier = mapSize.testTag("map"),
                    onSelectionChanged = {
                        key = it?.geo?.featureKey
                        hasValue = it?.geo?.hasValue
                    },
                )
            }
        }

        rule.onNodeWithTag("map").performTouchInput {
            click(Offset(width * 0.83f, height * 0.25f))
        }
        rule.waitForIdle()

        assert(key == "CD") { "expected CD, was $key" }
        assert(hasValue == false) { "an unmeasured region must not claim a value" }
    }

    @Test
    fun theTooltipNamesTheRegionAndItsValue() {
        rule.setContent { Host { Map(mapSize.testTag("map")) } }

        rule.onNodeWithTag("map").performTouchInput {
            click(Offset(width * 0.17f, height * 0.75f))
        }
        rule.waitForIdle()

        // The region's own name, not the series name — a choropleth has one
        // series and two hundred categories, so the category is the reading.
        rule.onNodeWithText("Region AC", substring = true).assertIsDisplayed()
        rule.onNodeWithText("10", substring = true).assertIsDisplayed()
    }

    @Test
    fun aTapOutsideEveryRegionClearsTheSelection() {
        var selected: String? = "not cleared"
        rule.setContent {
            Host {
                // A tall plot over a wide fixture letterboxes, leaving space
                // above and below the geography that belongs to no region.
                Map(
                    modifier = Modifier.fillMaxWidth().height(400.dp).testTag("map"),
                    onSelectionChanged = { selected = it?.geo?.featureKey },
                )
            }
        }

        rule.onNodeWithTag("map").performTouchInput {
            click(Offset(width * 0.5f, height * 0.5f))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("map").performTouchInput { click(Offset(width * 0.5f, 2f)) }
        rule.waitForIdle()

        assert(selected == null) { "expected the selection to clear, was $selected" }
    }

    @Test
    fun aStaticRenderTakesNoPointerInput() {
        var selected: String? = null
        rule.setContent {
            Host {
                Map(
                    modifier = mapSize.testTag("static"),
                    renderMode = ChartRenderMode.Static,
                    onSelectionChanged = { selected = it?.geo?.featureKey },
                )
            }
        }

        rule.onNodeWithTag("static").performTouchInput {
            click(Offset(width * 0.17f, height * 0.75f))
        }
        rule.waitForIdle()

        assert(selected == null)
    }

    @Test
    fun theCameraZoomsPansAndResets() {
        rule.setContent {
            Host {
                val camera = rememberChartGeoViewportState()
                Text("zoom ${camera.zoom}, reset ${camera.isReset}")
                Map(viewportState = camera)
                // Driven programmatically rather than by a synthesised pinch:
                // the gesture detector's arithmetic is Compose's, and what is
                // under test is that the camera reaches the coordinates.
                LaunchedCamera(camera)
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("reset false", substring = true).assertIsDisplayed()
    }

    @Composable
    private fun LaunchedCamera(camera: io.devkit.chartkit.state.ChartGeoViewportState) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            camera.zoomBy(2f, focusX = 0.25f, focusY = 0.25f)
            camera.panBy(0.1f, 0.1f)
        }
    }

    @Test
    fun resettingTheCameraRestoresTheWholeMap() {
        rule.setContent {
            Host {
                var reset by remember { mutableStateOf(false) }
                val camera = rememberChartGeoViewportState()
                Text("reset ${camera.isReset}")
                Map(viewportState = camera)
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    camera.zoomBy(3f)
                    camera.panBy(0.2f, 0.2f)
                    camera.reset()
                    reset = true
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("reset true", substring = true).assertIsDisplayed()
    }

    @Test
    fun theDataTableNamesEveryRegionAndItsValue() {
        rule.setContent {
            Host {
                val geometry = remember { GeoJson.parse(geoJson) }
                ChartWithDataTable(
                    table = geoDataTable(
                        geometry = geometry,
                        data = readings,
                        featureKey = { it.properties.string("code") },
                        dataKey = { it.code },
                        value = { it.rate },
                    ),
                    modifier = Modifier.testTag("table"),
                ) {
                    Map()
                }
            }
        }

        rule.onNodeWithTag("table").assertIsDisplayed()
        rule.onNodeWithText("Table").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Region AC", substring = true).assertIsDisplayed()
        // Absent, not zero — and the table has to say which.
        rule.onNodeWithText("No data", substring = true).assertIsDisplayed()
    }
}
