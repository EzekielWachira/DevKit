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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.charts.CandlestickChart
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.charts.ScatterChart
import io.devkit.chartkit.charts.VolumeChart
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.preview.ChartKitPreviewData
import io.devkit.chartkit.state.rememberChartInteractionGroup
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.viewport.ChartViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Linked charts: one viewport, one crosshair, independent value axes.
 *
 * The properties asserted are the ones a dashboard depends on and that a
 * screenshot would not show: that moving one chart's window moves the other's,
 * that a scrub on one selects on the other, and that the two keep their own
 * value scales while doing it.
 */
class ChartLinkedInteractionTest {

    @get:Rule
    val rule = createComposeRule()

    private val prices = ChartKitPreviewData.prices

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        MaterialTheme { Surface { content() } }
    }

    @Test
    fun aSharedViewportMovesBothCharts() {
        lateinit var group: io.devkit.chartkit.state.ChartInteractionGroup
        rule.setContent {
            Host {
                group = rememberChartInteractionGroup()
                Column {
                    CandlestickChart(
                        data = prices,
                        x = { it.timeMillis },
                        open = { it.open }, high = { it.high },
                        low = { it.low }, close = { it.close },
                        viewportState = group.viewport,
                        sharedCrosshair = group.crosshair,
                        animation = ChartAnimation.None,
                        modifier = Modifier.testTag(PRICE).fillMaxWidth().height(200.dp),
                    )
                    VolumeChart(
                        data = prices,
                        x = { it.timeMillis },
                        volume = { it.volume },
                        open = { it.open }, close = { it.close },
                        viewportState = group.viewport,
                        sharedCrosshair = group.crosshair,
                        animation = ChartAnimation.None,
                        modifier = Modifier.testTag(VOLUME).fillMaxWidth().height(120.dp),
                    )
                }
            }
        }

        rule.runOnIdle { group.viewport.viewport = ChartViewport(0.6, 0.9) }
        rule.waitForIdle()

        // One state object, read by both charts: there is no propagation step
        // to get wrong, which is the point of sharing state rather than
        // exchanging callbacks.
        rule.runOnIdle {
            assertEquals(0.6, group.viewport.viewport.start, 1e-9)
            assertTrue(group.viewport.zoom > 3.0)
        }
        rule.onNodeWithTag(PRICE).assertIsDisplayed()
        rule.onNodeWithTag(VOLUME).assertIsDisplayed()
    }

    @Test
    fun panningOneChartMovesTheOther() {
        lateinit var group: io.devkit.chartkit.state.ChartInteractionGroup
        rule.setContent {
            Host {
                group = rememberChartInteractionGroup(
                    viewport = io.devkit.chartkit.state.rememberChartViewportState(
                        initialViewport = ChartViewport(0.4, 0.6),
                    ),
                )
                Column {
                    CandlestickChart(
                        data = prices,
                        x = { it.timeMillis },
                        open = { it.open }, high = { it.high },
                        low = { it.low }, close = { it.close },
                        viewportState = group.viewport,
                        sharedCrosshair = group.crosshair,
                        interaction = ChartInteraction.Explorable,
                        animation = ChartAnimation.None,
                        modifier = Modifier.testTag(PRICE).fillMaxWidth().height(200.dp),
                    )
                    VolumeChart(
                        data = prices,
                        x = { it.timeMillis },
                        volume = { it.volume },
                        viewportState = group.viewport,
                        animation = ChartAnimation.None,
                        modifier = Modifier.testTag(VOLUME).fillMaxWidth().height(120.dp),
                    )
                }
            }
        }

        val before = rule.runOnIdle { group.viewport.viewport.start }
        rule.onNodeWithTag(PRICE).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue(
                "a leftward drag should advance the window",
                group.viewport.viewport.start > before,
            )
        }
    }

    @Test
    fun aSharedCrosshairSelectsOnEveryLinkedChart() {
        lateinit var group: io.devkit.chartkit.state.ChartInteractionGroup
        var volumeSelection: ChartSelection<ChartKitPreviewData.Candle>? = null

        rule.setContent {
            Host {
                group = rememberChartInteractionGroup()
                val volumeState = rememberChartState<ChartKitPreviewData.Candle>()
                Column {
                    CandlestickChart(
                        data = prices,
                        x = { it.timeMillis },
                        open = { it.open }, high = { it.high },
                        low = { it.low }, close = { it.close },
                        sharedCrosshair = group.crosshair,
                        animation = ChartAnimation.None,
                        modifier = Modifier.testTag(PRICE).fillMaxWidth().height(200.dp),
                    )
                    VolumeChart(
                        data = prices,
                        x = { it.timeMillis },
                        volume = { it.volume },
                        open = { it.open }, close = { it.close },
                        sharedCrosshair = group.crosshair,
                        state = volumeState,
                        onSelectionChanged = { volumeSelection = it },
                        animation = ChartAnimation.None,
                        modifier = Modifier.testTag(VOLUME).fillMaxWidth().height(120.dp),
                    )
                }
                volumeSelection = volumeState.selection
            }
        }

        // Published as a domain value, not a pixel: the two charts have
        // different value axes and different heights, and only the x is shared.
        val target = prices[40]
        rule.runOnIdle {
            group.crosshair.publish(io.devkit.chartkit.model.ChartX.Time(target.timeMillis))
        }
        rule.waitForIdle()

        rule.runOnIdle {
            val selected = volumeSelection
            assertNotNull("the linked chart should have selected", selected)
            assertEquals(target.volume, selected!!.y, 1.0)
        }
    }

    @Test
    fun linkedChartsKeepIndependentValueScales() {
        // Prices are in the low hundreds and volumes in the millions. A shared
        // value axis would flatten one of them entirely; a shared *domain* is
        // all that is shared.
        lateinit var group: io.devkit.chartkit.state.ChartInteractionGroup
        var priceSelection: ChartSelection<ChartKitPreviewData.Candle>? = null
        var volumeSelection: ChartSelection<ChartKitPreviewData.Candle>? = null

        rule.setContent {
            Host {
                group = rememberChartInteractionGroup()
                val priceState = rememberChartState<ChartKitPreviewData.Candle>()
                val volumeState = rememberChartState<ChartKitPreviewData.Candle>()
                Column {
                    CandlestickChart(
                        data = prices,
                        x = { it.timeMillis },
                        open = { it.open }, high = { it.high },
                        low = { it.low }, close = { it.close },
                        sharedCrosshair = group.crosshair,
                        state = priceState,
                        animation = ChartAnimation.None,
                        modifier = Modifier.testTag(PRICE).fillMaxWidth().height(200.dp),
                    )
                    VolumeChart(
                        data = prices,
                        x = { it.timeMillis },
                        volume = { it.volume },
                        sharedCrosshair = group.crosshair,
                        state = volumeState,
                        animation = ChartAnimation.None,
                        modifier = Modifier.testTag(VOLUME).fillMaxWidth().height(120.dp),
                    )
                }
                priceSelection = priceState.selection
                volumeSelection = volumeState.selection
            }
        }

        val target = prices[30]
        rule.runOnIdle {
            group.crosshair.publish(io.devkit.chartkit.model.ChartX.Time(target.timeMillis))
        }
        rule.waitForIdle()

        rule.runOnIdle {
            assertEquals(target.close, priceSelection!!.y, 1.0)
            assertEquals(target.volume, volumeSelection!!.y, 1.0)
            // Same x, entirely different y — which is the whole point.
            assertTrue(volumeSelection!!.y > priceSelection!!.y * 1000)
        }
    }

    @Test
    fun clearingTheSharedCrosshairClearsEveryChart() {
        lateinit var group: io.devkit.chartkit.state.ChartInteractionGroup
        var selection: ChartSelection<ChartKitPreviewData.Candle>? = null
        rule.setContent {
            Host {
                group = rememberChartInteractionGroup()
                val state = rememberChartState<ChartKitPreviewData.Candle>()
                VolumeChart(
                    data = prices,
                    x = { it.timeMillis },
                    volume = { it.volume },
                    sharedCrosshair = group.crosshair,
                    state = state,
                    animation = ChartAnimation.None,
                    modifier = Modifier.testTag(VOLUME).fillMaxWidth().height(120.dp),
                )
                selection = state.selection
            }
        }
        rule.runOnIdle {
            group.crosshair.publish(io.devkit.chartkit.model.ChartX.Time(prices[10].timeMillis))
        }
        rule.waitForIdle()
        rule.runOnIdle { assertNotNull(selection) }

        rule.runOnIdle { group.crosshair.clear() }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(null, selection) }
    }

    @Test
    fun aChartWithNoGroupIsUnaffectedByOne() {
        // Sharing is opt-in: a chart given no shared state behaves exactly as
        // it did before linked charts existed.
        var selection: ChartSelection<ChartKitPreviewData.MonthlyValue>? = null
        rule.setContent {
            Host {
                val state = rememberChartState<ChartKitPreviewData.MonthlyValue>()
                LineChart(
                    data = ChartKitPreviewData.revenue,
                    x = { it.month },
                    y = { it.amount },
                    state = state,
                    animation = ChartAnimation.None,
                    modifier = Modifier.testTag(PRICE).fillMaxWidth().height(160.dp),
                )
                selection = state.selection
            }
        }
        rule.runOnIdle { assertEquals(null, selection) }
        rule.onNodeWithTag(PRICE).assertIsDisplayed()
    }

    @Test
    fun aScatterChartReportsWhichObservationWasTapped() {
        var selected by mutableStateOf<ChartKitPreviewData.Observation?>(null)
        rule.setContent {
            Host {
                Column {
                    ScatterChart(
                        data = ChartKitPreviewData.observations,
                        x = { it.height },
                        y = { it.weight },
                        onSelectionChanged = { selected = it?.item },
                        animation = ChartAnimation.None,
                        modifier = Modifier.testTag(PRICE).fillMaxWidth().height(240.dp),
                    )
                    Text(selected?.let { "h=${it.height.toInt()}" } ?: "none")
                }
            }
        }
        rule.onNodeWithText("none").assertIsDisplayed()
        rule.onNodeWithTag(PRICE).performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width / 2f, height / 2f))
        }
        rule.waitForIdle()
        rule.runOnIdle { assertNotNull("a tap near the cloud should select", selected) }
    }

    private companion object {
        const val PRICE = "price-chart"
        const val VOLUME = "volume-chart"
    }
}
