package io.devkit.chartkit

import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.three.Bounds3D
import io.devkit.chartkit.three.Chart3DCamera
import io.devkit.chartkit.three.Chart3DCameraLimits
import io.devkit.chartkit.three.Chart3DException
import io.devkit.chartkit.three.Chart3DFrame
import io.devkit.chartkit.three.Chart3DHitTest
import io.devkit.chartkit.three.Chart3DLighting
import io.devkit.chartkit.three.Chart3DObject
import io.devkit.chartkit.three.Chart3DProjection
import io.devkit.chartkit.three.Chart3DProjector
import io.devkit.chartkit.three.Chart3DScene
import io.devkit.chartkit.three.Chart3DSideWall
import io.devkit.chartkit.three.Cuboid3D
import io.devkit.chartkit.three.FaceSide
import io.devkit.chartkit.three.Matrix4
import io.devkit.chartkit.three.Point3D
import io.devkit.chartkit.three.Projected2D
import io.devkit.chartkit.three.Vector3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The 3D pipeline, one stage at a time.
 *
 * Every one of these runs on the JVM, because every one of them is arithmetic.
 * That is the whole reason the `three` package holds no Compose type: a face
 * wound the wrong way, a projection that grows with depth, a sort that puts the
 * back wall in front or a light fixed to the world instead of to the camera are
 * all silent on a device — the chart draws *something* — and all trivially
 * detectable here.
 */
class Chart3DGeometryTest {

    private val tolerance = 1e-9

    // ---- vectors ----------------------------------------------------------

    @Test
    fun `dot and cross follow the right-hand rule`() {
        assertEquals(0.0, Vector3D.UnitX dot Vector3D.UnitY, tolerance)
        assertEquals(32.0, Vector3D(1.0, 2.0, 3.0) dot Vector3D(4.0, 5.0, 6.0), tolerance)
        assertEquals(Vector3D.UnitZ, Vector3D.UnitX cross Vector3D.UnitY)
        assertEquals(Vector3D(0.0, 0.0, -1.0), Vector3D.UnitY cross Vector3D.UnitX)
    }

    @Test
    fun `normalising keeps direction and fixes length`() {
        val normalized = Vector3D(0.0, 3.0, 4.0).normalized()
        assertEquals(1.0, normalized.length, tolerance)
        assertEquals(0.6, normalized.y, tolerance)
        assertEquals(0.8, normalized.z, tolerance)
    }

    /** A degenerate face's normal must be zero, never `NaN`. */
    @Test
    fun `normalising a zero vector yields zero rather than NaN`() {
        val normalized = Vector3D.Zero.normalized()
        assertEquals(Vector3D.Zero, normalized)
        assertTrue(normalized.isFinite)
    }

    // ---- matrices ---------------------------------------------------------

    @Test
    fun `identity leaves a point alone`() {
        val point = Point3D(3.0, -4.0, 5.0)
        assertEquals(point, Matrix4.Identity.transformPoint(point))
    }

    @Test
    fun `translation moves points and leaves directions alone`() {
        val matrix = Matrix4.translation(10.0, 20.0, 30.0)
        assertEquals(Point3D(11.0, 22.0, 33.0), matrix.transformPoint(Point3D(1.0, 2.0, 3.0)))
        // The distinction the culling test depends on: a normal that had been
        // translated would point almost straight away from the camera in a
        // scene pushed back by the camera distance.
        assertEquals(Vector3D(1.0, 2.0, 3.0), matrix.transformVector(Vector3D(1.0, 2.0, 3.0)))
    }

    @Test
    fun `rotations turn the axes as the right-hand rule says`() {
        val x = Matrix4.rotationX(90.0).transformPoint(Point3D(0.0, 1.0, 0.0))
        assertEquals(0.0, x.y, 1e-12)
        assertEquals(1.0, x.z, 1e-12)

        val y = Matrix4.rotationY(90.0).transformPoint(Point3D(1.0, 0.0, 0.0))
        assertEquals(0.0, y.x, 1e-12)
        assertEquals(-1.0, y.z, 1e-12)

        val z = Matrix4.rotationZ(90.0).transformPoint(Point3D(1.0, 0.0, 0.0))
        assertEquals(0.0, z.x, 1e-12)
        assertEquals(1.0, z.y, 1e-12)
    }

    @Test
    fun `composition applies the right-hand matrix first`() {
        val moveThenTurn = Matrix4.rotationZ(90.0) * Matrix4.translation(1.0, 0.0, 0.0)
        val moved = moveThenTurn.transformPoint(Point3D.Origin)
        assertEquals(0.0, moved.x, 1e-12)
        assertEquals(1.0, moved.y, 1e-12)

        val turnThenMove = Matrix4.translation(1.0, 0.0, 0.0) * Matrix4.rotationZ(90.0)
        val other = turnThenMove.transformPoint(Point3D.Origin)
        assertEquals(1.0, other.x, 1e-12)
        assertEquals(0.0, other.y, 1e-12)
    }

    // ---- projection -------------------------------------------------------

    @Test
    fun `perspective leaves the centre alone and shrinks with depth`() {
        val projection = Chart3DProjection.Perspective()
        val distance = 100.0
        val centre = projection.project(Point3D(0.0, 0.0, distance), distance)!!
        assertEquals(0.0, centre.x, tolerance)

        val near = projection.project(Point3D(10.0, 0.0, distance / 2.0), distance)!!
        val far = projection.project(Point3D(10.0, 0.0, distance * 2.0), distance)!!
        assertEquals(20.0, near.x, 1e-9)
        assertEquals(5.0, far.x, 1e-9)
        assertTrue("A nearer point must project further from the axis", near.x > far.x)
    }

    @Test
    fun `perspective refuses points at or behind the near plane`() {
        val projection = Chart3DProjection.Perspective(nearPlane = 0.1)
        assertNull(projection.project(Point3D(1.0, 1.0, 5.0), 100.0))
        assertNull(projection.project(Point3D(1.0, 1.0, -20.0), 100.0))
        assertNotNull(projection.project(Point3D(1.0, 1.0, 50.0), 100.0))
    }

    @Test
    fun `orthographic ignores depth entirely`() {
        val projection = Chart3DProjection.Orthographic
        val near = projection.project(Point3D(10.0, 4.0, 20.0), 100.0)!!
        val far = projection.project(Point3D(10.0, 4.0, 900.0), 100.0)!!
        assertEquals(near, far)
    }

    // ---- cuboids ----------------------------------------------------------

    @Test
    fun `a cuboid has eight vertices and six faces`() {
        val box = Cuboid3D(x = 0.0, width = 2.0, yStart = 0.0, yEnd = 5.0, z = 0.0, depth = 3.0)
        assertEquals(8, box.vertices.size)
        assertEquals(6, box.faces.size)
        assertEquals(6, box.faces.map { it.side }.distinct().size)
    }

    /**
     * The winding, asserted side by side.
     *
     * A reversed order is not a crash; it is a face that disappears when looked
     * at and appears when looked away from.
     */
    @Test
    fun `every face normal points out of the box`() {
        val box = Cuboid3D(x = 0.0, width = 2.0, yStart = 0.0, yEnd = 5.0, z = 0.0, depth = 3.0)
        fun normalOf(side: FaceSide) = box.faces.first { it.side == side }.normal
        assertEquals(Vector3D(0.0, 0.0, -1.0), normalOf(FaceSide.Front))
        assertEquals(Vector3D(0.0, 0.0, 1.0), normalOf(FaceSide.Back))
        assertEquals(Vector3D(-1.0, 0.0, 0.0), normalOf(FaceSide.Left))
        assertEquals(Vector3D(1.0, 0.0, 0.0), normalOf(FaceSide.Right))
        assertEquals(Vector3D(0.0, 1.0, 0.0), normalOf(FaceSide.Top))
        assertEquals(Vector3D(0.0, -1.0, 0.0), normalOf(FaceSide.Bottom))
    }

    @Test
    fun `a negative column is normalised and still wound outwards`() {
        val box = Cuboid3D(x = 0.0, width = 2.0, yStart = 0.0, yEnd = -4.0, z = 0.0, depth = 3.0)
        assertTrue(box.isNegative)
        assertEquals(-4.0, box.bounds.minY, tolerance)
        assertEquals(0.0, box.bounds.maxY, tolerance)
        assertEquals(
            Vector3D(0.0, 1.0, 0.0),
            box.faces.first { it.side == FaceSide.Top }.normal,
        )
        assertEquals(
            Vector3D(0.0, 0.0, -1.0),
            box.faces.first { it.side == FaceSide.Front }.normal,
        )
    }

    @Test
    fun `a zero-height column is degenerate but not malformed`() {
        val box = Cuboid3D(x = 0.0, width = 2.0, yStart = 0.0, yEnd = 0.0, z = 0.0, depth = 3.0)
        assertTrue(box.isDegenerate)
        // Its top and bottom still have area, so they still have normals; only
        // the four zero-area sides collapse.
        assertEquals(
            Vector3D(0.0, 1.0, 0.0),
            box.faces.first { it.side == FaceSide.Top }.normal,
        )
        assertTrue(box.faces.first { it.side == FaceSide.Front }.normal.isZero)
    }

    // ---- culling ----------------------------------------------------------

    @Test
    fun `looking straight on shows the front and hides the other five`() {
        val visible = visibleSidesOf(Chart3DCamera(rotationX = 0.0, rotationY = 0.0))
        assertEquals(setOf(FaceSide.Front), visible)
    }

    @Test
    fun `pitching up reveals the tops and never the bottoms`() {
        val visible = visibleSidesOf(Chart3DCamera(rotationX = 25.0, rotationY = 0.0))
        assertTrue("The reader is above the scene, so tops show", FaceSide.Top in visible)
        assertTrue("and bottoms cannot", FaceSide.Bottom !in visible)
        assertTrue(FaceSide.Front in visible)
    }

    @Test
    fun `a positive yaw reveals the right side and hides the left`() {
        val visible = visibleSidesOf(Chart3DCamera(rotationX = 0.0, rotationY = 25.0))
        assertTrue(FaceSide.Right in visible)
        assertTrue(FaceSide.Left !in visible)
    }

    @Test
    fun `a negative yaw reveals the left side instead`() {
        val visible = visibleSidesOf(Chart3DCamera(rotationX = 0.0, rotationY = -25.0))
        assertTrue(FaceSide.Left in visible)
        assertTrue(FaceSide.Right !in visible)
    }

    @Test
    fun `no more than three faces of a box are ever visible at once`() {
        val visible = visibleSidesOf(Chart3DCamera(rotationX = 30.0, rotationY = 30.0))
        assertEquals(setOf(FaceSide.Front, FaceSide.Right, FaceSide.Top), visible)
    }

    // ---- depth sorting ----------------------------------------------------

    @Test
    fun `faces are drawn back to front`() {
        val near = Cuboid3D(0.0, 10.0, 0.0, 10.0, 0.0, 10.0)
        val far = Cuboid3D(0.0, 10.0, 0.0, 10.0, 40.0, 10.0)
        val scene = Chart3DScene(listOf(Chart3DObject(near), Chart3DObject(far)))
        val projector = projectorFor(scene, Chart3DCamera(rotationX = 20.0, rotationY = 20.0))
        val faces = projector.project(scene).faces
        assertTrue(faces.size >= 2)
        val depths = faces.map { it.depth }
        assertEquals(depths.sortedDescending(), depths)
    }

    @Test
    fun `equal depths keep a deterministic order`() {
        // Two boxes in exactly the same place: every face pair ties on depth,
        // so only the tie-breakers can decide, and they must decide the same
        // way every time or the seam between them flickers.
        val a = Cuboid3D(0.0, 10.0, 0.0, 10.0, 0.0, 10.0)
        val b = Cuboid3D(0.0, 10.0, 0.0, 10.0, 0.0, 10.0)
        val scene = Chart3DScene(listOf(Chart3DObject(a), Chart3DObject(b)))
        val camera = Chart3DCamera(rotationX = 18.0, rotationY = 18.0)
        val first = projectorFor(scene, camera).project(scene).faces
        val second = projectorFor(scene, camera).project(scene).faces
        assertEquals(
            first.map { it.objectIndex to it.faceIndex },
            second.map { it.objectIndex to it.faceIndex },
        )
        // And the tie is broken by object order, so the second box wins the top.
        val ties = first.filter { it.side == FaceSide.Front }
        assertEquals(listOf(0, 1), ties.map { it.objectIndex })
    }

    // ---- lighting ---------------------------------------------------------

    @Test
    fun `a face square to the light is brightest and one turned away is ambient`() {
        val lighting = Chart3DLighting(
            ambient = 0.5,
            diffuse = 0.5,
            direction = Vector3D(0.0, 0.0, 1.0),
        )
        assertEquals(1.0, lighting.brightnessOf(Vector3D(0.0, 0.0, -1.0)), tolerance)
        assertEquals(0.5, lighting.brightnessOf(Vector3D(0.0, 0.0, 1.0)), tolerance)
        assertEquals(0.5, lighting.brightnessOf(Vector3D(1.0, 0.0, 0.0)), tolerance)
    }

    @Test
    fun `flat lighting shades nothing`() {
        listOf(Vector3D.UnitX, Vector3D.UnitY, Vector3D(0.0, 0.0, -1.0)).forEach {
            assertEquals(1.0, Chart3DLighting.Flat.brightnessOf(it), tolerance)
        }
    }

    @Test
    fun `the default light makes the top of a column the brightest face`() {
        val box = Cuboid3D(0.0, 10.0, 0.0, 30.0, 0.0, 10.0)
        val scene = Chart3DScene(listOf(Chart3DObject(box)))
        val projector = projectorFor(scene, Chart3DCamera.Default)
        val faces = projector.project(scene).faces.associateBy { it.side }
        val top = faces.getValue(FaceSide.Top).brightness
        val front = faces.getValue(FaceSide.Front).brightness
        val right = faces.getValue(FaceSide.Right).brightness
        assertTrue("top $top should be brighter than front $front", top > front)
        assertTrue("front $front should be brighter than the shaded side $right", front > right)
    }

    // ---- fit --------------------------------------------------------------

    @Test
    fun `a fitted scene stays inside the plot`() {
        val box = Cuboid3D(0.0, 300.0, 0.0, 200.0, 0.0, 60.0)
        val scene = Chart3DScene(listOf(Chart3DObject(box)))
        val plot = ChartRect(0f, 0f, 400f, 300f)
        val projector = Chart3DProjector.of(
            scene,
            Chart3DCamera(rotationX = 20.0, rotationY = 25.0),
            Chart3DProjection.Perspective(),
            plot,
        )!!
        box.vertices.forEach { vertex ->
            val screen = projector.toScreen(vertex)!!
            assertTrue("x ${screen.x} outside plot", screen.x >= -0.5 && screen.x <= 400.5)
            assertTrue("y ${screen.y} outside plot", screen.y >= -0.5 && screen.y <= 300.5)
        }
    }

    @Test
    fun `world y grows upward and screen y grows downward`() {
        val box = Cuboid3D(0.0, 100.0, 0.0, 100.0, 0.0, 10.0)
        val scene = Chart3DScene(listOf(Chart3DObject(box)))
        val projector = projectorFor(scene, Chart3DCamera(rotationX = 0.0, rotationY = 0.0))
        val low = projector.toScreen(Point3D(50.0, 0.0, 0.0))!!
        val high = projector.toScreen(Point3D(50.0, 100.0, 0.0))!!
        assertTrue("a higher value must be drawn nearer the top", high.y < low.y)
    }

    // ---- hit testing ------------------------------------------------------

    @Test
    fun `a convex polygon contains its own centre and not a point outside`() {
        val square = listOf(
            Projected2D(0.0, 0.0),
            Projected2D(10.0, 0.0),
            Projected2D(10.0, 10.0),
            Projected2D(0.0, 10.0),
        )
        assertTrue(Chart3DHitTest.contains(square, 5.0, 5.0))
        assertTrue("a point on an edge belongs to the face", Chart3DHitTest.contains(square, 0.0, 5.0))
        assertTrue(!Chart3DHitTest.contains(square, 11.0, 5.0))
        assertTrue(!Chart3DHitTest.contains(square, -0.5, 5.0))
    }

    @Test
    fun `winding does not change what a polygon contains`() {
        val clockwise = listOf(
            Projected2D(0.0, 0.0),
            Projected2D(0.0, 10.0),
            Projected2D(10.0, 10.0),
            Projected2D(10.0, 0.0),
        )
        assertTrue(Chart3DHitTest.contains(clockwise, 5.0, 5.0))
    }

    // ---- camera -----------------------------------------------------------

    @Test
    fun `rotation moves projected positions deterministically`() {
        val box = Cuboid3D(0.0, 100.0, 0.0, 100.0, 0.0, 50.0)
        val scene = Chart3DScene(listOf(Chart3DObject(box)))
        val straight = projectorFor(scene, Chart3DCamera(rotationX = 0.0, rotationY = 0.0))
        val turned = projectorFor(scene, Chart3DCamera(rotationX = 0.0, rotationY = 30.0))
        val corner = Point3D(0.0, 0.0, 0.0)
        assertTrue(
            "turning the scene must move its corners",
            abs(straight.toScreen(corner)!!.x - turned.toScreen(corner)!!.x) > 1.0,
        )
        // And repeating the same camera must reproduce the same pixel exactly.
        val again = projectorFor(scene, Chart3DCamera(rotationX = 0.0, rotationY = 30.0))
        assertEquals(turned.toScreen(corner), again.toScreen(corner))
    }

    @Test
    fun `limits hold the camera the right way up`() {
        val limits = Chart3DCameraLimits(rotationX = 0.0..60.0, rotationY = -45.0..45.0)
        val camera = Chart3DCamera(rotationX = 140.0, rotationY = -200.0).coerceIn(limits)
        assertEquals(60.0, camera.rotationX, tolerance)
        assertEquals(-45.0, camera.rotationY, tolerance)
    }

    @Test
    fun `an invalid camera is rejected rather than drawn`() {
        assertThrows { Chart3DCamera(rotationX = Double.NaN) }
        assertThrows { Chart3DCamera(distance = 0.0) }
        assertThrows { Chart3DCamera(distance = Double.POSITIVE_INFINITY) }
    }

    @Test
    fun `camera distance scales with the scene so one camera fits every size`() {
        val small = Chart3DScene(listOf(Chart3DObject(Cuboid3D(0.0, 100.0, 0.0, 100.0, 0.0, 20.0))))
        val large = Chart3DScene(listOf(Chart3DObject(Cuboid3D(0.0, 400.0, 0.0, 400.0, 0.0, 80.0))))
        val camera = Chart3DCamera(rotationX = 20.0, rotationY = 20.0)
        val plot = ChartRect(0f, 0f, 400f, 400f)
        val a = Chart3DProjector.of(small, camera, Chart3DProjection.Perspective(), plot)!!
        val b = Chart3DProjector.of(large, camera, Chart3DProjection.Perspective(), plot)!!
        // Four times the scene at four times the distance projects to the same
        // picture, which is what makes a camera a property of the view rather
        // than of the chart's size in pixels.
        val near = a.toScreen(Point3D(0.0, 0.0, 0.0))!!
        val far = b.toScreen(Point3D(0.0, 0.0, 0.0))!!
        assertEquals(near.x, far.x, 1e-6)
        assertEquals(near.y, far.y, 1e-6)
    }

    // ---- frame ------------------------------------------------------------

    @Test
    fun `the frame puts its side wall behind the data`() {
        val frame = Chart3DFrame.Auto
        assertEquals(
            Chart3DSideWall.Left,
            frame.resolveSide(Chart3DCamera(rotationY = 20.0)),
        )
        assertEquals(
            Chart3DSideWall.Right,
            frame.resolveSide(Chart3DCamera(rotationY = -20.0)),
        )
    }

    @Test
    fun `frame panels are flat and are not selectable`() {
        val volume = Bounds3D(0.0, 300.0, 0.0, 200.0, 0.0, 60.0)
        val panels = Chart3DFrame.Auto.panelsFor(volume, Chart3DCamera.Default)
        assertEquals(3, panels.size)
        panels.forEach {
            assertTrue("a frame panel is scenery", !it.selectable)
            assertNull("and stands for no data", it.key)
            val b = it.geometry.bounds
            val flat = b.width == 0.0 || b.height == 0.0 || b.depth == 0.0
            assertTrue("a frame panel is a plane", flat)
        }
    }

    @Test
    fun `an empty frame draws nothing at all`() {
        val volume = Bounds3D(0.0, 300.0, 0.0, 200.0, 0.0, 60.0)
        assertTrue(Chart3DFrame.None.panelsFor(volume, Chart3DCamera.Default).isEmpty())
        assertTrue(Chart3DFrame.None.gridSegments(volume, listOf(0.0, 0.5, 1.0)).isEmpty())
    }

    @Test
    fun `grid lines sit at the value fractions they were given`() {
        val volume = Bounds3D(0.0, 300.0, 0.0, 200.0, 0.0, 60.0)
        val lines = Chart3DFrame.Auto.gridSegments(volume, listOf(0.0, 0.5, 1.0))
        assertEquals(3, lines.size)
        assertEquals(listOf(0.0, 100.0, 200.0), lines.map { it.first.y })
        assertTrue("the value grid belongs on the back wall", lines.all { it.first.z == 60.0 })
    }

    // ---- degenerate cases -------------------------------------------------

    @Test
    fun `an edge-on scene produces no NaN`() {
        val box = Cuboid3D(0.0, 100.0, 0.0, 100.0, 0.0, 40.0)
        val scene = Chart3DScene(listOf(Chart3DObject(box)))
        listOf(-90.0, -89.9, 0.0, 89.9, 90.0).forEach { angle ->
            val projector = Chart3DProjector.of(
                scene,
                Chart3DCamera(rotationX = angle, rotationY = angle, distance = 3.0),
                Chart3DProjection.Perspective(),
                ChartRect(0f, 0f, 300f, 300f),
            )
            val faces = projector?.project(scene)?.faces.orEmpty()
            faces.forEach { face ->
                face.points.forEach { point ->
                    assertTrue("projected $point is not finite", point.isFinite)
                }
                assertTrue(face.depth.isFinite())
                assertTrue(face.brightness.isFinite())
            }
        }
    }

    @Test
    fun `an empty scene fits to nothing rather than dividing by it`() {
        assertNull(
            Chart3DProjector.of(
                Chart3DScene(emptyList()),
                Chart3DCamera.Default,
                Chart3DProjection.Perspective(),
                ChartRect(0f, 0f, 100f, 100f),
            ),
        )
    }

    @Test
    fun `a plot with no area produces no projector`() {
        val scene = Chart3DScene(listOf(Chart3DObject(Cuboid3D(0.0, 1.0, 0.0, 1.0, 0.0, 1.0))))
        assertNull(
            Chart3DProjector.of(
                scene,
                Chart3DCamera.Default,
                Chart3DProjection.Perspective(),
                ChartRect(0f, 0f, 0f, 0f),
            ),
        )
    }

    @Test
    fun `diagnostics account for every face`() {
        val box = Cuboid3D(0.0, 100.0, 0.0, 100.0, 0.0, 40.0)
        val scene = Chart3DScene(listOf(Chart3DObject(box)))
        val result = projectorFor(scene, Chart3DCamera.Default).project(scene)
        val d = result.diagnostics
        assertEquals(1, d.objectCount)
        assertEquals(6, d.faceCount)
        assertEquals(d.faceCount, d.renderedFaces + d.culledFaces + d.degenerateFaces + d.clippedFaces)
        assertEquals(result.faces.size, d.renderedFaces)
    }

    // ---- helpers ----------------------------------------------------------

    private fun projectorFor(scene: Chart3DScene, camera: Chart3DCamera): Chart3DProjector =
        Chart3DProjector.of(
            scene = scene,
            camera = camera,
            projection = Chart3DProjection.Perspective(),
            bounds = ChartRect(0f, 0f, 400f, 400f),
        )!!

    private fun visibleSidesOf(camera: Chart3DCamera): Set<FaceSide> {
        val box = Cuboid3D(0.0, 100.0, 0.0, 100.0, 0.0, 100.0)
        val scene = Chart3DScene(listOf(Chart3DObject(box)))
        return projectorFor(scene, camera).project(scene).faces.map { it.side }.toSet()
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (expected: Chart3DException) {
            return
        }
        throw AssertionError("Expected a Chart3DException")
    }
}
