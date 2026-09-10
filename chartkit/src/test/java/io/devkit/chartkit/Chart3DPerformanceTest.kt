package io.devkit.chartkit

import io.devkit.chartkit.geometry.BarGrouping
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.three.Chart3DCamera
import io.devkit.chartkit.three.Chart3DLighting
import io.devkit.chartkit.three.Chart3DObject
import io.devkit.chartkit.three.Chart3DProjection
import io.devkit.chartkit.three.Chart3DProjector
import io.devkit.chartkit.three.Chart3DScene
import io.devkit.chartkit.three.Column3DArrangement
import io.devkit.chartkit.three.Chart3DDepth
import io.devkit.chartkit.three.Column3DLayout
import io.devkit.chartkit.three.Column3DLayoutEngine
import io.devkit.chartkit.three.Column3DSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What makes a 3D chart fast enough, asserted as properties.
 *
 * ### No wall-clock assertions
 *
 * The same position the rest of ChartKit takes: a test that fails when a shared
 * machine is busy teaches a team to ignore it, and a millisecond figure measured
 * on one laptop and written into a README is a number about that laptop. What is
 * asserted here are the properties that *cause* the performance and that a
 * regression would break — that a camera move reuses the world geometry, that
 * culling leaves at most three faces of any box to draw, and that the work per
 * frame is proportional to the visible faces rather than to the data.
 *
 * The one measurement that is printed rather than asserted is there so that a
 * figure quoted anywhere else can be reproduced by running this.
 */
class Chart3DPerformanceTest {

    private val plot = ChartRect(0f, 0f, 1080f, 720f)

    @Test
    fun `culling leaves at most three faces of any box to draw`() {
        // The property the whole render budget rests on: six faces are built
        // and never more than three of them can face a viewer, so the draw loop
        // is half the size of the geometry however the chart is turned.
        listOf(
            Chart3DCamera(rotationX = 0.0, rotationY = 0.0),
            Chart3DCamera(rotationX = 20.0, rotationY = 25.0),
            Chart3DCamera(rotationX = 45.0, rotationY = -40.0),
            Chart3DCamera(rotationX = 70.0, rotationY = 55.0),
        ).forEach { camera ->
            val (scene, _) = sceneOf(categories = 10, series = 5)
            val result = projector(scene, camera).project(scene)
            val perObject = result.faces.groupBy { it.objectIndex }.mapValues { it.value.size }
            assertTrue(
                "a box showed ${perObject.values.maxOrNull()} faces at $camera",
                perObject.values.all { it <= 3 },
            )
            assertEquals(
                scene.objects.size * 6,
                result.diagnostics.faceCount,
            )
        }
    }

    @Test
    fun `moving the camera reuses the world geometry`() {
        val (scene, layout) = sceneOf(categories = 20, series = 6)
        val before = layout.segments.map { it.cuboid }

        val a = projector(scene, Chart3DCamera(rotationY = 10.0))
        val b = projector(scene, Chart3DCamera(rotationY = 40.0))
        a.project(scene)
        b.project(scene)

        // Same objects, same boxes, same vertices. A camera move costs a matrix
        // composition and one pass over the faces; it costs nothing in the stack
        // engine, the scales or the geometry.
        layout.segments.forEachIndexed { index, segment ->
            assertSame(before[index], segment.cuboid)
        }
    }

    @Test
    fun `the drawn work is proportional to the visible faces, not to the data`() {
        val small = sceneOf(categories = 10, series = 5).first
        val large = sceneOf(categories = 20, series = 6).first
        val camera = Chart3DCamera.Default

        val smallResult = projector(small, camera).project(small)
        val largeResult = projector(large, camera).project(large)

        val smallRatio = smallResult.diagnostics.renderedFaces.toDouble() / small.objects.size
        val largeRatio = largeResult.diagnostics.renderedFaces.toDouble() / large.objects.size
        // Twelve times the data must not cost more than three faces per box
        // either time: the cost per column is constant, so the total is linear.
        assertTrue("$smallRatio faces per box", smallRatio <= 3.0)
        assertTrue("$largeRatio faces per box", largeRatio <= 3.0)
    }

    @Test
    fun `a degenerate column costs nothing to draw`() {
        // Every value zero: six faces per box, four of them with no area at all,
        // and the two that remain facing opposite ways. One survives.
        val (scene, _) = sceneOf(categories = 20, series = 6, value = 0.0)
        val result = projector(scene, Chart3DCamera.Default).project(scene)
        assertTrue(
            "a flat chart drew ${result.diagnostics.renderedFaces} faces",
            result.diagnostics.renderedFaces <= scene.objects.size,
        )
        assertTrue(result.diagnostics.degenerateFaces > 0)
    }

    /**
     * A measurement, printed rather than asserted.
     *
     * Reproduces the figures quoted in the handover. It measures the projection
     * pass — camera transform, culling, sort and lighting for every face — which
     * is the part that runs again on every camera frame. It does not measure
     * Compose, the canvas, or a device.
     */
    @Test
    fun `measure the projection pass`() {
        val cases = listOf(
            Triple("50 columns", 10, 5),
            Triple("120 columns", 20, 6),
            Triple("500 columns", 50, 10),
        )
        // Warmed across every size first, so the first case measured is not the
        // one paying for the JIT the others then benefit from.
        cases.forEach { (_, categories, series) ->
            val (warm, _) = sceneOf(categories, series)
            val projector = projector(warm, Chart3DCamera.Default)
            repeat(WARMUP) { projector.project(warm) }
        }
        cases.forEach { (label, categories, series) ->
            val (scene, _) = sceneOf(categories, series)
            val projector = projector(scene, Chart3DCamera.Default)
            repeat(WARMUP) { projector.project(scene) }
            val start = System.nanoTime()
            repeat(RUNS) { projector.project(scene) }
            val perPass = (System.nanoTime() - start) / RUNS / 1000.0
            val faces = projector.project(scene).diagnostics
            println(
                "3D projection: $label (${scene.objects.size} boxes, " +
                    "${faces.renderedFaces} drawn faces) — " +
                    "%.1f microseconds per pass".format(perPass),
            )
        }
    }

    // ---- radial -----------------------------------------------------------

    @Test
    fun `a radial chart's tessellation follows the radius, not the slice count`() {
        // The property that makes Auto quality worth having: the same pie on a
        // small chart and a large one is cut into different numbers of segments,
        // so a dashboard tile does not pay for smoothness nobody can see.
        val small = radialScene(slices = 8, radiusPx = 70.0)
        val large = radialScene(slices = 8, radiusPx = 700.0)
        assertTrue(
            "a small pie should tessellate more coarsely: " +
                "${small.second.tessellationSegments} vs ${large.second.tessellationSegments}",
            small.second.tessellationSegments < large.second.tessellationSegments,
        )
    }

    @Test
    fun `a radial camera move reuses the tessellated sectors`() {
        val (scene, layout) = radialScene(slices = 10, radiusPx = 300.0)
        val before = layout.slices.map { it.sector }
        projector(scene, Chart3DCamera(rotationX = 20.0)).project(scene)
        projector(scene, Chart3DCamera(rotationX = 60.0)).project(scene)
        // Same sectors, same faces, same vertices: turning a pie costs a matrix
        // composition and one pass over the faces, and re-cuts no arcs.
        layout.slices.forEachIndexed { index, slice -> assertSame(before[index], slice.sector) }
    }

    @Test
    fun `culling removes about half of a pie's faces`() {
        // A solid of revolution always has its whole back half turned away, and
        // the back cap besides. Half the geometry is built and never drawn,
        // which is the budget a curved surface is affordable within.
        val (scene, _) = radialScene(slices = 8, radiusPx = 300.0)
        val result = projector(scene, Chart3DCamera.Radial).project(scene)
        val drawn = result.diagnostics.renderedFaces.toDouble() / result.diagnostics.faceCount
        assertTrue("a pie drew ${(drawn * 100).toInt()}% of its faces", drawn < 0.6)
    }

    /**
     * A measurement, printed rather than asserted.
     *
     * Reproduces the radial figures quoted in the handover. Like the column
     * measurement above, it covers the projection pass and nothing else.
     */
    @Test
    fun `measure the radial projection pass`() {
        val cases = listOf(
            Triple("5 slices", 5, 300.0),
            Triple("10 slices", 10, 300.0),
            Triple("20 slices", 20, 300.0),
            Triple("20 slices, large", 20, 700.0),
        )
        cases.forEach { (_, slices, radius) ->
            val (warm, _) = radialScene(slices, radius)
            val projector = projector(warm, Chart3DCamera.Radial)
            repeat(WARMUP) { projector.project(warm) }
        }
        cases.forEach { (label, slices, radius) ->
            val (scene, layout) = radialScene(slices, radius)
            val projector = projector(scene, Chart3DCamera.Radial)
            repeat(WARMUP) { projector.project(scene) }
            val start = System.nanoTime()
            repeat(RUNS) { projector.project(scene) }
            val perPass = (System.nanoTime() - start) / RUNS / 1000.0
            val diagnostics = projector.project(scene).diagnostics
            println(
                "3D radial projection: $label (${scene.objects.size} sectors, " +
                    "${layout.tessellationSegments} arc segments, " +
                    "${diagnostics.faceCount} faces, " +
                    "${diagnostics.renderedFaces} drawn) — " +
                    "%.1f microseconds per pass".format(perPass),
            )
        }
    }

    /** The same measurement for the tessellation itself, which a camera move skips. */
    @Test
    fun `measure the radial world build`() {
        val sizes = listOf(5, 10, 20)
        // Every size warmed before any is measured. Warming each immediately
        // before its own run charges the first case for the JIT compilation
        // that the others then benefit from, which made the five-slice build
        // look slower than the twenty-slice one.
        repeat(WARMUP / 4) { sizes.forEach { radialScene(it, 300.0) } }
        sizes.forEach { slices ->
            val start = System.nanoTime()
            repeat(RUNS / 5) { radialScene(slices, 300.0) }
            val perBuild = (System.nanoTime() - start) / (RUNS / 5) / 1000.0
            println(
                "3D radial world build: $slices slices — " +
                    "%.1f microseconds per build".format(perBuild),
            )
        }
    }

    // ---- 3D scatter --------------------------------------------------------

    /**
     * §189 and §191, as a property: a camera drag reuses every world point.
     *
     * The cloud is built once and handed to two projectors. Neither can reach
     * back into it, and the assertion is on identity rather than on equality —
     * a pipeline that recomputed the three scales per pointer frame would
     * produce equal points and would be the thing this test exists to catch.
     */
    @Test
    fun `a scatter camera move reuses every world point`() {
        val (scene, marks) = scatterScene(points = 2_000)
        val before = scene.marks
        val a = projector(scene, Chart3DCamera(rotationX = 20.0, rotationY = 15.0))
        val b = projector(scene, Chart3DCamera(rotationX = 45.0, rotationY = -35.0))
        a.project(scene)
        b.project(scene)
        assertSame("the mark list must survive a reprojection", before, scene.marks)
        marks.forEachIndexed { index, mark ->
            assertSame(mark, scene.marks[index])
            assertSame(mark.position, scene.marks[index].position)
        }
    }

    /** A parallel projection draws every marker at the size it was given. */
    @Test
    fun `orthographic marker sizes do not depend on the point count or the depth`() {
        val (scene, _) = scatterScene(points = 500)
        val result = Chart3DProjector.of(
            scene,
            Chart3DCamera(rotationX = 30.0, rotationY = 30.0),
            Chart3DProjection.Orthographic,
            plot,
        )!!.project(scene)
        assertEquals(1, result.marks.map { it.radius }.distinct().size)
    }

    /**
     * §188: the projection pass at three representative sizes.
     *
     * Printed, never asserted, for the reason at the top of this file. What the
     * numbers are good for is a comparison against each other — the pass should
     * be close to linear in the point count, and a change that made it
     * quadratic would show up here long before it showed up as a stutter.
     */
    @Test
    fun `measure the scatter projection pass`() {
        val camera = Chart3DCamera(rotationX = 25.0, rotationY = 30.0)
        listOf(100, 1_000, 10_000).forEach { points ->
            val (scene, _) = scatterScene(points)
            val projector = projector(scene, camera)
            // Warmed immediately before its own run: warming once for every
            // size charges the first case with the JIT the others benefit
            // from, and the first case is the small one.
            repeat(WARMUP) { projector.project(scene) }
            val start = System.nanoTime()
            repeat(RUNS) { projector.project(scene) }
            val perPass = (System.nanoTime() - start) / RUNS / 1000.0
            val result = projector.project(scene)
            println(
                "3D scatter projection: $points points — " +
                    "${result.diagnostics.renderedMarks} drawn, " +
                    "${result.diagnostics.renderedFaces} frame faces, " +
                    "%.1f microseconds per pass".format(perPass),
            )
        }
    }

    /** §188: what a tap costs, with and without the screen-space index. */
    @Test
    fun `measure scatter hit testing`() {
        val camera = Chart3DCamera(rotationX = 25.0, rotationY = 30.0)
        listOf(100, 1_000, 10_000).forEach { points ->
            val (scene, _) = scatterScene(points)
            val marks = projector(scene, camera).project(scene).marks
            val index = io.devkit.chartkit.layer.three.MarkIndex.of(marks)!!
            val probes = marks.filterIndexed { i, _ -> i % 7 == 0 }.map { it.center }

            repeat(WARMUP) { probes.forEach { io.devkit.chartkit.three.Chart3DHitTest.markAt(marks, it.x, it.y, 12.0) } }
            var start = System.nanoTime()
            repeat(RUNS / 10) {
                probes.forEach { io.devkit.chartkit.three.Chart3DHitTest.markAt(marks, it.x, it.y, 12.0) }
            }
            val perScan = (System.nanoTime() - start) / (RUNS / 10) / probes.size / 1000.0

            repeat(WARMUP) { probes.forEach { index.candidates(it.x, it.y, 12.0) } }
            start = System.nanoTime()
            repeat(RUNS / 10) { probes.forEach { index.candidates(it.x, it.y, 12.0) } }
            val perIndexed = (System.nanoTime() - start) / (RUNS / 10) / probes.size / 1000.0

            println(
                "3D scatter hit test: $points points — " +
                    "%.2f microseconds per full scan, ".format(perScan) +
                    "%.2f microseconds per indexed lookup".format(perIndexed),
            )
        }
    }

    /** §188: what building the cloud's world positions costs. */
    @Test
    fun `measure the scatter world build`() {
        listOf(100, 1_000, 10_000).forEach { points ->
            repeat(WARMUP / 4) { scatterScene(points) }
            val runs = (RUNS / 10).coerceAtLeast(1)
            val start = System.nanoTime()
            repeat(runs) { scatterScene(points) }
            val perBuild = (System.nanoTime() - start) / runs / 1000.0
            println(
                "3D scatter world build: $points points — " +
                    "%.1f microseconds per build".format(perBuild),
            )
        }
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * A deterministic point cloud, in the same three-scale pipeline the layer
     * uses — so what is measured is the real path and not a synthetic one.
     */
    private fun scatterScene(
        points: Int,
    ): Pair<Chart3DScene, List<io.devkit.chartkit.three.Chart3DMark>> {
        fun scale(min: Double, max: Double) = io.devkit.chartkit.scale.LinearScale(
            io.devkit.chartkit.scale.NumericDomain(min, max),
            rangeStart = 0f,
            rangeEnd = 1f,
        )
        val coordinates = io.devkit.chartkit.three.Cartesian3DCoordinates(
            xScale = scale(18.0, 80.0),
            yScale = scale(20_000.0, 200_000.0),
            zScale = scale(0.0, 100.0),
            box = io.devkit.chartkit.three.Chart3DPlotBox(
                width = plot.width.toDouble(),
                height = plot.height.toDouble(),
                depth = plot.height.toDouble(),
            ),
        )
        val marks = ArrayList<io.devkit.chartkit.three.Chart3DMark>(points)
        for (index in 0 until points) {
            // A cheap deterministic spread: three coprime strides, so the cloud
            // fills the box without a random source the run could vary by.
            val position = coordinates.worldOf(
                x = 18.0 + (index * 37 % 620) / 10.0,
                y = 20_000.0 + (index * 53 % 1800) * 100.0,
                z = (index * 29 % 1000) / 10.0,
            ) ?: continue
            marks += io.devkit.chartkit.three.Chart3DMark(
                position = position,
                radius = 5.0,
                marker = io.devkit.chartkit.three.Marker3D.Sphere,
                key = io.devkit.chartkit.three.Chart3DKey(
                    seriesId = "observations",
                    categoryIndex = index,
                    category = "",
                    stackId = "observations",
                    pointIndex = index,
                ),
            )
        }
        val frame = io.devkit.chartkit.three.Chart3DFrame.Auto
            .panelsFor(coordinates.volume, Chart3DCamera(rotationX = 25.0, rotationY = 30.0))
        return Chart3DScene(frame, Chart3DLighting.Default, marks) to marks
    }


    private fun radialScene(
        slices: Int,
        radiusPx: Double,
    ): Pair<Chart3DScene, io.devkit.chartkit.three.Radial3DLayout> {
        val values = List(slices) { 40.0 + (it * 17) % 61 }
        val layout = io.devkit.chartkit.three.Radial3DLayoutEngine.layout(
            slices = io.devkit.chartkit.geometry.computePolarSlices(values = values),
            labels = List(slices) { "S$it" },
            seriesId = "pie",
            direction = io.devkit.chartkit.geometry.PolarDirection.Clockwise,
            chartStartAngle = 0f,
            innerRadiusRatio = 0.45,
            depth = Chart3DDepth.Auto,
            quality = io.devkit.chartkit.three.Chart3DQuality.Auto,
            radiusPx = radiusPx,
            explodeOf = { 0.0 },
            reveal = 1f,
        )
        val objects = layout.slices.map { Chart3DObject(it.sector, it.sourceIndex) }
        return Chart3DScene(objects, Chart3DLighting.Default) to layout
    }

    private fun projector(scene: Chart3DScene, camera: Chart3DCamera): Chart3DProjector =
        Chart3DProjector.of(scene, camera, Chart3DProjection.Perspective(), plot)!!

    private fun sceneOf(
        categories: Int,
        series: Int,
        value: Double = 12.0,
    ): Pair<Chart3DScene, Column3DLayout> {
        val labels = List(categories) { "C$it" }
        val data = List(series) { index ->
            Column3DSeries(
                seriesId = "s$index",
                seriesName = "Series $index",
                paletteIndex = index,
                colorOverride = null,
                stackId = "all",
                values = List(categories) {
                    if (value == 0.0) 0.0 else value + (index + it) % 7
                },
                sourceIndices = List(categories) { it },
            )
        }
        val band = plot.width / categories * 0.8f
        val layout = Column3DLayoutEngine.layout(
            categories = labels,
            series = data,
            items = emptyMap(),
            grouping = BarGrouping.Stacked,
            arrangement = Column3DArrangement.Side,
            depth = Chart3DDepth.Auto,
            categoryCentres = List(categories) { plot.width / categories * (it + 0.5f) },
            bandWidth = band,
            valueFraction = { it / (value * series + 50.0) },
            plotWidth = plot.width,
            plotHeight = plot.height,
            groupPadding = 0.1,
            depthGap = 0.25,
            reveal = 1f,
        )
        val objects = layout.segments.map { Chart3DObject(it.cuboid, it.paletteIndex) }
        return Chart3DScene(objects, Chart3DLighting.Default) to layout
    }

    private companion object {
        const val WARMUP = 200
        const val RUNS = 500
    }
}
