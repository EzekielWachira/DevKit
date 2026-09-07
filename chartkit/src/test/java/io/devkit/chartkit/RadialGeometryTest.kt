package io.devkit.chartkit

import io.devkit.chartkit.geometry.RadialGeometry
import io.devkit.chartkit.geometry.RadialRangePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialGeometryTest {

    @Test
    fun `a value maps to its share of the range`() {
        assertEquals(0.72, RadialGeometry.progress(72.0, 0.0, 100.0), 1e-9)
        assertEquals(0.0, RadialGeometry.progress(0.0, 0.0, 100.0), 1e-9)
        assertEquals(1.0, RadialGeometry.progress(100.0, 0.0, 100.0), 1e-9)
    }

    @Test
    fun `the range need not start at zero or end at a hundred`() {
        // A radial chart of temperature between -10 and 40 is as valid as one
        // of percentages; forcing the caller to normalise first is the
        // conversion step ChartKit exists to avoid.
        assertEquals(0.5, RadialGeometry.progress(15.0, -10.0, 40.0), 1e-9)
        assertEquals(0.25, RadialGeometry.progress(2.5, 0.0, 10.0), 1e-9)
    }

    @Test
    fun `out-of-range values clamp by default`() {
        assertEquals(1.0, RadialGeometry.progress(150.0, 0.0, 100.0), 1e-9)
        assertEquals(0.0, RadialGeometry.progress(-50.0, 0.0, 100.0), 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the reject policy throws above the maximum`() {
        RadialGeometry.progress(150.0, 0.0, 100.0, RadialRangePolicy.Reject)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the reject policy throws below the minimum`() {
        RadialGeometry.progress(-1.0, 0.0, 100.0, RadialRangePolicy.Reject)
    }

    @Test
    fun `a missing or non-finite value is no progress at all`() {
        assertEquals(0.0, RadialGeometry.progress(null, 0.0, 100.0), 1e-9)
        assertEquals(0.0, RadialGeometry.progress(Double.NaN, 0.0, 100.0), 1e-9)
        assertEquals(0.0, RadialGeometry.progress(Double.POSITIVE_INFINITY, 0.0, 100.0), 1e-9)
    }

    @Test
    fun `a zero-width range yields zero rather than dividing by it`() {
        assertEquals(0.0, RadialGeometry.progress(50.0, 50.0, 50.0), 1e-9)
    }

    @Test
    fun `tracks are laid out from the outside in`() {
        assertEquals(90f, RadialGeometry.trackRadius(0, 100f, 20f, 6f), 0.01f)
        assertEquals(64f, RadialGeometry.trackRadius(1, 100f, 20f, 6f), 0.01f)
        assertEquals(38f, RadialGeometry.trackRadius(2, 100f, 20f, 6f), 0.01f)
    }

    @Test
    fun `a point on a ring picks that ring`() {
        assertEquals(0, RadialGeometry.trackAt(90f, 3, 100f, 20f, 6f))
        assertEquals(1, RadialGeometry.trackAt(64f, 3, 100f, 20f, 6f))
        assertEquals(2, RadialGeometry.trackAt(38f, 3, 100f, 20f, 6f))
    }

    @Test
    fun `a point anywhere across a ring's thickness still picks it`() {
        assertEquals(0, RadialGeometry.trackAt(81f, 3, 100f, 20f, 6f))
        assertEquals(0, RadialGeometry.trackAt(99f, 3, 100f, 20f, 6f))
    }

    @Test
    fun `the gap between two rings belongs to neither`() {
        // Ring 0 covers 80..100, ring 1 covers 54..74; 77 is in the gap.
        assertEquals(-1, RadialGeometry.trackAt(77f, 3, 100f, 20f, 6f))
    }

    @Test
    fun `a point outside every ring hits nothing`() {
        assertEquals(-1, RadialGeometry.trackAt(120f, 3, 100f, 20f, 6f))
        assertEquals(-1, RadialGeometry.trackAt(5f, 3, 100f, 20f, 6f))
    }

    @Test
    fun `tracks that would run through the centre are not counted`() {
        // Six rings of 20 with 6 spacing need a radius of 145; only some fit.
        val visible = RadialGeometry.visibleTrackCount(6, 100f, 20f, 6f)
        assertTrue("expected fewer than six, got $visible", visible in 1..5)
    }

    @Test
    fun `every configured track fits when there is room`() {
        assertEquals(3, RadialGeometry.visibleTrackCount(3, 200f, 20f, 6f))
    }

    @Test
    fun `an overflowing track is never hit`() {
        // Ring 5 would sit at a negative radius; a tap near the centre must not
        // select it.
        assertEquals(-1, RadialGeometry.trackAt(-10f, 6, 100f, 20f, 6f))
    }
}
