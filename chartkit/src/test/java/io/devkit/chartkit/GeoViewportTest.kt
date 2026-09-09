package io.devkit.chartkit

import io.devkit.chartkit.coordinate.GeoCoordinates
import io.devkit.chartkit.geo.GeoProjection
import io.devkit.chartkit.geo.ProjectedBounds
import io.devkit.chartkit.geo.ProjectedPoint
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.state.ChartGeoViewportState
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
}
