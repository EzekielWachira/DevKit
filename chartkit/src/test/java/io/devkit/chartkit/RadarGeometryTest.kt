package io.devkit.chartkit

import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.PolarGeometry
import io.devkit.chartkit.layer.polar.angleOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Spoke placement and the polar geometry a radar chart is built on. */
class RadarGeometryTest {

    @Test
    fun `spokes are evenly spaced from the start angle`() {
        assertEquals(0f, angleOf(0, 4, 0f), 1e-4f)
        assertEquals(90f, angleOf(1, 4, 0f), 1e-4f)
        assertEquals(180f, angleOf(2, 4, 0f), 1e-4f)
        assertEquals(270f, angleOf(3, 4, 0f), 1e-4f)
    }

    @Test
    fun `a start angle rotates every spoke`() {
        assertEquals(45f, angleOf(0, 4, 45f), 1e-4f)
        assertEquals(315f, angleOf(3, 4, 45f), 1e-4f)
    }

    @Test
    fun `five spokes divide the circle evenly and wrap`() {
        val angles = (0 until 5).map { angleOf(it, 5, 0f) }
        assertEquals(listOf(0f, 72f, 144f, 216f, 288f), angles)
    }

    @Test
    fun `a degenerate spoke count falls back to the start angle`() {
        assertEquals(30f, angleOf(0, 0, 30f), 1e-4f)
    }

    @Test
    fun `the first spoke points straight up`() {
        val centre = ChartOffset(100f, 100f)
        val point = PolarGeometry.pointOnCircle(centre, 50f, angleOf(0, 6, 0f))
        assertEquals(100f, point.x, 1e-3f)
        assertEquals(50f, point.y, 1e-3f)
    }

    @Test
    fun `a quarter turn clockwise points right`() {
        val centre = ChartOffset(0f, 0f)
        val point = PolarGeometry.pointOnCircle(centre, 10f, 90f)
        assertEquals(10f, point.x, 1e-3f)
        assertEquals(0f, point.y, 1e-3f)
    }

    @Test
    fun `every spoke's vertex is the same distance from the centre at full value`() {
        val centre = ChartOffset(0f, 0f)
        val radius = 40f
        (0 until 7).forEach { index ->
            val point = PolarGeometry.pointOnCircle(centre, radius, angleOf(index, 7, 0f))
            assertEquals(radius, PolarGeometry.radiusOf(centre, point), 1e-3f)
        }
    }

    @Test
    fun `a value halfway up its axis sits halfway out the spoke`() {
        // The layer maps `(value - min) / span` onto the radius; this is the
        // arithmetic that mapping relies on.
        val centre = ChartOffset(0f, 0f)
        val full = PolarGeometry.pointOnCircle(centre, 100f, 0f)
        val half = PolarGeometry.pointOnCircle(centre, 50f, 0f)
        assertEquals(2f, PolarGeometry.radiusOf(centre, full) / PolarGeometry.radiusOf(centre, half), 1e-3f)
    }

    @Test
    fun `the angle of a point round-trips through the spoke index`() {
        val centre = ChartOffset(20f, 20f)
        (0 until 6).forEach { index ->
            val angle = angleOf(index, 6, 0f)
            val point = PolarGeometry.pointOnCircle(centre, 30f, angle)
            assertTrue(abs(PolarGeometry.angleOf(centre, point) - angle) < 0.01f)
        }
    }
}
