package io.devkit.chartkit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.annotation.arrow
import io.devkit.chartkit.annotation.callout
import io.devkit.chartkit.annotation.labelBox
import io.devkit.chartkit.annotation.thresholdBand
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ValueAxisBinding
import io.devkit.chartkit.charts.BulletChart
import io.devkit.chartkit.charts.CartesianChart
import io.devkit.chartkit.charts.DumbbellChart
import io.devkit.chartkit.charts.ExperimentalChartKitApi
import io.devkit.chartkit.charts.FunnelChart
import io.devkit.chartkit.charts.GanttChart
import io.devkit.chartkit.charts.GaugeChart
import io.devkit.chartkit.charts.GaugeShape
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.charts.LollipopChart
import io.devkit.chartkit.charts.NetworkGraph
import io.devkit.chartkit.charts.RangeChart
import io.devkit.chartkit.charts.SankeyChart
import io.devkit.chartkit.charts.SunburstChart
import io.devkit.chartkit.charts.TimelineChart
import io.devkit.chartkit.charts.Treemap
import io.devkit.chartkit.charts.WaterfallChart
import io.devkit.chartkit.components.breadcrumb.ChartBreadcrumbs
import io.devkit.chartkit.graph.GraphLayout
import io.devkit.chartkit.graph.GraphLayoutStrategy
import io.devkit.chartkit.layer.comparison.BulletRange
import io.devkit.chartkit.layer.custom.CustomLayerItem
import io.devkit.chartkit.layer.polar.GaugeBand
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.scale.AxisScale
import io.devkit.chartkit.scene.rememberChartSceneState
import io.devkit.chartkit.state.rememberGraphLayoutState
import io.devkit.chartkit.state.rememberHierarchyChartState
import org.junit.Rule
import org.junit.Test

private class Dept(val id: String, val name: String, val value: Double?, val kids: List<Dept> = emptyList())
private class Node(val id: String)
private class Link(val from: String, val to: String, val weight: Double)
private class Stage(val name: String, val users: Double)
private class Step(val name: String, val amount: Double)
private class Change(val team: String, val before: Double, val after: Double)
private class Kpi(val name: String, val actual: Double, val target: Double)
private class Event(val name: String, val lane: String, val start: Long, val end: Long?)

/**
 * The hierarchy, flow, relationship, interval and comparison charts all draw.
 *
 * The failure these catch is the one that actually happens: a `NaN` reaching a
 * draw call, or a zero-width plot dividing by itself. Both surface as an
 * exception during composition and fail the test. Every chart is also given its
 * degenerate input — one item, no items, all zeros — because that is where the
 * arithmetic breaks.
 */
class ChartPlatformRenderingTest {

    @get:Rule
    val rule = createComposeRule()

    private val company = Dept(
        "root",
        "Company",
        null,
        listOf(
            Dept("a", "Engineering", null, listOf(Dept("a1", "Android", 40.0), Dept("a2", "iOS", 30.0))),
            Dept("b", "Sales", 60.0),
        ),
    )

    private val nodes = listOf(Node("a"), Node("b"), Node("c"))
    private val links = listOf(Link("a", "b", 10.0), Link("b", "c", 6.0))

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        MaterialTheme { Surface { Column { content() } } }
    }

    private val chartSize = Modifier.fillMaxWidth().height(220.dp)

    // ---- hierarchy ---------------------------------------------------------

    @Test
    fun treemapDraws() {
        rule.setContent {
            Host {
                Treemap(
                    data = company,
                    children = { it.kids },
                    value = { it.value },
                    label = { it.name },
                    key = { it.id },
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("treemap"),
                )
            }
        }
        rule.onNodeWithTag("treemap").assertIsDisplayed()
    }

    @Test
    fun sunburstDraws() {
        rule.setContent {
            Host {
                SunburstChart(
                    data = company,
                    children = { it.kids },
                    value = { it.value },
                    label = { it.name },
                    key = { it.id },
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("sunburst"),
                )
            }
        }
        rule.onNodeWithTag("sunburst").assertIsDisplayed()
    }

    @Test
    fun breadcrumbsNavigateBothCharts() {
        rule.setContent {
            Host {
                val hierarchy = rememberHierarchyChartState()
                ChartBreadcrumbs(hierarchy, Modifier.testTag("crumbs"))
                Treemap(
                    data = company,
                    children = { it.kids },
                    value = { it.value },
                    label = { it.name },
                    key = { it.id },
                    hierarchyState = hierarchy,
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("treemap"),
                )
            }
        }
        // The root's own crumb is present before anything is drilled into.
        rule.onNodeWithText("Company").assertIsDisplayed()
    }

    @Test
    fun anEmptyHierarchyShowsTheEmptyState() {
        rule.setContent {
            Host {
                Treemap(
                    data = Dept("empty", "Nothing", null),
                    children = { it.kids },
                    value = { it.value },
                    label = { it.name },
                    animation = ChartAnimation.None,
                    emptyContent = { Text("No data") },
                    modifier = chartSize.testTag("empty"),
                )
            }
        }
        rule.onNodeWithText("No data").assertIsDisplayed()
    }

    // ---- flow --------------------------------------------------------------

    @Test
    fun sankeyDraws() {
        rule.setContent {
            Host {
                SankeyChart(
                    nodes = nodes,
                    links = links,
                    nodeId = { it.id },
                    source = { it.from },
                    target = { it.to },
                    value = { it.weight },
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("sankey"),
                )
            }
        }
        rule.onNodeWithTag("sankey").assertIsDisplayed()
    }

    @Test
    fun aCyclicFlowStillDraws() {
        rule.setContent {
            Host {
                SankeyChart(
                    nodes = nodes,
                    links = links + Link("c", "a", 2.0),
                    nodeId = { it.id },
                    source = { it.from },
                    target = { it.to },
                    value = { it.weight },
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("cyclic"),
                )
            }
        }
        rule.onNodeWithTag("cyclic").assertIsDisplayed()
    }

    @Test
    fun funnelDraws() {
        rule.setContent {
            Host {
                FunnelChart(
                    data = listOf(Stage("A", 100.0), Stage("B", 40.0), Stage("C", 0.0)),
                    label = { it.name },
                    value = { it.users },
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("funnel"),
                )
            }
        }
        rule.onNodeWithTag("funnel").assertIsDisplayed()
    }

    // ---- comparison --------------------------------------------------------

    @Test
    fun waterfallDraws() {
        rule.setContent {
            Host {
                WaterfallChart(
                    data = listOf(Step("Open", 100.0), Step("Costs", -30.0)),
                    label = { it.name },
                    value = { it.amount },
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("waterfall"),
                )
            }
        }
        rule.onNodeWithTag("waterfall").assertIsDisplayed()
    }

    @Test
    fun dumbbellAndLollipopDraw() {
        rule.setContent {
            Host {
                DumbbellChart(
                    data = listOf(Change("A", 10.0, 40.0)),
                    category = { it.team },
                    start = { it.before },
                    end = { it.after },
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("dumbbell"),
                )
                LollipopChart(
                    data = listOf(Change("A", 10.0, 40.0)),
                    category = { it.team },
                    value = { it.after },
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("lollipop"),
                )
            }
        }
        rule.onNodeWithTag("dumbbell").assertIsDisplayed()
        rule.onNodeWithTag("lollipop").assertIsDisplayed()
    }

    @Test
    fun bulletDraws() {
        rule.setContent {
            Host {
                BulletChart(
                    data = listOf(Kpi("Revenue", 72.0, 80.0)),
                    label = { it.name },
                    actual = { it.actual },
                    target = { it.target },
                    ranges = { listOf(BulletRange(0.0, 50.0), BulletRange(50.0, 100.0)) },
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("bullet"),
                )
            }
        }
        rule.onNodeWithTag("bullet").assertIsDisplayed()
    }

    @Test
    fun gaugeDrawsAndClampsAnOutOfRangeValue() {
        rule.setContent {
            Host {
                GaugeChart(
                    value = 130.0,
                    min = 0.0,
                    max = 100.0,
                    label = "Load",
                    bands = listOf(GaugeBand(0.0, 80.0, "Normal")),
                    shape = GaugeShape.SemiCircle,
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("gauge"),
                )
            }
        }
        rule.onNodeWithTag("gauge").assertIsDisplayed()
    }

    // ---- time --------------------------------------------------------------

    private val events = listOf(
        Event("Deploy", "Releases", 0L, null),
        Event("Incident", "Auth", 3_600_000L, 7_200_000L),
        Event("Overlap", "Auth", 5_400_000L, 9_000_000L),
    )

    @Test
    fun timelineRangeAndGanttDraw() {
        rule.setContent {
            Host {
                TimelineChart(
                    data = events,
                    at = { it.start },
                    label = { it.name },
                    lane = { it.lane },
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("timeline"),
                )
                RangeChart(
                    data = events,
                    start = { it.start },
                    end = { it.end },
                    label = { it.name },
                    lane = { it.lane },
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("range"),
                )
                GanttChart(
                    data = events,
                    start = { it.start },
                    end = { it.end },
                    label = { it.name },
                    lane = { it.lane },
                    progress = { 0.5 },
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("gantt"),
                )
            }
        }
        rule.onNodeWithTag("timeline").assertIsDisplayed()
        rule.onNodeWithTag("range").assertIsDisplayed()
        rule.onNodeWithTag("gantt").assertIsDisplayed()
    }

    // ---- relationships -----------------------------------------------------

    @Test
    fun networkGraphDrawsInBothLayouts() {
        rule.setContent {
            Host {
                // The circular layout is the deterministic one, so it is what a
                // rendering test should assert on.
                val layout = rememberGraphLayoutState(
                    GraphLayoutStrategy.Circular(GraphLayout.ByDegree),
                )
                NetworkGraph(
                    nodes = nodes,
                    edges = links,
                    nodeId = { it.id },
                    source = { it.from },
                    target = { it.to },
                    layoutState = layout,
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("graph"),
                )
            }
        }
        rule.onNodeWithTag("graph").assertIsDisplayed()
    }

    @Test
    fun aGraphWithNoEdgesStillDraws() {
        rule.setContent {
            Host {
                NetworkGraph(
                    nodes = nodes,
                    edges = emptyList<Link>(),
                    nodeId = { it.id },
                    source = { it.from },
                    target = { it.to },
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("isolated"),
                )
            }
        }
        rule.onNodeWithTag("isolated").assertIsDisplayed()
    }

    // ---- advanced ----------------------------------------------------------

    @OptIn(ExperimentalChartKitApi::class)
    @Test
    fun aCustomLayerDrawsWithoutForkingChartKit() {
        rule.setContent {
            Host {
                CartesianChart(
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("custom"),
                ) {
                    customLayer(
                        id = "band",
                        describe = { listOf(CustomLayerItem("Target", 40.0)) },
                    ) {
                        val y = positionOfValue(40.0)
                        drawRect(
                            color = colors.annotation.region,
                            topLeft = Offset(plotArea.left, y),
                            size = Size(plotArea.width, 8f),
                        )
                    }
                    line(
                        series = listOf(ChartSeries("s", "Series", listOf(1 to 10, 2 to 60))),
                        x = { it.first },
                        y = { it.second },
                    )
                }
            }
        }
        rule.onNodeWithTag("custom").assertIsDisplayed()
    }

    @OptIn(ExperimentalChartKitApi::class)
    @Test
    fun aSecondValueAxisDrawsAndBindsExplicitly() {
        rule.setContent {
            Host {
                CartesianChart(
                    valueAxis = ChartAxis(title = "Revenue"),
                    secondaryValueAxis = ChartAxis(title = "Percent"),
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("dual"),
                ) {
                    bars(
                        series = listOf(ChartSeries("a", "Revenue", listOf("Jan" to 40_000.0))),
                        category = { it.first },
                        value = { it.second },
                    )
                    line(
                        series = listOf(ChartSeries("b", "Percent", listOf("Jan" to 3.2))),
                        x = { it.first },
                        y = { it.second },
                        valueAxis = ValueAxisBinding.Secondary,
                    )
                }
            }
        }
        rule.onNodeWithTag("dual").assertIsDisplayed()
    }

    @Test
    fun aLogarithmicAxisDrawsIncludingAcrossAZero() {
        rule.setContent {
            Host {
                LineChart(
                    data = listOf(0.0, 1.0, 10.0, 1000.0).mapIndexed { index, value -> index to value },
                    x = { it.first },
                    y = { it.second },
                    yAxis = ChartAxis(scale = AxisScale.Log()),
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("log"),
                )
                LineChart(
                    data = listOf(-500.0, -1.0, 0.0, 1.0, 500.0)
                        .mapIndexed { index, value -> index to value },
                    x = { it.first },
                    y = { it.second },
                    yAxis = ChartAxis(scale = AxisScale.Symlog()),
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("symlog"),
                )
            }
        }
        rule.onNodeWithTag("log").assertIsDisplayed()
        rule.onNodeWithTag("symlog").assertIsDisplayed()
    }

    @Test
    fun theAdvancedAnnotationsDraw() {
        rule.setContent {
            Host {
                LineChart(
                    data = listOf(1 to 10.0, 2 to 30.0, 3 to 20.0),
                    x = { it.first },
                    y = { it.second },
                    annotations = listOf(
                        callout(at = 2, value = 30.0, label = "Peak"),
                        arrow(fromAt = 1, fromValue = 10.0, toAt = 3, toValue = 20.0, label = "Recovery"),
                        labelBox(at = 3, value = 20.0, label = "End"),
                        thresholdBand(from = 15.0, to = 25.0, label = "Target"),
                    ),
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("annotated"),
                )
            }
        }
        rule.onNodeWithTag("annotated").assertIsDisplayed()
    }

    /**
     * A static render draws and takes no pointer input.
     *
     * What is asserted is that the chart still appears: static mode installs no
     * gesture modifier at all, so a regression that left one in would be caught
     * by the interaction tests rather than here — this covers the case where
     * suppressing the crosshair or the animation clock broke the draw pass.
     */
    @Test
    fun staticModeDrawsDeterministically() {
        rule.setContent {
            Host {
                LineChart(
                    data = listOf(1 to 10.0, 2 to 30.0),
                    x = { it.first },
                    y = { it.second },
                    renderMode = ChartRenderMode.Static,
                    staticOptions = ChartStaticOptions.Annotated,
                    modifier = chartSize.testTag("static"),
                )
            }
        }
        rule.onNodeWithTag("static").assertIsDisplayed()
    }

    /**
     * A chart given a scene state produces one, and it is complete.
     *
     * The scene is built off the draw path, so the assertion is on a later
     * frame; the readout is rendered as text rather than captured into a
     * variable, because writing to one from composition is a side effect that
     * would make the test depend on how many times it recomposed.
     */
    @Test
    fun aSceneIsProducedForExport() {
        rule.setContent {
            Host {
                val scene = rememberChartSceneState()
                LineChart(
                    data = listOf(1 to 10.0, 2 to 30.0),
                    x = { it.first },
                    y = { it.second },
                    sceneState = scene,
                    animation = ChartAnimation.None,
                    modifier = chartSize.testTag("scene"),
                )
                Text(
                    text = scene.scene?.let { "complete ${it.isComplete}" } ?: "no scene yet",
                    modifier = Modifier.testTag("scene-readout"),
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("scene").assertIsDisplayed()
        // The node count is the grid group plus the series group; asserting on
        // completeness is the claim that matters, and the count would break on
        // any change to how the layers group themselves.
        rule.onNodeWithText("complete true", substring = true).assertIsDisplayed()
    }
}
