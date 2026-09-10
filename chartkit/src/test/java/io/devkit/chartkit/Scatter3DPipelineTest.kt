package io.devkit.chartkit

import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.layer.three.MarkIndex
import io.devkit.chartkit.scale.LinearScale
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.three.Cartesian3DCoordinates
import io.devkit.chartkit.three.Chart3DCamera
import io.devkit.chartkit.three.Chart3DFrame
import io.devkit.chartkit.three.Chart3DGridPlanes
import io.devkit.chartkit.three.Chart3DHitTest
import io.devkit.chartkit.three.Chart3DKey
import io.devkit.chartkit.three.Chart3DLighting
import io.devkit.chartkit.three.Chart3DMark
import io.devkit.chartkit.three.Chart3DPlane
import io.devkit.chartkit.three.Chart3DPlotBox
import io.devkit.chartkit.three.Chart3DProjection
import io.devkit.chartkit.three.Chart3DProjector
import io.devkit.chartkit.three.Chart3DScene
import io.devkit.chartkit.three.Marker3D
import io.devkit.chartkit.three.Marker3DShading
import io.devkit.chartkit.three.Point3D
import io.devkit.chartkit.three.ProjectedFace
import io.devkit.chartkit.three.ProjectedMark
import io.devkit.chartkit.three.Vector3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The 3D scatter's own pipeline: three scales → world → camera → projection →
 * one depth order over faces *and* point marks → the thing a finger lands on.
 *
 * The tests that matter here are the ones about *order*. A marker drawn in the
 * wrong place is obvious; a marker drawn in the right place but in front of a
 * wall it is behind looks completely correct until the reader rotates the
 * chart, and by then they have no way to tell a depth bug from a data one.
 */
class Scatter3DPipelineTest {

    private val plot = ChartRect(0f, 0f, 800f, 600f)
    private val box = Chart3DPlotBox(width = 800.0, height = 600.0, depth = 600.0)

    private fun scale(min: Double, max: Double) =
        LinearScale(NumericDomain(min, max), rangeStart = 0f, rangeEnd = 1f)

    private val coordinates = Cartesian3DCoordinates(
        xScale = scale(0.0, 100.0),
        yScale = scale(0.0, 100.0),
        zScale = scale(0.0, 100.0),
        box = box,
    )

    private fun key(index: Int) = Chart3DKey(
        seriesId = "observations",
        categoryIndex = index,
        category = "",
        stackId = "observations",
        pointIndex = index,
    )

    private fun mark(x: Double, y: Double, z: Double, index: Int, radius: Double = 8.0) =
        Chart3DMark(
            position = coordinates.worldOf(x, y, z)!!,
            radius = radius,
            marker = Marker3D.Sphere,
            key = key(index),
        )

    private fun scene(
        marks: List<Chart3DMark>,
        frame: Chart3DFrame = Chart3DFrame.Auto,
        camera: Chart3DCamera = Chart3DCamera.Isometric,
    ) = Chart3DScene(
        objects = frame.panelsFor(coordinates.volume, camera),
        lighting = Chart3DLighting.Default,
        marks = marks,
    )

    private fun projector(
        scene: Chart3DScene,
        camera: Chart3DCamera = Chart3DCamera.Isometric,
        projection: Chart3DProjection = Chart3DProjection.Default,
    ): Chart3DProjector = Chart3DProjector.of(scene, camera, projection, plot)!!

    // ---- projection -------------------------------------------------------

    /** §168: a known point maps to a known world position and a stable screen one. */
    @Test
    fun `a known observation maps to a known world point and projects finitely`() {
        val world = coordinates.worldOf(25.0, 50.0, 75.0)!!
        assertEquals(200.0, world.x, 1e-9)
        assertEquals(300.0, world.y, 1e-9)
        assertEquals(450.0, world.z, 1e-9)

        val marks = listOf(mark(25.0, 50.0, 75.0, 0))
        val scene = scene(marks)
        val projector = projector(scene)
        val screen = projector.toScreen(world)
        assertNotNull(screen)
        assertTrue(screen!!.isFinite)
        val result = projector.project(scene)
        assertEquals(1, result.marks.size)
        assertEquals(screen, result.marks.single().center)
    }

    /**
     * §169: under perspective a nearer observation is drawn larger.
     *
     * The marker's radius is stated in screen pixels at the scene's centre, so
     * the assertion is not that some number changed — it is that the near one
     * exceeds the stated size and the far one falls short of it, in the ratio
     * the projection itself applies to the geometry around them.
     */
    @Test
    fun `perspective draws a near observation larger than an equal far one`() {
        val near = mark(50.0, 50.0, 0.0, 0)
        val far = mark(50.0, 50.0, 100.0, 1)
        val scene = scene(listOf(near, far))
        val result = projector(scene).project(scene)
        val drawnNear = result.marks.single { it.key == key(0) }
        val drawnFar = result.marks.single { it.key == key(1) }
        assertTrue(
            "near ${drawnNear.radius} should exceed far ${drawnFar.radius}",
            drawnNear.radius > drawnFar.radius,
        )
        assertTrue("near mark is closer to the camera", drawnNear.depth < drawnFar.depth)
    }

    /** §170: under a parallel projection depth changes position and never size. */
    @Test
    fun `orthographic draws two equal observations at equal size whatever their depth`() {
        val near = mark(50.0, 50.0, 0.0, 0)
        val far = mark(50.0, 50.0, 100.0, 1)
        val scene = scene(listOf(near, far))
        val result = projector(scene, projection = Chart3DProjection.Orthographic).project(scene)
        val drawnNear = result.marks.single { it.key == key(0) }
        val drawnFar = result.marks.single { it.key == key(1) }
        assertEquals(drawnNear.radius, drawnFar.radius, 1e-9)
        assertEquals(8.0, drawnNear.radius, 1e-9)
        assertTrue("depth ordering survives the parallel projection", drawnNear.depth < drawnFar.depth)
    }

    // ---- ordering ---------------------------------------------------------

    /** §171: overlapping markers are drawn rear first. */
    @Test
    fun `overlapping observations are drawn back to front`() {
        val marks = listOf(mark(50.0, 50.0, 100.0, 0), mark(50.0, 50.0, 0.0, 1))
        val scene = scene(marks)
        val drawn = projector(scene).project(scene).marks
        assertEquals(2, drawn.size)
        assertTrue(
            "the rear observation must be painted first",
            drawn.first().depth > drawn.last().depth,
        )
        assertEquals(key(0), drawn.first().key)
        assertEquals(key(1), drawn.last().key)
    }

    /**
     * The property two separate draw passes cannot have: a marker behind the
     * back wall is painted before it, and one in front of it after.
     */
    @Test
    fun `marks and frame walls interleave in one depth order`() {
        val inFront = mark(50.0, 50.0, 0.0, 0)
        val scene = scene(listOf(inFront))
        val items = projector(scene).project(scene).items
        val markPosition = items.indexOfFirst { it is ProjectedMark }
        val backWall = items.indexOfFirst { it is ProjectedFace && it.key == null }
        assertTrue("the scene drew a frame face", backWall >= 0)
        assertTrue("the scene drew the mark", markPosition >= 0)
        assertTrue(
            "an observation at the near face must be painted after the frame behind it",
            markPosition > backWall,
        )
    }

    @Test
    fun `the merged order is exactly the two sorted lists, still descending in depth`() {
        val marks = (0..9).map { mark(it * 10.0, 50.0, it * 10.0, it) }
        val scene = scene(marks)
        val items = projector(scene).project(scene).items
        val depths = items.map { it.depth }
        assertEquals(
            "the merged list must stay in descending depth",
            depths.sortedDescending(),
            depths,
        )
    }

    // ---- hit testing ------------------------------------------------------

    /** §172: the front-most projected point wins a tap. */
    @Test
    fun `a tap on two overlapping observations selects the nearer one`() {
        val marks = listOf(mark(50.0, 50.0, 100.0, 0), mark(50.0, 50.0, 0.0, 1))
        val scene = scene(marks)
        val result = projector(scene).project(scene)
        val front = result.marks.last()
        val hit = Chart3DHitTest.markAt(result.marks, front.center.x, front.center.y)
        assertNotNull(hit)
        assertEquals("the visible observation must win", key(1), hit!!.key)
    }

    /**
     * §112: a marker completely covered by another does not win the tap.
     *
     * Both observations are put on the camera's own axis — the centre of the
     * volume in x and y, with the camera square on — so the two really do
     * project to the same pixel and the rear one really is invisible. Off the
     * axis they would merely overlap, which is the weaker case the test above
     * already covers.
     */
    @Test
    fun `a fully hidden rear observation never wins a tap on the one covering it`() {
        val camera = Chart3DCamera(rotationX = 0.0, rotationY = 0.0)
        val marks = listOf(
            mark(50.0, 50.0, 100.0, 0, radius = 4.0),
            mark(50.0, 50.0, 0.0, 1, radius = 20.0),
        )
        val scene = scene(marks, camera = camera)
        val result = projector(scene, camera = camera).project(scene)
        val rear = result.marks.single { it.key == key(0) }
        val front = result.marks.single { it.key == key(1) }
        assertEquals("the two observations must land on the same pixel", front.center, rear.center)
        assertTrue("the rear one must be entirely inside the front one", front.radius > rear.radius)
        val hit = Chart3DHitTest.markAt(result.marks, rear.center.x, rear.center.y)
        assertEquals(key(1), hit?.key)
    }

    /** §111: a tap near a marker still selects it. */
    @Test
    fun `a tap a little off a marker still selects it, and a tap far away does not`() {
        val marks = listOf(mark(50.0, 50.0, 50.0, 0, radius = 4.0))
        val scene = scene(marks)
        val result = projector(scene).project(scene)
        val drawn = result.marks.single()
        val near = Chart3DHitTest.markAt(
            result.marks,
            drawn.center.x + drawn.radius + 6.0,
            drawn.center.y,
            slop = 12.0,
        )
        assertNotNull("a tap within the slop must select", near)
        val far = Chart3DHitTest.markAt(
            result.marks,
            drawn.center.x + drawn.radius + 60.0,
            drawn.center.y,
            slop = 12.0,
        )
        assertNull("a tap well away from every marker selects nothing", far)
    }

    /** §173: rotating the scene moves where a point is, and the test follows it. */
    @Test
    fun `rotating the camera moves an observation and the hit test follows`() {
        val marks = listOf(mark(10.0, 50.0, 90.0, 0))
        val scene = scene(marks)
        val before = projector(scene, camera = Chart3DCamera(rotationX = 20.0, rotationY = -30.0))
        val after = projector(scene, camera = Chart3DCamera(rotationX = 20.0, rotationY = 30.0))
        val projectedBefore = before.project(scene).marks.single()
        val projectedAfter = after.project(scene).marks.single()
        assertTrue(
            "a 60 degree turn must move the observation on screen",
            abs(projectedBefore.center.x - projectedAfter.center.x) > 1.0,
        )
        // The stale-geometry failure: testing the new tap against the old
        // projection. Each is only correct against its own.
        assertEquals(
            key(0),
            Chart3DHitTest.markAt(
                after.project(scene).marks,
                projectedAfter.center.x,
                projectedAfter.center.y,
            )?.key,
        )
        // And the failure a stale projection produces: the observation is no
        // longer where the old projection says it is, so a tap aimed at its new
        // position finds nothing in the old one.
        val moved = abs(projectedBefore.center.x - projectedAfter.center.x)
        assertTrue("the turn moved it further than its own radius", moved > projectedBefore.radius)
        assertNull(
            "the old projection has nothing where the observation now is",
            Chart3DHitTest.markAt(
                before.project(scene).marks,
                projectedAfter.center.x,
                projectedAfter.center.y,
            ),
        )
    }

    /** §68: the depth a hit test resolves by is the depth the renderer drew by. */
    @Test
    fun `render order and hit test frontness come from the same depth`() {
        val marks = (0..5).map { mark(50.0, 50.0, it * 20.0, it) }
        val scene = scene(marks)
        val result = projector(scene).project(scene)
        val drawnLast = result.marks.last()
        val hit = Chart3DHitTest.markAt(result.marks, drawnLast.center.x, drawnLast.center.y)
        assertEquals(
            "the last mark painted is the one a tap on that pixel resolves to",
            drawnLast.key,
            hit?.key,
        )
    }

    // ---- markers ----------------------------------------------------------

    /**
     * §175: a billboard faces the camera by construction.
     *
     * There is nothing to assert about its *orientation* — it has none — so the
     * property that matters is the one that makes it a billboard at all: its
     * centre is the projection of its world position, at every camera angle.
     */
    @Test
    fun `a billboard marker is centred on its projected world position at every angle`() {
        val position = coordinates.worldOf(30.0, 70.0, 20.0)!!
        val marks = listOf(
            Chart3DMark(position, radius = 6.0, marker = Marker3D.BillboardCircle, key = key(0)),
        )
        val scene = scene(marks)
        listOf(
            Chart3DCamera(rotationX = 0.0, rotationY = 0.0),
            Chart3DCamera(rotationX = 35.0, rotationY = 45.0),
            Chart3DCamera(rotationX = 70.0, rotationY = -80.0),
        ).forEach { camera ->
            val projector = projector(scene, camera = camera)
            val drawn = projector.project(scene).marks.single()
            assertEquals(projector.toScreen(position), drawn.center)
        }
    }

    /** §174: a sphere's shading is deterministic and comes from the scene's light. */
    @Test
    fun `sphere shading is the lighting model's own answer at two known normals`() {
        val lighting = Chart3DLighting.Default
        val shading = Marker3DShading.of(lighting)
        assertEquals(
            lighting.brightnessOf(-lighting.direction.normalized()),
            shading.highlight,
            1e-12,
        )
        assertEquals(
            lighting.brightnessOf(lighting.direction.normalized()),
            shading.terminator,
            1e-12,
        )
        assertTrue("a lit point is brighter than a shadowed one", shading.highlight > shading.terminator)
        // The default light travels down and to the right, so it *comes from*
        // above and to the left — and screen y grows downward.
        assertTrue("the highlight is to the left of centre", shading.offsetX < 0.0)
        assertTrue("the highlight is above centre", shading.offsetY < 0.0)
    }

    @Test
    fun `a light straight down the camera axis puts the highlight in the middle`() {
        val shading = Marker3DShading.of(
            Chart3DLighting(direction = Vector3D(0.0, 0.0, 1.0)),
        )
        assertEquals(0.0, shading.offsetX, 1e-12)
        assertEquals(0.0, shading.offsetY, 1e-12)
    }

    @Test
    fun `a marker with no radius is neither drawn nor selectable`() {
        val marks = listOf(mark(50.0, 50.0, 50.0, 0, radius = 0.0))
        val scene = scene(marks)
        val result = projector(scene).project(scene)
        assertTrue(result.marks.isEmpty())
        assertEquals(1, result.diagnostics.markCount)
        assertEquals(0, result.diagnostics.renderedMarks)
    }

    // ---- grid -------------------------------------------------------------

    @Test
    fun `the default grid draws the back wall only`() {
        val lines = Chart3DFrame.Auto.gridLines(
            volume = coordinates.volume,
            camera = Chart3DCamera.Isometric,
            planes = Chart3DGridPlanes.Primary,
            xFractions = listOf(0.0, 0.5, 1.0),
            yFractions = listOf(0.0, 0.5, 1.0),
            zFractions = listOf(0.0, 0.5, 1.0),
        )
        assertTrue(lines.isNotEmpty())
        assertTrue("only the back plane carries lines", lines.all { it.plane == Chart3DPlane.Back })
    }

    @Test
    fun `every grid line sits on the plane it names, at a tick the axis produced`() {
        val volume = coordinates.volume
        val lines = Chart3DFrame.Auto.gridLines(
            volume = volume,
            camera = Chart3DCamera(rotationX = 25.0, rotationY = 25.0),
            planes = Chart3DGridPlanes.All,
            xFractions = listOf(0.25),
            yFractions = listOf(0.5),
            zFractions = listOf(0.75),
        )
        lines.forEach { line ->
            when (line.plane) {
                Chart3DPlane.Back -> {
                    assertEquals(volume.maxZ, line.from.z, 1e-9)
                    assertEquals(volume.maxZ, line.to.z, 1e-9)
                }
                Chart3DPlane.Floor -> {
                    assertEquals(volume.minY, line.from.y, 1e-9)
                    assertEquals(volume.minY, line.to.y, 1e-9)
                }
                // Positive yaw brings the right side forward, so the far wall
                // — the only one that can be drawn without standing in front
                // of the data — is the left one.
                Chart3DPlane.Side -> {
                    assertEquals(volume.minX, line.from.x, 1e-9)
                    assertEquals(volume.minX, line.to.x, 1e-9)
                }
            }
        }
        val backY = lines.filter { it.plane == Chart3DPlane.Back && it.from.y == it.to.y }
        assertEquals(1, backY.size)
        assertEquals(volume.minY + volume.height * 0.5, backY.single().from.y, 1e-9)
    }

    @Test
    fun `no grid means no lines, whatever the ticks say`() {
        assertTrue(
            Chart3DFrame.Auto.gridLines(
                volume = coordinates.volume,
                camera = Chart3DCamera.Isometric,
                planes = Chart3DGridPlanes.None,
                xFractions = listOf(0.0, 0.5, 1.0),
                yFractions = listOf(0.0, 0.5, 1.0),
                zFractions = listOf(0.0, 0.5, 1.0),
            ).isEmpty(),
        )
    }

    // ---- spatial index ----------------------------------------------------

    /**
     * §109 and §110 in one test: the index must answer exactly what a full scan
     * answers, and it must be derived from the projection it is used with.
     *
     * The second half is structural rather than assertable — an index lives
     * inside the object holding its own projection, so there is no way to
     * consult a stale one — and what is checked here is the first half, on a
     * cloud dense enough that the index actually partitions.
     */
    @Test
    fun `the spatial index answers exactly what a full scan answers`() {
        val marks = (0 until 900).map { index ->
            mark(
                x = (index % 30) * 3.4,
                y = ((index / 30) % 30) * 3.4,
                z = (index % 17) * 5.8,
                index = index,
                radius = 5.0,
            )
        }
        val scene = scene(marks)
        val projected = projector(scene).project(scene).marks
        val index = MarkIndex.of(projected)!!
        var probes = 0
        projected.forEachIndexed { order, drawn ->
            if (order % 7 != 0) return@forEachIndexed
            probes++
            val scan = scanFrontMost(projected, drawn.center.x, drawn.center.y, slop = 6.0)
            val fromIndex = index.candidates(drawn.center.x, drawn.center.y, slop = 6.0)
                .filter { projected[it].contains(drawn.center.x, drawn.center.y, 6.0) }
                .maxOrNull()
                ?.let { projected[it] }
            assertEquals(
                "index and scan disagree at mark $order",
                scan?.key,
                fromIndex?.key,
            )
        }
        assertTrue("the test probed the cloud", probes > 100)
    }

    private fun scanFrontMost(
        marks: List<ProjectedMark>,
        x: Double,
        y: Double,
        slop: Double,
    ): ProjectedMark? = Chart3DHitTest.markAt(marks, x, y, slop)
}
