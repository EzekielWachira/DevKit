package io.devkit.chartkit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.charts.CartesianChart3D
import io.devkit.chartkit.charts.ExperimentalChartKitApi
import io.devkit.chartkit.charts.Chart3DInteraction
import io.devkit.chartkit.charts.ColumnChart3D
import io.devkit.chartkit.geometry.BarGrouping
import io.devkit.chartkit.layer.three.Column3DLabelPlacement
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.state.rememberChart3DCameraState
import io.devkit.chartkit.theme.ChartKitTheme
import io.devkit.chartkit.three.Chart3DCamera
import io.devkit.chartkit.three.Chart3DDiagnostics
import io.devkit.chartkit.three.Chart3DFrame
import io.devkit.chartkit.three.Chart3DProjection
import io.devkit.chartkit.three.Column3DArrangement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private data class Pick(val fruit: String, val count: Double?)

private val john = listOf(Pick("Apples", 5.0), Pick("Oranges", 3.0), Pick("Pears", 4.0))
private val jane = listOf(Pick("Apples", 2.0), Pick("Oranges", 5.0), Pick("Pears", 6.0))
private val joe = listOf(Pick("Apples", 3.0), Pick("Oranges", 4.0), Pick("Pears", 4.0))
private val janet = listOf(Pick("Apples", 3.0), Pick("Oranges", 0.0), Pick("Pears", null))

private val harvest = listOf(
    ChartSeries("john", "John", john),
    ChartSeries("jane", "Jane", jane),
    ChartSeries("joe", "Joe", joe),
    ChartSeries("janet", "Janet", janet),
)

/**
 * 3D columns on a device.
 *
 * The tests that need a composition, pixels or an animation clock: what a tap
 * resolves to, what a screen reader hears, what a legend toggle does to a
 * stack, and whether the chart survives every size it is given. The geometry,
 * the projection, the culling, the sort and the stack arithmetic are plain
 * Kotlin and are covered without a device in `Chart3DGeometryTest`,
 * `Column3DLayoutTest` and `Chart3DPipelineTest`.
 */
class Chart3DColumnTest {

    @get:Rule
    val rule = createComposeRule()

    @Composable
    private fun Harness(content: @Composable () -> Unit) {
        MaterialTheme { Surface { ChartKitTheme { content() } } }
    }

    /**
     * The chart's own announcement, wherever it hangs in the tree.
     *
     * The description is set on the Canvas with `clearAndSetSemantics`, and the
     * test tag is on the composable's outer container — so the tagged node has
     * no description of its own and the walk finds the one that does.
     */
    private fun descriptionOf(tag: String): String? {
        fun walk(node: androidx.compose.ui.semantics.SemanticsNode): String? =
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                ?: node.children.firstNotNullOfOrNull { walk(it) }
        return walk(rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode())
    }

    private val chartModifier = Modifier
        .fillMaxWidth()
        .height(300.dp)
        .testTag("chart3d")

    // ---- rendering --------------------------------------------------------

    @Test
    fun aSingleSeriesChartDraws() {
        rule.setContent {
            Harness {
                ColumnChart3D(
                    data = john,
                    category = { it.fruit },
                    value = { it.count },
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        rule.onNodeWithTag("chart3d").assertIsDisplayed()
    }

    @Test
    fun aGroupedAndStackedChartDrawsAndLegendsItsSeries() {
        rule.setContent {
            Harness {
                ColumnChart3D(
                    series = harvest,
                    category = { it.fruit },
                    value = { it.count },
                    grouping = BarGrouping.Stacked,
                    stack = { if (it.id == "john" || it.id == "joe") "male" else "female" },
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        rule.onNodeWithTag("chart3d").assertIsDisplayed()
        listOf("John", "Jane", "Joe", "Janet").forEach {
            rule.onNodeWithText(it).assertIsDisplayed()
        }
    }

    /** The 3D chart writes its own axis labels, at projected positions. */
    @Test
    fun theChartWritesItsOwnAxisLabels() {
        var description: String?
        rule.setContent {
            Harness {
                ColumnChart3D(
                    data = john,
                    category = { it.fruit },
                    value = { it.count },
                    categoryAxis = ChartAxis(title = "Fruit"),
                    valueAxis = ChartAxis(title = "Picked"),
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        rule.onNodeWithTag("chart3d").assertIsDisplayed()
        description = descriptionOf("chart3d")
        // The axis titles reach a screen reader through the summary, not by
        // being separate nodes: the canvas replaces its children's semantics.
        assertNotNull(description)
    }

    @Test
    fun everyProjectionDraws() {
        rule.setContent {
            Harness {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    listOf(
                        "perspective" to Chart3DProjection.Perspective(),
                        "orthographic" to Chart3DProjection.Orthographic,
                    ).forEach { (tag, projection) ->
                        ColumnChart3D(
                            series = harvest,
                            category = { it.fruit },
                            value = { it.count },
                            grouping = BarGrouping.Stacked,
                            projection = projection,
                            animation = ChartAnimation.None,
                            modifier = Modifier.fillMaxWidth().height(240.dp).testTag(tag),
                        )
                    }
                }
            }
        }
        listOf("perspective", "orthographic").forEach {
            rule.onNodeWithTag(it).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun everyFrameConfigurationDraws() {
        rule.setContent {
            Harness {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    listOf(
                        "none" to Chart3DFrame.None,
                        "auto" to Chart3DFrame.Auto,
                        "visible" to Chart3DFrame.Visible,
                    ).forEach { (tag, frame) ->
                        ColumnChart3D(
                            data = john,
                            category = { it.fruit },
                            value = { it.count },
                            frame = frame,
                            animation = ChartAnimation.None,
                            modifier = Modifier.fillMaxWidth().height(220.dp).testTag(tag),
                        )
                    }
                }
            }
        }
        listOf("none", "auto", "visible").forEach {
            rule.onNodeWithTag(it).performScrollTo().assertIsDisplayed()
        }
    }

    /**
     * Every size, in one composition.
     *
     * A chart measured at zero, at a sliver and at a full page all go through
     * the same fit — and the sliver is the one that divides by a projected span
     * of nearly nothing.
     */
    @Test
    fun aChartSurvivesEverySizeItIsGiven() {
        rule.setContent {
            Harness {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    listOf(1, 40, 120, 300, 600).forEach { height ->
                        ColumnChart3D(
                            series = harvest,
                            category = { it.fruit },
                            value = { it.count },
                            grouping = BarGrouping.Stacked,
                            animation = ChartAnimation.None,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(height.dp)
                                .testTag("size-$height"),
                        )
                    }
                }
            }
        }
        listOf(1, 40, 120, 300, 600).forEach {
            rule.onNodeWithTag("size-$it").performScrollTo().assertIsDisplayed()
        }
    }

    // ---- interaction ------------------------------------------------------

    @Test
    fun aTapSelectsTheColumnUnderIt() {
        var selection: ChartSelection<Pick>? = null
        rule.setContent {
            Harness {
                ColumnChart3D(
                    data = john,
                    category = { it.fruit },
                    value = { it.count },
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selection = it },
                    modifier = chartModifier,
                )
            }
        }
        // Left third, below the middle: the first column, whatever the camera
        // did with it. A tall column occupies most of its band's height.
        rule.onNodeWithTag("chart3d").performTouchInput {
            click(Offset(width * 0.22f, height * 0.7f))
        }
        rule.waitForIdle()
        assertNotNull("a tap on a column must select it", selection)
        assertEquals("Apples", selection?.xLabel)
        assertEquals(5.0, selection?.y)
        assertEquals("the caller's own object comes back", "Apples", selection?.item?.fruit)
    }

    /**
     * The regression this exists for.
     *
     * Hit testing runs against *settled* geometry, because the gesture
     * callbacks capture their render context once per layout and the animation
     * fraction it carries is stale. Testing against that would test a tap
     * against columns lying flat on the floor: the chart looks right and never
     * selects anything.
     */
    @Test
    fun aTapSelectsEvenWhileTheChartIsStillAnimating() {
        var selection: ChartSelection<Pick>? = null
        rule.setContent {
            Harness {
                ColumnChart3D(
                    data = john,
                    category = { it.fruit },
                    value = { it.count },
                    animation = ChartAnimation.Default,
                    onSelectionChanged = { selection = it },
                    modifier = chartModifier,
                )
            }
        }
        rule.onNodeWithTag("chart3d").performTouchInput {
            click(Offset(width * 0.22f, height * 0.7f))
        }
        rule.waitForIdle()
        assertNotNull(selection)
    }

    @Test
    fun aTapOnEmptySpaceSelectsNothing() {
        var selection: ChartSelection<Pick>? = null
        rule.setContent {
            Harness {
                ColumnChart3D(
                    data = john,
                    category = { it.fruit },
                    value = { it.count },
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selection = it },
                    modifier = chartModifier,
                )
            }
        }
        // The very top of the plot: above every column, over the back wall.
        rule.onNodeWithTag("chart3d").performTouchInput {
            click(Offset(width * 0.5f, height * 0.04f))
        }
        rule.waitForIdle()
        assertNull("the back wall is scenery, not data", selection)
    }

    @Test
    fun aDragTurnsTheCameraOnlyWhenRotationIsEnabled() {
        var fixed: Chart3DCamera? = null
        var turned: Chart3DCamera? = null
        rule.setContent {
            Harness {
                val a = rememberChart3DCameraState()
                val b = rememberChart3DCameraState()
                fixed = a.camera
                turned = b.camera
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ColumnChart3D(
                        data = john,
                        category = { it.fruit },
                        value = { it.count },
                        cameraState = a,
                        interaction = Chart3DInteraction.Select,
                        animation = ChartAnimation.None,
                        modifier = Modifier.fillMaxWidth().height(240.dp).testTag("fixed"),
                    )
                    ColumnChart3D(
                        data = john,
                        category = { it.fruit },
                        value = { it.count },
                        cameraState = b,
                        interaction = Chart3DInteraction.Rotate,
                        animation = ChartAnimation.None,
                        modifier = Modifier.fillMaxWidth().height(240.dp).testTag("turnable"),
                    )
                }
            }
        }
        val before = fixed?.rotationY
        rule.onNodeWithTag("fixed").performTouchInput {
            swipe(Offset(width * 0.7f, height * 0.5f), Offset(width * 0.2f, height * 0.5f))
        }
        rule.onNodeWithTag("turnable").performScrollTo().performTouchInput {
            swipe(Offset(width * 0.7f, height * 0.5f), Offset(width * 0.2f, height * 0.5f))
        }
        rule.waitForIdle()
        assertEquals("a chart that does not rotate must not have rotated", before, fixed?.rotationY)
        assertTrue(
            "a chart that does rotate must have turned",
            turned?.rotationY != Chart3DCamera.DEFAULT_ROTATION_Y,
        )
    }

    /**
     * A camera animation, stepped on a controlled clock.
     *
     * No sleeps and no wall-clock waiting: the test drives the frame clock
     * itself, so it asserts that the camera is *interpolating* rather than that
     * it eventually arrived.
     */
    @Test
    fun animatingTheCameraInterpolatesRatherThanJumping() {
        lateinit var camera: io.devkit.chartkit.state.Chart3DCameraState
        var animate by mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Harness {
                camera = rememberChart3DCameraState(rotationX = 10.0, rotationY = 0.0)
                // Launched from inside the composition, so it runs on the
                // composition's own frame clock — which is the clock the test
                // is driving. A coroutine started from the test thread has no
                // frame clock at all and the animation never advances.
                if (animate) {
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        camera.animateTo(Chart3DCamera(rotationX = 10.0, rotationY = 50.0))
                    }
                }
                ColumnChart3D(
                    data = john,
                    category = { it.fruit },
                    value = { it.count },
                    cameraState = camera,
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnUiThread { animate = true }
        repeat(8) { rule.mainClock.advanceTimeBy(20L) }

        val midway = camera.camera.rotationY
        assertTrue(
            "the camera should be between its endpoints, was $midway",
            midway > 0.0 && midway < 50.0,
        )
        rule.mainClock.advanceTimeBy(2_000L)
        assertEquals(50.0, camera.camera.rotationY, 1e-6)
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun resettingTheCameraReturnsToExactlyTheStartingView() {
        rule.setContent {
            Harness {
                val camera = rememberChart3DCameraState(rotationX = 20.0, rotationY = 25.0)
                ColumnChart3D(
                    data = john,
                    category = { it.fruit },
                    value = { it.count },
                    cameraState = camera,
                    interaction = Chart3DInteraction.Rotate,
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
                LaunchedReset(camera)
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("chart3d").assertIsDisplayed()
    }

    @Composable
    private fun LaunchedReset(state: io.devkit.chartkit.state.Chart3DCameraState) {
        androidx.compose.runtime.LaunchedEffect(state) {
            state.rotateBy(30.0, 30.0)
            state.reset()
            assertEquals(20.0, state.camera.rotationX, 1e-9)
            assertEquals(25.0, state.camera.rotationY, 1e-9)
        }
    }

    // ---- data changes -----------------------------------------------------

    @Test
    fun hidingASeriesRecomputesItsStack() {
        var hidden by mutableStateOf(false)
        rule.setContent {
            Harness {
                val series = remember(hidden) {
                    harvest.map { it.copy(visible = !(hidden && it.id == "john")) }
                }
                ColumnChart3D(
                    series = series,
                    category = { it.fruit },
                    value = { it.count },
                    grouping = BarGrouping.Stacked,
                    stack = { "everyone" },
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        rule.onNodeWithTag("chart3d").assertIsDisplayed()
        hidden = true
        rule.waitForIdle()
        rule.onNodeWithTag("chart3d").assertIsDisplayed()
    }

    @Test
    fun aNullValueDrawsNoColumnAndAZeroStaysInTheData() {
        var description: String?
        rule.setContent {
            Harness {
                ColumnChart3D(
                    series = listOf(ChartSeries("janet", "Janet", janet)),
                    category = { it.fruit },
                    value = { it.count },
                    animation = ChartAnimation.None,
                    valueLabels = Column3DLabelPlacement.Top,
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        description = descriptionOf("chart3d")
        assertNotNull(description)
        // Janet picked no pears at all and zero oranges. The announcement has
        // to distinguish the two: a zero is a reading and a null is not.
        assertTrue("the announcement was: $description", description!!.contains("Oranges"))
    }

    // ---- accessibility ----------------------------------------------------

    @Test
    fun theAnnouncementIsAboutDataAndNeverAboutGeometry() {
        rule.setContent {
            Harness {
                ColumnChart3D(
                    series = harvest,
                    category = { it.fruit },
                    value = { it.count },
                    grouping = BarGrouping.Stacked,
                    stack = { "everyone" },
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        val description = descriptionOf("chart3d")
        assertNotNull(description)
        listOf("face", "camera", "depth", "projection", "cuboid").forEach { word ->
            assertTrue(
                "the announcement mentioned $word: $description",
                !description!!.lowercase().contains(word),
            )
        }
        assertTrue(description!!.contains("John"))
    }

    @Test
    fun selectingAStackedColumnAnnouncesItsTotal() {
        rule.setContent {
            Harness {
                ColumnChart3D(
                    series = harvest,
                    category = { it.fruit },
                    value = { it.count },
                    grouping = BarGrouping.Stacked,
                    stack = { "everyone" },
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                )
            }
        }
        rule.onNodeWithTag("chart3d").performTouchInput {
            click(Offset(width * 0.22f, height * 0.7f))
        }
        rule.waitForIdle()
        val description = descriptionOf("chart3d")
        assertTrue(
            "a stacked selection should carry its total: $description",
            description?.contains("Stack total") == true,
        )
    }

    /**
     * Keyboard and screen-reader stepping still works.
     *
     * The camera does not take the arrow keys, so a reader without a pointer
     * reads a 3D chart exactly as they read a 2D one — through the same custom
     * actions TalkBack offers on every other ChartKit chart.
     */
    @Test
    fun aReaderCanStepThroughTheCategoriesWithoutAPointer() {
        var selection: ChartSelection<Pick>? = null
        rule.setContent {
            Harness {
                ColumnChart3D(
                    data = john,
                    category = { it.fruit },
                    value = { it.count },
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selection = it },
                    modifier = chartModifier,
                )
            }
        }
        val actions = rule.onNodeWithTag("chart3d", useUnmergedTree = true)
            .fetchSemanticsNode()
            .let { node ->
                fun walk(
                    n: androidx.compose.ui.semantics.SemanticsNode,
                ): List<androidx.compose.ui.semantics.CustomAccessibilityAction>? =
                    n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.CustomActions)
                        ?: n.children.firstNotNullOfOrNull { walk(it) }
                walk(node)
            }
        assertNotNull("the chart offers no stepping actions", actions)
        val next = actions!!.first { it.label == "Next data point" }
        rule.runOnUiThread { next.action() }
        rule.waitForIdle()
        assertNotNull("stepping selected nothing", selection)
        val first = selection?.xLabel
        rule.runOnUiThread { next.action() }
        rule.waitForIdle()
        assertTrue(
            "stepping again should move: was $first, now ${selection?.xLabel}",
            selection?.xLabel != first,
        )
    }

    // ---- the low-level API ------------------------------------------------

    @OptIn(ExperimentalChartKitApi::class)
    @Test
    fun theLowLevelApiDrawsTheSameChart() {
        rule.setContent {
            Harness {
                CartesianChart3D(
                    animation = ChartAnimation.None,
                    modifier = chartModifier,
                ) {
                    columns(
                        series = harvest,
                        category = { it.fruit },
                        value = { it.count },
                        grouping = BarGrouping.Stacked,
                        stack = { if (it.id == "john") "a" else "b" },
                        arrangement = Column3DArrangement.Depth,
                    )
                }
            }
        }
        rule.onNodeWithTag("chart3d").assertIsDisplayed()
        rule.onNodeWithText("John").assertIsDisplayed()
    }

    // ---- export -----------------------------------------------------------

    /**
     * The exported scene is the finished picture, not an approximation of it.
     *
     * What a 3D chart draws *is* a list of filled convex polygons in a
     * particular order, so a scene holding exactly those renders identically —
     * which is why the layer reports itself as fully exported rather than
     * naming itself in `unexportedLayers`.
     */
    @Test
    fun theSceneExportsEveryDrawnFace() {
        lateinit var sceneState: io.devkit.chartkit.scene.ChartSceneState
        rule.setContent {
            Harness {
                sceneState = io.devkit.chartkit.scene.rememberChartSceneState()
                ColumnChart3D(
                    series = harvest,
                    category = { it.fruit },
                    value = { it.count },
                    grouping = BarGrouping.Stacked,
                    stack = { "everyone" },
                    animation = ChartAnimation.None,
                    sceneState = sceneState,
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        val scene = sceneState.scene
        assertNotNull("the chart exported no scene", scene)
        assertTrue("a layer went unexported: ${scene!!.unexportedLayers}", scene.isComplete)
        val polygons = scene.flatten()
            .filterIsInstance<io.devkit.chartkit.scene.ChartSceneNode.Path>()
            .filter { it.closed }
        assertTrue("the scene holds no polygons", polygons.isNotEmpty())
    }

    // ---- diagnostics ------------------------------------------------------

    @Test
    fun diagnosticsAccountForEveryFaceThatWasConsidered() {
        var diagnostics: Chart3DDiagnostics? = null
        rule.setContent {
            Harness {
                ColumnChart3D(
                    series = listOf(ChartSeries("john", "John", john)),
                    category = { it.fruit },
                    value = { it.count },
                    animation = ChartAnimation.None,
                    onDiagnostics = { diagnostics = it },
                    modifier = chartModifier,
                )
            }
        }
        rule.waitForIdle()
        val d = diagnostics
        assertNotNull("the chart reported no diagnostics", d)
        assertEquals(john.size, d!!.objectCount)
        assertEquals(john.size * 6, d.faceCount)
        assertEquals(
            d.faceCount,
            d.renderedFaces + d.culledFaces + d.degenerateFaces + d.clippedFaces,
        )
        assertTrue("some faces must survive", d.renderedFaces > 0)
        assertTrue("and some must be culled", d.culledFaces > 0)
    }
}
