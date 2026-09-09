package io.devkit.chartkit

import io.devkit.chartkit.geometry.PolarDirection
import io.devkit.chartkit.geometry.PolarGeometry
import io.devkit.chartkit.geometry.PolarValuePolicy
import io.devkit.chartkit.geometry.computePolarSlices
import io.devkit.chartkit.three.ArcTessellator3D
import io.devkit.chartkit.three.Chart3DDepth
import io.devkit.chartkit.three.Chart3DQuality
import io.devkit.chartkit.three.FaceSide
import io.devkit.chartkit.three.Radial3DLayout
import io.devkit.chartkit.three.Radial3DLayoutEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The step between the 2D slice engine and the 3D scene.
 *
 * The theme running through these is that the layout adds a dimension and
 * changes nothing else: the same values, the same shares, the same slice
 * identities and the same angles as a flat pie over the same data, because they
 * are literally the same objects.
 */
class Radial3DLayoutTest {

    private val values = listOf(4823.0, 3112.0, 1841.0, 980.0, 700.0)
    private val labels = listOf("Chrome", "Safari", "Edge", "Firefox", "Other")
    private val radiusPx = 260.0

    // ---- reuse of the 2D engine ------------------------------------------

    @Test
    fun `the shares come from the slice engine and not from the geometry`() {
        val slices = slices(values)
        val layout = layout(slices)
        val total = values.sum()
        layout.slices.forEach { slice ->
            assertEquals(
                "the share must be the slice engine's own arithmetic",
                values[slice.sourceIndex] / total,
                slice.fraction,
                1e-12,
            )
            assertEquals(values[slice.sourceIndex], slice.value, 1e-12)
        }
    }

    @Test
    fun `a slice keeps its identity when the data is reordered`() {
        val layout = layout(slices(values))
        val keys = layout.slices.map { it.key }
        assertEquals(values.size, keys.distinct().size)
        keys.forEachIndexed { index, key ->
            // Keyed by the caller's own position, so a reorder moves the
            // geometry and not the identity — which is what lets a selection
            // and an animation survive a data change.
            assertEquals(index, key.pointIndex)
            assertEquals(labels[index], key.category)
        }
    }

    @Test
    fun `a zero value draws no slice, exactly as it does in 2D`() {
        val withZero = listOf(10.0, 0.0, 30.0)
        val slices = computePolarSlices(values = withZero)
        val layout = Radial3DLayoutEngine.layout(
            slices = slices,
            labels = listOf("a", "b", "c"),
            seriesId = "pie",
            direction = PolarDirection.Clockwise,
            chartStartAngle = 0f,
            innerRadiusRatio = 0.0,
            depth = Chart3DDepth.Auto,
            quality = Chart3DQuality.Auto,
            radiusPx = radiusPx,
            explodeOf = { 0.0 },
            reveal = 1f,
        )
        assertEquals(2, layout.slices.size)
        assertNull(layout.slices.firstOrNull { it.sourceIndex == 1 })
    }

    @Test
    fun `a negative value is dropped rather than reflected`() {
        val slices = computePolarSlices(
            values = listOf(10.0, -4.0, 30.0),
            policy = PolarValuePolicy.Ignore,
        )
        val layout = Radial3DLayoutEngine.layout(
            slices = slices,
            labels = listOf("a", "b", "c"),
            seriesId = "pie",
            direction = PolarDirection.Clockwise,
            chartStartAngle = 0f,
            innerRadiusRatio = 0.0,
            depth = Chart3DDepth.Auto,
            quality = Chart3DQuality.Auto,
            radiusPx = radiusPx,
            explodeOf = { 0.0 },
            reveal = 1f,
        )
        assertNull("a share of a whole is never negative", layout.slices.firstOrNull { it.sourceIndex == 1 })
        assertEquals(2, layout.slices.size)
    }

    // ---- pie and donut ----------------------------------------------------

    @Test
    fun `inner radius zero gives a pie and a fraction gives a donut`() {
        val pie = layout(slices(values), innerRatio = 0.0)
        val donut = layout(slices(values), innerRatio = 0.55)
        assertEquals(0.0, pie.innerRadius, 1e-12)
        assertEquals(0.55, donut.innerRadius, 1e-12)
        assertTrue(pie.slices.all { it.sector.faces.none { face -> face.side == FaceSide.Inner } })
        assertTrue(donut.slices.all { it.sector.faces.any { face -> face.side == FaceSide.Inner } })
    }

    @Test
    fun `the hole never swallows the ring`() {
        val absurd = layout(slices(values), innerRatio = 4.0)
        assertTrue("a hole is clamped, not obeyed", absurd.innerRadius < absurd.outerRadius)
    }

    // ---- depth ------------------------------------------------------------

    @Test
    fun `depth is stated against the radius and centred on the disc`() {
        val auto = layout(slices(values))
        val depth = auto.topY - auto.baseY
        assertEquals(Radial3DLayoutEngine.AUTO_DEPTH_FRACTION, depth, 1e-12)
        assertEquals("the disc's middle is the camera's pivot", 0.0, auto.baseY + auto.topY, 1e-12)

        val relative = layout(slices(values), depth = Chart3DDepth.Relative(0.4))
        assertEquals(0.4, relative.topY - relative.baseY, 1e-12)
    }

    @Test
    fun `an absolute depth is converted through the radius it will be drawn at`() {
        val small = layout(slices(values), depth = Chart3DDepth.Absolute(30f), radius = 100.0)
        val large = layout(slices(values), depth = Chart3DDepth.Absolute(30f), radius = 400.0)
        assertEquals(0.30, small.topY - small.baseY, 1e-9)
        assertEquals(0.075, large.topY - large.baseY, 1e-9)
    }

    // ---- angles -----------------------------------------------------------

    @Test
    fun `the first slice starts at twelve o'clock`() {
        val layout = layout(slices(values))
        val first = layout.slices.first { it.sourceIndex == 0 }
        assertEquals(0.0, first.sector.startAngle, 1e-9)
        val leading = first.sector.faces.first { it.side == FaceSide.Top }.vertices.first()
        // The disc is horizontal, so twelve o'clock — the top of the picture —
        // is the far side of the table: world +z.
        assertEquals("twelve o'clock is world +z", 0.0, leading.x, 1e-9)
        assertEquals(1.0, leading.z, 1e-9)
    }

    @Test
    fun `a counter-clockwise chart is the mirror image and still has a positive sweep`() {
        val clockwise = layout(slices(values), direction = PolarDirection.Clockwise)
        val counter = layout(
            slices(values, PolarDirection.CounterClockwise),
            direction = PolarDirection.CounterClockwise,
        )
        // Every sector is stated the same way whichever direction the chart
        // runs, so the winding below it is decided once rather than twice.
        assertTrue(clockwise.slices.all { it.sector.sweepAngle > 0.0 })
        assertTrue(counter.slices.all { it.sector.sweepAngle > 0.0 })
        val cw = clockwise.slices.first { it.sourceIndex == 0 }
        val ccw = counter.slices.first { it.sourceIndex == 0 }
        assertEquals(cw.sector.sweepAngle, ccw.sector.sweepAngle, 1e-9)
        // Clockwise, the first slice runs 0 → sweep. Counter-clockwise it runs
        // −sweep → 0, which is the same arc reflected in the vertical.
        assertEquals(0.0, cw.sector.startAngle, 1e-9)
        assertEquals(-cw.sector.sweepAngle, ccw.sector.startAngle, 1e-9)
    }

    @Test
    fun `a partial pie covers only the sweep it was given`() {
        val partial = computePolarSlices(values = values, totalSweep = 180f)
        val layout = layout(partial)
        val covered = layout.slices.sumOf { it.sector.sweepAngle }
        // Loose by a ten-thousandth: the slice engine states sweeps as Floats,
        // and summing five of them accumulates more error than a Double compare.
        assertEquals(180.0, covered, 1e-4)
    }

    // ---- animation --------------------------------------------------------

    @Test
    fun `a reveal of zero draws nothing and a reveal of one half draws half`() {
        val none = layout(slices(values), reveal = 0f)
        assertTrue("nothing has swept out yet", none.slices.isEmpty())

        val half = layout(slices(values), reveal = 0.5f)
        val whole = layout(slices(values), reveal = 1f)
        half.slices.forEach { slice ->
            val settled = whole.slices.first { it.sourceIndex == slice.sourceIndex }
            assertEquals(settled.sector.sweepAngle / 2.0, slice.sector.sweepAngle, 1e-9)
        }
    }

    @Test
    fun `the tessellation does not change as a slice sweeps in`() {
        // The count comes from the settled sweep, so an animating slice keeps a
        // stable topology and its vertices move rather than being rebuilt.
        val frames = listOf(0.2f, 0.4f, 0.6f, 0.8f, 1f).map { layout(slices(values), reveal = it) }
        val counts = frames.map { frame -> frame.slices.map { it.sector.segments } }
        counts.zipWithNext().forEach { (a, b) -> assertEquals(a, b) }
    }

    // ---- explode ----------------------------------------------------------

    @Test
    fun `an exploded slice moves along its own mid-angle`() {
        val layout = layout(slices(values), explodeOf = { index -> if (index == 1) 0.2 else 0.0 })
        val moved = layout.slices.first { it.sourceIndex == 1 }
        val still = layout.slices.first { it.sourceIndex == 0 }
        assertEquals(0.0, still.explode, 1e-12)
        assertEquals(0.2, moved.explode, 1e-12)

        val mid = Math.toRadians(moved.sector.midAngle)
        assertEquals(0.2 * kotlin.math.sin(mid), moved.sector.offsetX, 1e-9)
        assertEquals(0.2 * kotlin.math.cos(mid), moved.sector.offsetZ, 1e-9)
        // Displaced outward across the table, not sideways and not upward: the
        // slice's own anchor moves further from the axis by exactly the explode
        // distance, and stays at the same height.
        val before = still.sector.anchor()
        val after = moved.sector.anchor()
        assertEquals(before.y, after.y, 1e-12)
        assertTrue(hypot(before.x, before.z) < hypot(after.x, after.z))
    }

    @Test
    fun `several slices can be exploded at once`() {
        val layout = layout(slices(values), explodeOf = { index -> if (index % 2 == 0) 0.15 else 0.0 })
        assertEquals(3, layout.slices.count { it.explode > 0.0 })
        assertEquals(2, layout.slices.count { it.explode == 0.0 })
    }

    @Test
    fun `an exploded slice widens the fit so the chart still fits its plot`() {
        val still = layout(slices(values))
        val moved = layout(slices(values), explodeOf = { 0.25 })
        val reach = { l: Radial3DLayout -> l.fitPoints().maxOf { hypot(it.x, it.z) } }
        assertTrue(
            "an explode has to be accounted for, or the moved slice is drawn outside the plot",
            reach(moved) > reach(still) + 0.2,
        )
    }

    // ---- diagnostics ------------------------------------------------------

    @Test
    fun `the layout reports how many segments it tessellated into`() {
        val layout = layout(slices(values))
        val expected = layout.slices.sumOf { it.sector.segments }
        assertEquals(expected, layout.tessellationSegments)
        assertTrue(layout.tessellationSegments >= values.size * ArcTessellator3D.MIN_SEGMENTS)
    }

    @Test
    fun `every vertex of every slice is finite`() {
        val layout = layout(slices(values), innerRatio = 0.6, explodeOf = { 0.1 })
        layout.slices.forEach { slice ->
            slice.sector.faces.forEach { face ->
                assertTrue(face.vertices.all { it.isFinite })
                assertTrue(abs(face.normal.length - 1.0) < 1e-9)
            }
        }
        assertNotNull(layout.fitPoints().firstOrNull())
    }

    // ---- helpers ----------------------------------------------------------

    private fun slices(
        values: List<Double>,
        direction: PolarDirection = PolarDirection.Clockwise,
    ) = computePolarSlices(
        values = values,
        startAngle = 0f,
        totalSweep = PolarGeometry.FULL_CIRCLE,
        direction = direction,
    )

    @Suppress("LongParameterList")
    private fun layout(
        slices: List<io.devkit.chartkit.geometry.PolarSlice>,
        innerRatio: Double = 0.0,
        depth: Chart3DDepth = Chart3DDepth.Auto,
        direction: PolarDirection = PolarDirection.Clockwise,
        radius: Double = radiusPx,
        explodeOf: (Int) -> Double = { 0.0 },
        reveal: Float = 1f,
    ): Radial3DLayout = Radial3DLayoutEngine.layout(
        slices = slices,
        labels = labels,
        seriesId = "browsers",
        direction = direction,
        chartStartAngle = 0f,
        innerRadiusRatio = innerRatio,
        depth = depth,
        quality = Chart3DQuality.Auto,
        radiusPx = radius,
        explodeOf = explodeOf,
        reveal = reveal,
    )
}
