package io.devkit.chartkit

import io.devkit.chartkit.gauge.GaugeException
import io.devkit.chartkit.gauge.GaugeGeometry
import io.devkit.chartkit.gauge.GaugeMarkerShape
import io.devkit.chartkit.gauge.GaugeNeedleShape
import io.devkit.chartkit.gauge.GaugeOverflow
import io.devkit.chartkit.gauge.GaugeScale
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.geometry.PolarDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The value-to-angle mapping every part of a dial derives from.
 *
 * Pure arithmetic, so it is checked here rather than by looking at a rendered
 * gauge: a needle one degree out is invisible in a screenshot and obvious in a
 * number.
 */
class GaugeScaleTest {

    /** The reference speedometer: 0–200 over a half circle opening upward. */
    private val speedo = GaugeScale.between(0.0, 200.0, startAngle = -90f, endAngle = 90f)

    @Test
    fun `the minimum sits at the start angle and the maximum at the end`() {
        assertEquals(-90f, speedo.angleOf(0.0), 0.001f)
        assertEquals(90f, speedo.angleOf(200.0), 0.001f)
    }

    @Test
    fun `the midpoint sits halfway round the sweep`() {
        assertEquals(0f, speedo.angleOf(100.0), 0.001f)
        assertEquals(-45f, speedo.angleOf(50.0), 0.001f)
        assertEquals(45f, speedo.angleOf(150.0), 0.001f)
    }

    @Test
    fun `angles convert back to the values they came from`() {
        listOf(0.0, 25.0, 100.0, 175.0, 200.0).forEach { value ->
            assertEquals(value, speedo.valueAt(speedo.angleOf(value)), 0.001)
        }
    }

    @Test
    fun `a negative domain maps like any other`() {
        val scale = GaugeScale.between(-50.0, 50.0, startAngle = -90f, endAngle = 90f)
        assertEquals(-90f, scale.angleOf(-50.0), 0.001f)
        assertEquals(0f, scale.angleOf(0.0), 0.001f)
        assertEquals(90f, scale.angleOf(50.0), 0.001f)
    }

    @Test
    fun `a domain that does not include zero maps like any other`() {
        val scale = GaugeScale.between(900.0, 1100.0, startAngle = -90f, endAngle = 90f)
        assertEquals(0f, scale.angleOf(1000.0), 0.001f)
        assertEquals(1000.0, scale.valueAt(0f), 0.001)
    }

    @Test
    fun `a three-quarter sweep spans its own range`() {
        val scale = GaugeScale(0.0, 100.0, startAngle = 225f, sweepAngle = 270f)
        assertEquals(225f, scale.angleOf(0.0), 0.001f)
        assertEquals(360f, scale.angleOf(50.0), 0.001f)
        assertEquals(495f, scale.angleOf(100.0), 0.001f)
        // Normalised, the end lands at 135° — the mirror of the 225° start.
        assertEquals(135f, scale.endAngle, 0.001f)
    }

    @Test
    fun `a full circle ends where it started`() {
        val scale = GaugeScale(0.0, 360.0, startAngle = 0f, sweepAngle = 360f)
        assertEquals(0f, scale.angleOf(0.0), 0.001f)
        assertEquals(180f, scale.angleOf(180.0), 0.001f)
        assertEquals(0f, scale.endAngle, 0.001f)
    }

    @Test
    fun `counter-clockwise reverses the sweep without moving the start`() {
        val scale = GaugeScale(
            0.0, 100.0,
            startAngle = 90f,
            sweepAngle = 180f,
            direction = PolarDirection.CounterClockwise,
        )
        assertEquals(90f, scale.angleOf(0.0), 0.001f)
        assertEquals(0f, scale.angleOf(50.0), 0.001f)
        assertEquals(-90f, scale.angleOf(100.0), 0.001f)
        assertEquals(50.0, scale.valueAt(0f), 0.001)
    }

    @Test
    fun `between reads an end angle rather than a sweep`() {
        val scale = GaugeScale.between(0.0, 10.0, startAngle = -135f, endAngle = 135f)
        assertEquals(270f, scale.sweepAngle, 0.001f)
    }

    @Test
    fun `between reads equal angles as a full turn`() {
        val scale = GaugeScale.between(0.0, 10.0, startAngle = 0f, endAngle = 0f)
        assertEquals(360f, scale.sweepAngle, 0.001f)
    }

    // ---- clamping and overflow -------------------------------------------

    @Test
    fun `clamping pins the drawn position without touching the value`() {
        assertEquals(1.0, speedo.fractionOf(250.0), 0.0001)
        assertEquals(0.0, speedo.fractionOf(-40.0), 0.0001)
        assertEquals(90f, speedo.angleOf(250.0), 0.001f)
        // The scale reports where to draw; it never rewrites what was measured.
        assertFalse(250.0 in speedo)
    }

    @Test
    fun `overflow lets the needle leave the arc when asked`() {
        val scale = GaugeScale.between(
            0.0, 200.0, startAngle = -90f, endAngle = 90f,
            overflow = GaugeOverflow.AllowOverflow,
        )
        assertEquals(1.25, scale.fractionOf(250.0), 0.0001)
        assertEquals(135f, scale.angleOf(250.0), 0.001f)
    }

    @Test
    fun `reject refuses a reading outside the range`() {
        val scale = GaugeScale.between(
            0.0, 200.0, startAngle = -90f, endAngle = 90f,
            overflow = GaugeOverflow.Reject,
        )
        assertEquals(0.5, scale.fractionOf(100.0), 0.0001)
        assertTrue(runCatching { scale.fractionOf(250.0) }.exceptionOrNull() is GaugeException)
    }

    @Test
    fun `a non-finite reading draws at the start rather than at NaN`() {
        assertEquals(0.0, speedo.fractionOf(Double.NaN), 0.0001)
        assertEquals(0.0, speedo.fractionOf(Double.POSITIVE_INFINITY), 0.0001)
    }

    // ---- invalid domains --------------------------------------------------

    @Test
    fun `a zero-width range is refused`() {
        val error = runCatching { GaugeScale(5.0, 5.0, 0f, 180f) }.exceptionOrNull()
        assertTrue(error is GaugeException)
        assertTrue(error!!.message!!.contains("max > min"))
    }

    @Test
    fun `a backwards range is refused and says what to do instead`() {
        val error = runCatching { GaugeScale(100.0, 0.0, 0f, 180f) }.exceptionOrNull()
        assertTrue(error is GaugeException)
        assertTrue(error!!.message!!.contains("CounterClockwise"))
    }

    @Test
    fun `NaN and infinite bounds are refused`() {
        listOf(
            Double.NaN to 100.0,
            0.0 to Double.NaN,
            Double.NEGATIVE_INFINITY to 100.0,
            0.0 to Double.POSITIVE_INFINITY,
        ).forEach { (min, max) ->
            assertTrue(
                "[$min, $max] should be refused",
                runCatching { GaugeScale(min, max, 0f, 180f) }.exceptionOrNull() is GaugeException,
            )
        }
    }

    @Test
    fun `a sweep of zero or more than a full turn is refused`() {
        assertTrue(runCatching { GaugeScale(0.0, 1.0, 0f, 0f) }.exceptionOrNull() is GaugeException)
        assertTrue(runCatching { GaugeScale(0.0, 1.0, 0f, 400f) }.exceptionOrNull() is GaugeException)
    }

    // ---- reading an angle back -------------------------------------------

    @Test
    fun `an angle off the arc reads as no value`() {
        // Straight down, below a gauge that opens upward.
        assertNull(speedo.valueAtOrNull(180f))
        assertNull(speedo.valueAtOrNull(-135f))
    }

    @Test
    fun `an angle just past an end reads as that end`() {
        assertEquals(200.0, speedo.valueAtOrNull(95f, tolerance = 10f))
        assertEquals(0.0, speedo.valueAtOrNull(-95f, tolerance = 10f))
        // And without tolerance it is still off the arc.
        assertNull(speedo.valueAtOrNull(95f))
    }

    @Test
    fun `an angle on the arc reads as its value`() {
        assertEquals(100.0, speedo.valueAtOrNull(0f)!!, 0.001)
        assertEquals(50.0, speedo.valueAtOrNull(-45f)!!, 0.001)
    }

    @Test
    fun `a full circle accepts every angle`() {
        val scale = GaugeScale(0.0, 360.0, startAngle = 0f, sweepAngle = 360f)
        listOf(0f, 90f, 180f, 270f, 359f).forEach { assertNotNull(scale.valueAtOrNull(it)) }
    }

    @Test
    fun `snapping rounds to a step from the minimum and stays in range`() {
        // 97/5 is 19.4, which rounds to 19 steps — 95, not 100.
        assertEquals(95.0, speedo.snap(97.0, step = 5.0), 0.001)
        assertEquals(100.0, speedo.snap(98.0, step = 5.0), 0.001)
        assertEquals(0.0, speedo.snap(-20.0, step = 5.0), 0.001)
        assertEquals(200.0, speedo.snap(260.0, step = 5.0), 0.001)
        // A step of zero is not a grid; the value is only brought into range.
        assertEquals(97.0, speedo.snap(97.0, step = 0.0), 0.001)
    }
}

/** Needle outlines, marker outlines, and how big an arc really is. */
class GaugeGeometryTest {

    private val origin = ChartOffset(0f, 0f)

    @Test
    fun `a full circle occupies its whole square`() {
        val bounds = GaugeGeometry.arcBounds(startAngle = 0f, sweepAngle = 360f, radius = 100f)
        assertEquals(-100f, bounds.left, 0.01f)
        assertEquals(-100f, bounds.top, 0.01f)
        assertEquals(100f, bounds.right, 0.01f)
        assertEquals(100f, bounds.bottom, 0.01f)
    }

    @Test
    fun `a semicircle opening upward occupies half the square`() {
        // -90° through 0° to 90°: left, top and right extremes, plus the pivot.
        val bounds = GaugeGeometry.arcBounds(startAngle = -90f, sweepAngle = 180f, radius = 100f)
        assertEquals(-100f, bounds.left, 0.01f)
        assertEquals(-100f, bounds.top, 0.01f)
        assertEquals(100f, bounds.right, 0.01f)
        // Nothing is drawn below the pivot, so the box stops there.
        assertEquals(0f, bounds.bottom, 0.01f)
        assertEquals(200f, bounds.width, 0.01f)
        assertEquals(100f, bounds.height, 0.01f)
    }

    @Test
    fun `a three-quarter dial keeps the gap at the bottom`() {
        val bounds = GaugeGeometry.arcBounds(startAngle = 225f, sweepAngle = 270f, radius = 100f)
        assertEquals(-100f, bounds.left, 0.01f)
        assertEquals(-100f, bounds.top, 0.01f)
        assertEquals(100f, bounds.right, 0.01f)
        // The arc reaches down to the diagonals but not to six o'clock.
        assertTrue(bounds.bottom < 100f)
        assertTrue(bounds.bottom > 0f)
    }

    @Test
    fun `an arc that crosses a compass point is not clipped to its endpoints`() {
        // -45° to 45° passes through twelve o'clock, which is its topmost point
        // and neither of its ends.
        val bounds = GaugeGeometry.arcBounds(startAngle = -45f, sweepAngle = 90f, radius = 100f)
        assertEquals(-100f, bounds.top, 0.01f)
    }

    @Test
    fun `excluding the centre narrows a shallow arc`() {
        val withCentre = GaugeGeometry.arcBounds(-20f, 40f, 100f, includeCenter = true)
        val without = GaugeGeometry.arcBounds(-20f, 40f, 100f, includeCenter = false)
        assertTrue(without.height < withCentre.height)
    }

    @Test
    fun `a semicircle in a wide box is sized by its width, not its height`() {
        // A 400x200 card: a full circle would be limited to r=100 by the height.
        // A semicircle's box is 2r wide and r tall, so both give r=200 here.
        val fit = GaugeGeometry.fit(
            bounds = ChartRect(0f, 0f, 400f, 200f),
            startAngle = -90f,
            sweepAngle = 180f,
        )
        assertEquals(200f, fit.radius, 0.01f)
        // And its pivot sits at the bottom of the box, where the dial's is.
        assertEquals(200f, fit.center.x, 0.01f)
        assertEquals(200f, fit.center.y, 0.01f)
    }

    @Test
    fun `a full circle is centred and sized by the shorter side`() {
        val fit = GaugeGeometry.fit(ChartRect(0f, 0f, 400f, 200f), startAngle = 0f, sweepAngle = 360f)
        assertEquals(100f, fit.radius, 0.01f)
        assertEquals(200f, fit.center.x, 0.01f)
        assertEquals(100f, fit.center.y, 0.01f)
    }

    @Test
    fun `a reserve for labels shrinks the radius, not the placement`() {
        val plain = GaugeGeometry.fit(ChartRect(0f, 0f, 400f, 400f), 0f, 360f)
        val reserved = GaugeGeometry.fit(ChartRect(0f, 0f, 400f, 400f), 0f, 360f, reserve = 20f)
        assertEquals(plain.radius - 20f, reserved.radius, 0.01f)
        assertEquals(plain.center.x, reserved.center.x, 0.01f)
    }

    @Test
    fun `an empty box produces no gauge rather than a negative radius`() {
        assertEquals(0f, GaugeGeometry.fit(ChartRect.Zero, -90f, 180f).radius, 0.001f)
        assertEquals(0f, GaugeGeometry.fit(ChartRect(0f, 0f, 10f, 10f), -90f, 180f, reserve = 40f).radius, 0.001f)
    }

    // ---- needles ----------------------------------------------------------

    @Test
    fun `a needle points at its angle`() {
        // Straight up: zero degrees in ChartKit's convention.
        val points = GaugeGeometry.needlePolygon(
            center = origin, angle = 0f, shape = GaugeNeedleShape.Needle,
            length = 100f, tail = 0f, baseWidth = 10f, tipWidth = 2f,
        )
        // A blade needle has a flat tip: two points, one either side of the
        // axis by half the tip width.
        val tips = points.filter { kotlin.math.abs(it.y + 100f) < 0.5f }
        assertEquals(2, tips.size)
        assertEquals(0f, tips.sumOf { it.x.toDouble() }.toFloat(), 0.5f)
        assertEquals(1f, kotlin.math.abs(tips.first().x), 0.5f)
    }

    @Test
    fun `a needle at ninety degrees points right`() {
        val points = GaugeGeometry.needlePolygon(
            center = origin, angle = 90f, shape = GaugeNeedleShape.Triangle,
            length = 100f, tail = 0f, baseWidth = 10f, tipWidth = 0f,
        )
        val tip = points.maxByOrNull { it.x }!!
        assertEquals(100f, tip.x, 0.5f)
        assertEquals(0f, tip.y, 0.5f)
    }

    @Test
    fun `a longer needle reaches further and keeps its base`() {
        fun tipDistance(length: Float): Float {
            val points = GaugeGeometry.needlePolygon(
                origin, 0f, GaugeNeedleShape.Needle, length, 0f, 10f, 2f,
            )
            return -points.minOf { it.y }
        }
        assertEquals(60f, tipDistance(60f), 0.5f)
        assertEquals(140f, tipDistance(140f), 0.5f)
    }

    @Test
    fun `a tail extends behind the pivot`() {
        val without = GaugeGeometry.needlePolygon(
            origin, 0f, GaugeNeedleShape.Needle, 100f, tail = 0f, baseWidth = 10f, tipWidth = 2f,
        )
        val with = GaugeGeometry.needlePolygon(
            origin, 0f, GaugeNeedleShape.Needle, 100f, tail = 20f, baseWidth = 10f, tipWidth = 2f,
        )
        // Up is negative y, so a tail on an upward needle reaches positive y.
        assertEquals(0f, without.maxOf { it.y }, 0.5f)
        assertEquals(20f, with.maxOf { it.y }, 0.5f)
    }

    @Test
    fun `a triangle tapers to a point and a needle does not`() {
        val triangle = GaugeGeometry.needlePolygon(
            origin, 0f, GaugeNeedleShape.Triangle, 100f, 0f, 10f, 0f,
        )
        val needle = GaugeGeometry.needlePolygon(
            origin, 0f, GaugeNeedleShape.Needle, 100f, 0f, 10f, 4f,
        )
        // One point at the tip against two.
        assertEquals(1, triangle.count { kotlin.math.abs(it.y + 100f) < 0.5f })
        assertEquals(2, needle.count { kotlin.math.abs(it.y + 100f) < 0.5f })
    }

    @Test
    fun `every needle shape produces a closable outline`() {
        GaugeNeedleShape.entries.forEach { shape ->
            val points = GaugeGeometry.needlePolygon(origin, 30f, shape, 100f, 10f, 8f, 3f)
            assertTrue("$shape produced ${points.size} points", points.size >= 3)
            assertTrue("$shape produced a non-finite point", points.all { it.x.isFinite() && it.y.isFinite() })
        }
    }

    @Test
    fun `a needle of no length draws nothing`() {
        assertTrue(GaugeGeometry.needlePolygon(origin, 0f, GaugeNeedleShape.Needle, 0f, 0f, 8f, 2f).isEmpty())
    }

    // ---- markers ----------------------------------------------------------

    @Test
    fun `a triangular marker points inward at the arc`() {
        val points = GaugeGeometry.markerPolygon(
            origin, angle = 0f, radius = 100f, shape = GaugeMarkerShape.Triangle, size = 12f,
        )
        assertEquals(3, points.size)
        // Up is negative y, so the apex on the arc is the *greater* y of the
        // two and the base outside it the lesser.
        assertEquals(-100f, points.maxOf { it.y }, 0.5f)
        assertEquals(-112f, points.minOf { it.y }, 0.5f)
    }

    @Test
    fun `a dot marker is a single point on the arc`() {
        val points = GaugeGeometry.markerPolygon(origin, 90f, 100f, GaugeMarkerShape.Dot, 10f)
        assertEquals(1, points.size)
        assertEquals(100f, points.first().x, 0.5f)
    }

    @Test
    fun `a marker of no size draws nothing`() {
        assertTrue(GaugeGeometry.markerPolygon(origin, 0f, 100f, GaugeMarkerShape.Triangle, 0f).isEmpty())
    }
}
