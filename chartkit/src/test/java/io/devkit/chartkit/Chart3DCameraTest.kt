package io.devkit.chartkit

import io.devkit.chartkit.axis.AxisDimension
import io.devkit.chartkit.axis.AxisPosition
import io.devkit.chartkit.axis.AxisRegistry
import io.devkit.chartkit.axis.ChartAxisException
import io.devkit.chartkit.axis.ChartAxisId
import io.devkit.chartkit.axis.ChartAxisSpec
import io.devkit.chartkit.geometry.BarGrouping
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.state.Chart3DCameraState
import io.devkit.chartkit.three.Chart3DCamera
import io.devkit.chartkit.three.Chart3DCameraLimits
import io.devkit.chartkit.three.Chart3DDepth
import io.devkit.chartkit.three.Chart3DHitTest
import io.devkit.chartkit.three.Chart3DLighting
import io.devkit.chartkit.three.Chart3DObject
import io.devkit.chartkit.three.Chart3DProjection
import io.devkit.chartkit.three.Chart3DProjector
import io.devkit.chartkit.three.Chart3DScene
import io.devkit.chartkit.three.Chart3DSceneDepth
import io.devkit.chartkit.three.Column3DArrangement
import io.devkit.chartkit.three.Column3DLayout
import io.devkit.chartkit.three.Column3DLayoutEngine
import io.devkit.chartkit.three.Column3DSeries
import io.devkit.chartkit.three.FaceSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The interactive half of the 3D column chart: a camera that moves, and data
 * that does not move with it.
 *
 * ### What is actually being protected
 *
 * The architecture's central claim is that a camera change reprojects and
 * recomputes nothing else — §15, §189, §191. That claim is invisible from the
 * outside: a chart that rebuilt its entire stack layout on every pointer frame
 * would look identical and simply be slow. So the tests assert *identity* of
 * the world geometry across camera changes, which is the only observable form
 * the claim takes.
 */
class Chart3DCameraTest {

    private val categories = listOf("Jan", "Feb", "Mar", "Apr")
    private val plot = ChartRect(0f, 0f, 800f, 600f)

    private fun series() = listOf(
        Column3DSeries(
            seriesId = "revenue",
            seriesName = "Revenue",
            paletteIndex = 0,
            colorOverride = null,
            stackId = "revenue",
            values = listOf(40.0, 65.0, 30.0, 80.0),
            sourceIndices = listOf(0, 1, 2, 3),
        ),
        Column3DSeries(
            seriesId = "cost",
            seriesName = "Cost",
            paletteIndex = 1,
            colorOverride = null,
            stackId = "cost",
            values = listOf(22.0, 31.0, 18.0, 44.0),
            sourceIndices = listOf(0, 1, 2, 3),
        ),
    )

    private fun layout(
        sceneDepth: Chart3DSceneDepth = Chart3DSceneDepth.Auto,
        arrangement: Column3DArrangement = Column3DArrangement.Depth,
        colorByPoint: Boolean = false,
    ): Column3DLayout = Column3DLayoutEngine.layout(
        categories = categories,
        series = series(),
        items = emptyMap(),
        grouping = BarGrouping.Grouped,
        arrangement = arrangement,
        depth = Chart3DDepth.Auto,
        categoryCentres = List(categories.size) { (it + 0.5f) * plot.width / categories.size },
        bandWidth = plot.width / categories.size * 0.8f,
        valueFraction = { value -> value / 100.0 },
        plotWidth = plot.width,
        plotHeight = plot.height,
        groupPadding = 0.1,
        depthGap = 0.25,
        sceneDepth = sceneDepth,
        colorByPoint = colorByPoint,
    )

    private fun scene(layout: Column3DLayout) = Chart3DScene(
        objects = layout.segments.map { Chart3DObject(it.cuboid, it.paletteIndex) },
        lighting = Chart3DLighting.Default,
    )

    // ---- camera state -----------------------------------------------------

    private fun state(
        camera: Chart3DCamera = Chart3DCamera.Default,
        limits: Chart3DCameraLimits = Chart3DCameraLimits.Default,
    ) = Chart3DCameraState(initial = camera, limits = limits)

    @Test
    fun `rotating by a delta and rotating to an angle reach the same camera`() {
        val byDelta = state()
        byDelta.rotateBy(deltaX = 10.0, deltaY = -8.0)
        val toAngle = state()
        toAngle.rotateTo(
            rotationX = Chart3DCamera.DEFAULT_ROTATION_X + 10.0,
            rotationY = Chart3DCamera.DEFAULT_ROTATION_Y - 8.0,
        )
        assertEquals(byDelta.camera, toAngle.camera)
    }

    @Test
    fun `rotating one axis leaves the other exactly where it was`() {
        val camera = state()
        camera.rotateTo(rotationY = 40.0)
        assertEquals(Chart3DCamera.DEFAULT_ROTATION_X, camera.camera.rotationX, 1e-12)
        assertEquals(40.0, camera.camera.rotationY, 1e-12)
    }

    @Test
    fun `zooming by a factor is multiplicative and zooming to a distance is absolute`() {
        val camera = state()
        val start = camera.camera.distance
        camera.zoomBy(2f)
        assertEquals(start / 2.0, camera.camera.distance, 1e-12)
        camera.zoomTo(5.5)
        assertEquals(5.5, camera.camera.distance, 1e-12)
    }

    @Test
    fun `a zoom of zero or a negative distance is ignored rather than obeyed`() {
        val camera = state()
        val before = camera.camera
        camera.zoomBy(0f)
        camera.zoomBy(Float.NaN)
        camera.zoomTo(-3.0)
        camera.zoomTo(Double.NaN)
        assertEquals(before, camera.camera)
    }

    /** §179: the reader cannot be put under the floor by a gesture. */
    @Test
    fun `pitch and yaw are held inside the limits however far a drag goes`() {
        val camera = state(limits = Chart3DCameraLimits.Default)
        repeat(50) { camera.rotateBy(deltaX = 20.0, deltaY = 20.0) }
        assertEquals(Chart3DCameraLimits.Default.rotationX.endInclusive, camera.camera.rotationX, 1e-12)
        assertEquals(Chart3DCameraLimits.Default.rotationY.endInclusive, camera.camera.rotationY, 1e-12)
        repeat(80) { camera.rotateBy(deltaX = -20.0, deltaY = -20.0) }
        assertEquals(Chart3DCameraLimits.Default.rotationX.start, camera.camera.rotationX, 1e-12)
        assertEquals(Chart3DCameraLimits.Default.rotationY.start, camera.camera.rotationY, 1e-12)
    }

    @Test
    fun `a true 3D plot may be turned further than a column chart, and still not upside down`() {
        val limits = Chart3DCameraLimits.Cartesian3D
        assertTrue(limits.rotationY.endInclusive > Chart3DCameraLimits.Default.rotationY.endInclusive)
        assertTrue("never below the floor plane", limits.rotationX.start >= 0.0)
        assertTrue("never past edge-on from above", limits.rotationX.endInclusive < 90.0)
        assertTrue("x and z never trade places", limits.rotationY.endInclusive < 90.0)
    }

    /** §180: reset returns to exactly the configured camera, not to a default. */
    @Test
    fun `reset returns to exactly the camera the state was created with`() {
        val initial = Chart3DCamera(rotationX = 22.0, rotationY = -14.0, distance = 4.1)
        val camera = state(initial)
        repeat(7) { camera.rotateBy(deltaX = 6.0, deltaY = -11.0) }
        camera.zoomBy(1.8f)
        assertNotEquals(initial, camera.camera)
        camera.reset()
        assertEquals(initial, camera.camera)
    }

    @Test
    fun `a camera outside its limits is clamped on the way in, not accepted and drawn`() {
        val camera = state(limits = Chart3DCameraLimits.Default)
        camera.camera = Chart3DCamera(rotationX = 300.0, rotationY = -300.0, distance = 100.0)
        assertEquals(Chart3DCameraLimits.Default.rotationX.endInclusive, camera.camera.rotationX, 1e-12)
        assertEquals(Chart3DCameraLimits.Default.rotationY.start, camera.camera.rotationY, 1e-12)
        assertEquals(Chart3DCameraLimits.Default.distance.endInclusive, camera.camera.distance, 1e-12)
    }

    // ---- camera changes do not touch the data ------------------------------

    /** §161: the same world cuboids, projected two ways. */
    @Test
    fun `a camera change reprojects the very same world geometry`() {
        val layout = layout()
        val scene = scene(layout)
        val near = Chart3DProjector.of(
            scene, Chart3DCamera(rotationX = 10.0, rotationY = 10.0), Chart3DProjection.Default, plot,
        )!!
        val far = Chart3DProjector.of(
            scene, Chart3DCamera(rotationX = 40.0, rotationY = -25.0), Chart3DProjection.Default, plot,
        )!!

        // The scene handed to both projectors is one object, and neither
        // projector can reach back into it: the cuboids' vertices are the same
        // instances after both passes.
        val cuboid = layout.segments.first().cuboid
        val facesBefore = cuboid.faces
        val verticesBefore = facesBefore.map { it.vertices }
        near.project(scene)
        far.project(scene)
        // The very same list instance, not merely an equal one: a projector
        // that rebuilt geometry would have replaced it.
        assertSame("projecting must not rebuild a cuboid's faces", facesBefore, cuboid.faces)
        assertEquals(verticesBefore, cuboid.faces.map { it.vertices })

        val a = near.project(scene).faces.first { it.side == FaceSide.Top }
        val b = far.project(scene).faces.first { it.side == FaceSide.Top }
        assertNotEquals("two cameras must produce two pictures", a.points, b.points)
    }

    /** §163: the projection is a configuration, not a second renderer. */
    @Test
    fun `switching to orthographic reprojects the same scene without rebuilding it`() {
        val layout = layout()
        val scene = scene(layout)
        val camera = Chart3DCamera(rotationX = 25.0, rotationY = 25.0)
        val perspective = Chart3DProjector.of(scene, camera, Chart3DProjection.Default, plot)!!
        val parallel = Chart3DProjector.of(scene, camera, Chart3DProjection.Orthographic, plot)!!

        val fromPerspective = perspective.project(scene)
        val fromParallel = parallel.project(scene)
        assertEquals(
            "the same objects reach both projections",
            fromPerspective.diagnostics.objectCount,
            fromParallel.diagnostics.objectCount,
        )

        // The property that distinguishes them: two columns of equal height at
        // different depths are drawn at equal heights under a parallel
        // projection and at unequal ones under perspective.
        val front = layout.segments.first { it.key.stackId == "revenue" && it.key.categoryIndex == 0 }
        val back = layout.segments.first { it.key.stackId == "cost" && it.key.categoryIndex == 0 }
        fun span(projector: Chart3DProjector, cuboid: io.devkit.chartkit.three.Cuboid3D): Double {
            val top = projector.toScreen(cuboid.faceCenter(FaceSide.Top))!!
            val bottom = projector.toScreen(cuboid.faceCenter(FaceSide.Bottom))!!
            return abs(top.y - bottom.y)
        }
        val perspectiveRatio = span(perspective, front.cuboid) / span(perspective, back.cuboid)
        val parallelRatio = span(parallel, front.cuboid) / span(parallel, back.cuboid)
        assertTrue(
            "perspective must foreshorten the far row: $perspectiveRatio",
            abs(perspectiveRatio - 1.0) > abs(parallelRatio - 1.0),
        )
    }

    /** §164: hit testing follows the camera. */
    @Test
    fun `a rotated chart still selects the column the finger is on`() {
        val layout = layout()
        val scene = scene(layout)
        listOf(
            Chart3DCamera(rotationX = 5.0, rotationY = 0.0),
            Chart3DCamera(rotationX = 30.0, rotationY = 35.0),
            Chart3DCamera(rotationX = 55.0, rotationY = -45.0),
        ).forEach { camera ->
            val projector = Chart3DProjector.of(scene, camera, Chart3DProjection.Default, plot)!!
            val faces = projector.project(scene).faces
            val target = layout.segments.first {
                it.key.categoryIndex == 2 && it.key.seriesId == "revenue"
            }
            val centre = projector.toScreen(target.cuboid.faceCenter(FaceSide.Top))!!
            val hit = Chart3DHitTest.faceAt(faces, centre.x, centre.y)
            assertNotNull("nothing under the top of the March column at $camera", hit)
            assertEquals("March, at $camera", 2, hit!!.key!!.categoryIndex)
        }
    }

    // ---- scene depth ------------------------------------------------------

    /** §162: scene depth is the room, not the columns. */
    @Test
    fun `a deeper scene leaves every column exactly as thick as it was`() {
        val shallow = layout(sceneDepth = Chart3DSceneDepth.Auto)
        val deep = layout(sceneDepth = Chart3DSceneDepth.Relative(2.5))

        assertTrue(
            "the volume must have deepened",
            deep.volume.depth > shallow.volume.depth * 2.0,
        )
        // Compared with a tolerance, not exactly: a deeper scene offsets every
        // box in z, and subtracting two larger coordinates to get the same
        // thickness lands an ulp away from subtracting two smaller ones.
        shallow.segments.zip(deep.segments).forEach { (a, b) ->
            assertEquals(
                "column thickness is not scene depth",
                a.cuboid.bounds.depth,
                b.cuboid.bounds.depth,
                1e-9,
            )
        }
        assertEquals(
            "and nothing about the values changed",
            shallow.segments.map { it.value },
            deep.segments.map { it.value },
        )
        assertEquals(
            "nor the heights they are drawn at",
            shallow.segments.map { it.cuboid.bounds.maxY },
            deep.segments.map { it.cuboid.bounds.maxY },
        )
    }

    @Test
    fun `extra scene depth is shared in front of and behind the content`() {
        val shallow = layout(sceneDepth = Chart3DSceneDepth.Auto)
        val deep = layout(sceneDepth = Chart3DSceneDepth.Relative(3.0))
        val shallowSpan = shallow.segments.minOf { it.cuboid.bounds.minZ } to
            shallow.segments.maxOf { it.cuboid.bounds.maxZ }
        val deepSpan = deep.segments.minOf { it.cuboid.bounds.minZ } to
            deep.segments.maxOf { it.cuboid.bounds.maxZ }
        val shallowMiddle = (shallowSpan.first + shallowSpan.second) / 2.0
        val deepMiddle = (deepSpan.first + deepSpan.second) / 2.0
        assertEquals(shallow.volume.depth / 2.0, shallowMiddle, 1e-9)
        assertEquals(
            "the content stays centred in the room, so a deeper scene does not lurch forward",
            deep.volume.depth / 2.0,
            deepMiddle,
            1e-9,
        )
    }

    @Test
    fun `an absolute scene depth shallower than the columns is refused, not drawn through`() {
        val natural = layout(sceneDepth = Chart3DSceneDepth.Auto)
        val squashed = layout(sceneDepth = Chart3DSceneDepth.Absolute(1f))
        assertEquals(
            "a volume shallower than its content would push the back row through the wall",
            natural.volume.depth,
            squashed.volume.depth,
            1e-9,
        )
    }

    @Test
    fun `scene depth policies resolve against a reference and a floor`() {
        assertEquals(
            40.0,
            Chart3DSceneDepth.resolve(Chart3DSceneDepth.Auto, reference = 40.0),
            1e-12,
        )
        assertEquals(
            100.0,
            Chart3DSceneDepth.resolve(Chart3DSceneDepth.Relative(2.5), reference = 40.0),
            1e-12,
        )
        assertEquals(
            17.0,
            Chart3DSceneDepth.resolve(Chart3DSceneDepth.Absolute(17f), reference = 40.0, minimum = 1.0),
            1e-12,
        )
        assertEquals(
            "a column chart's floor is its own content",
            40.0,
            Chart3DSceneDepth.resolve(Chart3DSceneDepth.Absolute(17f), reference = 40.0),
            1e-12,
        )
    }

    // ---- colour by point ---------------------------------------------------

    /** §25: the palette walks the categories, and the series identity survives. */
    @Test
    fun `colour by point gives each category its own palette slot without losing the series`() {
        val bySeries = layout(colorByPoint = false)
        val byPoint = layout(colorByPoint = true)

        val revenueBySeries = bySeries.segments.filter { it.key.seriesId == "revenue" }
        assertEquals(
            "every column of one series shares its colour",
            setOf(0),
            revenueBySeries.map { it.paletteIndex }.toSet(),
        )
        val revenueByPoint = byPoint.segments.filter { it.key.seriesId == "revenue" }
        assertEquals(
            "one palette slot per category",
            listOf(0, 1, 2, 3),
            revenueByPoint.map { it.paletteIndex },
        )
        assertEquals(
            "and the series a segment belongs to is untouched",
            revenueBySeries.map { it.key },
            revenueByPoint.map { it.key },
        )
    }

    @Test
    fun `an explicit point colour wins over both the series colour and the palette`() {
        val withColours = Column3DLayoutEngine.layout(
            categories = categories,
            series = listOf(
                Column3DSeries(
                    seriesId = "revenue",
                    seriesName = "Revenue",
                    paletteIndex = 0,
                    colorOverride = 0xFF00FF00.toInt(),
                    stackId = "revenue",
                    values = listOf(40.0, 65.0, 30.0, 80.0),
                    sourceIndices = listOf(0, 1, 2, 3),
                    pointColors = listOf(0xFFFF0000.toInt(), null, 0xFF0000FF.toInt(), null),
                ),
            ),
            items = emptyMap(),
            grouping = BarGrouping.Grouped,
            arrangement = Column3DArrangement.Side,
            depth = Chart3DDepth.Auto,
            categoryCentres = List(categories.size) { (it + 0.5f) * 200f },
            bandWidth = 160f,
            valueFraction = { it / 100.0 },
            plotWidth = plot.width,
            plotHeight = plot.height,
            groupPadding = 0.1,
            depthGap = 0.25,
        )
        assertEquals(
            listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF00FF00.toInt()),
            withColours.segments.map { it.colorOverride },
        )
    }

    // ---- the Z axis in the registry ---------------------------------------

    private fun spec(id: String, dimension: AxisDimension, primary: Boolean = false) =
        ChartAxisSpec(id = ChartAxisId(id), dimension = dimension, primary = primary)

    /** §165: registering Z, looking it up, and not breaking X or Y. */
    @Test
    fun `a Z axis registers alongside X and Y without disturbing either`() {
        val registry = AxisRegistry.of(
            listOf(
                spec("x", AxisDimension.X),
                spec("y", AxisDimension.Y, primary = true),
                spec("z", AxisDimension.Z),
            ),
        )
        assertEquals(1, registry.xAxes.size)
        assertEquals(1, registry.yAxes.size)
        assertEquals(1, registry.zAxes.size)
        assertEquals(ChartAxisId("z"), registry.primaryZ?.id)
        assertEquals(ChartAxisId("y"), registry.primaryY?.id)
        assertNotNull(registry.find(ChartAxisId("z")))
    }

    @Test
    fun `a duplicate id is refused whichever dimension it is in`() {
        assertThrows(ChartAxisException::class.java) {
            AxisRegistry.of(
                listOf(
                    spec("shared", AxisDimension.Y),
                    spec("shared", AxisDimension.Z),
                ),
            )
        }
    }

    @Test
    fun `asking for the Z axis as a Y axis is a configuration error, not a fallback`() {
        val registry = AxisRegistry.of(
            listOf(spec("y", AxisDimension.Y), spec("depth", AxisDimension.Z)),
        )
        val failure = assertThrows(ChartAxisException::class.java) {
            registry.requireAxis(ChartAxisId("depth"), "scatter3d", AxisDimension.Y)
        }
        assertTrue(failure.message!!.contains("registered as a Z axis"))
    }

    @Test
    fun `a Z axis has no plot edge and never appears on one`() {
        val registry = AxisRegistry.of(
            listOf(
                spec("x", AxisDimension.X),
                spec("y", AxisDimension.Y),
                spec("z", AxisDimension.Z),
            ),
        )
        AxisPosition.entries.forEach { position ->
            assertTrue(
                "a Z axis must not be laid out at $position",
                registry.axesAt(position).none { it.dimension == AxisDimension.Z },
            )
        }
        assertTrue(AxisDimension.X.onPlotEdge)
        assertTrue(AxisDimension.Y.onPlotEdge)
        assertTrue(!AxisDimension.Z.onPlotEdge)
    }

    @Test
    fun `placing a Z axis on an edge says so rather than drawing it somewhere`() {
        val failure = assertThrows(ChartAxisException::class.java) {
            AxisRegistry.of(
                listOf(
                    ChartAxisSpec(
                        id = ChartAxisId("z"),
                        dimension = AxisDimension.Z,
                        position = AxisPosition.End,
                    ),
                ),
            )
        }
        assertTrue(failure.message!!.contains("depth axis"))
    }

    @Test
    fun `two primary Z axes are refused, exactly as two primary Y axes are`() {
        assertThrows(ChartAxisException::class.java) {
            AxisRegistry.of(
                listOf(
                    spec("z1", AxisDimension.Z, primary = true),
                    spec("z2", AxisDimension.Z, primary = true),
                ),
            )
        }
    }
}
