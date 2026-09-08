package io.devkit.chartkit

import io.devkit.chartkit.coordinate.GeoCoordinates
import io.devkit.chartkit.geo.EquirectangularProjection
import io.devkit.chartkit.geo.GeoCoordinate
import io.devkit.chartkit.geo.GeoProjection
import io.devkit.chartkit.geo.MercatorProjection
import io.devkit.chartkit.geo.ProjectedBounds
import io.devkit.chartkit.geo.ProjectedPoint
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The projections, and the fit that turns their output into pixels. */
class GeoProjectionTest {

    @Test
    fun equirectangularIsIdentityOnDegrees() {
        val projected = GeoProjection.Equirectangular.project(GeoCoordinate(12.0, -34.0))

        assertEquals(12.0, projected.x, 1e-9)
        assertEquals(-34.0, projected.y, 1e-9)
    }

    @Test
    fun equirectangularInvertsExactly() {
        val original = GeoCoordinate(-77.0365, 38.8977)

        val roundTripped = GeoProjection.Equirectangular
            .invert(GeoProjection.Equirectangular.project(original))

        assertNotNull(roundTripped)
        assertEquals(original.longitude, roundTripped!!.longitude, 1e-9)
        assertEquals(original.latitude, roundTripped.latitude, 1e-9)
    }

    @Test
    fun aStandardParallelNarrowsLongitudeWithoutTouchingLatitude() {
        val projection = EquirectangularProjection(standardParallel = 60.0)

        val projected = projection.project(GeoCoordinate(10.0, 60.0))

        // cos(60°) = 0.5, which is the whole point: at 60° north a degree of
        // longitude is half a degree of latitude on the ground.
        assertEquals(5.0, projected.x, 1e-6)
        assertEquals(60.0, projected.y, 1e-9)
    }

    @Test
    fun mercatorIsZeroAtTheEquatorAndGrowsNorthward() {
        val equator = GeoProjection.Mercator.project(GeoCoordinate(0.0, 0.0))
        val north = GeoProjection.Mercator.project(GeoCoordinate(0.0, 45.0))

        assertEquals(0.0, equator.y, 1e-9)
        assertTrue(north.y > 0.0)
    }

    @Test
    fun mercatorClampsThePolesRatherThanReturningInfinity() {
        val pole = GeoProjection.Mercator.project(GeoCoordinate(0.0, 90.0))
        val cutoff = GeoProjection.Mercator
            .project(GeoCoordinate(0.0, MercatorProjection.MAX_MERCATOR_LATITUDE))

        assertTrue(pole.isFinite)
        // A single Antarctic vertex projecting to infinity would collapse the
        // fit and take the whole map with it.
        assertEquals(cutoff.y, pole.y, 1e-9)
    }

    @Test
    fun mercatorInvertsWithinItsClamp() {
        val original = GeoCoordinate(13.405, 52.52)

        val roundTripped = GeoProjection.Mercator
            .invert(GeoProjection.Mercator.project(original))

        assertNotNull(roundTripped)
        assertEquals(original.longitude, roundTripped!!.longitude, 1e-9)
        assertEquals(original.latitude, roundTripped.latitude, 1e-9)
    }

    @Test
    fun aNonFiniteCoordinateProducesANonFinitePointRatherThanThrowing() {
        // Thrown from inside a draw pass this would take down the frame.
        assertFalse(GeoProjection.Mercator.project(GeoCoordinate(Double.NaN, 0.0)).isFinite)
        assertFalse(GeoProjection.Equirectangular.project(GeoCoordinate(0.0, Double.NaN)).isFinite)
        assertNull(GeoProjection.Mercator.invert(ProjectedPoint(Double.NaN, 0.0)))
    }

    // The fit ------------------------------------------------------------

    private fun coordinates(
        width: Float = 200f,
        height: Float = 100f,
        zoom: Float = 1f,
        panX: Float = 0f,
        panY: Float = 0f,
        padding: Float = 0f,
    ) = GeoCoordinates(
        plotArea = ChartRect.fromSize(width, height),
        projection = GeoProjection.Equirectangular,
        extent = ProjectedBounds(-10.0, -10.0, 10.0, 10.0),
        zoom = zoom,
        panX = panX,
        panY = panY,
        padding = padding,
    )

    @Test
    fun theExtentCentreLandsAtThePlotCentre() {
        val screen = coordinates().screenOf(ProjectedPoint(0.0, 0.0))

        assertEquals(100f, screen.x, 1e-3f)
        assertEquals(50f, screen.y, 1e-3f)
    }

    @Test
    fun bothAxesShareOneScale() {
        val fit = coordinates()

        val right = fit.screenOf(ProjectedPoint(10.0, 0.0))
        val top = fit.screenOf(ProjectedPoint(0.0, 10.0))

        // The extent is square and the plot is 200x100, so the height binds:
        // 5 pixels per unit both ways. A per-axis fit would have put `right` at
        // the plot's edge and stretched the geography by a factor of two.
        assertEquals(150f, right.x, 1e-3f)
        assertEquals(0f, top.y, 1e-3f)
    }

    @Test
    fun northIsUp() {
        val fit = coordinates()

        val north = fit.screenOf(ProjectedPoint(0.0, 5.0))
        val south = fit.screenOf(ProjectedPoint(0.0, -5.0))

        assertTrue(north.y < south.y)
    }

    @Test
    fun screenAndProjectedAreExactInverses() {
        val fit = coordinates(zoom = 2.5f, panX = 0.1f, panY = -0.2f, padding = 8f)

        val original = ProjectedPoint(3.25, -7.5)
        val back = fit.projectedAt(fit.screenOf(original))

        // Approximate inverses would make hit testing wrong only when zoomed,
        // which is the hardest kind of bug to notice.
        assertTrue(abs(back.x - original.x) < 1e-3)
        assertTrue(abs(back.y - original.y) < 1e-3)
    }

    @Test
    fun paddingShrinksTheFittedArea() {
        val padded = coordinates(padding = 10f)

        val top = padded.screenOf(ProjectedPoint(0.0, 10.0))

        assertEquals(10f, top.y, 1e-3f)
    }

    @Test
    fun zoomingInNarrowsTheVisibleExtent() {
        val out = coordinates().visibleExtent()
        val zoomed = coordinates(zoom = 4f).visibleExtent()

        assertTrue(zoomed.width < out.width)
        assertTrue(zoomed.height < out.height)
    }

    @Test
    fun unzoomedTheVisibleExtentCoversEveryFeature() {
        // Culling tests against this box, so an unzoomed map must cull nothing.
        val visible = coordinates().visibleExtent()

        assertTrue(visible.minX <= -10.0 && visible.maxX >= 10.0)
        assertTrue(visible.minY <= -10.0 && visible.maxY >= 10.0)
    }

    @Test
    fun zoomToFitAndPanToCentrePlaceATargetInTheMiddle() {
        val fit = coordinates()
        val target = ProjectedBounds(2.0, 2.0, 4.0, 4.0)

        val zoom = fit.zoomToFit(target)
        val (panX, panY) = fit.panToCentre(target, zoom)
        val moved = coordinates(zoom = zoom, panX = panX, panY = panY)

        val centre = moved.screenOf(ProjectedPoint(3.0, 3.0))
        assertEquals(moved.contentArea.centerX, centre.x, 0.5f)
        assertEquals(moved.contentArea.centerY, centre.y, 0.5f)
    }

    @Test
    fun zoomToFitNeverZoomsBackOut() {
        val fit = coordinates()

        // A target larger than the whole map would otherwise produce a zoom
        // below 1, which is not a state the camera has.
        assertEquals(1f, fit.zoomToFit(ProjectedBounds(-100.0, -100.0, 100.0, 100.0)), 1e-6f)
    }

    @Test
    fun anEmptyPlotIsNotDrawable() {
        val empty = GeoCoordinates(
            plotArea = ChartRect.fromSize(0f, 0f),
            projection = GeoProjection.Equirectangular,
            extent = ProjectedBounds(-1.0, -1.0, 1.0, 1.0),
        )

        assertFalse(empty.isDrawable)
        assertFalse(empty.screenOf(ProjectedPoint(0.0, 0.0)).isFinite)
    }

    @Test
    fun aCoordinateRoundTripsThroughTheWholeTransform() {
        val fit = GeoCoordinates(
            plotArea = ChartRect.fromSize(300f, 300f),
            projection = GeoProjection.Mercator,
            extent = ProjectedBounds(-0.5, -0.5, 0.5, 0.5),
            zoom = 1.5f,
        )
        val original = GeoCoordinate(10.0, 20.0)

        val screen: ChartOffset = fit.screenOf(original)
        val back = fit.coordinateAt(screen)

        assertNotNull(back)
        assertEquals(original.longitude, back!!.longitude, 1e-3)
        assertEquals(original.latitude, back.latitude, 1e-3)
    }
}
