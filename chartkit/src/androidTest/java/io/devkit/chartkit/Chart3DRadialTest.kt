package io.devkit.chartkit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.charts.Chart3DInteraction
import io.devkit.chartkit.charts.DonutChart3D
import io.devkit.chartkit.charts.PieChart3D
import io.devkit.chartkit.geometry.PolarDirection
import io.devkit.chartkit.layer.polar.SliceLabelContent
import io.devkit.chartkit.layer.polar.SliceLabelPosition
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.state.rememberChart3DCameraState
import io.devkit.chartkit.theme.ChartKitTheme
import io.devkit.chartkit.three.Chart3DCamera
import io.devkit.chartkit.three.Chart3DDepth
import io.devkit.chartkit.three.Chart3DDiagnostics
import io.devkit.chartkit.three.Chart3DProjection
import io.devkit.chartkit.three.Chart3DQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private data class Share(val name: String, val users: Double?)

private val browsers = listOf(
    Share("Chrome", 4823.0),
    Share("Safari", 3112.0),
    Share("Edge", 1841.0),
    Share("Firefox", 980.0),
)

/**
 * 3D pies and donuts on a device.
 *
 * The tests that need a composition, real pixels or an animation clock: what a
 * tap resolves to, what a screen reader hears, where centre content ends up,
 * and whether the chart survives every size it is given. The geometry, the
 * tessellation, the projection, the culling, the sort and the hit testing are
 * plain Kotlin and are covered without a device in `Radial3DGeometryTest`,
 * `Radial3DLayoutTest` and `Radial3DPipelineTest`.
 */
class Chart3DRadialTest {

    @get:Rule
    val rule = createComposeRule()

    @Composable
    private fun Harness(content: @Composable () -> Unit) {
        MaterialTheme { Surface { ChartKitTheme { content() } } }
    }

    /** The chart's own announcement, wherever it hangs in the tree. */
    private fun descriptionOf(tag: String): String? {
        fun walk(node: androidx.compose.ui.semantics.SemanticsNode): String? =
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                ?: node.children.firstNotNullOfOrNull { walk(it) }
        return walk(rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode())
    }

    private val chartModifier = Modifier
        .fillMaxWidth()
        .height(320.dp)
        .testTag("pie3d")

    // ---- rendering --------------------------------------------------------

    @Test
    fun aPieDraws() {
        rule.setContent {
            Harness {
                PieChart3D(
                    data = browsers,
                    value = { it.users },
                    label = { it.name },
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("pie3d").assertIsDisplayed()
    }

    @Test
    fun aDonutDraws() {
        rule.setContent {
            Harness {
                DonutChart3D(
                    data = browsers,
                    value = { it.users },
                    label = { it.name },
                    innerRadiusRatio = 0.55f,
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("pie3d").assertIsDisplayed()
    }

    @Test
    fun aDonutHasMoreFacesThanAPieBecauseOfItsInnerWall() {
        var pieFaces: Chart3DDiagnostics? = null
        var donutFaces: Chart3DDiagnostics? = null
        rule.setContent {
            Harness {
                Column {
                    PieChart3D(
                        data = browsers,
                        value = { it.users },
                        label = { it.name },
                        animation = ChartAnimation.None,
                        quality = Chart3DQuality.Low,
                        onDiagnostics = { pieFaces = it },
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                    )
                    DonutChart3D(
                        data = browsers,
                        value = { it.users },
                        label = { it.name },
                        innerRadiusRatio = 0.5f,
                        animation = ChartAnimation.None,
                        quality = Chart3DQuality.Low,
                        onDiagnostics = { donutFaces = it },
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
        val pie = pieFaces
        val donut = donutFaces
        assertNotNull("the pie reported no diagnostics", pie)
        assertNotNull("the donut reported no diagnostics", donut)
        assertTrue(
            "a donut adds an inner wall to every slice: ${pie!!.faceCount} vs ${donut!!.faceCount}",
            donut.faceCount > pie.faceCount,
        )
        assertTrue("both tessellate their arcs", donut.tessellationSegments > 0)
    }

    @Test
    fun aChartWithNoUsableValuesShowsItsEmptyState() {
        rule.setContent {
            Harness {
                PieChart3D(
                    data = listOf(Share("a", 0.0), Share("b", null), Share("c", -4.0)),
                    value = { it.users },
                    label = { it.name },
                    animation = ChartAnimation.None,
                    emptyContent = { Text("Nothing to plot") },
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Nothing to plot").assertIsDisplayed()
    }

    @Test
    fun everySizeItIsGivenIsDrawable() {
        // Including sizes at which nothing sensible can be drawn. A chart that
        // threw at 1dp would take an app down during a layout animation.
        val sizes = listOf(1, 24, 90, 240, 600)
        rule.setContent {
            Harness {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    sizes.forEach { side ->
                        PieChart3D(
                            data = browsers,
                            value = { it.users },
                            label = { it.name },
                            animation = ChartAnimation.None,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(side.dp)
                                .testTag("size-$side"),
                        )
                    }
                }
            }
        }
        sizes.forEach { rule.onNodeWithTag("size-$it").performScrollTo().assertIsDisplayed() }
    }

    // ---- interaction ------------------------------------------------------

    @Test
    fun aTapOnASliceSelectsIt() {
        var selection: ChartSelection<Share>? = null
        rule.setContent {
            Harness {
                PieChart3D(
                    data = browsers,
                    value = { it.users },
                    label = { it.name },
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selection = it },
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        // Right of centre and a little above the middle: inside Chrome, which
        // sweeps clockwise from twelve o'clock through nearly half the circle.
        rule.onNodeWithTag("pie3d").performTouchInput {
            click(Offset(width * 0.66f, height * 0.42f))
        }
        rule.waitForIdle()
        assertNotNull("a tap on a slice must select something", selection)
        assertEquals("Chrome", selection!!.item?.name)
        assertEquals(4823.0, selection!!.y, 1e-9)
    }

    @Test
    fun aTapSelectsEvenWhileTheChartIsStillAnimating() {
        // The gesture callbacks capture their render context once per layout,
        // so the reveal they carry is whatever it was when the chart was
        // measured — zero, on a chart that animates in. Hit testing against
        // that geometry would test a tap against a pie of no sweep at all.
        var selection: ChartSelection<Share>? = null
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Harness {
                PieChart3D(
                    data = browsers,
                    value = { it.users },
                    label = { it.name },
                    animation = ChartAnimation.Default,
                    onSelectionChanged = { selection = it },
                    modifier = chartModifier,
                )
            }
        }
        rule.mainClock.advanceTimeBy(16)
        rule.onNodeWithTag("pie3d").performTouchInput {
            click(Offset(width * 0.66f, height * 0.42f))
        }
        rule.mainClock.advanceTimeBy(64)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertNotNull("a tap during the reveal must still select", selection)
        assertEquals("Chrome", selection!!.item?.name)
    }

    @Test
    fun aTapOutsideThePieClearsTheSelection() {
        var selection: ChartSelection<Share>? = null
        rule.setContent {
            Harness {
                PieChart3D(
                    data = browsers,
                    value = { it.users },
                    label = { it.name },
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selection = it },
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("pie3d").performTouchInput {
            click(Offset(width * 0.66f, height * 0.42f))
        }
        rule.waitForIdle()
        assertNotNull(selection)
        rule.onNodeWithTag("pie3d").performTouchInput { click(Offset(4f, 4f)) }
        rule.waitForIdle()
        assertNull("a tap on the background clears the selection", selection)
    }

    @Test
    fun aSelectedSliceExplodesAndTheSelectionSurvivesIt() {
        // The slice moves; the *identity* does not. A hit test run after the
        // explode animation has finished must still find the same slice, at its
        // new position — which only works because the hit test reads the
        // displaced geometry rather than the original.
        var selection: ChartSelection<Share>? = null
        rule.setContent {
            Harness {
                PieChart3D(
                    data = browsers,
                    value = { it.users },
                    label = { it.name },
                    explodeSelected = true,
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selection = it },
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("pie3d").performTouchInput {
            click(Offset(width * 0.66f, height * 0.42f))
        }
        rule.waitForIdle()
        assertEquals("Chrome", selection?.item?.name)
        rule.onNodeWithTag("pie3d").performTouchInput {
            click(Offset(width * 0.66f, height * 0.42f))
        }
        rule.waitForIdle()
        assertEquals(
            "the displaced slice is still under the same finger",
            "Chrome",
            selection?.item?.name,
        )
    }

    @Test
    fun rotationIsOffUntilItIsAskedFor() {
        var camera: Chart3DCamera? = null
        rule.setContent {
            Harness {
                val state = rememberChart3DCameraState(camera = Chart3DCamera.Radial)
                camera = state.camera
                PieChart3D(
                    data = browsers,
                    value = { it.users },
                    label = { it.name },
                    cameraState = state,
                    interaction = Chart3DInteraction.Select,
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        val before = camera
        rule.onNodeWithTag("pie3d").performTouchInput {
            swipe(Offset(width * 0.5f, height * 0.5f), Offset(width * 0.5f, height * 0.2f))
        }
        rule.waitForIdle()
        assertEquals("a drag must not move a camera the chart was told to hold still", before, camera)
    }

    @Test
    fun aDragTurnsTheCameraWhenRotationIsOn() {
        var camera: Chart3DCamera? = null
        rule.setContent {
            Harness {
                val state = rememberChart3DCameraState(camera = Chart3DCamera.Radial)
                camera = state.camera
                PieChart3D(
                    data = browsers,
                    value = { it.users },
                    label = { it.name },
                    cameraState = state,
                    interaction = Chart3DInteraction.RotateAndSelect,
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        val before = camera!!
        rule.onNodeWithTag("pie3d").performTouchInput {
            swipe(Offset(width * 0.5f, height * 0.5f), Offset(width * 0.5f, height * 0.25f))
        }
        rule.waitForIdle()
        assertTrue(
            "dragging up must lower the pitch, was ${before.rotationX} then ${camera!!.rotationX}",
            camera!!.rotationX < before.rotationX,
        )
    }

    // ---- centre content ---------------------------------------------------

    @Test
    fun centreContentIsPlacedInsideTheProjectedHole() {
        rule.setContent {
            Harness {
                Box(Modifier.testTag("holder")) {
                    DonutChart3D(
                        data = browsers,
                        value = { it.users },
                        label = { it.name },
                        innerRadiusRatio = 0.6f,
                        animation = ChartAnimation.None,
                        centerContent = { Text("11,456", Modifier.testTag("total")) },
                        modifier = chartModifier,
                    )
                }
            }
        }
        rule.waitForIdle()
        val content = rule.onNodeWithTag("total", useUnmergedTree = true).fetchSemanticsNode()
        val chart = rule.onNodeWithTag("pie3d", useUnmergedTree = true).fetchSemanticsNode()
        val box = content.boundsInRoot
        val plot = chart.boundsInRoot
        assertTrue("the total must be inside the chart", box.left >= plot.left)
        assertTrue(box.right <= plot.right)
        assertTrue(box.top >= plot.top)
        assertTrue(box.bottom <= plot.bottom)
    }

    @Test
    fun centreContentIsNotDrawnForAPie() {
        // A pie has no hole, so there is nowhere for it to go. Drawing it over
        // the middle of the slices would put an unreadable total on top of the
        // data.
        rule.setContent {
            Harness {
                PieChart3D(
                    data = browsers,
                    value = { it.users },
                    label = { it.name },
                    animation = ChartAnimation.None,
                    centerContent = { Text("nowhere", Modifier.testTag("total")) },
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("total", useUnmergedTree = true).assertDoesNotExist()
    }

    // ---- accessibility ----------------------------------------------------

    @Test
    fun theAnnouncementCarriesValuesAndShares() {
        rule.setContent {
            Harness {
                PieChart3D(
                    data = browsers,
                    value = { it.users },
                    label = { it.name },
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        val description = descriptionOf("pie3d")
        assertNotNull(description)
        // 4,823 of the 10,756 these four browsers total.
        listOf("Chrome", "Safari", "44.8%", "4823").forEach {
            assertTrue("the announcement is missing \"$it\": $description", description!!.contains(it))
        }
        listOf("camera", "projection", "face", "depth").forEach {
            assertTrue(
                "the announcement must not describe the rendering: $description",
                !description!!.lowercase().contains(it),
            )
        }
    }

    @Test
    fun theAnnouncementIsTheSameAtEveryCameraAngle() {
        // The whole accessibility argument, as a test: the semantics come from
        // the data, so nothing a camera does can change them.
        var pitch by mutableStateOf(10.0)
        rule.setContent {
            Harness {
                PieChart3D(
                    data = browsers,
                    value = { it.users },
                    label = { it.name },
                    cameraState = rememberChart3DCameraState(
                        camera = Chart3DCamera(rotationX = pitch, rotationY = 0.0),
                    ),
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        val flat = descriptionOf("pie3d")
        pitch = 70.0
        rule.waitForIdle()
        assertEquals(flat, descriptionOf("pie3d"))
    }

    @Test
    fun selectingASliceAnnouncesItsShare() {
        rule.setContent {
            Harness {
                PieChart3D(
                    data = browsers,
                    value = { it.users },
                    label = { it.name },
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("pie3d").performTouchInput {
            click(Offset(width * 0.66f, height * 0.42f))
        }
        rule.waitForIdle()
        val description = descriptionOf("pie3d")
        assertNotNull(description)
        assertTrue(
            "a selected slice must announce its share: $description",
            description!!.contains("Chrome") && description.contains("44.8%"),
        )
    }

    // ---- configuration ----------------------------------------------------

    @Test
    fun everyProjectionDepthAndQualityCombinationDraws() {
        val projections = listOf(Chart3DProjection.Perspective(), Chart3DProjection.Orthographic)
        val depths = listOf(Chart3DDepth.Auto, Chart3DDepth.Relative(0.5), Chart3DDepth.Absolute(20f))
        val qualities = listOf(Chart3DQuality.Low, Chart3DQuality.High)
        var index by mutableStateOf(0)
        val combinations = projections.flatMap { p ->
            depths.flatMap { d -> qualities.map { q -> Triple(p, d, q) } }
        }
        rule.setContent {
            Harness {
                val (projection, depth, quality) = combinations[index]
                DonutChart3D(
                    data = browsers,
                    value = { it.users },
                    label = { it.name },
                    projection = projection,
                    depth = depth,
                    quality = quality,
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        combinations.indices.forEach { at ->
            index = at
            rule.waitForIdle()
            rule.onNodeWithTag("pie3d").assertIsDisplayed()
        }
    }

    @Test
    fun labelsAndPartialSweepsDraw() {
        val positions = listOf(
            SliceLabelPosition.Inside,
            SliceLabelPosition.Outside,
            SliceLabelPosition.Auto,
        )
        var index by mutableStateOf(0)
        rule.setContent {
            Harness {
                PieChart3D(
                    data = browsers,
                    value = { it.users },
                    label = { it.name },
                    labelPosition = positions[index],
                    labelContent = SliceLabelContent.LabelAndPercentage,
                    // A partial sweep, a gap and a counter-clockwise chart at
                    // once: three things that each change the angles, and none
                    // of which the 3D layer computes for itself.
                    startAngle = 45f,
                    sweepAngle = 270f,
                    sliceGap = 2f,
                    direction = PolarDirection.CounterClockwise,
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        positions.indices.forEach { at ->
            index = at
            rule.waitForIdle()
            rule.onNodeWithTag("pie3d").assertIsDisplayed()
        }
    }

    @Test
    fun aDataChangeKeepsTheChartDrawable() {
        var second by mutableStateOf(false)
        rule.setContent {
            Harness {
                PieChart3D(
                    data = if (second) {
                        listOf(Share("Chrome", 1200.0), Share("Safari", 4000.0))
                    } else {
                        browsers
                    },
                    value = { it.users },
                    label = { it.name },
                    animation = ChartAnimation.Default,
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        second = true
        rule.waitForIdle()
        rule.onNodeWithTag("pie3d").assertIsDisplayed()
        val description = descriptionOf("pie3d")
        assertTrue(
            "the announcement follows the data: $description",
            description!!.contains("4000"),
        )
    }
}
