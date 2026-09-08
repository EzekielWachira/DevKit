package io.devkit.chartkit

import io.devkit.chartkit.coordinate.PolarCoordinates
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.geometry.PolarDirection
import io.devkit.chartkit.geometry.PolarGeometry
import io.devkit.chartkit.geometry.PolarValuePolicy
import io.devkit.chartkit.geometry.computePolarSlices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The angle convention is the thing most easily got wrong, so it is asserted
 * directly: zero at the top, increasing clockwise, and screen y growing down.
 */
class PolarAngleTest {

    private val center = ChartOffset(100f, 100f)

    @Test
    fun `zero degrees is at the top of the circle`() {
        val point = PolarGeometry.pointOnCircle(center, radius = 50f, angleDegrees = 0f)
        assertEquals(100f, point.x, TOLERANCE)
        assertEquals(50f, point.y, TOLERANCE)
    }

    @Test
    fun `ninety degrees is at three o'clock, because angles run clockwise`() {
        val point = PolarGeometry.pointOnCircle(center, radius = 50f, angleDegrees = 90f)
        assertEquals(150f, point.x, TOLERANCE)
        assertEquals(100f, point.y, TOLERANCE)
    }

    @Test
    fun `one hundred and eighty degrees is at the bottom`() {
        val point = PolarGeometry.pointOnCircle(center, radius = 50f, angleDegrees = 180f)
        assertEquals(100f, point.x, TOLERANCE)
        assertEquals(150f, point.y, TOLERANCE)
    }

    @Test
    fun `angleOf inverts pointOnCircle`() {
        listOf(0f, 37f, 90f, 180f, 271f, 359f).forEach { angle ->
            val point = PolarGeometry.pointOnCircle(center, 60f, angle)
            assertEquals(angle, PolarGeometry.angleOf(center, point), 0.01f)
        }
    }

    @Test
    fun `radiusOf inverts the radius`() {
        val point = PolarGeometry.pointOnCircle(center, 42f, 123f)
        assertEquals(42f, PolarGeometry.radiusOf(center, point), TOLERANCE)
    }

    @Test
    fun `the canvas conversion moves zero from three o'clock to twelve`() {
        assertEquals(-90f, PolarGeometry.toCanvasAngle(0f), TOLERANCE)
        assertEquals(0f, PolarGeometry.toCanvasAngle(90f), TOLERANCE)
    }

    @Test
    fun `angles normalise into zero until three hundred and sixty`() {
        assertEquals(10f, PolarGeometry.normalizeAngle(370f), TOLERANCE)
        assertEquals(350f, PolarGeometry.normalizeAngle(-10f), TOLERANCE)
        assertEquals(0f, PolarGeometry.normalizeAngle(720f), TOLERANCE)
        assertEquals(0f, PolarGeometry.normalizeAngle(Float.NaN), TOLERANCE)
    }

    @Test
    fun `containment handles wrap-around without a special case`() {
        // A slice from 350° sweeping 20° covers 350..360 and 0..10.
        assertTrue(PolarGeometry.isAngleWithin(355f, start = 350f, sweep = 20f))
        assertTrue(PolarGeometry.isAngleWithin(5f, start = 350f, sweep = 20f))
        assertFalse(PolarGeometry.isAngleWithin(20f, start = 350f, sweep = 20f))
    }

    @Test
    fun `containment respects the sweep direction`() {
        assertTrue(
            PolarGeometry.isAngleWithin(80f, 90f, 20f, PolarDirection.CounterClockwise),
        )
        assertFalse(
            PolarGeometry.isAngleWithin(100f, 90f, 20f, PolarDirection.CounterClockwise),
        )
    }

    @Test
    fun `a zero sweep contains nothing`() {
        assertFalse(PolarGeometry.isAngleWithin(0f, start = 0f, sweep = 0f))
    }

    @Test
    fun `a full sweep contains everything`() {
        assertTrue(PolarGeometry.isAngleWithin(200f, start = 0f, sweep = 360f))
    }

    @Test
    fun `the mid angle is halfway through the sweep`() {
        assertEquals(45f, PolarGeometry.midAngle(0f, 90f), TOLERANCE)
        assertEquals(315f, PolarGeometry.midAngle(0f, 90f, PolarDirection.CounterClockwise), TOLERANCE)
    }

    @Test
    fun `the anchor radius sits in the middle of the ring`() {
        assertEquals(75f, PolarGeometry.anchorRadius(50f, 100f), TOLERANCE)
        assertEquals(50f, PolarGeometry.anchorRadius(0f, 100f), TOLERANCE)
    }

    @Test
    fun `the radius fits the shorter side, so a pie is never an ellipse`() {
        assertEquals(50f, PolarGeometry.radiusWithin(ChartRect(0f, 0f, 400f, 100f)), TOLERANCE)
        assertEquals(40f, PolarGeometry.radiusWithin(ChartRect(0f, 0f, 100f, 100f), padding = 10f), TOLERANCE)
        assertEquals(0f, PolarGeometry.radiusWithin(ChartRect.Zero), TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.01f
    }
}

class PolarSliceTest {

    @Test
    fun `values are normalised, so they need not sum to anything`() {
        val slices = computePolarSlices(listOf(40.0, 30.0, 20.0, 10.0))
        assertEquals(listOf(0.4, 0.3, 0.2, 0.1), slices.map { it.fraction })
        assertEquals(144f, slices[0].sweepAngle, 0.01f)
    }

    @Test
    fun `the same proportions produce the same chart at any scale`() {
        val large = computePolarSlices(listOf(40.0, 60.0)).map { it.sweepAngle }
        val small = computePolarSlices(listOf(0.4, 0.6)).map { it.sweepAngle }
        assertEquals(large, small)
    }

    @Test
    fun `slices tile the circle without gaps by default`() {
        val slices = computePolarSlices(listOf(1.0, 2.0, 3.0))
        assertEquals(360f, slices.sumOf { it.sweepAngle.toDouble() }.toFloat(), 0.01f)
        // Each starts where the last ended.
        assertEquals(slices[0].sweepAngle, slices[1].startAngle, 0.01f)
    }

    @Test
    fun `a custom start angle rotates the whole chart`() {
        val slices = computePolarSlices(listOf(1.0, 1.0), startAngle = 90f)
        assertEquals(90f, slices[0].startAngle, 0.01f)
        assertEquals(270f, slices[1].startAngle, 0.01f)
    }

    @Test
    fun `a partial sweep scales every slice`() {
        val slices = computePolarSlices(listOf(1.0, 1.0), totalSweep = 180f)
        assertEquals(90f, slices[0].sweepAngle, 0.01f)
        assertEquals(90f, slices[1].sweepAngle, 0.01f)
    }

    @Test
    fun `counter-clockwise slices advance the other way`() {
        val slices = computePolarSlices(
            listOf(1.0, 1.0),
            direction = PolarDirection.CounterClockwise,
        )
        assertEquals(0f, slices[0].startAngle, 0.01f)
        assertEquals(180f, slices[1].startAngle, 0.01f)
    }

    @Test
    fun `a gap is taken out of each slice, so the circle still closes`() {
        val slices = computePolarSlices(listOf(1.0, 1.0, 1.0), gapDegrees = 6f)
        slices.forEach { assertEquals(114f, it.sweepAngle, 0.01f) }
        // Positions are unchanged: the gap shortens the arc, it does not move it.
        assertEquals(120f, slices[1].startAngle, 0.01f)
    }

    @Test
    fun `a gap wider than the slice does not invert it`() {
        val slices = computePolarSlices(listOf(1000.0, 0.1), gapDegrees = 20f)
        assertTrue("a sliver must not get a negative sweep", slices[1].sweepAngle >= 0f)
    }

    @Test
    fun `a single value fills the circle`() {
        val slices = computePolarSlices(listOf(7.0))
        assertEquals(1, slices.size)
        assertEquals(360f, slices.single().sweepAngle, 0.01f)
        assertEquals(1.0, slices.single().fraction, 1e-9)
    }

    @Test
    fun `an empty dataset produces no slices`() {
        assertTrue(computePolarSlices(emptyList()).isEmpty())
    }

    @Test
    fun `all zeros produce zero-sweep slices, not a division by zero`() {
        val slices = computePolarSlices(listOf(0.0, 0.0, 0.0))
        assertEquals(3, slices.size)
        slices.forEach {
            assertEquals(0f, it.sweepAngle, 0.001f)
            assertEquals(0.0, it.fraction, 1e-9)
        }
    }

    @Test
    fun `negative values are dropped rather than drawn as positive shares`() {
        val slices = computePolarSlices(listOf(40.0, -10.0, 60.0))
        assertEquals(0f, slices[1].sweepAngle, 0.001f)
        // The remaining two share the whole circle between them.
        assertEquals(0.4, slices[0].fraction, 1e-9)
        assertEquals(0.6, slices[2].fraction, 1e-9)
    }

    @Test
    fun `non-finite values are dropped`() {
        val slices = computePolarSlices(listOf(50.0, Double.NaN, Double.POSITIVE_INFINITY, 50.0))
        assertEquals(0.5, slices[0].fraction, 1e-9)
        assertEquals(0.0, slices[1].fraction, 1e-9)
        assertEquals(0.0, slices[2].fraction, 1e-9)
        slices.forEach { assertTrue(it.sweepAngle.isFinite()) }
    }

    @Test
    fun `a dropped value keeps its slot, so nothing is recoloured`() {
        val slices = computePolarSlices(listOf(1.0, -1.0, 1.0))
        assertEquals(listOf(0, 1, 2), slices.map { it.sourceIndex })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the reject policy throws on a negative value`() {
        computePolarSlices(listOf(1.0, -1.0), policy = PolarValuePolicy.Reject)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the reject policy throws on a non-finite value`() {
        computePolarSlices(listOf(1.0, Double.NaN), policy = PolarValuePolicy.Reject)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an impossible sweep is rejected`() {
        computePolarSlices(listOf(1.0), totalSweep = 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative gap is rejected`() {
        computePolarSlices(listOf(1.0), gapDegrees = -1f)
    }

    @Test
    fun `very small values still produce a slice with a real fraction`() {
        val slices = computePolarSlices(listOf(1_000_000.0, 0.001))
        assertTrue(slices[1].fraction > 0.0)
        assertTrue(slices[1].sweepAngle >= 0f)
    }
}

class PolarHitTestTest {

    private val center = ChartOffset(100f, 100f)
    private val slices = computePolarSlices(listOf(25.0, 25.0, 25.0, 25.0))

    private fun hit(angle: Float, radius: Float) = PolarGeometry.hitTestSlices(
        slices = slices,
        center = center,
        point = PolarGeometry.pointOnCircle(center, radius, angle),
        innerRadius = 0f,
        outerRadius = 80f,
    )

    @Test
    fun `an angle picks its quarter`() {
        assertEquals(0, hit(45f, 40f))
        assertEquals(1, hit(135f, 40f))
        assertEquals(2, hit(225f, 40f))
        assertEquals(3, hit(315f, 40f))
    }

    @Test
    fun `a point beyond the outer radius hits nothing`() {
        assertEquals(-1, hit(45f, 200f))
    }

    @Test
    fun `a donut hole belongs to no slice`() {
        val index = PolarGeometry.hitTestSlices(
            slices = slices,
            center = center,
            point = PolarGeometry.pointOnCircle(center, 20f, 45f),
            innerRadius = 40f,
            outerRadius = 80f,
        )
        assertEquals(-1, index)
    }

    @Test
    fun `a point on the ring of a donut hits its slice`() {
        val index = PolarGeometry.hitTestSlices(
            slices = slices,
            center = center,
            point = PolarGeometry.pointOnCircle(center, 60f, 200f),
            innerRadius = 40f,
            outerRadius = 80f,
        )
        assertEquals(2, index)
    }

    @Test
    fun `the exact centre belongs to no slice of a donut`() {
        val index = PolarGeometry.hitTestSlices(
            slices = slices,
            center = center,
            point = center,
            innerRadius = 30f,
            outerRadius = 80f,
        )
        assertEquals(-1, index)
    }

    @Test
    fun `a gap between slices matches nothing`() {
        val gapped = computePolarSlices(listOf(1.0, 1.0), gapDegrees = 20f)
        // The first slice draws 0..160; 170° falls in its gap.
        val index = PolarGeometry.hitTestSlices(
            slices = gapped,
            center = center,
            point = PolarGeometry.pointOnCircle(center, 40f, 170f),
            innerRadius = 0f,
            outerRadius = 80f,
        )
        assertEquals(-1, index)
    }

    @Test
    fun `a zero-sweep slice is never hit`() {
        val withZero = computePolarSlices(listOf(0.0, 100.0))
        val index = PolarGeometry.hitTestSlices(
            slices = withZero,
            center = center,
            point = PolarGeometry.pointOnCircle(center, 40f, 0.1f),
            innerRadius = 0f,
            outerRadius = 80f,
        )
        assertEquals(1, index)
    }
}

class PolarCoordinatesTest {

    private val plot = ChartRect(0f, 0f, 200f, 200f)

    private fun coordinates(inner: Float = 0f) = PolarCoordinates(
        plotArea = plot,
        center = ChartOffset(100f, 100f),
        innerRadius = inner,
        outerRadius = 80f,
    )

    @Test
    fun `a ring knows its own thickness`() {
        assertEquals(80f, coordinates().ringThickness, 0.01f)
        assertEquals(30f, coordinates(inner = 50f).ringThickness, 0.01f)
    }

    @Test
    fun `a fraction of the ring maps between the radii`() {
        val donut = coordinates(inner = 40f)
        val middle = donut.pointAtFraction(0f, 0.5f)
        assertEquals(100f, middle.x, 0.01f)
        assertEquals(40f, middle.y, 0.01f)
    }

    @Test
    fun `containment in the ring excludes the hole`() {
        val donut = coordinates(inner = 40f)
        assertFalse(donut.containsInRing(ChartOffset(100f, 100f)))
        assertTrue(donut.containsInRing(ChartOffset(100f, 40f)))
        assertFalse(donut.containsInRing(ChartOffset(100f, 0f)))
    }

    @Test
    fun `a zero-size plot is not drawable`() {
        val empty = PolarCoordinates(
            plotArea = ChartRect.Zero,
            center = ChartOffset.Zero,
            innerRadius = 0f,
            outerRadius = 0f,
        )
        assertFalse(empty.isDrawable)
        assertNotNull(empty.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an inner radius larger than the outer one is rejected`() {
        PolarCoordinates(plot, ChartOffset(100f, 100f), innerRadius = 90f, outerRadius = 80f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative inner radius is rejected`() {
        PolarCoordinates(plot, ChartOffset(100f, 100f), innerRadius = -1f, outerRadius = 80f)
    }
}
