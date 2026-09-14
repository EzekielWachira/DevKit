package io.devkit.chartkit

import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.geometry.ParallelAxisSpec
import io.devkit.chartkit.geometry.ParallelLayout
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.state.ChartParallelBrushState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Axis placement, per-axis domains and the polylines that cross them. */
class ParallelLayoutTest {

    private val bounds = ChartRect(0f, 0f, 300f, 100f)

    // Deliberately different magnitudes: the case that forces per-axis domains.
    private val specs = listOf(
        ParallelAxisSpec("price", listOf(10_000.0, 30_000.0, 20_000.0)),
        ParallelAxisSpec("mpg", listOf(50.0, 20.0, 35.0)),
        ParallelAxisSpec("weight", listOf(1_000.0, 2_000.0, 1_500.0)),
    )

    private fun layout(
        specs: List<ParallelAxisSpec> = this.specs,
        bounds: ChartRect = this.bounds,
        inset: Float = 0f,
    ) = ParallelLayout.layout(specs, bounds, inset = inset)

    @Test
    fun `axes are evenly spaced across the plot`() {
        val geometry = layout()

        assertEquals(listOf(0f, 150f, 300f), geometry.axes.map { it.x })
    }

    @Test
    fun `each axis takes its own domain from its own values`() {
        val geometry = layout()

        assertEquals(10_000.0, geometry.axes[0].domain.min, 1e-9)
        assertEquals(30_000.0, geometry.axes[0].domain.max, 1e-9)
        assertEquals(20.0, geometry.axes[1].domain.min, 1e-9)
        assertEquals(50.0, geometry.axes[1].domain.max, 1e-9)
    }

    @Test
    fun `a domain is the values' own range, not zero to the maximum`() {
        // Padding out to a zero nobody measured would compress every real
        // difference into the top of the axis.
        val geometry = layout()

        assertTrue(geometry.axes.none { it.domain.min == 0.0 })
    }

    @Test
    fun `a caller's fixed domain overrides the data's own`() {
        val fixed = listOf(
            ParallelAxisSpec("score", listOf(3.0, 7.0), NumericDomain(0.0, 10.0)),
            ParallelAxisSpec("other", listOf(1.0, 2.0)),
        )
        val geometry = layout(fixed)

        assertEquals(0.0, geometry.axes[0].domain.min, 1e-9)
        assertEquals(10.0, geometry.axes[0].domain.max, 1e-9)
    }

    @Test
    fun `the maximum sits at the top of its axis and the minimum at the bottom`() {
        val axis = layout().axes[0]

        assertEquals(0f, axis.positionOf(30_000.0)!!, 1e-3f)
        assertEquals(100f, axis.positionOf(10_000.0)!!, 1e-3f)
    }

    @Test
    fun `rows keep their relative order within an axis whatever the units`() {
        // The one comparison a parallel axis actually supports.
        val geometry = layout()
        val price = geometry.axes[0]
        val cheap = price.positionOf(10_000.0)!!
        val dear = price.positionOf(30_000.0)!!

        assertTrue("cheaper should sit lower on the axis", cheap > dear)
    }

    @Test
    fun `a dimension whose values are all equal is drawn down the middle`() {
        // No interval to scale against; putting every row at an edge would
        // claim a difference that is not there.
        val flat = listOf(
            ParallelAxisSpec("same", listOf(5.0, 5.0, 5.0)),
            ParallelAxisSpec("other", listOf(1.0, 2.0, 3.0)),
        )
        val axis = layout(flat).axes[0]

        assertEquals(50f, axis.positionOf(5.0)!!, 1e-3f)
    }

    @Test
    fun `one polyline per row, one point per axis`() {
        val geometry = layout()

        assertEquals(3, geometry.polylines.size)
        geometry.polylines.forEach { assertEquals(3, it.points.size) }
    }

    @Test
    fun `a missing value breaks the line rather than closing over it`() {
        val gapped = listOf(
            ParallelAxisSpec("a", listOf(1.0, 2.0)),
            ParallelAxisSpec("b", listOf(null, 5.0)),
            ParallelAxisSpec("c", listOf(3.0, 6.0)),
        )
        val geometry = layout(gapped)
        val broken = geometry.polylines[0]

        assertNull(broken.points[1])
        // Two isolated points with a hole between them stroke nothing: a
        // segment needs two consecutive present points.
        assertTrue(broken.segments.isEmpty())
    }

    @Test
    fun `a row present on consecutive axes still strokes those`() {
        val gapped = listOf(
            ParallelAxisSpec("a", listOf(1.0)),
            ParallelAxisSpec("b", listOf(2.0)),
            ParallelAxisSpec("c", listOf(null)),
            ParallelAxisSpec("d", listOf(4.0)),
        )
        val geometry = layout(gapped)

        assertEquals(1, geometry.polylines[0].segments.size)
        assertEquals(2, geometry.polylines[0].segments[0].size)
    }

    @Test
    fun `an inset keeps the axes clear of the plot edges`() {
        val geometry = layout(inset = 10f)

        geometry.axes.forEach { axis ->
            assertEquals(10f, axis.top, 1e-3f)
            assertEquals(90f, axis.bottom, 1e-3f)
        }
    }

    @Test
    fun `fewer than two dimensions lays out nothing`() {
        // One axis is not a comparison, and the spacing would divide by zero.
        val single = listOf(ParallelAxisSpec("only", listOf(1.0)))

        assertTrue(layout(single).axes.isEmpty())
    }

    @Test
    fun `an empty plot lays out nothing`() {
        assertTrue(layout(bounds = ChartRect(0f, 0f, 0f, 0f)).axes.isEmpty())
    }

    @Test
    fun `an inset taller than the plot lays out nothing rather than inverting`() {
        assertTrue(layout(inset = 80f).axes.isEmpty())
    }

    @Test
    fun `valueAt inverts positionOf`() {
        val axis = layout().axes[0]
        listOf(10_000.0, 17_500.0, 30_000.0).forEach { value ->
            val y = axis.positionOf(value)!!
            assertEquals(value, axis.valueAt(y), 1e-6)
        }
    }

    @Test
    fun `valueAt clamps outside the axis rather than extrapolating`() {
        val axis = layout().axes[0]

        assertEquals(30_000.0, axis.valueAt(-500f), 1e-6)
        assertEquals(10_000.0, axis.valueAt(9_999f), 1e-6)
    }

    @Test
    fun `the axes helper agrees with the full layout`() {
        // They are two entries to the same spacing, and a drift between them
        // would brush the axis next to the one under the finger.
        val full = layout(inset = 6f).axes
        val quick = ParallelLayout.axes(specs, bounds, inset = 6f)

        assertEquals(full.size, quick.size)
        full.zip(quick).forEach { (a, b) ->
            assertEquals(a.x, b.x, 1e-6f)
            assertEquals(a.top, b.top, 1e-6f)
            assertEquals(a.domain.min, b.domain.min, 1e-9)
        }
    }

    @Test
    fun `distance to a line is zero on the line and grows away from it`() {
        val geometry = layout()
        val line = geometry.polylines[0]
        val onIt = line.points[0]!!

        assertEquals(0f, ParallelLayout.distanceSquaredTo(line, onIt), 1e-3f)
        val away = ChartOffset(onIt.x, onIt.y + 20f)
        assertTrue(ParallelLayout.distanceSquaredTo(line, away) > 100f)
    }

    @Test
    fun `a line with no strokeable segment is infinitely far away`() {
        val gapped = listOf(
            ParallelAxisSpec("a", listOf(1.0)),
            ParallelAxisSpec("b", listOf(null)),
            ParallelAxisSpec("c", listOf(3.0)),
        )
        val line = layout(gapped).polylines[0]

        assertEquals(Float.MAX_VALUE, ParallelLayout.distanceSquaredTo(line, ChartOffset(0f, 0f)), 1f)
    }

    @Test
    fun `the layout is deterministic`() {
        val first = layout()
        val second = layout()

        first.polylines.zip(second.polylines).forEach { (a, b) ->
            assertEquals(a.points, b.points)
        }
    }
}

/** What a brush admits, and what it refuses. */
class ParallelBrushTest {

    private fun brushes() = ChartParallelBrushState()

    @Test
    fun `nothing brushed admits everything`() {
        val state = brushes()

        assertTrue(state.isEmpty)
        assertTrue(state.admits { 42.0 })
        assertTrue(state.admits { null })
    }

    @Test
    fun `a brush admits values inside it and refuses values outside`() {
        val state = brushes()
        state.brush(0, 10.0..20.0)

        assertTrue(state.admits { 15.0 })
        assertFalse(state.admits { 5.0 })
        assertFalse(state.admits { 25.0 })
    }

    @Test
    fun `a row missing the brushed value is excluded`() {
        // Letting it through because nothing contradicts the filter would put
        // rows of unknown weight into the answer to "weight between 1200 and
        // 1600", which is not what anybody means by that question.
        val state = brushes()
        state.brush(0, 10.0..20.0)

        assertFalse(state.admits { null })
        assertFalse(state.admits { Double.NaN })
    }

    @Test
    fun `brushes on several axes are combined with and`() {
        val state = brushes()
        state.brush(0, 10.0..20.0)
        state.brush(1, 100.0..200.0)

        assertTrue(state.admits { axis -> if (axis == 0) 15.0 else 150.0 })
        assertFalse(state.admits { axis -> if (axis == 0) 15.0 else 500.0 })
    }

    @Test
    fun `a zero-length drag clears the axis rather than hiding every row`() {
        val state = brushes()
        state.brush(0, 10.0..20.0)
        state.brush(0, 15.0..15.0)

        assertTrue(state.isEmpty)
    }

    @Test
    fun `a non-finite range is ignored`() {
        val state = brushes()
        state.brush(0, Double.NaN..20.0)

        assertTrue(state.isEmpty)
    }

    @Test
    fun `clearing one axis leaves the others`() {
        val state = brushes()
        state.brush(0, 1.0..2.0)
        state.brush(1, 3.0..4.0)
        state.clear(0)

        assertEquals(1, state.activeCount)
        assertNotNull(state.ranges[1])
        assertNull(state.ranges[0])
    }

    @Test
    fun `clearAll empties everything`() {
        val state = brushes()
        state.brush(0, 1.0..2.0)
        state.brush(1, 3.0..4.0)
        state.clearAll()

        assertTrue(state.isEmpty)
        assertEquals(0, state.activeCount)
    }

    @Test
    fun `ranges are in value space, so they survive any layout`() {
        val state = brushes()
        state.brush(0, 1_200.0..1_600.0)

        assertEquals(1_200.0, state.ranges[0]!!.start, 1e-9)
        assertEquals(1_600.0, state.ranges[0]!!.endInclusive, 1e-9)
    }
}
