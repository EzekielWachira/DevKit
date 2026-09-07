package io.devkit.chartkit

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartGrid
import io.devkit.chartkit.charts.AreaChart
import io.devkit.chartkit.charts.BarChart
import io.devkit.chartkit.charts.HorizontalBarChart
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.geometry.BarGrouping
import io.devkit.chartkit.geometry.LineInterpolation
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.model.MissingValuePolicy
import io.devkit.chartkit.preview.ChartKitPreviewData
import io.devkit.chartkit.preview.ChartKitPreviewData.MonthlyValue
import org.junit.Rule
import org.junit.Test

/**
 * Composition-level checks that every chart type draws without failing.
 *
 * Pixel comparison is out of scope: the repository has no screenshot-test
 * infrastructure, and standing one up for a single library would be a bigger
 * change than the library. What these do cover is the failure that actually
 * happens — a `NaN` reaching a draw call, or an empty plot area dividing by
 * zero — which surfaces as an exception during composition and fails here.
 */
class ChartRenderingTest {

    @get:Rule
    val rule = createComposeRule()

    private val revenue = ChartKitPreviewData.revenue

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        MaterialTheme { Surface { content() } }
    }

    private fun chartModifier() = Modifier
        .testTag(CHART)
        .fillMaxWidth()
        .height(200.dp)

    @Test
    fun lineChartDraws() {
        rule.setContent {
            Host {
                LineChart(
                    data = revenue,
                    x = { it.month },
                    y = { it.amount },
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun everyLineInterpolationDraws() {
        // One `setContent` and a driving state, rather than one per variant:
        // the rule's activity accepts content only once, and switching state is
        // also the closer analogue of a real configuration change.
        val interpolation = mutableStateOf(LineInterpolation.Linear)
        rule.setContent {
            Host {
                LineChart(
                    data = revenue,
                    x = { it.month },
                    y = { it.amount },
                    interpolation = interpolation.value,
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        LineInterpolation.entries.forEach { entry ->
            rule.runOnUiThread { interpolation.value = entry }
            rule.waitForIdle()
            rule.onNodeWithTag(CHART).assertIsDisplayed()
        }
    }

    @Test
    fun areaChartDraws() {
        rule.setContent {
            Host {
                AreaChart(
                    data = revenue,
                    x = { it.month },
                    y = { it.amount },
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun everyBarGroupingDraws() {
        val grouping = mutableStateOf(BarGrouping.Grouped)
        rule.setContent {
            Host {
                BarChart(
                    series = cohorts(),
                    category = { it.month },
                    value = { it.amount },
                    grouping = grouping.value,
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        BarGrouping.entries.forEach { entry ->
            rule.runOnUiThread { grouping.value = entry }
            rule.waitForIdle()
            rule.onNodeWithTag(CHART).assertIsDisplayed()
        }
    }

    @Test
    fun horizontalBarChartDraws() {
        rule.setContent {
            Host {
                HorizontalBarChart(
                    data = ChartKitPreviewData.productLines,
                    category = { it.month },
                    value = { it.amount },
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun negativeAndMixedValuesDraw() {
        rule.setContent {
            Host {
                BarChart(
                    data = ChartKitPreviewData.netMargin,
                    category = { it.month },
                    value = { it.amount },
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun aConstantSeriesDoesNotCollapse() {
        rule.setContent {
            Host {
                LineChart(
                    data = ChartKitPreviewData.constantSeries,
                    x = { it.month },
                    y = { it.amount },
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun aSinglePointDraws() {
        rule.setContent {
            Host {
                LineChart(
                    data = listOf(MonthlyValue("Jan", 42.0)),
                    x = { it.month },
                    y = { it.amount },
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun missingValuesDrawUnderEveryPolicy() {
        val policy = mutableStateOf(MissingValuePolicy.Break)
        rule.setContent {
            Host {
                LineChart(
                    data = ChartKitPreviewData.sensorReadings,
                    x = { it.atMillis },
                    y = { it.value },
                    missingValuePolicy = policy.value,
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        MissingValuePolicy.entries.forEach { entry ->
            rule.runOnUiThread { policy.value = entry }
            rule.waitForIdle()
            rule.onNodeWithTag(CHART).assertIsDisplayed()
        }
    }

    @Test
    fun aVeryShortChartDoesNotFail() {
        // The plot area is squeezed to nothing by its own axes. Layers must
        // decline to draw rather than divide by a zero-height plot.
        rule.setContent {
            Host {
                LineChart(
                    data = revenue,
                    x = { it.month },
                    y = { it.amount },
                    animation = ChartAnimation.None,
                    modifier = Modifier.testTag(CHART).fillMaxWidth().height(8.dp),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun aDenseSeriesDraws() {
        rule.setContent {
            Host {
                LineChart(
                    data = ChartKitPreviewData.dense(5_000),
                    x = { it.month.toInt() },
                    y = { it.amount },
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun gridAndAxisConfigurationsDraw() {
        val grid = mutableStateOf(ChartGrid.None)
        rule.setContent {
            Host {
                LineChart(
                    data = revenue,
                    x = { it.month },
                    y = { it.amount },
                    grid = grid.value,
                    xAxis = ChartAxis(title = "Month"),
                    yAxis = ChartAxis(title = "Amount", tickCount = 3),
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        ChartGrid.entries.forEach { entry ->
            rule.runOnUiThread { grid.value = entry }
            rule.waitForIdle()
            rule.onNodeWithTag(CHART).assertIsDisplayed()
        }
    }

    @Test
    fun hiddenAxesDraw() {
        rule.setContent {
            Host {
                BarChart(
                    data = revenue,
                    category = { it.month },
                    value = { it.amount },
                    categoryAxis = ChartAxis.Hidden,
                    valueAxis = ChartAxis.Hidden,
                    grid = ChartGrid.None,
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun valueLabelsDraw() {
        rule.setContent {
            Host {
                BarChart(
                    data = revenue,
                    category = { it.month },
                    value = { it.amount },
                    valueLabels = true,
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun animationRunsToCompletionWithValidGeometry() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Host {
                BarChart(
                    data = revenue,
                    category = { it.month },
                    value = { it.amount },
                    animation = ChartAnimation.Default,
                    modifier = chartModifier(),
                )
            }
        }
        // Stepping a controlled clock rather than sleeping: every intermediate
        // frame is drawn, so a `NaN` produced only part-way through the reveal
        // is caught here instead of intermittently in production.
        repeat(12) { rule.mainClock.advanceTimeBy(50) }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun aLegendListsEverySeries() {
        rule.setContent {
            Host {
                LineChart(
                    series = cohorts(),
                    x = { it.month },
                    y = { it.amount },
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithText("New").assertIsDisplayed()
        rule.onNodeWithText("Returning").assertIsDisplayed()
    }

    @Test
    fun customEmptyContentReplacesTheDefault() {
        rule.setContent {
            Host {
                LineChart(
                    data = emptyList<MonthlyValue>(),
                    x = { it.month },
                    y = { it.amount },
                    animation = ChartAnimation.None,
                    emptyContent = { Text("Nothing yet") },
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithText("Nothing yet").assertIsDisplayed()
    }

    @Test
    fun loadingContentReplacesThePlot() {
        rule.setContent {
            Host {
                LineChart(
                    data = revenue,
                    x = { it.month },
                    y = { it.amount },
                    isLoading = true,
                    animation = ChartAnimation.None,
                    loadingContent = { Text("Fetching") },
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithText("Fetching").assertIsDisplayed()
    }

    @Test
    fun errorContentReplacesThePlot() {
        rule.setContent {
            Host {
                LineChart(
                    data = revenue,
                    x = { it.month },
                    y = { it.amount },
                    error = IllegalStateException("offline"),
                    animation = ChartAnimation.None,
                    errorContent = { Text("Failed: ${it.message}") },
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithText("Failed: offline").assertIsDisplayed()
    }

    private fun cohorts(): List<ChartSeries<MonthlyValue>> = listOf(
        ChartSeries("new", "New", ChartKitPreviewData.newCustomers),
        ChartSeries("returning", "Returning", ChartKitPreviewData.returningCustomers),
    )

    private companion object {
        const val CHART = "chart"
    }
}
