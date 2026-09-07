package io.devkit.chartkit

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.charts.BarChart
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.interaction.ChartSelectionMode
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.preview.ChartKitPreviewData
import io.devkit.chartkit.preview.ChartKitPreviewData.MonthlyValue
import io.devkit.chartkit.state.rememberChartState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Selection, tooltips and legend toggling.
 *
 * Assertions are about *which item* was selected rather than about exact
 * pixels: a test tied to a coordinate breaks whenever a default padding
 * changes, which says nothing about whether selection works.
 */
class ChartInteractionTest {

    @get:Rule
    val rule = createComposeRule()

    private val revenue = ChartKitPreviewData.revenue

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        MaterialTheme { Surface { content() } }
    }

    @Test
    fun tappingABarSelectsIt() {
        var selected: ChartSelection<MonthlyValue>? = null
        rule.setContent {
            Host {
                BarChart(
                    data = revenue,
                    category = { it.month },
                    value = { it.amount },
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selected = it },
                    modifier = Modifier.testTag(CHART).fillMaxWidth().height(200.dp),
                )
            }
        }
        rule.onNodeWithTag(CHART).performClick()
        rule.waitForIdle()

        assertNotNull("a tap in the plot should select something", selected)
        assertTrue(
            "the selection should carry the caller's own item",
            selected!!.item in revenue,
        )
        assertEquals(selected!!.item.amount, selected!!.y, 1e-6)
    }

    @Test
    fun tappingSelectsTheBarUnderTheFinger() {
        var selected: ChartSelection<MonthlyValue>? = null
        rule.setContent {
            Host {
                BarChart(
                    data = revenue,
                    category = { it.month },
                    value = { it.amount },
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selected = it },
                    modifier = Modifier.testTag(CHART).fillMaxWidth().height(200.dp),
                )
            }
        }
        // A click near the left of the plot must not select the last category.
        rule.onNodeWithTag(CHART).performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width * 0.15f, height * 0.6f))
        }
        rule.waitForIdle()
        assertNotNull(selected)
        assertTrue(
            "expected an early month, got ${selected!!.xLabel}",
            selected!!.pointIndex <= 2,
        )
    }

    @Test
    fun scrubbingAcrossALineMovesTheSelection() {
        val seen = mutableListOf<String>()
        rule.setContent {
            Host {
                LineChart(
                    data = revenue,
                    x = { it.month },
                    y = { it.amount },
                    animation = ChartAnimation.None,
                    selectionMode = ChartSelectionMode.TapAndScrub,
                    onSelectionChanged = { selection -> selection?.let { seen += it.xLabel } },
                    modifier = Modifier.testTag(CHART).fillMaxWidth().height(200.dp),
                )
            }
        }
        rule.onNodeWithTag(CHART).performTouchInput { swipeRight() }
        rule.waitForIdle()

        assertTrue("a scrub should report selections, saw $seen", seen.isNotEmpty())
        assertTrue("a scrub across the plot should change selection, saw $seen", seen.distinct().size > 1)
    }

    @Test
    fun selectionModeNoneIgnoresGestures() {
        var selected: ChartSelection<MonthlyValue>? = null
        rule.setContent {
            Host {
                BarChart(
                    data = revenue,
                    category = { it.month },
                    value = { it.amount },
                    animation = ChartAnimation.None,
                    selectionMode = ChartSelectionMode.None,
                    onSelectionChanged = { selected = it },
                    modifier = Modifier.testTag(CHART).fillMaxWidth().height(200.dp),
                )
            }
        }
        rule.onNodeWithTag(CHART).performClick()
        rule.waitForIdle()
        assertNull(selected)
    }

    @Test
    fun aSelectionShowsTheDefaultTooltip() {
        rule.setContent {
            Host {
                BarChart(
                    data = revenue,
                    category = { it.month },
                    value = { it.amount },
                    animation = ChartAnimation.None,
                    modifier = Modifier.testTag(CHART).fillMaxWidth().height(220.dp),
                )
            }
        }
        rule.onNodeWithTag(CHART).performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.7f))
        }
        rule.waitForIdle()
        // The default tooltip reports the category it selected.
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun aCustomTooltipReceivesTheCallersOwnItem() {
        rule.setContent {
            Host {
                BarChart(
                    data = revenue,
                    category = { it.month },
                    value = { it.amount },
                    animation = ChartAnimation.None,
                    tooltip = { selection -> Text("Picked ${selection.item.month}") },
                    modifier = Modifier.testTag(CHART).fillMaxWidth().height(220.dp),
                )
            }
        }
        rule.onNodeWithTag(CHART).performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.7f))
        }
        rule.waitForIdle()
        rule.onNodeWithText("Picked", substring = true).assertIsDisplayed()
    }

    @Test
    fun hoistedStateExposesTheSelectionToTheCaller() {
        rule.setContent {
            Host {
                val state = rememberChartState<MonthlyValue>()
                BarChart(
                    data = revenue,
                    category = { it.month },
                    value = { it.amount },
                    animation = ChartAnimation.None,
                    state = state,
                    modifier = Modifier.testTag(CHART).fillMaxWidth().height(200.dp),
                )
                Text(state.selection?.item?.month ?: "none")
            }
        }
        rule.onNodeWithText("none").assertIsDisplayed()
        rule.onNodeWithTag(CHART).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("none").assertDoesNotExist()
    }

    @Test
    fun aLegendTogglesSeriesVisibility() {
        rule.setContent {
            Host {
                val state = rememberChartState<MonthlyValue>()
                LineChart(
                    series = listOf(
                        ChartSeries("new", "New", ChartKitPreviewData.newCustomers),
                        ChartSeries("returning", "Returning", ChartKitPreviewData.returningCustomers),
                    ),
                    x = { it.month },
                    y = { it.amount },
                    animation = ChartAnimation.None,
                    legendTogglesSeries = true,
                    state = state,
                    modifier = Modifier.testTag(CHART).fillMaxWidth().height(200.dp),
                )
                Text("hidden=${state.hiddenSeriesIds.size}")
            }
        }
        // Two series announced before the toggle.
        rule.onNodeWithText("hidden=0").assertIsDisplayed()
        rule.onNodeWithContentDescription("2 series", substring = true).assertIsDisplayed()

        rule.onNodeWithText("New").performClick()
        rule.waitForIdle()

        // The state changed *and* the chart redrew. Asserting only the former
        // would pass even if the chart's geometry were cached against a key
        // that never invalidates, which is exactly the bug worth catching.
        rule.onNodeWithText("hidden=1").assertIsDisplayed()
        rule.onNodeWithContentDescription("2 series", substring = true).assertDoesNotExist()
    }

    private companion object {
        const val CHART = "chart"
    }
}
