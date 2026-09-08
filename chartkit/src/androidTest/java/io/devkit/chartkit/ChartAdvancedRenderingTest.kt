package io.devkit.chartkit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.annotation.domainRange
import io.devkit.chartkit.annotation.eventMarker
import io.devkit.chartkit.annotation.horizontalRule
import io.devkit.chartkit.annotation.region
import io.devkit.chartkit.annotation.valueRange
import io.devkit.chartkit.annotation.verticalRule
import io.devkit.chartkit.charts.BoxPlot
import io.devkit.chartkit.charts.BubbleChart
import io.devkit.chartkit.charts.CalendarHeatmap
import io.devkit.chartkit.charts.CandlestickChart
import io.devkit.chartkit.charts.Heatmap
import io.devkit.chartkit.charts.Histogram
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.charts.OhlcChart
import io.devkit.chartkit.charts.RadarChart
import io.devkit.chartkit.charts.ScatterChart
import io.devkit.chartkit.charts.ViolinPlot
import io.devkit.chartkit.charts.VolumeChart
import io.devkit.chartkit.geometry.ScatterShape
import io.devkit.chartkit.layer.heatmap.HeatmapCellLabels
import io.devkit.chartkit.layer.statistical.ViolinOverlay
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.preview.ChartKitPreviewData
import io.devkit.chartkit.stats.BoxStatistics
import io.devkit.chartkit.stats.HistogramBins
import io.devkit.chartkit.stats.HistogramMetric
import org.junit.Rule
import org.junit.Test
import java.util.TimeZone

/**
 * Every advanced chart draws, including on the inputs that break arithmetic.
 *
 * The failure these catch is the one that actually happens: a `NaN` reaching a
 * draw call, or a zero-width plot dividing by itself. Both surface as an
 * exception during composition and fail the test.
 */
class ChartAdvancedRenderingTest {

    @get:Rule
    val rule = createComposeRule()

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        MaterialTheme { Surface { content() } }
    }

    private fun chartModifier() = Modifier
        .testTag(CHART)
        .fillMaxWidth()
        .height(240.dp)

    // ---- statistical --------------------------------------------------------

    @Test
    fun scatterChartDraws() {
        rule.setContent {
            Host {
                ScatterChart(
                    data = ChartKitPreviewData.observations,
                    x = { it.height },
                    y = { it.weight },
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun everyScatterShapeDraws() {
        val shape = mutableStateOf(ScatterShape.Circle)
        rule.setContent {
            Host {
                ScatterChart(
                    data = ChartKitPreviewData.observations.take(20),
                    x = { it.height },
                    y = { it.weight },
                    shape = shape.value,
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        ScatterShape.entries.forEach { candidate ->
            rule.runOnUiThread { shape.value = candidate }
            rule.waitForIdle()
            rule.onNodeWithTag(CHART).assertIsDisplayed()
        }
    }

    @Test
    fun multiSeriesScatterDraws() {
        rule.setContent {
            Host {
                ScatterChart(
                    series = listOf(
                        ChartSeries("a", "Group A", ChartKitPreviewData.observations.take(40)),
                        ChartSeries("b", "Group B", ChartKitPreviewData.observations.drop(60)),
                    ),
                    x = { it.height },
                    y = { it.weight },
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithText("Group A").assertIsDisplayed()
        rule.onNodeWithText("Group B").assertIsDisplayed()
    }

    @Test
    fun bubbleChartDraws() {
        rule.setContent {
            Host {
                BubbleChart(
                    data = ChartKitPreviewData.observations.take(25),
                    x = { it.height },
                    y = { it.weight },
                    size = { it.age },
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun everyHistogramBinStrategyDraws() {
        val bins = mutableStateOf<HistogramBins>(HistogramBins.Auto)
        rule.setContent {
            Host {
                Histogram(
                    data = ChartKitPreviewData.responseTimes,
                    value = { it },
                    bins = bins.value,
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        listOf(
            HistogramBins.Auto,
            HistogramBins.Count(12),
            HistogramBins.Width(25.0),
            HistogramBins.Custom(listOf(0.0, 100.0, 250.0, 600.0)),
        ).forEach { candidate ->
            rule.runOnUiThread { bins.value = candidate }
            rule.waitForIdle()
            rule.onNodeWithTag(CHART).assertIsDisplayed()
        }
    }

    @Test
    fun everyHistogramMetricDraws() {
        val metric = mutableStateOf(HistogramMetric.Count)
        rule.setContent {
            Host {
                Histogram(
                    data = ChartKitPreviewData.responseTimes,
                    value = { it },
                    metric = metric.value,
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        HistogramMetric.entries.forEach { candidate ->
            rule.runOnUiThread { metric.value = candidate }
            rule.waitForIdle()
            rule.onNodeWithTag(CHART).assertIsDisplayed()
        }
    }

    @Test
    fun histogramOfConstantValuesDraws() {
        rule.setContent {
            Host {
                Histogram(
                    data = List(50) { 7.0 },
                    value = { it },
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun boxPlotFromRawSamplesDraws() {
        rule.setContent {
            Host {
                BoxPlot(
                    data = ChartKitPreviewData.latencies,
                    label = { it.name },
                    values = { it.samples },
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun boxPlotFromPrecomputedStatisticsDraws() {
        val summaries = listOf(
            "api" to BoxStatistics(100.0, 160.0, 210.0, 280.0, 430.0, listOf(600.0)),
            "web" to BoxStatistics(80.0, 120.0, 150.0, 190.0, 260.0),
        )
        rule.setContent {
            Host {
                BoxPlot(
                    data = summaries,
                    label = { it.first },
                    statistics = { it.second },
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun violinPlotDrawsWithEveryOverlay() {
        val overlay = mutableStateOf(ViolinOverlay.Box)
        rule.setContent {
            Host {
                ViolinPlot(
                    data = ChartKitPreviewData.latencies,
                    label = { it.name },
                    values = { it.samples },
                    overlay = overlay.value,
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        ViolinOverlay.entries.forEach { candidate ->
            rule.runOnUiThread { overlay.value = candidate }
            rule.waitForIdle()
            rule.onNodeWithTag(CHART).assertIsDisplayed()
        }
    }

    @Test
    fun violinOfConstantSamplesDraws() {
        rule.setContent {
            Host {
                ViolinPlot(
                    data = listOf("flat" to List(30) { 5.0 }),
                    label = { it.first },
                    values = { it.second },
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    // ---- density ------------------------------------------------------------

    @Test
    fun heatmapDraws() {
        rule.setContent {
            Host {
                Heatmap(
                    data = ChartKitPreviewData.trafficGrid,
                    x = { it.day },
                    y = { it.hour },
                    value = { it.requests },
                    cellLabels = HeatmapCellLabels.Auto,
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun heatmapWithMissingCellsHiddenDraws() {
        rule.setContent {
            Host {
                Heatmap(
                    data = ChartKitPreviewData.trafficGrid,
                    x = { it.day },
                    y = { it.hour },
                    value = { it.requests },
                    showMissing = false,
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun calendarHeatmapDraws() {
        rule.setContent {
            Host {
                CalendarHeatmap(
                    data = ChartKitPreviewData.dailyActivity,
                    date = { it.dateMillis },
                    value = { it.count },
                    timeZone = TimeZone.getTimeZone("UTC"),
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    // ---- polar --------------------------------------------------------------

    @Test
    fun radarChartDraws() {
        rule.setContent {
            Host {
                RadarChart(
                    series = listOf(
                        ChartSeries("q1", "Q1", ChartKitPreviewData.profileQ1),
                        ChartSeries("q2", "Q2", ChartKitPreviewData.profileQ2),
                    ),
                    metric = { it.skill },
                    value = { it.value },
                    valueRange = 0.0..100.0,
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithText("Q1").assertIsDisplayed()
    }

    @Test
    fun radarWithFewerThanThreeMetricsShowsTheEmptyState() {
        rule.setContent {
            Host {
                RadarChart(
                    data = ChartKitPreviewData.profileQ1.take(2),
                    metric = { it.skill },
                    value = { it.value },
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        // A two-spoke radar is a line, not a chart; the empty state is the
        // honest response rather than a degenerate shape.
        rule.onNodeWithText("No data").assertIsDisplayed()
    }

    // ---- financial ----------------------------------------------------------

    @Test
    fun candlestickChartDraws() {
        rule.setContent {
            Host {
                CandlestickChart(
                    data = ChartKitPreviewData.prices,
                    x = { it.timeMillis },
                    open = { it.open },
                    high = { it.high },
                    low = { it.low },
                    close = { it.close },
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun ohlcChartDraws() {
        rule.setContent {
            Host {
                OhlcChart(
                    data = ChartKitPreviewData.prices.take(40),
                    x = { it.timeMillis },
                    open = { it.open },
                    high = { it.high },
                    low = { it.low },
                    close = { it.close },
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun volumeChartDraws() {
        rule.setContent {
            Host {
                VolumeChart(
                    data = ChartKitPreviewData.prices,
                    x = { it.timeMillis },
                    volume = { it.volume },
                    open = { it.open },
                    close = { it.close },
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun volumeWithoutPricesDrawsNeutral() {
        rule.setContent {
            Host {
                VolumeChart(
                    data = ChartKitPreviewData.prices,
                    x = { it.timeMillis },
                    volume = { it.volume },
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    // ---- annotations --------------------------------------------------------

    @Test
    fun everyAnnotationKindDraws() {
        rule.setContent {
            Host {
                LineChart(
                    data = ChartKitPreviewData.revenue,
                    x = { it.month },
                    y = { it.amount },
                    annotations = listOf(
                        horizontalRule(value = 40_000.0, label = "Target"),
                        verticalRule(at = "Mar", label = "Release"),
                        valueRange(from = 30_000.0, to = 40_000.0, label = "On track"),
                        domainRange(from = "Feb", to = "Apr", label = "Campaign"),
                        region(
                            domainFrom = "Apr", domainTo = "Jun",
                            valueFrom = 35_000.0, valueTo = 45_000.0,
                            label = "Goal",
                        ),
                        eventMarker(at = "May", value = 44_100.0, label = "Peak"),
                    ),
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun anAnnotationAboveTheDataWidensTheAxis() {
        // A target far above every observed value is invisible unless the axis
        // grows to it — and a reader who cannot see the target cannot see the
        // gap to it.
        rule.setContent {
            Host {
                Column {
                    LineChart(
                        data = ChartKitPreviewData.revenue,
                        x = { it.month },
                        y = { it.amount },
                        annotations = listOf(horizontalRule(value = 120_000.0, label = "Stretch")),
                        animation = ChartAnimation.None,
                        modifier = chartModifier(),
                    )
                }
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun everyAdvancedChartSurvivesAnEmptyDataset() {
        val kind = mutableStateOf(0)
        rule.setContent {
            Host {
                when (kind.value) {
                    0 -> ScatterChart(
                        data = emptyList<ChartKitPreviewData.Observation>(),
                        x = { it.height }, y = { it.weight },
                        animation = ChartAnimation.None, modifier = chartModifier(),
                    )
                    1 -> Histogram(
                        data = emptyList<Double>(), value = { it },
                        animation = ChartAnimation.None, modifier = chartModifier(),
                    )
                    2 -> BoxPlot(
                        data = emptyList<ChartKitPreviewData.Distribution>(),
                        label = { it.name }, values = { it.samples },
                        animation = ChartAnimation.None, modifier = chartModifier(),
                    )
                    3 -> Heatmap(
                        data = emptyList<ChartKitPreviewData.GridCell>(),
                        x = { it.day }, y = { it.hour }, value = { it.requests },
                        animation = ChartAnimation.None, modifier = chartModifier(),
                    )
                    else -> CandlestickChart(
                        data = emptyList<ChartKitPreviewData.Candle>(),
                        x = { it.timeMillis },
                        open = { it.open }, high = { it.high },
                        low = { it.low }, close = { it.close },
                        animation = ChartAnimation.None, modifier = chartModifier(),
                    )
                }
            }
        }
        (0..4).forEach { candidate ->
            rule.runOnUiThread { kind.value = candidate }
            rule.waitForIdle()
            rule.onNodeWithText("No data").assertIsDisplayed()
        }
    }

    private companion object {
        const val CHART = "advanced-chart"
    }
}
