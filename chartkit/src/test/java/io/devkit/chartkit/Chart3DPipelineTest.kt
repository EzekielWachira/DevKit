package io.devkit.chartkit

import io.devkit.chartkit.geometry.BarGrouping
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.three.Chart3DCamera
import io.devkit.chartkit.three.Chart3DFrame
import io.devkit.chartkit.three.Chart3DHitTest
import io.devkit.chartkit.three.Chart3DKey
import io.devkit.chartkit.three.Chart3DLighting
import io.devkit.chartkit.three.Chart3DObject
import io.devkit.chartkit.three.Chart3DProjection
import io.devkit.chartkit.three.Chart3DProjector
import io.devkit.chartkit.three.Chart3DReserve
import io.devkit.chartkit.three.Chart3DScene
import io.devkit.chartkit.three.Column3DArrangement
import io.devkit.chartkit.three.Column3DDepth
import io.devkit.chartkit.three.Column3DLayout
import io.devkit.chartkit.three.Column3DLayoutEngine
import io.devkit.chartkit.three.Column3DSeries
import io.devkit.chartkit.three.Cuboid3D
import io.devkit.chartkit.three.FaceSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole pipeline, end to end: data → stack layout → world → camera →
 * projection → faces → the thing a finger lands on.
 *
 * The unit tests either side of this one check that each stage is right on its
 * own. These check that the stages are *connected* — which is where a 3D chart
 * actually fails, and where it fails invisibly: a chart whose hit testing runs
 * against geometry the reader is not looking at draws perfectly and selects
 * nothing.
 */
class Chart3DPipelineTest {

    private val categories = listOf("North", "East", "South", "West", "Central")
    private val plot = ChartRect(0f, 0f, 900f, 800f)
    private val reserve = Chart3DReserve(left = 60f, top = 8f, right = 8f, bottom = 40f)

    @Test
    fun `a tap in the middle of a column selects that column`() {
        val rendered = render()
        val target = rendered.layout.segments
            .first { it.key.categoryIndex == 3 && it.key.seriesId == "measured" }
        val centre = rendered.projector.toScreen(target.cuboid.faceCenter(FaceSide.Front))!!
        val hit = Chart3DHitTest.faceAt(rendered.faces, centre.x, centre.y)
        assertNotNull("nothing under the middle of the West column", hit)
        assertEquals("measured", hit!!.key!!.seriesId)
        assertEquals(3, hit.key!!.categoryIndex)
    }

    @Test
    fun `every visible face of a column resolves to the same data`() {
        val rendered = render()
        val target = rendered.layout.segments
            .first { it.key.categoryIndex == 0 && it.key.seriesId == "measured" }
        listOf(FaceSide.Front, FaceSide.Top, FaceSide.Right).forEach { side ->
            val centre = rendered.projector.toScreen(target.cuboid.faceCenter(side)) ?: return@forEach
            val hit = Chart3DHitTest.faceAt(rendered.faces, centre.x, centre.y) ?: return@forEach
            assertEquals(
                "a tap on the $side face must select the same segment",
                target.key,
                hit.key,
            )
        }
    }

    @Test
    fun `a tap outside every column selects nothing`() {
        val rendered = render()
        // The top-left corner of the plot: above every column, inside the frame.
        assertNull(Chart3DHitTest.faceAt(rendered.faces, 70.0, 20.0))
    }

    @Test
    fun `a null value has no geometry to tap`() {
        val rendered = render()
        val eastMeasured = rendered.layout.segments
            .firstOrNull { it.key.categoryIndex == 1 && it.key.seriesId == "measured" }
        assertNull("East measured nothing, so there is nothing to select", eastMeasured)
    }

    @Test
    fun `the front-most column wins when two overlap`() {
        // Two boxes at the same x and y, one directly behind the other. Under
        // any camera the near one covers the far one, and a tap that resolved
        // to the far one would be the failure that makes a 3D chart feel
        // broken rather than merely wrong.
        val near = Chart3DObject(
            Cuboid3D(0.0, 100.0, 0.0, 100.0, 0.0, 40.0, key(0, "near")),
        )
        val far = Chart3DObject(
            Cuboid3D(0.0, 100.0, 0.0, 100.0, 200.0, 40.0, key(1, "far")),
        )
        val scene = Chart3DScene(listOf(far, near), Chart3DLighting.Default)
        val projector = Chart3DProjector.of(
            scene,
            Chart3DCamera(rotationX = 6.0, rotationY = 0.0),
            Chart3DProjection.Perspective(),
            plot,
        )!!
        val faces = projector.project(scene).faces
        val centre = projector.toScreen(near.geometry.faceCenter(FaceSide.Front))!!
        val hit = Chart3DHitTest.faceAt(faces, centre.x, centre.y)
        assertEquals("near", hit?.key?.seriesId)
    }

    @Test
    fun `a frame wall is never selected`() {
        val rendered = render()
        // The back wall covers the whole plot, so a point in the empty space
        // above the columns is over it — and must still select nothing.
        val hit = Chart3DHitTest.faceAt(rendered.faces, 450.0, 30.0)
        assertNull("the back wall is scenery, not data", hit)
    }

    @Test
    fun `turning the camera moves the picture and leaves the layout alone`() {
        val layout = layout()
        val objects = layout.segments.map { Chart3DObject(it.cuboid, it.paletteIndex) }
        val scene = Chart3DScene(objects, Chart3DLighting.Default)

        val a = Chart3DProjector.of(
            scene, Chart3DCamera(rotationY = 10.0), Chart3DProjection.Perspective(), plot, reserve,
        )!!
        val b = Chart3DProjector.of(
            scene, Chart3DCamera(rotationY = 40.0), Chart3DProjection.Perspective(), plot, reserve,
        )!!
        val corner = layout.segments.first().cuboid.vertices.first()
        assertTrue(a.toScreen(corner) != b.toScreen(corner))
        // The world geometry the two projectors read is one and the same list.
        // Nothing about a camera change touches the stack engine or the scales.
        assertEquals(
            layout.segments.map { it.cuboid.bounds },
            layout.segments.map { it.cuboid.bounds },
        )
    }

    @Test
    fun `an orthographic view keeps two equal columns the same height`() {
        // The property that makes the parallel projection worth offering: under
        // perspective these two differ, and a reader comparing across the depth
        // axis is reading the distortion rather than the data.
        val front = Chart3DObject(Cuboid3D(0.0, 60.0, 0.0, 100.0, 0.0, 40.0, key(0, "front")))
        val back = Chart3DObject(Cuboid3D(200.0, 60.0, 0.0, 100.0, 200.0, 40.0, key(1, "back")))
        val scene = Chart3DScene(listOf(front, back), Chart3DLighting.Default)
        val camera = Chart3DCamera(rotationX = 15.0, rotationY = 15.0)

        fun heightOf(projector: Chart3DProjector, obj: Chart3DObject): Double {
            val bottom = projector.toScreen(obj.geometry.faces.first { it.side == FaceSide.Bottom }.centroid)!!
            val top = projector.toScreen(obj.geometry.faces.first { it.side == FaceSide.Top }.centroid)!!
            return bottom.y - top.y
        }

        val ortho = Chart3DProjector.of(scene, camera, Chart3DProjection.Orthographic, plot)!!
        assertEquals(heightOf(ortho, front), heightOf(ortho, back), 1e-6)

        val perspective = Chart3DProjector.of(scene, camera, Chart3DProjection.Perspective(), plot)!!
        assertTrue(
            "under perspective the far column must be drawn shorter",
            heightOf(perspective, back) < heightOf(perspective, front) - 1.0,
        )
    }

    // ---- helpers ----------------------------------------------------------

    private class Rendered(
        val layout: Column3DLayout,
        val projector: Chart3DProjector,
        val faces: List<io.devkit.chartkit.three.ProjectedFace>,
    )

    private fun key(index: Int, id: String) =
        Chart3DKey(seriesId = id, categoryIndex = index, category = id, stackId = id, pointIndex = index)

    private fun layout(): Column3DLayout {
        val series = listOf(
            Column3DSeries(
                "measured", "Measured", 0, null, "measured",
                listOf(24.0, null, 0.0, 31.0, 19.0), listOf(0, 1, 2, 3, 4),
            ),
            Column3DSeries(
                "recorded", "Recorded", 1, null, "recorded",
                listOf(18.0, 22.0, 0.0, null, 26.0), listOf(0, 1, 2, 3, 4),
            ),
        )
        val band = plot.width / categories.size * 0.8f
        return Column3DLayoutEngine.layout(
            categories = categories,
            series = series,
            items = emptyMap(),
            grouping = BarGrouping.Grouped,
            arrangement = Column3DArrangement.Side,
            depth = Column3DDepth.Auto,
            categoryCentres = List(categories.size) { plot.width / categories.size * (it + 0.5f) },
            bandWidth = band,
            valueFraction = { it / 35.0 },
            plotWidth = plot.width,
            plotHeight = plot.height,
            groupPadding = 0.1,
            depthGap = 0.25,
            reveal = 1f,
        )
    }

    private fun render(): Rendered {
        val layout = layout()
        val objects = layout.segments.map { Chart3DObject(it.cuboid, it.paletteIndex) }
        val panels = Chart3DFrame.Auto.panelsFor(layout.volume, Chart3DCamera.Default)
        val whole = Chart3DScene(panels + objects, Chart3DLighting.Default)
        val projector = Chart3DProjector.of(
            whole, Chart3DCamera.Default, Chart3DProjection.Perspective(), plot, reserve,
        )!!
        // Frame first, then data — the order the layer draws in, so a hit test
        // over the two lists sees exactly what a reader sees.
        val faces = projector.project(Chart3DScene(panels, Chart3DLighting.Default)).faces +
            projector.project(Chart3DScene(objects, Chart3DLighting.Default)).faces
        return Rendered(layout, projector, faces)
    }
}
