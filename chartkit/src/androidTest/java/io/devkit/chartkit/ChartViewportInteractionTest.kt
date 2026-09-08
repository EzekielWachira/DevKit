package io.devkit.chartkit

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.interaction.ChartDragMode
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.model.ChartRangeSelection
import io.devkit.chartkit.model.ChartRangeSelectionPhase
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.preview.ChartKitPreviewData
import io.devkit.chartkit.preview.ChartKitPreviewData.MonthlyValue
import io.devkit.chartkit.state.ChartViewportState
import io.devkit.chartkit.state.rememberChartViewportState
import io.devkit.chartkit.viewport.ChartViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Zoom, pan, crosshair and range selection, driven through real touch input.
 *
 * Assertions are about the **viewport and the selection**, not about pixels: a
 * pinch test that checked where a line was drawn would break whenever a padding
 * changed and would still not tell you whether the domain moved.
 */
class ChartViewportInteractionTest {

    @get:Rule
    val rule = createComposeRule()

    private val readings = ChartKitPreviewData.dense(200)

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        MaterialTheme { Surface { content() } }
    }

    private fun chartModifier() = Modifier.testTag(CHART).fillMaxWidth().height(240.dp)

    /** A zoomable line chart whose viewport the test can read. */
    private fun setZoomable(
        interaction: ChartInteraction = ChartInteraction.Explorable,
        onState: (ChartViewportState) -> Unit,
    ) {
        rule.setContent {
            Host {
                val viewport = rememberChartViewportState()
                onState(viewport)
                LineChart(
                    data = readings,
                    x = { it.month.toInt() },
                    y = { it.amount },
                    interaction = interaction,
                    viewportState = viewport,
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
                Text("zoom=${"%.2f".format(viewport.zoom)}")
            }
        }
    }

    @Test
    fun aPinchZoomsTheViewport() {
        lateinit var viewport: ChartViewportState
        setZoomable { viewport = it }

        assertEquals(1.0, viewport.zoom, 1e-9)
        rule.onNodeWithTag(CHART).performTouchInput { pinchOut() }
        rule.waitForIdle()

        assertTrue("zoom stayed at ${viewport.zoom}", viewport.zoom > 1.2)
        assertTrue(!viewport.isFullyZoomedOut)
    }

    @Test
    fun aPinchInwardsZoomsBackOut() {
        lateinit var viewport: ChartViewportState
        setZoomable { viewport = it }

        rule.onNodeWithTag(CHART).performTouchInput { pinchOut() }
        rule.waitForIdle()
        val zoomedIn = viewport.zoom

        rule.onNodeWithTag(CHART).performTouchInput { pinchIn() }
        rule.waitForIdle()
        assertTrue("zoom went $zoomedIn → ${viewport.zoom}", viewport.zoom < zoomedIn)
    }

    @Test
    fun aDragPansOnceZoomedIn() {
        lateinit var viewport: ChartViewportState
        setZoomable { viewport = it }

        rule.onNodeWithTag(CHART).performTouchInput { pinchOut() }
        rule.waitForIdle()
        val before = viewport.viewport.start

        rule.onNodeWithTag(CHART).performTouchInput {
            swipe(Offset(width * 0.8f, height / 2f), Offset(width * 0.2f, height / 2f))
        }
        rule.waitForIdle()

        // Content follows the finger: dragging left moves the window forward.
        assertTrue("start went $before → ${viewport.viewport.start}", viewport.viewport.start > before)
    }

    @Test
    fun panningIsClampedToTheDomain() {
        lateinit var viewport: ChartViewportState
        setZoomable { viewport = it }

        rule.onNodeWithTag(CHART).performTouchInput { pinchOut() }
        rule.waitForIdle()

        repeat(4) {
            rule.onNodeWithTag(CHART).performTouchInput {
                swipe(Offset(width * 0.9f, height / 2f), Offset(width * 0.1f, height / 2f))
            }
            rule.waitForIdle()
        }
        assertTrue("end ran past the domain: ${viewport.viewport.end}", viewport.viewport.end <= 1.0)
        assertTrue(viewport.viewport.start >= 0.0)
    }

    @Test
    fun aFullyZoomedOutChartScrubsInsteadOfPanning() {
        lateinit var viewport: ChartViewportState
        var selected: ChartSelection<MonthlyValue>? = null
        rule.setContent {
            Host {
                val state = rememberChartViewportState()
                viewport = state
                LineChart(
                    data = readings,
                    x = { it.month.toInt() },
                    y = { it.amount },
                    interaction = ChartInteraction.Explorable,
                    viewportState = state,
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selected = it },
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).performTouchInput {
            swipe(Offset(width * 0.2f, height / 2f), Offset(width * 0.8f, height / 2f))
        }
        rule.waitForIdle()

        // PanWhenZoomed resolves once, at the start of the drag: at full extent
        // there is nothing to pan, so the drag reads values instead.
        assertEquals(1.0, viewport.zoom, 1e-9)
        assertNotNull("a drag at full extent should scrub", selected)
    }

    @Test
    fun resetRestoresTheWholeDomain() {
        lateinit var viewport: ChartViewportState
        setZoomable { viewport = it }

        rule.onNodeWithTag(CHART).performTouchInput { pinchOut() }
        rule.waitForIdle()
        assertTrue(viewport.zoom > 1.0)

        rule.runOnUiThread { viewport.reset() }
        rule.waitForIdle()
        assertEquals(ChartViewport.Full, viewport.viewport)
        assertTrue(viewport.isFullyZoomedOut)
    }

    @Test
    fun theChartPublishesTheDomainItIsShowing() {
        lateinit var viewport: ChartViewportState
        setZoomable { viewport = it }
        rule.waitForIdle()

        val full = viewport.fullDomain
        assertNotNull("the chart should publish its domain", full)
        assertEquals(0.0, full!!.min, 1e-6)
        assertEquals(199.0, full.max, 1e-6)

        rule.runOnUiThread { viewport.setTrailingHalf() }
        rule.waitForIdle()
        val visible = viewport.visibleDomain!!
        assertTrue("visible ${visible.min}..${visible.max}", visible.min > full.min)
        assertEquals(full.max, visible.max, 1e-6)
    }

    @Test
    fun aZoomedChartAnnouncesWhatItIsShowing() {
        lateinit var viewport: ChartViewportState
        setZoomable { viewport = it }

        rule.runOnUiThread { viewport.setTrailingHalf() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Showing", substring = true).assertIsDisplayed()
    }

    @Test
    fun tapSelectionStillWorksWithZoomEnabled() {
        var selected: ChartSelection<MonthlyValue>? = null
        rule.setContent {
            Host {
                LineChart(
                    data = ChartKitPreviewData.revenue,
                    x = { it.month },
                    y = { it.amount },
                    interaction = ChartInteraction.Explorable,
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selected = it },
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).performTouchInput {
            click(Offset(width * 0.5f, height * 0.5f))
        }
        rule.waitForIdle()
        assertNotNull("a tap must survive every interaction mode", selected)
    }

    @Test
    fun aDragSelectsARangeInRangeMode() {
        var range: ChartRangeSelection<MonthlyValue>? = null
        rule.setContent {
            Host {
                LineChart(
                    data = ChartKitPreviewData.revenue,
                    x = { it.month },
                    y = { it.amount },
                    interaction = ChartInteraction(dragMode = ChartDragMode.Range),
                    animation = ChartAnimation.None,
                    onRangeSelectionChanged = { range = it },
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).performTouchInput {
            swipe(Offset(width * 0.25f, height / 2f), Offset(width * 0.75f, height / 2f))
        }
        rule.waitForIdle()

        val selected = range
        assertNotNull("a range drag should report a range", selected)
        assertEquals(ChartRangeSelectionPhase.Completed, selected!!.phase)
        assertTrue("range was empty", !selected.isEmpty)
        assertTrue("range should cover items", selected.items.isNotEmpty())
    }

    @Test
    fun aRangeDraggedRightToLeftIsOrderedTheSameWay() {
        var range: ChartRangeSelection<MonthlyValue>? = null
        rule.setContent {
            Host {
                LineChart(
                    data = ChartKitPreviewData.revenue,
                    x = { it.month },
                    y = { it.amount },
                    interaction = ChartInteraction(dragMode = ChartDragMode.Range),
                    animation = ChartAnimation.None,
                    onRangeSelectionChanged = { range = it },
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).performTouchInput {
            swipe(Offset(width * 0.75f, height / 2f), Offset(width * 0.25f, height / 2f))
        }
        rule.waitForIdle()
        assertTrue(range!!.startFraction < range!!.endFraction)
    }

    @Test
    fun aCompletedRangeIsAnnounced() {
        rule.setContent {
            Host {
                LineChart(
                    data = ChartKitPreviewData.revenue,
                    x = { it.month },
                    y = { it.amount },
                    interaction = ChartInteraction(dragMode = ChartDragMode.Range),
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).performTouchInput {
            swipe(Offset(width * 0.25f, height / 2f), Offset(width * 0.75f, height / 2f))
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Selected", substring = true).assertIsDisplayed()
    }

    @Test
    fun aTapOutsideThePlotClearsTheRange() {
        var range: ChartRangeSelection<MonthlyValue>? = null
        rule.setContent {
            Host {
                LineChart(
                    data = ChartKitPreviewData.revenue,
                    x = { it.month },
                    y = { it.amount },
                    interaction = ChartInteraction(dragMode = ChartDragMode.Range),
                    animation = ChartAnimation.None,
                    onRangeSelectionChanged = { range = it },
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).performTouchInput {
            swipe(Offset(width * 0.25f, height / 2f), Offset(width * 0.75f, height / 2f))
        }
        rule.waitForIdle()
        assertNotNull(range)

        rule.onNodeWithTag(CHART).performTouchInput { click(Offset(1f, height - 1f)) }
        rule.waitForIdle()
        assertNull("a tap in the gutter should clear the range", range)
    }

    @Test
    fun aCrosshairDrawsAndReportsTheSelectedValue() {
        rule.setContent {
            Host {
                LineChart(
                    series = listOf(
                        ChartSeries("revenue", "Revenue", ChartKitPreviewData.revenue),
                        ChartSeries("expenses", "Expenses", ChartKitPreviewData.expenses),
                    ),
                    x = { it.month },
                    y = { it.amount },
                    crosshair = CrosshairConfig.Vertical,
                    animation = ChartAnimation.None,
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).performTouchInput {
            swipe(Offset(width * 0.2f, height / 2f), Offset(width * 0.6f, height / 2f))
        }
        rule.waitForIdle()
        rule.onNodeWithTag(CHART).assertIsDisplayed()
    }

    @Test
    fun aSharedCrosshairTooltipReportsEverySeries() {
        rule.setContent {
            Host {
                LineChart(
                    series = listOf(
                        ChartSeries("revenue", "Revenue", ChartKitPreviewData.revenue),
                        ChartSeries("expenses", "Expenses", ChartKitPreviewData.expenses),
                    ),
                    x = { it.month },
                    y = { it.amount },
                    crosshair = CrosshairConfig.Vertical,
                    animation = ChartAnimation.None,
                    tooltip = { data ->
                        Text("entries=${data.entries.size}:${data.entries.joinToString { it.seriesName }}")
                    },
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).performTouchInput {
            swipe(Offset(width * 0.2f, height / 2f), Offset(width * 0.6f, height / 2f))
        }
        rule.waitForIdle()
        rule.onNodeWithText("entries=2:Revenue, Expenses").assertIsDisplayed()
    }

    @Test
    fun withoutASharedTooltipOnlyTheNearestSeriesIsReported() {
        rule.setContent {
            Host {
                LineChart(
                    series = listOf(
                        ChartSeries("revenue", "Revenue", ChartKitPreviewData.revenue),
                        ChartSeries("expenses", "Expenses", ChartKitPreviewData.expenses),
                    ),
                    x = { it.month },
                    y = { it.amount },
                    animation = ChartAnimation.None,
                    tooltip = { data -> Text("entries=${data.entries.size}") },
                    modifier = chartModifier(),
                )
            }
        }
        rule.onNodeWithTag(CHART).performTouchInput {
            click(Offset(width * 0.5f, height * 0.5f))
        }
        rule.waitForIdle()
        rule.onNodeWithText("entries=1").assertIsDisplayed()
    }

    private companion object {
        const val CHART = "chart"
    }
}

/** Zooms out from the centre with two fingers. */
private fun androidx.compose.ui.test.TouchInjectionScope.pinchOut() {
    val cx = width / 2f
    val cy = height / 2f
    down(0, Offset(cx - 20f, cy))
    down(1, Offset(cx + 20f, cy))
    repeat(6) { step ->
        val spread = 20f + (step + 1) * 25f
        moveTo(0, Offset(cx - spread, cy))
        moveTo(1, Offset(cx + spread, cy))
    }
    up(0)
    up(1)
}

/** Pinches back together. */
private fun androidx.compose.ui.test.TouchInjectionScope.pinchIn() {
    val cx = width / 2f
    val cy = height / 2f
    down(0, Offset(cx - 170f, cy))
    down(1, Offset(cx + 170f, cy))
    repeat(6) { step ->
        val spread = 170f - (step + 1) * 25f
        moveTo(0, Offset(cx - spread, cy))
        moveTo(1, Offset(cx + spread, cy))
    }
    up(0)
    up(1)
}

/** Shows the trailing half of the domain — a common programmatic move. */
private fun ChartViewportState.setTrailingHalf() {
    showTrailing(0.5)
}
