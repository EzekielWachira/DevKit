package io.devkit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.charts.ParallelCoordinatesChart
import io.devkit.chartkit.charts.ParallelDimension
import io.devkit.chartkit.state.ChartParallelBrushState
import io.devkit.chartkit.theme.ChartKitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class Row(val a: Double, val b: Double, val c: Double)

/**
 * Brushing, driven by an actual gesture.
 *
 * The layout arithmetic is unit-tested on the JVM; what cannot be tested there
 * is whether a finger coming down near an axis reaches the brush state at all —
 * the drag has to travel through `PlanarChartCore`'s pointer input, the axis has
 * to be resolved from the same spacing the layer drew, and the pixels have to
 * come back as values. That path only exists on a device, so this is where it
 * is checked.
 */
class ParallelBrushingTest {

    @get:Rule
    val rule = createComposeRule()

    private val rows = (0 until 20).map { index ->
        Row(a = index.toDouble(), b = (20 - index).toDouble(), c = (index % 5).toDouble())
    }

    private lateinit var brushes: ChartParallelBrushState

    private fun show() {
        rule.setContent {
            brushes = io.devkit.chartkit.state.rememberParallelBrushState()
            MaterialTheme {
                ChartKitTheme {
                    Surface {
                        Box(Modifier.size(WIDTH, HEIGHT)) {
                            ParallelCoordinatesChart(
                                data = rows,
                                dimensions = listOf(
                                    ParallelDimension("A") { it.a },
                                    ParallelDimension("B") { it.b },
                                    ParallelDimension("C") { it.c },
                                ),
                                brushState = brushes,
                                modifier = Modifier.fillMaxSize().testTag(CHART),
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun draggingDownAnAxisBrushesIt() {
        show()
        assertTrue("expected no filters before the drag", brushes.isEmpty)

        rule.onNodeWithTag(CHART).performTouchInput {
            // The leftmost axis stands at the left edge of the content box.
            val x = left + 4f
            swipe(start = Offset(x, top + height * 0.2f), end = Offset(x, top + height * 0.6f))
        }
        rule.waitForIdle()

        assertEquals(1, brushes.activeCount)
        val range = brushes.ranges[0]
        assertNotNull("axis 0 should be brushed", range)
        assertTrue("a brush should span a real interval", range!!.endInclusive > range.start)
    }

    @Test
    fun aBrushExcludesRowsOutsideIt() {
        show()
        rule.onNodeWithTag(CHART).performTouchInput {
            val x = left + 4f
            // The top half of the axis: the larger values of dimension A.
            swipe(start = Offset(x, top + height * 0.1f), end = Offset(x, top + height * 0.4f))
        }
        rule.waitForIdle()

        val range = brushes.ranges[0]!!
        val admitted = rows.count { brushes.admits { axis -> if (axis == 0) it.a else null } }
        assertTrue("some rows should survive", admitted > 0)
        assertTrue("not every row should survive", admitted < rows.size)
        rows.forEach { row ->
            val survives = brushes.admits { axis -> if (axis == 0) row.a else null }
            assertEquals("row ${row.a}", row.a in range, survives)
        }
    }

    @Test
    fun draggingASecondAxisKeepsTheFirstBrush() {
        show()
        rule.onNodeWithTag(CHART).performTouchInput {
            swipe(
                start = Offset(left + 4f, top + height * 0.2f),
                end = Offset(left + 4f, top + height * 0.5f),
            )
        }
        rule.waitForIdle()
        rule.onNodeWithTag(CHART).performTouchInput {
            swipe(
                start = Offset(centerX, top + height * 0.2f),
                end = Offset(centerX, top + height * 0.5f),
            )
        }
        rule.waitForIdle()

        assertEquals("both axes should stay brushed", 2, brushes.activeCount)
    }

    @Test
    fun aTapOnABrushedAxisClearsIt() {
        show()
        rule.onNodeWithTag(CHART).performTouchInput {
            swipe(
                start = Offset(left + 4f, top + height * 0.2f),
                end = Offset(left + 4f, top + height * 0.6f),
            )
        }
        rule.waitForIdle()
        assertEquals(1, brushes.activeCount)

        // The gesture has to exist separately from dragging: a drag clears on
        // touch-down and rebrushes as the finger moves, so no drag can leave an
        // axis unfiltered.
        rule.onNodeWithTag(CHART).performTouchInput {
            click(Offset(left + 4f, top + height * 0.4f))
        }
        rule.waitForIdle()

        assertTrue("the tap should have cleared the brush", brushes.isEmpty)
    }

    @Test
    fun aTapAwayFromABrushedAxisLeavesTheBrushAlone() {
        // Only the axis a tap actually lands on is cleared; the gesture must not
        // become "tap anywhere to lose your filters".
        show()
        rule.onNodeWithTag(CHART).performTouchInput {
            swipe(
                start = Offset(left + 4f, top + height * 0.2f),
                end = Offset(left + 4f, top + height * 0.6f),
            )
        }
        rule.waitForIdle()
        assertEquals(1, brushes.activeCount)

        rule.onNodeWithTag(CHART).performTouchInput {
            click(Offset(left + width * 0.25f, top + height * 0.5f))
        }
        rule.waitForIdle()

        assertEquals("a tap in open space must not clear a brush", 1, brushes.activeCount)
    }

    @Test
    fun aDragFarFromEveryAxisBrushesNothing() {
        show()
        rule.onNodeWithTag(CHART).performTouchInput {
            // A quarter of the way between the first and second axes: well
            // outside the reach of either.
            swipe(
                start = Offset(left + width * 0.25f, top + height * 0.2f),
                end = Offset(left + width * 0.25f, top + height * 0.6f),
            )
        }
        rule.waitForIdle()

        assertTrue("a drag in open space should not filter anything", brushes.isEmpty)
        assertFalse(brushes.activeCount > 0)
    }

    private companion object {
        val WIDTH = 400.dp
        val HEIGHT = 300.dp
        const val CHART = "parallel-brush-test"
    }
}
