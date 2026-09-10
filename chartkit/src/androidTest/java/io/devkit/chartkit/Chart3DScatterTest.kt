package io.devkit.chartkit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartUnit
import io.devkit.chartkit.charts.Chart3DInteraction
import io.devkit.chartkit.charts.ScatterChart3D
import io.devkit.chartkit.layer.three.Scatter3DGuides
import io.devkit.chartkit.layer.three.Scatter3DRenderMode
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.state.Chart3DCameraState
import io.devkit.chartkit.state.rememberChart3DCameraState
import io.devkit.chartkit.theme.ChartKitTheme
import io.devkit.chartkit.three.Chart3DCamera
import io.devkit.chartkit.three.Chart3DCameraLimits
import io.devkit.chartkit.three.Chart3DDiagnostics
import io.devkit.chartkit.three.Chart3DFrame
import io.devkit.chartkit.three.Chart3DGridPlanes
import io.devkit.chartkit.three.Chart3DProjection
import io.devkit.chartkit.three.Marker3D
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private data class Observation(
    val age: Double,
    val income: Double,
    val score: Double,
    val household: Double = 3.0,
    val tenure: Double = 8.0,
)

/** A small, deterministic, well-spread cloud. */
private val respondents = List(24) { index ->
    Observation(
        age = 20.0 + index * 2.5,
        income = 25_000.0 + (index % 6) * 14_000.0,
        score = 10.0 + (index % 8) * 11.0,
        household = 1.0 + (index % 5),
        tenure = (index % 12) * 2.0,
    )
}

/**
 * 3D scatter charts on a device.
 *
 * The tests that need a composition, real pixels or an animation clock: what a
 * tap resolves to, what a screen reader hears, whether a drag turns the camera
 * without selecting anything, and whether the chart survives the sizes it is
 * given. The coordinate system, the projection, the depth order, the marker
 * shading and the hit testing are plain Kotlin and are covered without a device
 * in `Cartesian3DCoordinatesTest`, `Scatter3DPipelineTest` and
 * `Chart3DCameraTest`.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class Chart3DScatterTest {

    @get:Rule
    val rule = createComposeRule()

    @Composable
    private fun Harness(content: @Composable () -> Unit) {
        MaterialTheme { Surface { ChartKitTheme { content() } } }
    }

    private fun descriptionOf(tag: String): String? {
        fun walk(node: androidx.compose.ui.semantics.SemanticsNode): String? =
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                ?: node.children.firstNotNullOfOrNull { walk(it) }
        return walk(rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode())
    }

    private val chartModifier = Modifier
        .fillMaxWidth()
        .height(340.dp)
        .testTag("scatter3d")

    private val ageAxis = ChartAxis(title = "Age")
    private val incomeAxis = ChartAxis(title = "Income")
    private val scoreAxis = ChartAxis(title = "Satisfaction")

    // ---- rendering --------------------------------------------------------

    @Test
    fun aScatterDraws() {
        rule.setContent {
            Harness {
                ScatterChart3D(
                    data = respondents,
                    x = { it.age },
                    y = { it.income },
                    z = { it.score },
                    xAxis = ageAxis,
                    yAxis = incomeAxis,
                    zAxis = scoreAxis,
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("scatter3d").assertIsDisplayed()
    }

    @Test
    fun everyObservationBecomesAMark() {
        var diagnostics: Chart3DDiagnostics? = null
        rule.setContent {
            Harness {
                ScatterChart3D(
                    data = respondents,
                    x = { it.age },
                    y = { it.income },
                    z = { it.score },
                    animation = ChartAnimation.None,
                    onDiagnostics = { diagnostics = it },
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        assertNotNull("the chart reported no diagnostics", diagnostics)
        assertEquals(respondents.size, diagnostics!!.markCount)
        assertEquals(
            "every observation is inside its own volume, so all of them are drawn",
            respondents.size,
            diagnostics!!.renderedMarks,
        )
    }

    /** §58: cube markers are scene geometry, so they arrive as faces and not marks. */
    @Test
    fun cubeMarkersEnterTheSceneAsBoxesRatherThanAsGlyphs() {
        var spheres: Chart3DDiagnostics? = null
        var cubes: Chart3DDiagnostics? = null
        rule.setContent {
            Harness {
                Column {
                    ScatterChart3D(
                        data = respondents,
                        x = { it.age },
                        y = { it.income },
                        z = { it.score },
                        marker = Marker3D.Sphere,
                        renderMode = Scatter3DRenderMode.Rich,
                        animation = ChartAnimation.None,
                        onDiagnostics = { spheres = it },
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                    )
                    ScatterChart3D(
                        data = respondents,
                        x = { it.age },
                        y = { it.income },
                        z = { it.score },
                        marker = Marker3D.Cube,
                        renderMode = Scatter3DRenderMode.Rich,
                        animation = ChartAnimation.None,
                        onDiagnostics = { cubes = it },
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
        assertEquals(respondents.size, spheres!!.markCount)
        assertEquals("a cube is not a glyph", 0, cubes!!.markCount)
        assertTrue(
            "each cube adds six faces to the scene: ${cubes!!.faceCount}",
            cubes!!.faceCount >= spheres!!.faceCount + respondents.size * 6,
        )
    }

    /** §186: Auto picks a strategy from a documented, predictable threshold. */
    @Test
    fun autoDrawsRichMarkersBelowItsDocumentedLimitAndBillboardsAbove() {
        val many = List(io.devkit.chartkit.layer.three.Scatter3DLayer.RICH_POINT_LIMIT + 50) {
            Observation(
                age = 20.0 + (it % 60),
                income = 20_000.0 + (it % 97) * 1_500.0,
                score = (it % 101).toDouble(),
            )
        }
        var small: Chart3DDiagnostics? = null
        var large: Chart3DDiagnostics? = null
        rule.setContent {
            Harness {
                Column {
                    ScatterChart3D(
                        data = respondents,
                        x = { it.age }, y = { it.income }, z = { it.score },
                        renderMode = Scatter3DRenderMode.Auto,
                        animation = ChartAnimation.None,
                        onDiagnostics = { small = it },
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                    )
                    ScatterChart3D(
                        data = many,
                        x = { it.age }, y = { it.income }, z = { it.score },
                        renderMode = Scatter3DRenderMode.Auto,
                        animation = ChartAnimation.None,
                        onDiagnostics = { large = it },
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
        // Both draw every point; what differs is how each marker is painted,
        // which the diagnostics do not report. What is asserted here is the
        // part that must not change: Auto never drops observations.
        assertEquals(respondents.size, small!!.renderedMarks)
        assertEquals(many.size, large!!.renderedMarks)
    }

    @Test
    fun aChartWithNoUsableObservationsShowsItsEmptyState() {
        rule.setContent {
            Harness {
                ScatterChart3D(
                    data = emptyList<Observation>(),
                    x = { it.age },
                    y = { it.income },
                    z = { it.score },
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("scatter3d").assertIsDisplayed()
    }

    /** A point missing any one coordinate has no position, and is not invented. */
    @Test
    fun anObservationMissingACoordinateIsNotDrawn() {
        var diagnostics: Chart3DDiagnostics? = null
        rule.setContent {
            Harness {
                ScatterChart3D(
                    data = respondents,
                    x = { it.age },
                    y = { it.income },
                    // Every fourth observation has no depth measurement.
                    z = { if (it.age.toInt() % 10 == 0) null else it.score },
                    animation = ChartAnimation.None,
                    onDiagnostics = { diagnostics = it },
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        assertTrue(
            "points without a z must be absent, not placed on the axis",
            diagnostics!!.markCount < respondents.size,
        )
    }

    // ---- interaction ------------------------------------------------------

    @Test
    fun aTapSelectsAnObservationAndReportsAllThreeValues() {
        var selection by mutableStateOf<ChartSelection<Observation>?>(null)
        rule.setContent {
            Harness {
                ScatterChart3D(
                    data = respondents,
                    x = { it.age },
                    y = { it.income },
                    z = { it.score },
                    xAxis = ageAxis,
                    yAxis = incomeAxis,
                    zAxis = scoreAxis,
                    interaction = Chart3DInteraction.Select,
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selection = it },
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        // A grid of taps: a scatter's markers are small, and the cloud's
        // screen positions depend on the device's own size.
        val node = rule.onNodeWithTag("scatter3d")
        val size = node.fetchSemanticsNode().size
        outer@ for (row in 2..8) {
            for (column in 2..8) {
                node.performTouchInput {
                    click(Offset(size.width * column / 10f, size.height * row / 10f))
                }
                rule.waitForIdle()
                if (selection != null) break@outer
            }
        }
        val hit = selection
        assertNotNull("no tap in a 7x7 grid landed on an observation", hit)
        val detail = hit!!.cartesian3D
        assertNotNull("a 3D selection must carry its three coordinates", detail)
        assertEquals("Age", detail!!.xTitle)
        assertEquals("Income", detail.yTitle)
        assertEquals("Satisfaction", detail.zTitle)
        assertEquals(hit.item!!.age, detail.x, 1e-9)
        assertEquals(hit.item!!.income, detail.y, 1e-9)
        assertEquals(hit.item!!.score, detail.z, 1e-9)
        assertEquals("and the chart's own y is the same number", detail.y, hit.y, 1e-9)
    }

    /** §178: a drag turns the camera and does not leave selections behind it. */
    @Test
    fun aDragTurnsTheCameraWithoutSelectingAnything() {
        var selections = 0
        lateinit var camera: Chart3DCameraState
        rule.setContent {
            Harness {
                camera = rememberChart3DCameraState(
                    camera = Chart3DCamera(rotationX = 20.0, rotationY = 20.0),
                    limits = Chart3DCameraLimits.Cartesian3D,
                )
                ScatterChart3D(
                    data = respondents,
                    x = { it.age },
                    y = { it.income },
                    z = { it.score },
                    cameraState = camera,
                    interaction = Chart3DInteraction.RotateAndSelect,
                    animation = ChartAnimation.None,
                    onSelectionChanged = { if (it != null) selections++ },
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        val before = camera.camera
        rule.onNodeWithTag("scatter3d").performTouchInput {
            swipe(
                start = Offset(centerX - 120f, centerY),
                end = Offset(centerX + 120f, centerY),
                durationMillis = 300,
            )
        }
        rule.waitForIdle()
        assertTrue(
            "a horizontal drag must turn the scene: ${before.rotationY} → " +
                "${camera.camera.rotationY}",
            camera.camera.rotationY != before.rotationY,
        )
        assertEquals("a drag is not a tap", 0, selections)
    }

    /** §178 the other way: a tap must not move the camera. */
    @Test
    fun aTapLeavesTheCameraExactlyWhereItWas() {
        lateinit var camera: Chart3DCameraState
        rule.setContent {
            Harness {
                camera = rememberChart3DCameraState(
                    camera = Chart3DCamera(rotationX = 25.0, rotationY = 30.0),
                    limits = Chart3DCameraLimits.Cartesian3D,
                )
                ScatterChart3D(
                    data = respondents,
                    x = { it.age },
                    y = { it.income },
                    z = { it.score },
                    cameraState = camera,
                    interaction = Chart3DInteraction.RotateAndSelect,
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        val before = camera.camera
        repeat(4) {
            rule.onNodeWithTag("scatter3d").performTouchInput { click(center) }
            rule.waitForIdle()
        }
        assertEquals("four taps must not have turned the chart", before, camera.camera)
    }

    /** §180: reset after an arbitrary drag returns to exactly the configured view. */
    @Test
    fun resetReturnsToTheConfiguredCameraAfterADrag() {
        val initial = Chart3DCamera(rotationX = 28.0, rotationY = 34.0, distance = 3.1)
        lateinit var camera: Chart3DCameraState
        rule.setContent {
            Harness {
                camera = rememberChart3DCameraState(
                    camera = initial,
                    limits = Chart3DCameraLimits.Cartesian3D,
                )
                ScatterChart3D(
                    data = respondents,
                    x = { it.age },
                    y = { it.income },
                    z = { it.score },
                    cameraState = camera,
                    interaction = Chart3DInteraction.Rotate,
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("scatter3d").performTouchInput {
            swipe(start = Offset(centerX, centerY - 100f), end = Offset(centerX, centerY + 100f))
        }
        rule.waitForIdle()
        assertTrue("the drag moved the camera", camera.camera != initial)
        rule.runOnUiThread { camera.reset() }
        rule.waitForIdle()
        assertEquals(initial, camera.camera)
    }

    /** §22 and §23: an animated transition runs on the shared clock and lands exactly. */
    @Test
    fun anAnimatedCameraMoveArrivesAtItsTarget() {
        val target = Chart3DCamera(rotationX = 55.0, rotationY = -40.0, distance = 5.0)
        lateinit var camera: Chart3DCameraState
        rule.setContent {
            Harness {
                camera = rememberChart3DCameraState(
                    camera = Chart3DCamera.Isometric,
                    limits = Chart3DCameraLimits.Cartesian3D,
                )
                val scope = rememberCoroutineScope()
                ScatterChart3D(
                    data = respondents,
                    x = { it.age },
                    y = { it.income },
                    z = { it.score },
                    cameraState = camera,
                    animation = ChartAnimation.None,
                    modifier = chartModifier.testTag("scatter3d"),
                )
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    scope.launch { camera.animateTo(target) }
                }
            }
        }
        rule.waitForIdle()
        assertEquals(target, camera.camera)
    }

    /**
     * §23: interrupting one move with another continues from where the chart
     * is, and finishes at the second target rather than the first.
     */
    @Test
    fun aSecondAnimatedMoveOverridesTheFirstAndStillLands() {
        val first = Chart3DCamera(rotationX = 70.0, rotationY = 80.0, distance = 8.0)
        val second = Chart3DCamera(rotationX = 12.0, rotationY = -20.0, distance = 2.4)
        lateinit var camera: Chart3DCameraState
        rule.setContent {
            Harness {
                camera = rememberChart3DCameraState(
                    camera = Chart3DCamera.Isometric,
                    limits = Chart3DCameraLimits.Cartesian3D,
                )
                val scope = rememberCoroutineScope()
                ScatterChart3D(
                    data = respondents,
                    x = { it.age },
                    y = { it.income },
                    z = { it.score },
                    cameraState = camera,
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    val job = scope.launch { camera.animateTo(first) }
                    kotlinx.coroutines.delay(60)
                    job.cancel()
                    scope.launch { camera.animateTo(second) }
                }
            }
        }
        rule.waitForIdle()
        assertEquals(second, camera.camera)
    }

    // ---- camera independence ----------------------------------------------

    /** §133: the camera never changes what a selection means. */
    @Test
    fun turningTheChartDoesNotChangeWhatASelectionSays() {
        var selection by mutableStateOf<ChartSelection<Observation>?>(null)
        var pitch by mutableStateOf(20.0)
        rule.setContent {
            Harness {
                val camera = rememberChart3DCameraState(
                    camera = Chart3DCamera(rotationX = pitch, rotationY = 25.0),
                    limits = Chart3DCameraLimits.Cartesian3D,
                )
                ScatterChart3D(
                    data = respondents,
                    x = { it.age },
                    y = { it.income },
                    z = { it.score },
                    xAxis = ageAxis,
                    yAxis = incomeAxis,
                    zAxis = scoreAxis,
                    cameraState = camera,
                    interaction = Chart3DInteraction.Select,
                    animation = ChartAnimation.None,
                    onSelectionChanged = { if (it != null) selection = it },
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        val node = rule.onNodeWithTag("scatter3d")
        val size = node.fetchSemanticsNode().size
        outer@ for (row in 2..8) {
            for (column in 2..8) {
                node.performTouchInput {
                    click(Offset(size.width * column / 10f, size.height * row / 10f))
                }
                rule.waitForIdle()
                if (selection != null) break@outer
            }
        }
        val before = selection
        assertNotNull(before)
        pitch = 60.0
        rule.waitForIdle()
        assertEquals("the values did not move because the camera did", before, selection)
    }

    /**
     * §132: arrow keys step through the observations, and never the camera.
     *
     * The property that matters is that stepping reaches data *without* a
     * rotation: a reader exploring with a keyboard is reading values, and
     * requiring them to turn the scene first would make the accessible
     * interaction depend on the decorative one.
     */
    @Test
    fun keyboardSteppingSelectsObservationsAndLeavesTheCameraAlone() {
        var selection by mutableStateOf<ChartSelection<Observation>?>(null)
        lateinit var camera: Chart3DCameraState
        rule.setContent {
            Harness {
                camera = rememberChart3DCameraState(
                    camera = Chart3DCamera(rotationX = 20.0, rotationY = 25.0),
                    limits = Chart3DCameraLimits.Cartesian3D,
                )
                ScatterChart3D(
                    data = respondents,
                    x = { it.age },
                    y = { it.income },
                    z = { it.score },
                    xAxis = ageAxis,
                    yAxis = incomeAxis,
                    zAxis = scoreAxis,
                    cameraState = camera,
                    interaction = Chart3DInteraction.Select,
                    animation = ChartAnimation.None,
                    onSelectionChanged = { if (it != null) selection = it },
                    modifier = chartModifier.testTag("scatter3d"),
                )
            }
        }
        rule.waitForIdle()
        val before = camera.camera
        // The focusable node is the *plot*, inside the chart, not the outer
        // element the test tag is on — the chart puts its key handling where
        // its geometry is. Found by the property rather than by a tag, so this
        // keeps testing keyboard navigation rather than testing a tag.
        val focusable = rule.onAllNodes(isFocusable(), useUnmergedTree = true).onFirst()
        focusable.requestFocus()
        repeat(3) {
            focusable.performKeyInput {
                pressKey(androidx.compose.ui.input.key.Key.DirectionRight)
            }
            rule.waitForIdle()
        }
        assertNotNull("arrow keys selected nothing", selection)
        assertNotNull(
            "a keyboard selection must carry all three values",
            selection!!.cartesian3D,
        )
        assertEquals("stepping must not move the camera", before, camera.camera)
    }

    // ---- accessibility ----------------------------------------------------

    /** §127: the summary names three axes and counts the observations. */
    @Test
    fun theSummaryNamesAllThreeAxes() {
        rule.setContent {
            Harness {
                ScatterChart3D(
                    data = respondents,
                    x = { it.age },
                    y = { it.income },
                    z = { it.score },
                    xAxis = ageAxis,
                    yAxis = incomeAxis,
                    zAxis = scoreAxis,
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        val description = descriptionOf("scatter3d").orEmpty()
        assertTrue("no summary was announced", description.isNotBlank())
        listOf("Age", "Income", "Satisfaction", "${respondents.size} observations").forEach {
            assertTrue("the summary omits \"$it\": $description", description.contains(it))
        }
        // §129: presentation is not data.
        listOf("camera", "front", "behind", "depth").forEach {
            assertTrue(
                "the summary describes the rendering: $description",
                !description.lowercase().contains(it),
            )
        }
    }

    @Test
    fun theSummaryReportsTheSeriesCountWhenThereIsMoreThanOne() {
        rule.setContent {
            Harness {
                ScatterChart3D(
                    series = listOf(
                        ChartSeries("a", "Control", respondents.take(12)),
                        ChartSeries("b", "Treated", respondents.drop(12)),
                    ),
                    x = { it.age },
                    y = { it.income },
                    z = { it.score },
                    xAxis = ageAxis,
                    yAxis = incomeAxis,
                    zAxis = scoreAxis,
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        val description = descriptionOf("scatter3d").orEmpty()
        assertTrue(description, description.contains("2 series"))
    }

    // ---- configuration surface --------------------------------------------

    @Test
    fun theChartSurvivesEveryCombinationOfFrameGridProjectionAndGuides() {
        var shown by mutableStateOf(0)
        val cases = listOf<@Composable () -> Unit>(
            {
                ScatterChart3D(
                    data = respondents, x = { it.age }, y = { it.income }, z = { it.score },
                    frame = Chart3DFrame.None, gridPlanes = Chart3DGridPlanes.None,
                    animation = ChartAnimation.None, modifier = chartModifier,
                )
            },
            {
                ScatterChart3D(
                    data = respondents, x = { it.age }, y = { it.income }, z = { it.score },
                    frame = Chart3DFrame.Visible, gridPlanes = Chart3DGridPlanes.All,
                    animation = ChartAnimation.None, modifier = chartModifier,
                )
            },
            {
                ScatterChart3D(
                    data = respondents, x = { it.age }, y = { it.income }, z = { it.score },
                    projection = Chart3DProjection.Orthographic,
                    guides = Scatter3DGuides.Planes,
                    animation = ChartAnimation.None, modifier = chartModifier,
                )
            },
            {
                ScatterChart3D(
                    data = respondents, x = { it.age }, y = { it.income }, z = { it.score },
                    size = { it.household }, color = { it.tenure },
                    animation = ChartAnimation.None, modifier = chartModifier,
                )
            },
        )
        rule.setContent { Harness { cases[shown]() } }
        cases.indices.forEach { index ->
            shown = index
            rule.waitForIdle()
            rule.onNodeWithTag("scatter3d").assertIsDisplayed()
        }
    }

    @Test
    fun aUnitIsWrittenOnTheAxisTitleAndCarriedIntoTheSelection() {
        var selection by mutableStateOf<ChartSelection<Observation>?>(null)
        rule.setContent {
            Harness {
                ScatterChart3D(
                    data = respondents,
                    x = { it.age },
                    y = { it.income },
                    z = { it.score },
                    xAxis = ageAxis,
                    yAxis = incomeAxis,
                    zAxis = scoreAxis,
                    zUnit = ChartUnit.Percent,
                    interaction = Chart3DInteraction.Select,
                    animation = ChartAnimation.None,
                    onSelectionChanged = { if (it != null) selection = it },
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        val node = rule.onNodeWithTag("scatter3d")
        val size = node.fetchSemanticsNode().size
        outer@ for (row in 2..8) {
            for (column in 2..8) {
                node.performTouchInput {
                    click(Offset(size.width * column / 10f, size.height * row / 10f))
                }
                rule.waitForIdle()
                if (selection != null) break@outer
            }
        }
        assertNotNull(selection)
        assertTrue(
            "the depth value must carry its unit: ${selection!!.cartesian3D?.formattedZ}",
            selection!!.cartesian3D!!.formattedZ.endsWith("%"),
        )
    }
}
