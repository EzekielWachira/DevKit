package io.devkit.chartkit

import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.geometry.PolarDirection
import io.devkit.chartkit.geometry.computePolarSlices
import io.devkit.chartkit.three.Chart3DCamera
import io.devkit.chartkit.three.Chart3DDepth
import io.devkit.chartkit.three.Chart3DHitTest
import io.devkit.chartkit.three.Chart3DKey
import io.devkit.chartkit.three.Chart3DLighting
import io.devkit.chartkit.three.Chart3DObject
import io.devkit.chartkit.three.Chart3DProjection
import io.devkit.chartkit.three.Chart3DProjector
import io.devkit.chartkit.three.Chart3DQuality
import io.devkit.chartkit.three.Chart3DReserve
import io.devkit.chartkit.three.Chart3DScene
import io.devkit.chartkit.three.FaceSide
import io.devkit.chartkit.three.Point3D
import io.devkit.chartkit.three.ProjectedFace
import io.devkit.chartkit.three.Radial3DLayout
import io.devkit.chartkit.three.Radial3DLayoutEngine
import io.devkit.chartkit.three.RadialSector3D
import io.devkit.chartkit.three.Sector3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pie all the way through the shared 3D pipeline: values → slices → sectors →
 * camera → projection → culling → sort → the thing a finger lands on.
 *
 * These are the tests that would fail if the radial geometry had been given its
 * own camera, its own projection, its own culling or its own sort — because
 * every one of them uses the *column* chart's. Nothing in this file is a radial
 * variant of anything.
 */
class Radial3DPipelineTest {

    private val values = listOf(4823.0, 3112.0, 1841.0, 980.0, 700.0)
    private val labels = listOf("Chrome", "Safari", "Edge", "Firefox", "Other")
    private val plot = ChartRect(0f, 0f, 720f, 620f)
    private val reserve = Chart3DReserve(8f, 8f, 8f, 8f)

    // ---- projection -------------------------------------------------------

    @Test
    fun `perspective shrinks a far point and orthographic does not`() {
        val nearPoint = Point3D(0.0, 0.0, 4.0)
        val farPoint = Point3D(0.0, 0.0, 8.0)
        val edgeNear = Point3D(1.0, 0.0, 4.0)
        val edgeFar = Point3D(1.0, 0.0, 8.0)

        val perspective = Chart3DProjection.Perspective()
        val nearWidth = perspective.project(edgeNear, 4.0)!!.x - perspective.project(nearPoint, 4.0)!!.x
        val farWidth = perspective.project(edgeFar, 4.0)!!.x - perspective.project(farPoint, 4.0)!!.x
        assertTrue("a far span must project narrower: $nearWidth vs $farWidth", farWidth < nearWidth)

        val ortho = Chart3DProjection.Orthographic
        assertEquals(
            ortho.project(edgeNear, 4.0)!!.x - ortho.project(nearPoint, 4.0)!!.x,
            ortho.project(edgeFar, 4.0)!!.x - ortho.project(farPoint, 4.0)!!.x,
            1e-12,
        )
    }

    @Test
    fun `a tilted pie is drawn as an ellipse wider than it is tall`() {
        val rendered = render(camera = Chart3DCamera(rotationX = 45.0, rotationY = 0.0))
        val points = rendered.faces.flatMap { it.points }
        val width = points.maxOf { it.x } - points.minOf { it.x }
        val height = points.maxOf { it.y } - points.minOf { it.y }
        assertTrue("pitch foreshortens the vertical: ${width}x$height", width > height)
    }

    @Test
    fun `looking straight down draws a circle, and a shallow pitch flattens it`() {
        // The disc lies flat, so pitch means what it means for a table: 90°
        // looks straight down and the circle is round, and a shallow angle
        // squashes it. An upright disc would behave the other way round, and
        // that is the difference between a plate and the underside of one.
        val overhead = render(camera = Chart3DCamera(rotationX = 89.0, rotationY = 0.0))
        val shallow = render(camera = Chart3DCamera(rotationX = 20.0, rotationY = 0.0))

        fun aspect(rendered: Rendered): Double {
            val points = rendered.faces.flatMap { it.points }
            val width = points.maxOf { it.x } - points.minOf { it.x }
            val height = points.maxOf { it.y } - points.minOf { it.y }
            return height / width
        }
        assertEquals("from overhead, the disc is round", 1.0, aspect(overhead), 0.06)
        assertTrue("a shallow view squashes it: ${aspect(shallow)}", aspect(shallow) < 0.6)
    }

    @Test
    fun `the fit uses the pie's own rim rather than the box around it`() {
        val scene = scene(layout())
        val bounds = ChartRect(0f, 0f, 600f, 600f)
        val camera = Chart3DCamera.Radial
        val toRim = Chart3DProjector.of(
            scene, camera, Chart3DProjection.Perspective(), bounds, Chart3DReserve.None,
            fitTo = layout().fitPoints(),
        )!!
        val toBox = Chart3DProjector.of(
            scene, camera, Chart3DProjection.Perspective(), bounds, Chart3DReserve.None,
        )!!
        assertTrue(
            "fitting to the bounding box leaves the disc smaller than it needs to be",
            toRim.scale > toBox.scale,
        )
    }

    // ---- culling ----------------------------------------------------------

    @Test
    fun `the top surface survives and the underside is culled`() {
        val rendered = render(camera = Chart3DCamera(rotationX = 45.0, rotationY = 0.0))
        assertTrue(
            "the reader is looking down at the top surface",
            rendered.faces.any { it.side == FaceSide.Top },
        )
        assertEquals(
            "the underside of a plate is not visible from above it",
            0,
            rendered.faces.count { it.side == FaceSide.Bottom },
        )
    }

    @Test
    fun `the rim is drawn below the surface, not above it`() {
        // The single assertion that separates a plate on a table from the
        // underside of one. Both silhouettes are the same ellipse with the same
        // proportions; only which side the extrusion falls on says which way up
        // the reader is looking, and getting it wrong is invisible to every
        // other test in this file.
        val rendered = render(camera = Chart3DCamera(rotationX = 45.0, rotationY = 0.0))
        val cap = rendered.faces.filter { it.side == FaceSide.Top }
        val rim = rendered.faces.filter { it.side == FaceSide.Outer }
        assertTrue(rim.isNotEmpty())
        val capY = cap.flatMap { it.points }.map { it.y }.average()
        val rimY = rim.flatMap { it.points }.map { it.y }.average()
        assertTrue(
            "the rim must sit below the surface on screen, was cap=$capY rim=$rimY",
            rimY > capY,
        )
    }

    @Test
    fun `the visible rim is the near half of the ring`() {
        // Six o'clock is the edge nearest the reader on a horizontal disc, and
        // its wall is the one that shows. Twelve o'clock is the far edge and is
        // turned away.
        val rendered = render(camera = Chart3DCamera(rotationX = 45.0, rotationY = 0.0))
        val rim = rendered.faces.filter { it.side == FaceSide.Outer }
        val centre = rendered.projector.toScreen(Point3D.Origin)!!
        assertTrue(
            "every visible rim face belongs to the near half of the ring",
            rim.all { face -> face.points.map { it.y }.average() > centre.y },
        )
    }

    @Test
    fun `the far half of the rim is culled and the near half is not`() {
        val rendered = render(camera = Chart3DCamera(rotationX = 45.0, rotationY = 0.0))
        val outer = rendered.faces.filter { it.side == FaceSide.Outer }
        assertTrue("some of the rim must be visible", outer.isNotEmpty())
        val all = layout().slices.sumOf { slice ->
            slice.sector.faces.count { it.side == FaceSide.Outer }
        }
        assertTrue("roughly half a cylinder faces away: ${outer.size} of $all", outer.size < all)
    }

    @Test
    fun `a donut shows the wall of its own hole`() {
        val rendered = render(
            innerRatio = 0.55,
            camera = Chart3DCamera(rotationX = 50.0, rotationY = 0.0),
        )
        assertTrue(
            "the far side of the hole's wall faces the reader",
            rendered.faces.any { it.side == FaceSide.Inner },
        )
    }

    @Test
    fun `orthographic culling keeps the same surfaces as perspective`() {
        val perspective = render(projection = Chart3DProjection.Perspective())
        val ortho = render(projection = Chart3DProjection.Orthographic)
        assertTrue(perspective.faces.any { it.side == FaceSide.Top })
        assertTrue(ortho.faces.any { it.side == FaceSide.Top })
        assertEquals(0, ortho.faces.count { it.side == FaceSide.Bottom })
    }

    // ---- depth sorting ----------------------------------------------------

    @Test
    fun `faces are drawn back to front`() {
        val rendered = render()
        rendered.faces.zipWithNext().forEach { (first, second) ->
            assertTrue(
                "a nearer face must never be drawn before a further one",
                first.depth >= second.depth - 1e-9,
            )
        }
    }

    @Test
    fun `the order is total, so two coplanar faces cannot swap between frames`() {
        val once = render().faces.map { it.objectIndex to it.faceIndex }
        val again = render().faces.map { it.objectIndex to it.faceIndex }
        assertEquals("the same scene must sort the same way every time", once, again)
        // Ties are broken by identity rather than left to the comparator, which
        // is what stops a seam flickering as the camera moves by a thousandth
        // of a degree.
        val ties = render().faces.zipWithNext().filter { (a, b) -> a.depth == b.depth }
        ties.forEach { (a, b) ->
            assertTrue(
                a.objectIndex < b.objectIndex ||
                    (a.objectIndex == b.objectIndex && a.faceIndex < b.faceIndex),
            )
        }
    }

    @Test
    fun `a whole slice is not one depth, so a big slice sorts per face`() {
        // The reason the caps are tessellated: a 180-degree slice reaches from
        // the front of the chart to the back, and drawing all of it at one
        // depth would put its far half in front of a neighbour it passes behind.
        val rendered = render()
        val chrome = rendered.faces.filter { it.key?.pointIndex == 0 }
        val spread = chrome.maxOf { it.depth } - chrome.minOf { it.depth }
        assertTrue("the largest slice spans a real depth range: $spread", spread > 0.05)
    }

    // ---- hit testing ------------------------------------------------------

    @Test
    fun `a tap on a slice's cap selects that slice`() {
        val rendered = render()
        val layout = layout()
        layout.slices.forEach { slice ->
            val anchor = rendered.projector.toScreen(slice.sector.anchor()) ?: return@forEach
            val hit = Chart3DHitTest.faceAt(rendered.faces, anchor.x, anchor.y)
            assertNotNull("nothing under the middle of ${slice.key.category}", hit)
            assertEquals(slice.key, hit!!.key)
        }
    }

    @Test
    fun `every surface of a slice selects the same slice`() {
        val rendered = render(innerRatio = 0.5)
        val target = layout(innerRatio = 0.5).slices.first { it.sourceIndex == 0 }
        val resolved = HashSet<FaceSide>()

        rendered.faces.filter { it.key == target.key }.forEach { face ->
            val (x, y) = centroidOf(face)
            val hit = Chart3DHitTest.faceAt(rendered.faces, x, y) ?: return@forEach
            if (hit.key == target.key) {
                resolved += face.side
                return@forEach
            }
            // The only acceptable reason a tap on one of this slice's own faces
            // resolves elsewhere is that something is genuinely in front of it
            // — a face seen almost edge-on projects to a sliver, and the pixel
            // at its centroid really does show whatever covers it. Anything
            // else is the hit test disagreeing with the picture.
            assertTrue(
                "a tap on ${face.side} resolved to ${hit.key} which is not in front of it",
                hit.depth < face.depth,
            )
        }

        // A donut seen from in front shows its cap, its rim and the wall of its
        // own hole, and all three name the same slice.
        listOf(FaceSide.Top, FaceSide.Outer, FaceSide.Inner).forEach { side ->
            assertTrue("a tap on the $side surface must reach the slice", side in resolved)
        }
    }

    @Test
    fun `a tap on a radial wall selects the slice it belongs to`() {
        // With a gap between the slices, so the walls are exposed. On a pie
        // drawn with no gap every internal radial wall is exactly where its
        // neighbour's solid begins and is therefore hidden by it — correct
        // geometry, and the reason a wall is only worth hit testing on a chart
        // that has gaps or exploded slices.
        val rendered = render(
            camera = Chart3DCamera(rotationX = 35.0, rotationY = 25.0),
            gapDegrees = 6f,
        )
        val walls = rendered.faces.filter {
            it.key != null && (it.side == FaceSide.Start || it.side == FaceSide.End)
        }
        assertTrue("some radial wall must be visible from an angle", walls.isNotEmpty())

        // Sampled across the face rather than at its centroid. A wall exposed
        // by a gap is only *partly* exposed: the neighbouring slice's cap is
        // nearer and covers part of it, the middle included. That is what the
        // reader sees, and it is exactly why hit testing resolves per face and
        // front-most rather than per slice.
        val reachable = walls.count { face ->
            samplesOf(face).any { (x, y) ->
                Chart3DHitTest.faceAt(rendered.faces, x, y)?.key == face.key
            }
        }
        assertTrue("every gap should expose some of a wall, none did", reachable > 0)
    }

    @Test
    fun `a tap outside the pie selects nothing`() {
        val rendered = render()
        assertNull(Chart3DHitTest.faceAt(rendered.faces, 4.0, 4.0))
        assertNull(Chart3DHitTest.faceAt(rendered.faces, plot.right - 2.0, plot.bottom - 2.0))
    }

    @Test
    fun `a hidden slice cannot win a tap on the one in front of it`() {
        // Two sectors at the same angle, one stacked well below the other.
        // Seen from above the upper one covers the lower one, and resolving to
        // the lower one is the failure that makes a 3D chart feel broken rather
        // than merely wrong.
        val near = Chart3DObject(
            Sector3D(1.0, 0.0, 90.0, -0.2, 0.2, segments = 12, key = key(0, "near")),
        )
        val far = Chart3DObject(
            Sector3D(1.0, 0.0, 90.0, -2.4, -2.0, segments = 12, key = key(1, "far")),
        )
        val scene = Chart3DScene(listOf(far, near), Chart3DLighting.Default)
        val projector = Chart3DProjector.of(
            scene, Chart3DCamera(rotationX = 20.0, rotationY = 0.0),
            Chart3DProjection.Perspective(), plot, reserve,
        )!!
        val faces = projector.project(scene).faces
        val anchor = projector.toScreen(
            RadialSector3D(0.0, 1.0, 0.0, 90.0, -0.2, 0.2, segments = 12).anchor(),
        )!!
        val hit = Chart3DHitTest.faceAt(faces, anchor.x, anchor.y)
        assertEquals("near", hit?.key?.seriesId)
    }

    @Test
    fun `an exploded slice is hit where it moved to and not where it was`() {
        // On a donut, so that "where it was" is genuinely vacated. A pie slice
        // reaches the middle, so a slice displaced by less than its own radius
        // still covers most of the ground it started on — which is correct
        // geometry and makes the negative half of this assertion meaningless.
        // Safari, whose mid-angle is on the *near* half of the ring. There the
        // inner wall is turned away and the hole is genuinely empty, so a tap
        // on vacated ground really does hit nothing. On the far half the ring's
        // own inner wall is visible through the hole and still belongs to the
        // slice — which is correct, and would make the negative half of this
        // assertion meaningless.
        val explode: (Int) -> Double = { index -> if (index == 1) 0.4 else 0.0 }
        val moved = render(innerRatio = 0.5, explodeOf = explode)
        val target = layout(innerRatio = 0.5).slices.first { it.sourceIndex == 1 }
        val movedTarget = layout(innerRatio = 0.5, explodeOf = explode)
            .slices.first { it.sourceIndex == 1 }

        // Both points projected through the *same* projector. An explode widens
        // the scene and so changes the fit, and comparing a screen position
        // taken under one fit with a hit test run under another would be
        // comparing two different pictures.
        val wasAt = moved.projector.toScreen(target.sector.anchor())!!
        val nowAt = moved.projector.toScreen(movedTarget.sector.anchor())!!
        assertTrue(
            "the slice has to have visibly moved for this test to mean anything",
            kotlin.math.hypot(nowAt.x - wasAt.x, nowAt.y - wasAt.y) > 12.0,
        )
        assertEquals(
            "a tap where the slice now is selects it",
            target.key,
            Chart3DHitTest.faceAt(moved.faces, nowAt.x, nowAt.y)?.key,
        )
        assertTrue(
            "a tap where it used to be does not",
            Chart3DHitTest.faceAt(moved.faces, wasAt.x, wasAt.y)?.key != target.key,
        )
    }

    // ---- lighting ---------------------------------------------------------

    @Test
    fun `the rim is shaded across its own sweep`() {
        val rendered = render()
        val rim = rendered.faces.filter { it.side == FaceSide.Outer }
        val brightest = rim.maxOf { it.brightness }
        val darkest = rim.minOf { it.brightness }
        assertTrue(
            "a curved wall lit by one face colour would not read as curved: $darkest..$brightest",
            brightest - darkest > 0.05,
        )
        assertTrue(rim.all { it.brightness in 0.0..1.0 })
    }

    @Test
    fun `the top surface is lit evenly, because it is flat`() {
        val rendered = render()
        val cap = rendered.faces.filter { it.side == FaceSide.Top }
        assertEquals(1, cap.map { "%.6f".format(it.brightness) }.distinct().size)
    }

    // ---- diagnostics ------------------------------------------------------

    @Test
    fun `the diagnostics count what the pass actually did`() {
        val layout = layout()
        val scene = scene(layout)
        val projector = Chart3DProjector.of(
            scene, Chart3DCamera.Radial, Chart3DProjection.Perspective(), plot, reserve,
            fitTo = layout.fitPoints(),
        )!!
        val result = projector.project(scene)
        assertEquals(values.size, result.diagnostics.objectCount)
        assertEquals(result.faces.size, result.diagnostics.renderedFaces)
        assertTrue("a solid pie always culls its own far side", result.diagnostics.culledFaces > 0)
        assertEquals(
            result.diagnostics.faceCount,
            result.diagnostics.renderedFaces + result.diagnostics.culledFaces +
                result.diagnostics.degenerateFaces + result.diagnostics.clippedFaces,
        )
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Points spread across a projected face: its centroid, and the centroid
     * pulled part-way toward each corner.
     *
     * Enough to find a visible part of a face that is partly covered, without
     * turning the test into a rasteriser.
     */
    private fun samplesOf(face: ProjectedFace): List<Pair<Double, Double>> {
        val centre = centroidOf(face)
        return listOf(centre) + face.points.flatMap { corner ->
            listOf(0.4, 0.7, 0.9).map { t ->
                centre.first + (corner.x - centre.first) * t to
                    centre.second + (corner.y - centre.second) * t
            }
        }
    }

    private fun centroidOf(face: ProjectedFace): Pair<Double, Double> {
        var x = 0.0
        var y = 0.0
        face.points.forEach { x += it.x; y += it.y }
        return x / face.points.size to y / face.points.size
    }

    private fun key(index: Int, seriesId: String) =
        Chart3DKey(seriesId, index, labels[index], seriesId, index)

    private fun layout(
        innerRatio: Double = 0.0,
        explodeOf: (Int) -> Double = { 0.0 },
        gapDegrees: Float = 0f,
    ): Radial3DLayout = Radial3DLayoutEngine.layout(
        slices = computePolarSlices(values = values, gapDegrees = gapDegrees),
        labels = labels,
        seriesId = "browsers",
        direction = PolarDirection.Clockwise,
        chartStartAngle = 0f,
        innerRadiusRatio = innerRatio,
        depth = Chart3DDepth.Auto,
        quality = Chart3DQuality.Auto,
        radiusPx = 280.0,
        explodeOf = explodeOf,
        reveal = 1f,
    )

    private fun scene(layout: Radial3DLayout) = Chart3DScene(
        layout.slices.map { Chart3DObject(it.sector, it.sourceIndex) },
        Chart3DLighting.Default,
    )

    @Suppress("LongParameterList")
    private fun render(
        innerRatio: Double = 0.0,
        camera: Chart3DCamera = Chart3DCamera.Radial,
        projection: Chart3DProjection = Chart3DProjection.Perspective(),
        explodeOf: (Int) -> Double = { 0.0 },
        gapDegrees: Float = 0f,
    ): Rendered {
        val layout = layout(innerRatio, explodeOf, gapDegrees)
        val scene = scene(layout)
        val projector = Chart3DProjector.of(
            scene, camera, projection, plot, reserve, fitTo = layout.fitPoints(),
        )!!
        return Rendered(projector, projector.project(scene).faces)
    }

    private class Rendered(val projector: Chart3DProjector, val faces: List<ProjectedFace>)
}
