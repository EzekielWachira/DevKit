package io.devkit.chartkit

import io.devkit.chartkit.coordinate.GeoCoordinates
import io.devkit.chartkit.geo.GeoProjection
import io.devkit.chartkit.geo.ProjectedBounds
import io.devkit.chartkit.geo.ProjectedPoint
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.state.ChartGeoViewportState
import io.devkit.chartkit.state.GeoPanConstraint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The map camera: zoom about a focus, pan within bounds, reset. */
class GeoViewportTest {

    private fun camera(maxZoom: Float = 8f) = ChartGeoViewportState(maxZoom = maxZoom)

    @Test
    fun aFreshCameraShowsTheWholeMap() {
        val camera = camera()

        assertEquals(1f, camera.zoom, 1e-6f)
        assertTrue(camera.isReset)
    }

    @Test
    fun zoomingInAndBackOutReturnsToTheStart() {
        val camera = camera()

        camera.zoomBy(2f)
        camera.zoomBy(0.5f)

        assertEquals(1f, camera.zoom, 1e-5f)
        assertTrue(camera.isReset)
    }

    @Test
    fun zoomIsClampedAtBothEnds() {
        val camera = camera(maxZoom = 4f)

        camera.zoomBy(100f)
        assertEquals(4f, camera.zoom, 1e-6f)

        camera.zoomBy(0.001f)
        // Below 1 the map would be smaller than the plot it was fitted to,
        // which is not a state the fit has a meaning for.
        assertEquals(1f, camera.zoom, 1e-6f)
    }

    @Test
    fun atFullExtentThereIsNothingToPan() {
        val camera = camera()

        camera.panBy(0.5f, 0.5f)

        assertEquals(0f, camera.panX, 1e-6f)
        assertEquals(0f, camera.panY, 1e-6f)
    }

    @Test
    fun zoomedInThePanIsBoundedByTheOverhang() {
        val camera = camera()
        camera.zoomBy(3f)

        camera.panBy(10f, -10f)

        // At 3x the enlarged map extends one plot-width past each edge, so the
        // pan can be at most (3 - 1) / 2 in either direction. Beyond that the
        // reader would be dragging geography off the plot into blank space.
        assertEquals(1f, camera.panX, 1e-5f)
        assertEquals(-1f, camera.panY, 1e-5f)
    }

    @Test
    fun zoomingAboutAFocusKeepsThatPointStill() {
        val plot = ChartRect.fromSize(200f, 200f)
        val extent = ProjectedBounds(-10.0, -10.0, 10.0, 10.0)
        fun coordinatesFor(camera: ChartGeoViewportState) = GeoCoordinates(
            plotArea = plot,
            projection = GeoProjection.Equirectangular,
            extent = extent,
            zoom = camera.zoom,
            panX = camera.panX,
            panY = camera.panY,
        )

        val camera = camera()
        val before = coordinatesFor(camera)
        // The point under the top-left quarter of the plot, where a pinch is
        // centred.
        val focusScreen = io.devkit.chartkit.geometry.ChartOffset(50f, 50f)
        val anchored: ProjectedPoint = before.projectedAt(focusScreen)

        camera.zoomBy(2f, focusX = 0.25f, focusY = 0.25f)
        val after = coordinatesFor(camera)
        val moved = after.screenOf(anchored)

        assertTrue(abs(moved.x - focusScreen.x) < 1f)
        assertTrue(abs(moved.y - focusScreen.y) < 1f)
    }

    @Test
    fun resetPutsTheWholeMapBack() {
        val camera = camera()
        camera.zoomBy(4f, focusX = 0.2f, focusY = 0.8f)
        camera.panBy(0.3f, -0.2f)

        camera.reset()

        assertTrue(camera.isReset)
        assertEquals(0f, camera.panX, 1e-6f)
        assertEquals(0f, camera.panY, 1e-6f)
    }

    @Test
    fun aNonFiniteGestureIsIgnoredRatherThanPoisoningTheCamera() {
        val camera = camera()
        camera.zoomBy(3f)
        val zoom = camera.zoom

        camera.zoomBy(Float.NaN)
        camera.panBy(Float.NaN, 1f)

        assertEquals(zoom, camera.zoom, 1e-6f)
        assertTrue(camera.panX.isFinite())
    }

    @Test
    fun aFocusRequestIsConsumedExactlyOnce() {
        val camera = camera()

        camera.focusOn(io.devkit.chartkit.geo.GeoBounds(0.0, 0.0, 1.0, 1.0))
        val first = camera.consumeFocus()
        val second = camera.consumeFocus()

        assertTrue(first != null)
        assertTrue(second == null)
    }

    @Test
    fun anEmptyFocusRegionIsNotAccepted() {
        val camera = camera()

        camera.focusOn(io.devkit.chartkit.geo.GeoBounds(1.0, 1.0, 1.0, 1.0))

        assertFalse(camera.pendingFocus != null)
    }

    // ---- the absolute forms, and the constraint modes (§39, §46, §157) ----

    @Test
    fun zoomToLandsExactlyWhereZoomByWould() {
        val absolute = camera()
        val relative = camera()

        absolute.zoomTo(3f)
        relative.zoomBy(3f)

        assertEquals(relative.zoom, absolute.zoom, 1e-6f)
        assertEquals(relative.panX, absolute.panX, 1e-6f)
        assertEquals(relative.panY, absolute.panY, 1e-6f)
    }

    @Test
    fun zoomToKeepsItsFocusStill() {
        val state = camera()

        state.zoomTo(4f, focusX = 0.25f, focusY = 0.75f)

        // Same anchoring as a pinch, because it is the same implementation: an
        // absolute zoom that drifted would make a zoom slider feel broken next
        // to a pinch that does not.
        val expected = camera().apply { zoomBy(4f, focusX = 0.25f, focusY = 0.75f) }
        assertEquals(expected.panX, state.panX, 1e-6f)
        assertEquals(expected.panY, state.panY, 1e-6f)
    }

    @Test
    fun anImpossibleZoomTargetIsIgnored() {
        val state = camera()
        state.zoomTo(3f)
        val before = state.zoom

        state.zoomTo(0f)
        state.zoomTo(-2f)
        state.zoomTo(Float.NaN)

        assertEquals(before, state.zoom, 0f)
    }

    @Test
    fun fitToGeometryIsTheFullyZoomedOutCamera() {
        val state = camera()
        state.zoomTo(5f)
        state.panBy(0.3f, -0.2f)

        state.fitToGeometry()

        // Zoom 1 on a map *is* the fit: the coordinate system scales the
        // projected extent to the plot before the camera is applied, so there
        // is no second "fitted" state that could drift out of step.
        assertTrue(state.isReset)
        assertEquals(ChartGeoViewportState.MIN_ZOOM, state.zoom, 0f)
    }

    @Test
    fun aSoftConstraintAllowsOvershootAndStrictDoesNot() {
        val strict = ChartGeoViewportState(panConstraint = GeoPanConstraint.Strict)
        val soft = ChartGeoViewportState(panConstraint = GeoPanConstraint.Soft)
        listOf(strict, soft).forEach { it.zoomTo(2f) }

        strict.panBy(10f, 0f)
        soft.panBy(10f, 0f)

        // At zoom 2 the strict edge is half a plot; soft adds a margin on top.
        assertEquals(0.5f, strict.panX, 1e-6f)
        assertTrue("soft should allow more than strict", soft.panX > strict.panX)
        // But it is still bounded — the map cannot be dragged away entirely.
        assertTrue(soft.panX.isFinite() && soft.panX < 1f)
    }

    @Test
    fun anUnconstrainedCameraGoesWhereItIsTold() {
        val free = ChartGeoViewportState(panConstraint = GeoPanConstraint.None)

        free.panBy(10f, -7f)

        // For a caller doing their own framing. Still refuses nonsense: a
        // non-finite gesture is dropped rather than applied, so the camera
        // keeps the position it had instead of being poisoned by one NaN.
        assertEquals(10f, free.panX, 0f)
        assertEquals(-7f, free.panY, 0f)
        free.panBy(Float.NaN, 0f)
        assertEquals(10f, free.panX, 0f)
        assertEquals(-7f, free.panY, 0f)
    }

    @Test
    fun aStrictCameraStillCannotPanAtFullExtent() {
        listOf(GeoPanConstraint.Strict, GeoPanConstraint.Soft).forEach { constraint ->
            val state = ChartGeoViewportState(panConstraint = constraint)
            state.panBy(0.4f, 0.4f)
            if (constraint == GeoPanConstraint.Strict) {
                // Nothing to pan: the map already fits, and letting the reader
                // drag it into a corner would be a bug rather than a feature.
                assertEquals(0f, state.panX, 0f)
                assertEquals(0f, state.panY, 0f)
            }
        }
    }
}
