package io.devkit.chartkit

import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.viewport.ChartViewport
import io.devkit.chartkit.viewport.ChartZoomLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zoom and pan are asserted here rather than through pointer instrumentation.
 *
 * The interesting behaviour — focal-point preservation, clamping, limits — is
 * arithmetic, and arithmetic is worth testing where the failure is legible. A
 * pinch test can tell you the viewport moved; only this can tell you it kept
 * the right value under the fingers.
 */
class ChartViewportTest {

    @Test
    fun `the default viewport shows everything`() {
        val viewport = ChartViewport.Full
        assertEquals(1.0, viewport.width, 1e-9)
        assertEquals(1.0, viewport.zoom, 1e-9)
        assertTrue(viewport.isFullyZoomedOut)
    }

    @Test
    fun `zooming in halves the visible width and doubles the zoom`() {
        val zoomed = ChartViewport.Full.zoomedBy(2.0)
        assertEquals(0.5, zoomed.width, 1e-9)
        assertEquals(2.0, zoomed.zoom, 1e-9)
        assertFalse(zoomed.isFullyZoomedOut)
    }

    @Test
    fun `zooming about the centre keeps the centre in place`() {
        val zoomed = ChartViewport.Full.zoomedBy(2.0, focus = 0.5)
        assertEquals(0.25, zoomed.start, 1e-9)
        assertEquals(0.75, zoomed.end, 1e-9)
    }

    @Test
    fun `zooming about the left edge keeps the left edge in place`() {
        val zoomed = ChartViewport.Full.zoomedBy(2.0, focus = 0.0)
        assertEquals(0.0, zoomed.start, 1e-9)
        assertEquals(0.5, zoomed.end, 1e-9)
    }

    @Test
    fun `zooming about the right edge keeps the right edge in place`() {
        val zoomed = ChartViewport.Full.zoomedBy(2.0, focus = 1.0)
        assertEquals(0.5, zoomed.start, 1e-9)
        assertEquals(1.0, zoomed.end, 1e-9)
    }

    @Test
    fun `the value under the focal point does not move`() {
        // The invariant that makes a pinch feel like a real chart: put two
        // fingers on 0.3 of the domain and 0.3 stays under them.
        val start = ChartViewport(0.2, 0.8)
        val focus = 0.25
        val anchorBefore = start.start + start.width * focus
        val zoomed = start.zoomedBy(1.7, focus)
        val anchorAfter = zoomed.start + zoomed.width * focus
        assertEquals(anchorBefore, anchorAfter, 1e-6)
    }

    @Test
    fun `zooming out never shows more than the whole domain`() {
        val zoomed = ChartViewport(0.4, 0.6).zoomedBy(0.01)
        assertEquals(0.0, zoomed.start, 1e-9)
        assertEquals(1.0, zoomed.end, 1e-9)
    }

    @Test
    fun `a zoom near an edge is pushed inside the domain, not past it`() {
        val zoomed = ChartViewport(0.0, 0.2).zoomedBy(0.5, focus = 0.0)
        assertEquals(0.0, zoomed.start, 1e-9)
        assertEquals(0.4, zoomed.end, 1e-9)
    }

    @Test
    fun `the maximum zoom is respected`() {
        var viewport = ChartViewport.Full
        repeat(20) { viewport = viewport.zoomedBy(2.0, maxZoom = 10.0) }
        assertEquals(10.0, viewport.zoom, 1e-6)
    }

    @Test
    fun `an absurd zoom factor cannot collapse the domain`() {
        val zoomed = ChartViewport.Full.zoomedBy(1e12, maxZoom = 1e12)
        assertTrue("width ${zoomed.width}", zoomed.width > 0.0)
        // Clamped to the floor rather than allowed to underflow the scale.
        assertEquals(ChartViewport.MIN_WIDTH, zoomed.width, ChartViewport.MIN_WIDTH * 0.01)
    }

    @Test
    fun `repeated zooming never produces an invalid viewport`() {
        var viewport = ChartViewport.Full
        repeat(200) { viewport = viewport.zoomedBy(1.7, focus = 0.31, maxZoom = 1e9) }
        assertTrue(viewport.width > 0.0)
        assertTrue(viewport.start >= 0.0 && viewport.end <= 1.0)
    }

    @Test
    fun `a non-finite or non-positive factor changes nothing`() {
        val viewport = ChartViewport(0.2, 0.8)
        assertEquals(viewport, viewport.zoomedBy(Double.NaN))
        assertEquals(viewport, viewport.zoomedBy(0.0))
        assertEquals(viewport, viewport.zoomedBy(-2.0))
    }

    @Test
    fun `panning moves the window without resizing it`() {
        val panned = ChartViewport(0.2, 0.6).pannedBy(0.1)
        assertEquals(0.3, panned.start, 1e-9)
        assertEquals(0.7, panned.end, 1e-9)
        assertEquals(0.4, panned.width, 1e-9)
    }

    @Test
    fun `panning left is clamped at the start of the domain`() {
        val panned = ChartViewport(0.1, 0.5).pannedBy(-0.5)
        assertEquals(0.0, panned.start, 1e-9)
        assertEquals(0.4, panned.width, 1e-9)
    }

    @Test
    fun `panning right is clamped at the end of the domain`() {
        val panned = ChartViewport(0.5, 0.9).pannedBy(0.5)
        assertEquals(1.0, panned.end, 1e-9)
        assertEquals(0.4, panned.width, 1e-9)
    }

    @Test
    fun `a fully zoomed-out viewport cannot pan`() {
        assertEquals(ChartViewport.Full, ChartViewport.Full.pannedBy(0.3))
        assertEquals(ChartViewport.Full, ChartViewport.Full.pannedBy(-0.3))
    }

    @Test
    fun `reset shows the whole domain again`() {
        assertEquals(ChartViewport.Full, ChartViewport(0.3, 0.4).reset())
    }

    @Test
    fun `the visible domain is the window applied to the full one`() {
        val visible = ChartViewport(0.25, 0.75).visibleDomain(NumericDomain(0.0, 100.0))
        assertEquals(25.0, visible.min, 1e-9)
        assertEquals(75.0, visible.max, 1e-9)
    }

    @Test
    fun `the visible category range rounds outward so partial bands still draw`() {
        val range = ChartViewport(0.3, 0.5).visibleCategoryRange(10)
        assertEquals(3, range.first)
        assertEquals(4, range.last)
    }

    @Test
    fun `the visible category range is safe for an empty axis`() {
        assertTrue(ChartViewport.Full.visibleCategoryRange(0).isEmpty())
    }

    @Test
    fun `a fraction of the full domain maps into the window`() {
        val viewport = ChartViewport(0.25, 0.75)
        assertEquals(0.0, viewport.fractionWithin(0.25), 1e-9)
        assertEquals(0.5, viewport.fractionWithin(0.5), 1e-9)
        assertEquals(1.0, viewport.fractionWithin(0.75), 1e-9)
    }

    @Test
    fun `trailing shows the end of the domain`() {
        val viewport = ChartViewport.trailing(0.1)
        assertEquals(1.0, viewport.end, 1e-9)
        assertEquals(0.9, viewport.start, 1e-9)
    }

    @Test
    fun `between orders its arguments`() {
        assertEquals(ChartViewport.between(0.2, 0.6), ChartViewport.between(0.6, 0.2))
    }

    @Test
    fun `between collapses to the full domain rather than to nothing`() {
        assertEquals(ChartViewport.Full, ChartViewport.between(0.4, 0.4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a collapsed viewport is rejected`() {
        ChartViewport(0.5, 0.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-finite viewport is rejected`() {
        ChartViewport(0.0, Double.NaN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a maximum zoom below one is rejected`() {
        ChartZoomLimits(maxZoom = 0.5)
    }

    @Test
    fun `zoom limits report the fixed minimum`() {
        assertEquals(1.0, ChartZoomLimits.Default.minZoom, 1e-9)
    }
}
