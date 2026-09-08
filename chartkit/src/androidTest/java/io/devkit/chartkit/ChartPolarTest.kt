package io.devkit.chartkit

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.charts.DonutChart
import io.devkit.chartkit.charts.PieChart
import io.devkit.chartkit.charts.RadialBarChart
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.geometry.PolarValuePolicy
import io.devkit.chartkit.layer.polar.SliceLabelContent
import io.devkit.chartkit.layer.polar.SliceLabelPosition
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.preview.ChartKitPreviewData
import io.devkit.chartkit.preview.ChartKitPreviewData.Share
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Pie, donut and radial bar, driven through real touch input.
 *
 * The taps are aimed by angle rather than at hardcoded pixels: four equal
 * slices starting at twelve o'clock put the first quarter between three
 * o'clock and twelve, so a tap up and to the right of the centre must land on
 * it whatever the chart's size.
 */
class ChartPolarTest {

    @get:Rule
    val rule = createComposeRule()

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        MaterialTheme { Surface { content() } }
    }

    /** Four equal shares, so each occupies exactly one quadrant. */
    private val quarters = listOf(
        Share("North", 25.0),
        Share("East", 25.0),
        Share("South", 25.0),
        Share("West", 25.0),
    )

    private fun squareModifier() = Modifier.testTag(CHART).size(300.dp)

    @Test
    fun pieChartDraws() {
        rule.setContent {
            Host {
                PieChart(
                    data = ChartKitPreviewData.expenseShares,
                    value = { it.amount },
                    label = { it.category },
                    animation = ChartAnimation.None,
                    modifier = Modifier.testTag(CHART).fillMaxWidth().height(280.dp),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun aPieLegendNamesEverySlice() {
        rule.setContent {
            Host {
                PieChart(
                    data = ChartKitPreviewData.expenseShares,
                    value = { it.amount },
                    label = { it.category },
                    animation = ChartAnimation.None,
                    modifier = Modifier.testTag(CHART).fillMaxWidth().height(300.dp),
                )
            }
        }
        rule.onNodeWithText("Rent").assertIsDisplayed()
        rule.onNodeWithText("Other").assertIsDisplayed()
    }

    @Test
    fun tappingASliceSelectsTheOneUnderTheFinger() {
        var selected: ChartSelection<Share>? = null
        rule.setContent {
            Host {
                PieChart(
                    data = quarters,
                    value = { it.amount },
                    label = { it.category },
                    legend = LegendPosition.None,
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selected = it },
                    modifier = squareModifier(),
                )
            }
        }
        // Up and to the right of the centre — 45°, the first quarter.
        rule.onNodeWithTag(CHART).performTouchInput {
            click(Offset(width * 0.68f, height * 0.32f))
        }
        rule.waitForIdle()
        assertEquals("North", selected?.item?.category)
    }

    @Test
    fun eachQuadrantSelectsItsOwnSlice() {
        var selected: ChartSelection<Share>? = null
        rule.setContent {
            Host {
                PieChart(
                    data = quarters,
                    value = { it.amount },
                    label = { it.category },
                    legend = LegendPosition.None,
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selected = it },
                    modifier = squareModifier(),
                )
            }
        }
        val expectations = listOf(
            0.68f to 0.32f to "North",
            0.68f to 0.68f to "East",
            0.32f to 0.68f to "South",
            0.32f to 0.32f to "West",
        )
        expectations.forEach { (position, label) ->
            rule.onNodeWithTag(CHART).performTouchInput {
                click(Offset(width * position.first, height * position.second))
            }
            rule.waitForIdle()
            assertEquals(label, selected?.item?.category)
        }
    }

    @Test
    fun aSelectionReportsItsShareOfTheWhole() {
        var selected: ChartSelection<Share>? = null
        rule.setContent {
            Host {
                PieChart(
                    data = quarters,
                    value = { it.amount },
                    label = { it.category },
                    legend = LegendPosition.None,
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selected = it },
                    modifier = squareModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).performTouchInput {
            click(Offset(width * 0.68f, height * 0.32f))
        }
        rule.waitForIdle()
        assertNotNull(selected?.polar)
        assertEquals(0.25, selected!!.polar!!.fraction, 1e-6)
        assertEquals("North", selected!!.polar!!.label)
    }

    @Test
    fun tappingOutsideTheCircleClearsTheSelection() {
        var selected: ChartSelection<Share>? = null
        rule.setContent {
            Host {
                PieChart(
                    data = quarters,
                    value = { it.amount },
                    label = { it.category },
                    legend = LegendPosition.None,
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selected = it },
                    modifier = squareModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).performTouchInput {
            click(Offset(width * 0.68f, height * 0.32f))
        }
        rule.waitForIdle()
        assertNotNull(selected)

        // The corner of a square plot is outside the inscribed circle.
        rule.onNodeWithTag(CHART).performTouchInput { click(Offset(2f, 2f)) }
        rule.waitForIdle()
        assertNull(selected)
    }

    @Test
    fun aDonutHoleBelongsToNoSlice() {
        var selected: ChartSelection<Share>? = null
        rule.setContent {
            Host {
                DonutChart(
                    data = quarters,
                    value = { it.amount },
                    label = { it.category },
                    innerRadiusRatio = 0.6f,
                    legend = LegendPosition.None,
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selected = it },
                    modifier = squareModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).performTouchInput {
            click(Offset(width / 2f, height / 2f))
        }
        rule.waitForIdle()
        assertNull("the hole is not a slice", selected)
    }

    @Test
    fun aDonutRingStillSelects() {
        var selected: ChartSelection<Share>? = null
        rule.setContent {
            Host {
                DonutChart(
                    data = quarters,
                    value = { it.amount },
                    label = { it.category },
                    innerRadiusRatio = 0.5f,
                    legend = LegendPosition.None,
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selected = it },
                    modifier = squareModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).performTouchInput {
            click(Offset(width * 0.78f, height * 0.22f))
        }
        rule.waitForIdle()
        assertEquals("North", selected?.item?.category)
    }

    @Test
    fun donutCentreContentIsLaidOutAndReadable() {
        rule.setContent {
            Host {
                DonutChart(
                    data = quarters,
                    value = { it.amount },
                    label = { it.category },
                    legend = LegendPosition.None,
                    animation = ChartAnimation.None,
                    centerContent = { Text("100") },
                    modifier = squareModifier(),
                )
            }
        }
        // A real composable in the hole, not text rasterised onto the canvas —
        // so it is in the semantics tree and a screen reader can read it.
        rule.onNodeWithText("100").assertIsDisplayed()
    }

    @Test
    fun donutCentreContentDoesNotStealSliceTaps() {
        var selected: ChartSelection<Share>? = null
        rule.setContent {
            Host {
                DonutChart(
                    data = quarters,
                    value = { it.amount },
                    label = { it.category },
                    innerRadiusRatio = 0.5f,
                    legend = LegendPosition.None,
                    animation = ChartAnimation.None,
                    centerContent = { Text("100") },
                    onSelectionChanged = { selected = it },
                    modifier = squareModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).performTouchInput {
            click(Offset(width * 0.78f, height * 0.22f))
        }
        rule.waitForIdle()
        assertEquals("North", selected?.item?.category)
    }

    @Test
    fun sliceLabelsDraw() {
        rule.setContent {
            Host {
                PieChart(
                    data = quarters,
                    value = { it.amount },
                    label = { it.category },
                    labelPosition = SliceLabelPosition.Inside,
                    labelContent = SliceLabelContent.Percentage,
                    legend = LegendPosition.None,
                    animation = ChartAnimation.None,
                    modifier = squareModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun outsideSliceLabelsDraw() {
        val position = mutableStateOf(SliceLabelPosition.Inside)
        rule.setContent {
            Host {
                PieChart(
                    data = quarters,
                    value = { it.amount },
                    label = { it.category },
                    labelPosition = position.value,
                    legend = LegendPosition.None,
                    animation = ChartAnimation.None,
                    modifier = squareModifier(),
                )
            }
        }
        rule.runOnUiThread { position.value = SliceLabelPosition.Outside }
        rule.waitForIdle()
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun invalidValuesDrawWithoutCrashing() {
        rule.setContent {
            Host {
                PieChart(
                    data = ChartKitPreviewData.invalidShares,
                    value = { it.amount },
                    label = { it.category },
                    animation = ChartAnimation.None,
                    modifier = Modifier.testTag(CHART).fillMaxWidth().height(280.dp),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun anAllZeroPieShowsItsEmptyState() {
        rule.setContent {
            Host {
                PieChart(
                    data = listOf(Share("A", 0.0), Share("B", 0.0)),
                    value = { it.amount },
                    label = { it.category },
                    animation = ChartAnimation.None,
                    emptyContent = { Text("Nothing to show") },
                    modifier = Modifier.testTag(CHART).fillMaxWidth().height(280.dp),
                )
            }
        }
        rule.onNodeWithText("Nothing to show").assertIsDisplayed()
    }

    @Test
    fun anEmptyPieShowsItsEmptyState() {
        rule.setContent {
            Host {
                PieChart(
                    data = emptyList<Share>(),
                    value = { it.amount },
                    label = { it.category },
                    animation = ChartAnimation.None,
                    emptyContent = { Text("No expenses") },
                    modifier = Modifier.testTag(CHART).fillMaxWidth().height(280.dp),
                )
            }
        }
        rule.onNodeWithText("No expenses").assertIsDisplayed()
    }

    @Test
    fun aSingleSliceFillsTheCircleAndIsSelectable() {
        var selected: ChartSelection<Share>? = null
        rule.setContent {
            Host {
                PieChart(
                    data = listOf(Share("Everything", 7.0)),
                    value = { it.amount },
                    label = { it.category },
                    legend = LegendPosition.None,
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selected = it },
                    modifier = squareModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).performTouchInput {
            click(Offset(width * 0.3f, height * 0.7f))
        }
        rule.waitForIdle()
        assertEquals("Everything", selected?.item?.category)
        assertEquals(1.0, selected!!.polar!!.fraction, 1e-9)
    }

    @Test
    fun aPieAnnouncesItsSharesFactually() {
        rule.setContent {
            Host {
                PieChart(
                    data = quarters,
                    value = { it.amount },
                    label = { it.category },
                    accessibility = ChartAccessibility(title = "Compass"),
                    animation = ChartAnimation.None,
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                )
            }
        }
        rule.onNodeWithContentDescription("Compass", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("North (25%)", substring = true).assertIsDisplayed()
    }

    @Test
    fun aRejectingPieFailsRatherThanDrawingNonsense() {
        var threw = false
        try {
            rule.setContent {
                Host {
                    PieChart(
                        data = listOf(Share("Bad", -1.0), Share("Good", 1.0)),
                        value = { it.amount },
                        label = { it.category },
                        valuePolicy = PolarValuePolicy.Reject,
                        animation = ChartAnimation.None,
                        modifier = squareModifier(),
                    )
                }
            }
            rule.waitForIdle()
        } catch (expected: IllegalArgumentException) {
            threw = true
        } catch (expected: Exception) {
            // Compose wraps composition failures; the cause is what matters.
            threw = generateSequence(expected as Throwable) { it.cause }
                .any { it is IllegalArgumentException }
        }
        assertTrue("the reject policy should have refused a negative share", threw)
    }

    @Test
    fun radialBarChartDraws() {
        rule.setContent {
            Host {
                RadialBarChart(
                    data = ChartKitPreviewData.systemMetrics,
                    value = { it.value },
                    label = { it.name },
                    maxValue = 100.0,
                    animation = ChartAnimation.None,
                    modifier = Modifier.testTag(CHART).fillMaxWidth().height(300.dp),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
        rule.onNodeWithText("CPU").assertIsDisplayed()
    }

    @Test
    fun tappingTheOutermostTrackSelectsTheFirstMetric() {
        var selected: ChartSelection<ChartKitPreviewData.Metric>? = null
        rule.setContent {
            Host {
                RadialBarChart(
                    data = ChartKitPreviewData.systemMetrics,
                    value = { it.value },
                    label = { it.name },
                    maxValue = 100.0,
                    legend = LegendPosition.None,
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selected = it },
                    modifier = squareModifier(),
                )
            }
        }
        // Just inside the top of the circle: the outermost ring.
        rule.onNodeWithTag(CHART).performTouchInput {
            click(Offset(width / 2f, height * 0.06f))
        }
        rule.waitForIdle()
        assertEquals("CPU", selected?.item?.name)
    }

    @Test
    fun aRadialSelectionReportsProgressThroughItsRange() {
        var selected: ChartSelection<ChartKitPreviewData.Metric>? = null
        rule.setContent {
            Host {
                RadialBarChart(
                    data = ChartKitPreviewData.systemMetrics,
                    value = { it.value },
                    label = { it.name },
                    maxValue = 100.0,
                    legend = LegendPosition.None,
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selected = it },
                    modifier = squareModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).performTouchInput {
            click(Offset(width / 2f, height * 0.06f))
        }
        rule.waitForIdle()
        assertEquals(0.72, selected!!.polar!!.fraction, 1e-6)
        assertEquals(72.0, selected!!.y, 1e-9)
    }

    @Test
    fun aRadialChartAnnouncesValuesAgainstTheirRange() {
        rule.setContent {
            Host {
                RadialBarChart(
                    data = ChartKitPreviewData.systemMetrics,
                    value = { it.value },
                    label = { it.name },
                    maxValue = 100.0,
                    accessibility = ChartAccessibility(title = "System"),
                    animation = ChartAnimation.None,
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                )
            }
        }
        rule.onNodeWithContentDescription("out of", substring = true).assertIsDisplayed()
    }

    @Test
    fun aRadialChartWithoutAMaximumDerivesOneFromTheData() {
        rule.setContent {
            Host {
                RadialBarChart(
                    data = ChartKitPreviewData.systemMetrics,
                    value = { it.value },
                    label = { it.name },
                    animation = ChartAnimation.None,
                    modifier = Modifier.testTag(CHART).fillMaxWidth().height(300.dp),
                )
            }
        }
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    private companion object {
        const val CHART = "chart"
    }
}
