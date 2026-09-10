package io.devkit

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.devkit.chartdemo.ChartDemoData
import io.devkit.chartdemo.Scatter3DDemoData
import io.devkit.chartdemo.WorldDemoData
import io.devkit.chartkit.capture.ChartCaptureOptions
import io.devkit.chartkit.capture.chartCapture
import io.devkit.chartkit.capture.rememberChartCaptureState
import io.devkit.chartkit.charts.AreaChart
import io.devkit.chartkit.charts.BarChart
import io.devkit.chartkit.charts.BoxPlot
import io.devkit.chartkit.charts.BubbleChart
import io.devkit.chartkit.charts.BulletChart
import io.devkit.chartkit.charts.CalendarHeatmap
import io.devkit.chartkit.axis.AxisPosition
import io.devkit.chartkit.axis.AxisStyleMode
import io.devkit.chartkit.axis.ChartAxisId
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.charts.CandlestickChart
import io.devkit.chartkit.charts.CartesianChart
import io.devkit.chartkit.charts.ExperimentalChartKitApi
import io.devkit.chartkit.charts.ChoroplethMap
import io.devkit.chartkit.charts.ColumnChart3D
import io.devkit.chartkit.charts.DonutChart
import io.devkit.chartkit.charts.DialGauge
import io.devkit.chartkit.charts.DumbbellChart
import io.devkit.chartkit.charts.OhlcChart
import io.devkit.chartkit.charts.PieChart3D
import io.devkit.chartkit.charts.RangeChart
import io.devkit.chartkit.charts.VennDiagram
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.annotation.horizontalRule
import io.devkit.chartkit.annotation.verticalRule
import io.devkit.chartkit.geometry.BarGrouping
import io.devkit.chartkit.set.SetDefinition
import io.devkit.chartkit.set.SetIntersection
import io.devkit.chartkit.charts.FunnelChart
import io.devkit.chartkit.charts.GaugeChart
import io.devkit.chartkit.charts.Heatmap
import io.devkit.chartkit.charts.Histogram
import io.devkit.chartkit.charts.HorizontalBarChart
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.charts.NetworkGraph
import io.devkit.chartkit.layer.graph.GraphLabels
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.charts.PieChart
import io.devkit.chartkit.charts.RadarChart
import io.devkit.chartkit.charts.RadialBarChart
import io.devkit.chartkit.charts.SankeyChart
import io.devkit.chartkit.charts.ScatterChart
import io.devkit.chartkit.charts.ScatterChart3D
import io.devkit.chartkit.charts.SunburstChart
import io.devkit.chartkit.charts.Treemap
import io.devkit.chartkit.charts.ViolinPlot
import io.devkit.chartkit.charts.VolumeChart
import io.devkit.chartkit.charts.WaterfallChart
import io.devkit.chartkit.charts.WorldMap
import io.devkit.chartkit.export.ChartSvg
import io.devkit.chartkit.geo.GeoJson
import io.devkit.chartkit.geo.GeoProjection
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.scene.ChartSceneState
import io.devkit.chartkit.scene.rememberChartSceneState
import io.devkit.chartkit.theme.ChartKitTheme
import io.devkit.chartkit.theme.materialDerivedChartColors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Renders every documented chart and writes it to disk, for the documentation
 * site.
 *
 * ### Why this is a test and not a script
 *
 * A chart is a Compose composable. There is no way to draw one without a
 * composition, a density and a frame clock, which means a device — so the thing
 * that produces the pictures has to run where the library runs. Making it a
 * test is not a trick; it is the only place that has what a chart needs.
 *
 * It also means the pictures are produced by the **real rendering code**. A
 * screenshot of a demo screen would prove the same thing, but it would be a
 * photograph of a phone rather than the chart itself: cropped by hand, at
 * whatever density that emulator happened to have, and impossible to regenerate
 * identically.
 *
 * ### Vector where possible, raster where not
 *
 * Nine chart composables can describe themselves as a [ChartScene] — the
 * picture as data rather than as draw calls — which [ChartSvg] writes as SVG.
 * Those are written as `.svg`: a few kilobytes, crisp at any zoom, and
 * selectable text.
 *
 * The rest are captured as PNG. That is not a shortcoming of the capture; it is
 * that a layer has to be *taught* to describe itself, and most have not been.
 * `ChartScene.isComplete` says which is which, so this decides per chart rather
 * than by a list that would go stale.
 *
 * ### Both themes
 *
 * Every chart is rendered light and dark, because the docs site follows the
 * reader's system setting and a chart drawn for one is unreadable on the other.
 *
 * Run it through `scripts/capture-docs-assets.sh`, which pulls the output into
 * `docs-assets/`.
 */
@OptIn(ExperimentalChartKitApi::class)
class DocsAssetCaptureTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * One picture: the slug of the documentation page it belongs to, and the
     * chart to draw.
     *
     * The slug is the contract with `scripts/build_docs.py`, which embeds
     * `docs-assets/chartkit/<slug>.{svg,png}` into the page of the same name.
     * Nothing lists the pairings anywhere else, so adding a chart here is all
     * it takes to put it on that page.
     */
    private class Shot(
        val slug: String,
        val content: @Composable (ChartSceneState, Modifier) -> Unit,
    )

    private val demo = ChartDemoData


    private val shots: List<Shot> = listOf(
        Shot("line-chart") { scene, m ->
            LineChart(demo.revenue, x = { it.month }, y = { it.amount }, sceneState = scene, modifier = m)
        },
        Shot("area-chart") { _, m ->
            AreaChart(demo.revenue, x = { it.month }, y = { it.amount }, modifier = m)
        },
        Shot("bar-chart") { scene, m ->
            BarChart(demo.revenue, category = { it.month }, value = { it.amount }, sceneState = scene, modifier = m)
        },
        Shot("horizontal-bars") { _, m ->
            HorizontalBarChart(demo.productLines, category = { it.month }, value = { it.amount }, modifier = m)
        },
        Shot("scatter-chart") { scene, m ->
            ScatterChart(demo.people, x = { it.heightCm }, y = { it.weightKg }, sceneState = scene, modifier = m)
        },
        Shot("bubble-chart") { _, m ->
            // Twenty of the hundred and sixty, and a bounded size range.
            //
            // A bubble chart is read by comparing areas, which needs the areas
            // to be separable: the whole sample at the default sizing drew a
            // single overlapping mass in which no individual bubble — and so no
            // value of the third variable — could be seen at all. A scatter can
            // carry that many points; a bubble chart cannot, and a picture that
            // pretends otherwise teaches the wrong thing about when to reach
            // for one.
            BubbleChart(
                demo.people.filterIndexed { index, _ -> index % 8 == 0 },
                x = { it.heightCm }, y = { it.weightKg }, size = { it.ageYears },
                minBubbleSize = 5.dp, maxBubbleSize = 18.dp,
                modifier = m,
            )
        },
        Shot("histogram") { scene, m ->
            Histogram(demo.responseTimes, value = { value: Double -> value }, sceneState = scene, modifier = m)
        },
        Shot("box-plot") { _, m ->
            BoxPlot(demo.endpoints, label = { it.path }, values = { it.latencies }, modifier = m)
        },
        Shot("violin-plot") { _, m ->
            ViolinPlot(demo.endpoints, label = { it.path }, values = { it.latencies }, modifier = m)
        },
        Shot("pie-chart") { _, m ->
            PieChart(demo.expenseBreakdown, value = { it.amount }, label = { it.category }, modifier = m)
        },
        Shot("donut-chart") { _, m ->
            DonutChart(demo.expenseBreakdown, value = { it.amount }, label = { it.category }, modifier = m)
        },
        Shot("radial-bar-chart") { _, m ->
            RadialBarChart(demo.systemMetrics, value = { it.value }, label = { it.name }, maxValue = 100.0, modifier = m)
        },
        Shot("radar-chart") { _, m ->
            // Scores out of a hundred, on one explicit range. The default
            // per-axis normalisation gives each spoke its own domain, and a
            // single series makes that domain one value wide — every point then
            // sits on the outer ring and the chart is a regular hexagon no
            // matter what the scores are.
            RadarChart(
                demo.profileThisQuarter,
                metric = { it.aspect }, value = { it.score },
                valueRange = 0.0..100.0,
                modifier = m,
            )
        },
        Shot("heatmap") { _, m ->
            Heatmap(demo.trafficGrid, x = { it.day }, y = { it.hour }, value = { it.requests }, modifier = m)
        },
        Shot("calendar-heatmap") { _, m ->
            CalendarHeatmap(demo.dailyActivity, date = { it.dateMillis }, value = { it.commits }, modifier = m)
        },
        Shot("candlestick-chart") { scene, m ->
            CandlestickChart(
                demo.prices,
                x = { it.timeMillis }, open = { it.open }, high = { it.high },
                low = { it.low }, close = { it.close }, sceneState = scene, modifier = m,
            )
        },
        Shot("volume-chart") { scene, m ->
            VolumeChart(
                demo.prices, x = { it.timeMillis }, volume = { it.volume },
                open = { it.open }, close = { it.close }, sceneState = scene, modifier = m,
            )
        },
        Shot("treemap") { _, m ->
            Treemap(demo.company, children = { it.teams }, value = { it.revenue }, label = { it.name }, key = { it.id }, modifier = m)
        },
        Shot("sunburst") { _, m ->
            SunburstChart(demo.company, children = { it.teams }, value = { it.revenue }, label = { it.name }, key = { it.id }, modifier = m)
        },
        Shot("sankey-diagram") { _, m ->
            SankeyChart(
                nodes = demo.flowStages, links = demo.flowSteps,
                nodeId = { it.id }, nodeLabel = { it.name },
                source = { it.from }, target = { it.to }, value = { it.users }, modifier = m,
            )
        },
        Shot("funnel-chart") { _, m ->
            FunnelChart(demo.funnelSteps, label = { it.name }, value = { it.users }, modifier = m)
        },
        Shot("waterfall-chart") { _, m ->
            WaterfallChart(demo.movements, label = { it.name }, value = { it.amount }, modifier = m)
        },
        Shot("bullet-graph") { _, m ->
            BulletChart(demo.kpis, label = { it.name }, actual = { it.actual }, target = { it.target }, modifier = m)
        },
        Shot("gauge-chart") { _, m ->
            GaugeChart(value = 72.0, min = 0.0, max = 100.0, modifier = m)
        },
        Shot("network-graph") { _, m ->
            // The force simulation runs on Dispatchers.Default, which neither
            // `waitForIdle` nor the test frame clock waits for, so a capture
            // taken here caught the layout before it had positions and wrote a
            // blank image. `Static` is the library's own answer: it substitutes
            // the circular layout, which is instant and reproducible — the
            // whole requirement for an exported picture.
            NetworkGraph(
                nodes = demo.services, edges = demo.serviceCalls,
                nodeId = { it.name }, source = { it.from }, target = { it.to },
                nodeWeight = { it.requests },
                renderMode = ChartRenderMode.Static,
                labels = GraphLabels.All,
                modifier = m,
            )
        },
        Shot("multiple-series") { scene, m ->
            LineChart(
                series = listOf(
                    ChartSeries("revenue", "Revenue", demo.revenue),
                    ChartSeries("expenses", "Expenses", demo.expenses),
                ),
                x = { it.month }, y = { it.amount }, sceneState = scene, modifier = m,
            )
        },
        Shot("3d-columns") { _, m ->
            ColumnChart3D(demo.revenue, category = { it.month }, value = { it.amount }, modifier = m)
        },
        Shot("3d-scatter") { _, m ->
            ScatterChart3D(
                Scatter3DDemoData.survey,
                x = { it.age }, y = { it.income }, z = { it.satisfaction }, modifier = m,
            )
        },
        Shot("geographic-maps") { _, m ->
            ChoroplethMap(
                geometry = worldGeometry(),
                data = WorldDemoData.statistics(worldGeometry()),
                featureKey = WorldDemoData.featureKey,
                dataKey = { it.code },
                value = { it.value },
                featureLabel = WorldDemoData.featureLabel,
                projection = GeoProjection.World,
                modifier = m,
            )
        },
    )

    /** The rest of the catalogue, kept separate only to stay under Kotlin's
     *  limit on how large a single expression may be. */
    private val moreShots: List<Shot> = listOf(
        Shot("grouped-bars") { scene, m ->
            BarChart(
                series = cohorts(), category = { it.quarter }, value = { it.customers },
                grouping = BarGrouping.Grouped, sceneState = scene, modifier = m,
            )
        },
        Shot("stacked-bars") { scene, m ->
            BarChart(
                series = cohorts(), category = { it.quarter }, value = { it.customers },
                grouping = BarGrouping.Stacked, sceneState = scene, modifier = m,
            )
        },
        Shot("100-stacked-bars") { scene, m ->
            BarChart(
                series = cohorts(), category = { it.quarter }, value = { it.customers },
                grouping = BarGrouping.StackedPercent, sceneState = scene, modifier = m,
            )
        },
        Shot("ohlc-chart") { _, m ->
            OhlcChart(
                demo.prices, x = { it.timeMillis }, open = { it.open }, high = { it.high },
                low = { it.low }, close = { it.close }, modifier = m,
            )
        },
        Shot("dumbbell-and-lollipop") { _, m ->
            DumbbellChart(
                demo.teamChanges, category = { it.team },
                start = { it.before }, end = { it.after },
                startLabel = "Before", endLabel = "After", modifier = m,
            )
        },
        Shot("dial-gauges") { _, m ->
            DialGauge(value = 128.0, min = 0.0, max = 200.0, label = "Speed", unit = "km/h", modifier = m)
        },
        Shot("timeline-range-and-gantt-charts") { _, m ->
            RangeChart(
                demo.roadmap, start = { it.startMillis }, end = { it.endMillis },
                lane = { it.stream }, label = { it.name }, modifier = m,
            )
        },
        Shot("set-relationship-diagrams") { _, m ->
            VennDiagram(
                sets = listOf(
                    SetDefinition(id = "android", label = "Android", value = 200.0),
                    SetDefinition(id = "ios", label = "iOS", value = 160.0),
                    SetDefinition(id = "web", label = "Web", value = 120.0),
                ),
                intersections = listOf(
                    SetIntersection(sets = setOf("android", "ios"), value = 70.0),
                    SetIntersection(sets = setOf("android", "web"), value = 45.0),
                    SetIntersection(sets = setOf("ios", "web"), value = 30.0),
                    SetIntersection(sets = setOf("android", "ios", "web"), value = 18.0),
                ),
                modifier = m,
            )
        },
        Shot("3d-pie-and-donut") { _, m ->
            PieChart3D(
                demo.expenseBreakdown, value = { it.amount }, label = { it.category },
                modifier = m,
            )
        },
        Shot("annotations") { scene, m ->
            LineChart(
                demo.revenue, x = { it.month }, y = { it.amount },
                annotations = listOf(
                    horizontalRule(value = 38_000.0, label = "Target"),
                    verticalRule(at = "Mar", label = "v2.0"),
                ),
                sceneState = scene, modifier = m,
            )
        },
        Shot("value-labels") { scene, m ->
            BarChart(
                demo.revenue, category = { it.month }, value = { it.amount },
                valueLabels = true, sceneState = scene, modifier = m,
            )
        },
        Shot("combined-charts") { _, m ->
            CartesianChart(modifier = m, legend = LegendPosition.Bottom) {
                bars(
                    series = listOf(ChartSeries("actual", "Actual", demo.revenue)),
                    category = { it.month }, value = { it.amount },
                )
                line(
                    series = listOf(ChartSeries("target", "Target", demo.forecast)),
                    x = { it.month }, y = { it.amount },
                )
            }
        },
        Shot("multi-axis-combo-charts") { _, m ->
            val revenueAxis = ChartAxisId("revenue")
            val marginAxis = ChartAxisId("margin")
            CartesianChart(modifier = m, legend = LegendPosition.Bottom) {
                yAxis(
                    id = revenueAxis, position = AxisPosition.Start, title = "Revenue",
                    domain = DomainPolicy.IncludeZero(), primary = true,
                )
                yAxis(
                    id = marginAxis, position = AxisPosition.End, title = "Margin",
                    domain = DomainPolicy.Auto(), style = AxisStyleMode.MatchSeries,
                )
                bars(
                    series = listOf(ChartSeries("revenue", "Revenue", demo.revenue)),
                    category = { it.month }, value = { it.amount }, yAxis = revenueAxis,
                )
                line(
                    series = listOf(ChartSeries("margin", "Margin", demo.netMargin)),
                    x = { it.month }, y = { it.amount }, yAxis = marginAxis,
                )
            }
        },
        Shot("legends") { scene, m ->
            LineChart(
                series = listOf(
                    ChartSeries("revenue", "Revenue", demo.revenue),
                    ChartSeries("expenses", "Expenses", demo.expenses),
                ),
                x = { it.month }, y = { it.amount },
                legend = LegendPosition.Bottom, sceneState = scene, modifier = m,
            )
        },
    )

    private fun cohorts() = listOf(
        ChartSeries("new", "New", demo.newCustomers),
        ChartSeries("returning", "Returning", demo.returningCustomers),
        ChartSeries("churned", "Churned", demo.churnedCustomers),
    )

    private var cachedWorld: io.devkit.chartkit.geo.GeoFeatureCollection? = null

    private fun worldGeometry(): io.devkit.chartkit.geo.GeoFeatureCollection {
        cachedWorld?.let { return it }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = context.assets.open(WorldDemoData.ASSET).readBytes().decodeToString()
        return io.devkit.chartkit.geo.TopoJson.parse(text, "countries").also { cachedWorld = it }
    }

    /** Every shot, in one list. */
    private val all: List<Shot> get() = shots + moreShots

    @Test
    fun captureEveryDocumentedChart() {
        val outputs = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "docs-assets/chartkit",
        )
        outputs.deleteRecursively()
        outputs.mkdirs()

        var index by mutableStateOf(0)
        var dark by mutableStateOf(false)
        var live: Pair<ChartSceneState, io.devkit.chartkit.capture.ChartCaptureState>? = null

        rule.setContent {
            // Keyed on the shot, so each chart gets its own scene and capture
            // state rather than inheriting the previous chart's.
            key(index, dark) {
                val scene = rememberChartSceneState()
                val capture = rememberChartCaptureState()
                SideEffect { live = scene to capture }
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    ChartKitTheme(colors = materialDerivedChartColors(isDark = dark)) {
                        Surface {
                            Box(Modifier.size(WIDTH, HEIGHT)) {
                                all[index].content(scene, Modifier.fillMaxSize().chartCapture(capture))
                            }
                        }
                    }
                }
            }
        }

        var vector = 0
        var raster = 0

        all.indices.forEach { shotIndex ->
            listOf(false, true).forEach { isDark ->
                index = shotIndex
                dark = isDark
                rule.waitForIdle()
                // A second settle: the first frame lays out, the second draws.
                rule.mainClock.advanceTimeBy(600)
                rule.waitForIdle()

                val (scene, capture) = live ?: error("nothing composed for ${all[shotIndex].slug}")
                val suffix = if (isDark) "-dark" else ""
                val slug = all[shotIndex].slug

                val current = scene.scene
                if (current != null && current.isComplete) {
                    File(outputs, "$slug$suffix.svg").writeText(
                        ChartSvg.render(current, title = slug.replace('-', ' ')),
                    )
                    vector++
                } else {
                    val bitmap = runBlocking {
                        capture.capture(
                            ChartCaptureOptions(
                                scale = 1f,
                                // Opaque: a transparent chart is invisible on
                                // whichever background the reader's browser has.
                                background = if (isDark) DARK_PAPER else LIGHT_PAPER,
                            ),
                        )
                    }
                    File(outputs, "$slug$suffix.png").outputStream().use { out ->
                        bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    raster++
                }
            }
        }

        val written = outputs.listFiles().orEmpty()
        println("docs-assets: ${written.size} files ($vector vector, $raster raster) in $outputs")
        written.sortedBy { it.name }.forEach { println("  ${it.name}  ${it.length()} bytes") }
        assertTrue("nothing was written", written.isNotEmpty())
        assertTrue(
            "expected two files per chart, got ${written.size} for ${all.size} charts",
            written.size == all.size * 2,
        )
    }

    private companion object {
        val WIDTH = 400.dp
        val HEIGHT = 260.dp

        /** Material's own surfaces, so a capture matches the docs page around it. */
        val LIGHT_PAPER = Color(0xFFFEF7FF)
        val DARK_PAPER = Color(0xFF141218)
    }
}
