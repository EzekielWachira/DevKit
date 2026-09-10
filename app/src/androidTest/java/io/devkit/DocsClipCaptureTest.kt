package io.devkit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.devkit.chartdemo.ChartDemoData
import io.devkit.chartdemo.Scatter3DDemoData
import io.devkit.chartdemo.WorldDemoData
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.charts.ScatterChart3D
import io.devkit.chartkit.charts.Treemap
import io.devkit.chartkit.charts.WorldMap
import io.devkit.chartkit.geo.GeoProjection
import io.devkit.chartkit.geo.TopoJson
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.theme.ChartKitTheme
import io.devkit.chartkit.theme.materialDerivedChartColors
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/**
 * Performs a real gesture on a real chart, slowly enough to be recorded.
 *
 * The documentation can show what a chart *looks* like with a picture. It
 * cannot show what a chart *does* — the crosshair following a finger, a 3D
 * scene turning under a drag, a map zooming about the point being pinched —
 * and those are the parts of ChartKit that a still cannot argue for.
 *
 * ChartKit is an Android library on `androidx.compose`, not Compose
 * Multiplatform, so there is no honest way to put a live chart in a web page: a
 * JavaScript reimplementation would be a different chart wearing this one's
 * name. A recording of the real thing is the truthful alternative.
 *
 * ### Why the sleeps
 *
 * Compose's test gestures dispatch synthetic events against a virtual clock, so
 * a whole drag can complete in a single frame — correct for a test, useless for
 * a recording, which would catch the start and the end and nothing between.
 * Each gesture is therefore broken into small steps with real time between
 * them, so what the screen recorder captures is the animation a person would
 * see.
 *
 * One clip per test method, recorded by `scripts/record-docs-clips.sh`, which
 * starts `screenrecord` around each invocation.
 */
class DocsClipCaptureTest {

    @get:Rule
    val rule = createComposeRule()

    private val demo = ChartDemoData

    /** The whole screen, dark, because the recordings sit in a dark page. */
    @Composable
    private fun Stage(content: @Composable (Modifier) -> Unit) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            ChartKitTheme(colors = materialDerivedChartColors(isDark = true)) {
                Surface {
                    Box(Modifier.fillMaxSize().padding(12.dp)) {
                        content(Modifier.fillMaxSize().testTag(TAG))
                    }
                }
            }
        }
    }

    /** Lets the recorder catch the settled chart before anything moves. */
    private fun settle(millis: Long = 1_200) {
        rule.waitForIdle()
        Thread.sleep(millis)
    }

    /** A drag, in steps, with real time between them so it can be filmed. */
    private fun drag(from: Offset, to: Offset, steps: Int = 40, millis: Long = 16) {
        rule.onNodeWithTag(TAG).performTouchInput { down(from) }
        repeat(steps) { step ->
            val t = (step + 1f) / steps
            rule.onNodeWithTag(TAG).performTouchInput {
                moveTo(Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t))
            }
            Thread.sleep(millis)
        }
        rule.onNodeWithTag(TAG).performTouchInput { up() }
    }

    @Test
    fun clipCrosshair() {
        rule.setContent {
            Stage { m ->
                LineChart(
                    demo.revenue,
                    x = { it.month },
                    y = { it.amount },
                    interaction = ChartInteraction.Explorable,
                    modifier = m,
                )
            }
        }
        settle()
        val node = rule.onNodeWithTag(TAG).fetchSemanticsNode()
        val w = node.size.width.toFloat()
        val h = node.size.height.toFloat()
        // Across the plot and back, so the readout is seen tracking in both
        // directions rather than only sweeping one way.
        drag(Offset(w * 0.08f, h * 0.5f), Offset(w * 0.92f, h * 0.5f))
        settle(500)
        drag(Offset(w * 0.92f, h * 0.5f), Offset(w * 0.30f, h * 0.5f))
        settle()
    }

    @Test
    fun clipCamera3D() {
        rule.setContent {
            Stage { m ->
                ScatterChart3D(
                    Scatter3DDemoData.survey,
                    x = { it.age }, y = { it.income }, z = { it.satisfaction },
                    modifier = m,
                )
            }
        }
        settle()
        val node = rule.onNodeWithTag(TAG).fetchSemanticsNode()
        val w = node.size.width.toFloat()
        val h = node.size.height.toFloat()
        // Around the scene and back up, which is what makes the third axis
        // read as depth rather than as decoration.
        drag(Offset(w * 0.5f, h * 0.5f), Offset(w * 0.9f, h * 0.42f), steps = 45)
        settle(300)
        drag(Offset(w * 0.5f, h * 0.5f), Offset(w * 0.15f, h * 0.62f), steps = 45)
        settle()
    }

    @Test
    fun clipMap() {
        rule.setContent {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val world = androidx.compose.runtime.remember {
                TopoJson.parse(
                    context.assets.open(WorldDemoData.ASSET).readBytes().decodeToString(),
                    "countries",
                )
            }
            Stage { m ->
                WorldMap(geometry = world, projection = GeoProjection.World, modifier = m)
            }
        }
        settle()
        val node = rule.onNodeWithTag(TAG).fetchSemanticsNode()
        val w = node.size.width.toFloat()
        val h = node.size.height.toFloat()

        // Pinch out about a point left of centre, then drag: the geography
        // under the fingers should stay under them.
        rule.onNodeWithTag(TAG).performTouchInput {
            down(0, Offset(w * 0.38f, h * 0.48f))
            down(1, Offset(w * 0.52f, h * 0.52f))
        }
        repeat(35) { step ->
            val t = (step + 1f) / 35f
            rule.onNodeWithTag(TAG).performTouchInput {
                moveTo(0, Offset(w * (0.38f - 0.22f * t), h * (0.48f - 0.16f * t)))
                moveTo(1, Offset(w * (0.52f + 0.22f * t), h * (0.52f + 0.16f * t)))
            }
            Thread.sleep(18)
        }
        rule.onNodeWithTag(TAG).performTouchInput { up(0); up(1) }
        settle(500)
        drag(Offset(w * 0.65f, h * 0.55f), Offset(w * 0.35f, h * 0.45f), steps = 35)
        settle()
    }

    @Test
    fun clipDrillDown() {
        rule.setContent {
            Stage { m ->
                Treemap(
                    demo.company,
                    children = { it.teams },
                    value = { it.revenue },
                    label = { it.name },
                    key = { it.id },
                    modifier = m,
                )
            }
        }
        settle()
        val node = rule.onNodeWithTag(TAG).fetchSemanticsNode()
        val w = node.size.width.toFloat()
        val h = node.size.height.toFloat()
        // Into the largest tile, pause on the level below, then back out.
        rule.onNodeWithTag(TAG).performTouchInput { click(Offset(w * 0.3f, h * 0.3f)) }
        settle(1_600)
        rule.onNodeWithTag(TAG).performTouchInput { click(Offset(w * 0.5f, h * 0.5f)) }
        settle(1_600)
    }

    private companion object {
        const val TAG = "clip-subject"
    }
}
