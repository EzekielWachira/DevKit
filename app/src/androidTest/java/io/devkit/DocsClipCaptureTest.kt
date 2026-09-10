package io.devkit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import io.devkit.chartkit.charts.ChoroplethMap
import io.devkit.chartkit.charts.WorldMap
import io.devkit.chartkit.geo.GeoProjection
import io.devkit.chartkit.geo.TopoJson
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.theme.ChartKitTheme
import io.devkit.chartkit.theme.materialDerivedChartColors
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
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

    /**
     * Whether this run records the dark variant.
     *
     * Passed in as `-e dark true` rather than being a second test method,
     * because the gesture is the same in both schemes and duplicating it would
     * make the two drift apart. The recording script runs each clip twice.
     */
    private val dark: Boolean =
        InstrumentationRegistry.getArguments().getString("dark")?.toBoolean() ?: false

    /**
     * The whole screen, in the scheme being recorded.
     *
     * Both are recorded because the site follows the reader's system setting,
     * and a dark phone playing in the middle of a light page reads as a
     * screenshot of something else.
     */
    @Composable
    private fun Stage(content: @Composable (Modifier) -> Unit) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            ChartKitTheme(colors = materialDerivedChartColors(isDark = dark)) {
                Surface {
                    Box(Modifier.fillMaxSize().padding(12.dp)) {
                        content(Modifier.fillMaxSize().testTag(TAG))
                    }
                }
            }
        }
    }

    /**
     * Announces the scheme actually used, for the recording script to check.
     *
     * The script asks for a scheme with `-e dark`; this is the only way it can
     * find out whether it got one. A run that silently ignored the argument —
     * a stale test APK on the device, a typo in the flag — produces a correctly
     * *named* dark clip containing a light recording, which nothing downstream
     * notices and every reader does. That shipped once.
     */
    @Before
    fun announceScheme() {
        // Logcat rather than `println`: standard output from an instrumentation
        // test does not reach the `am instrument` stream, so a check that read
        // it would fail for every run whether or not anything was wrong.
        android.util.Log.i("DocsClip", "scheme=${if (dark) "dark" else "light"}")
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
            val statistics = androidx.compose.runtime.remember(world) {
                WorldDemoData.statistics(world)
            }
            Stage { m ->
                // Shaded rather than a bare outline. A base map's land is a
                // quiet backdrop by design, and at 480px downscaled through a
                // video codec it all but disappears — the clip has to show the
                // zoom, not test the reader's eyesight.
                ChoroplethMap(
                    geometry = world,
                    data = statistics,
                    featureKey = WorldDemoData.featureKey,
                    dataKey = { it.code },
                    value = { it.value },
                    featureLabel = WorldDemoData.featureLabel,
                    projection = GeoProjection.World,
                    legend = io.devkit.chartkit.components.legend.LegendPosition.None,
                    modifier = m,
                )
            }
        }
        settle()
        val node = rule.onNodeWithTag(TAG).fetchSemanticsNode()
        val w = node.size.width.toFloat()
        val h = node.size.height.toFloat()

        // Pinch about the middle of the plot, which in a portrait frame is the
        // middle of the *map*: a world map is letterboxed here, so a focal
        // point even a little above centre is in empty space, and zooming about
        // it walks the geography off the screen. The first version of this clip
        // did exactly that and recorded six seconds of white.
        rule.onNodeWithTag(TAG).performTouchInput {
            down(0, Offset(w * 0.42f, h * 0.5f))
            down(1, Offset(w * 0.58f, h * 0.5f))
        }
        repeat(35) { step ->
            val t = (step + 1f) / 35f
            rule.onNodeWithTag(TAG).performTouchInput {
                moveTo(0, Offset(w * (0.42f - 0.28f * t), h * (0.5f - 0.10f * t)))
                moveTo(1, Offset(w * (0.58f + 0.28f * t), h * (0.5f + 0.10f * t)))
            }
            Thread.sleep(18)
        }
        rule.onNodeWithTag(TAG).performTouchInput { up(0); up(1) }
        settle(600)
        // Then pan, which is only possible once zoomed.
        drag(Offset(w * 0.7f, h * 0.5f), Offset(w * 0.3f, h * 0.45f), steps = 35)
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
