package io.devkit.chartkit

import io.devkit.chartkit.scale.AxisScale
import io.devkit.chartkit.scale.LinearScale
import io.devkit.chartkit.scale.LogScale
import io.devkit.chartkit.scale.LogTransform
import io.devkit.chartkit.scale.LogValuePolicy
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.scale.SymlogScale
import io.devkit.chartkit.scale.SymlogTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.E
import kotlin.math.abs

/**
 * Logarithmic and symmetric-log axes.
 *
 * The properties that matter to a reader: equal pixel distances are equal
 * ratios, the mapping inverts, the ticks land on powers, and the values a log
 * axis cannot represent behave the way the policy says rather than becoming
 * `NaN` inside a draw call.
 */
class LogScaleTest {

    private val domain = NumericDomain(1.0, 1000.0)
    private val scale = LogScale(domain, rangeStart = 0f, rangeEnd = 300f)

    @Test
    fun `each decade occupies the same distance`() {
        val decades = listOf(1.0, 10.0, 100.0, 1000.0).map { scale.scale(it) }
        val gaps = decades.zipWithNext { a, b -> b - a }
        gaps.forEach { assertEquals(100f, it, 0.01f) }
    }

    @Test
    fun `the ends map to the ends of the range`() {
        assertEquals(0f, scale.scale(1.0), 0.01f)
        assertEquals(300f, scale.scale(1000.0), 0.01f)
    }

    @Test
    fun `the mapping inverts`() {
        listOf(1.0, 3.0, 42.0, 500.0, 1000.0).forEach { value ->
            assertEquals(value, scale.invert(scale.scale(value)), value * 1e-4)
        }
    }

    @Test
    fun `base two divides the axis into octaves`() {
        val octaves = LogScale(NumericDomain(1.0, 8.0), 0f, 300f, base = 2.0)
        val positions = listOf(1.0, 2.0, 4.0, 8.0).map { octaves.scale(it) }
        positions.zipWithNext { a, b -> assertEquals(100f, b - a, 0.01f) }
    }

    @Test
    fun `a natural-log axis is available through the base`() {
        val natural = LogTransform(base = E)
        assertEquals(1.0, natural.forward(E), 1e-9)
        assertEquals(E, natural.inverse(1.0), 1e-9)
    }

    @Test
    fun `ticks are powers of the base`() {
        val ticks = LogTransform(10.0).ticks(domain, count = 5)
        assertTrue(ticks.containsAll(listOf(1.0, 10.0, 100.0, 1000.0)))
    }

    @Test
    fun `a single decade is subdivided so the axis has more than two labels`() {
        val ticks = LogTransform(10.0).ticks(NumericDomain(1.0, 10.0), count = 6)
        assertTrue("got $ticks", ticks.size >= 4)
        assertTrue(ticks.contains(1.0))
        assertTrue(ticks.contains(10.0))
    }

    @Test
    fun `a very wide axis strides decades rather than labelling forty of them`() {
        val ticks = LogTransform(10.0).ticks(NumericDomain(1.0, 1e12), count = 5)
        assertTrue("got ${ticks.size} ticks", ticks.size <= 8)
    }

    @Test
    fun `zero is clamped to the axis floor by default`() {
        // `log(0)` is negative infinity, which has no pixel. Clamping keeps the
        // point on the chart at the cost of drawing it at the axis' start.
        assertEquals(0f, scale.scale(0.0), 0.01f)
    }

    @Test
    fun `zero produces no position under the skip policy`() {
        val skipping = LogScale(domain, 0f, 300f, policy = LogValuePolicy.Skip)
        assertTrue(skipping.scale(0.0).isNaN())
        assertTrue(skipping.scale(-5.0).isNaN())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero can be rejected outright`() {
        LogScale(domain, 0f, 300f, policy = LogValuePolicy.Reject).scale(0.0)
    }

    @Test
    fun `a domain including zero is lifted to something a log axis can map`() {
        val lifted = LogTransform(10.0).constrainDomain(NumericDomain(0.0, 1000.0))
        assertTrue(lifted.min > 0.0)
        assertEquals(1000.0, lifted.max, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a base of one is rejected`() {
        LogTransform(base = 1.0)
    }

    @Test
    fun `an axis declares its own scale kind`() {
        assertTrue(AxisScale.Log().transform() is LogTransform)
        assertTrue(AxisScale.Symlog().transform() is SymlogTransform)
    }
}

/** Symmetric-log: logarithmic in the tails, linear across zero. */
class SymlogScaleTest {

    private val transform = SymlogTransform(linearThreshold = 1.0, base = 10.0)
    private val scale = SymlogScale(NumericDomain(-1000.0, 1000.0), 0f, 400f)

    @Test
    fun `zero maps to zero in transformed space`() {
        assertEquals(0.0, transform.forward(0.0), 1e-12)
    }

    @Test
    fun `the mapping is symmetric about zero`() {
        assertEquals(-transform.forward(250.0), transform.forward(-250.0), 1e-12)
        assertEquals(200f, scale.scale(0.0), 0.01f)
    }

    @Test
    fun `inside the linear region the mapping is a straight line`() {
        val quarter = transform.forward(0.25)
        val half = transform.forward(0.5)
        assertEquals(2.0, half / quarter, 1e-9)
    }

    @Test
    fun `the two halves agree at the threshold`() {
        // Continuity at ±threshold is what stops a visible kink appearing there.
        val below = transform.forward(0.999999)
        val above = transform.forward(1.000001)
        assertTrue(abs(above - below) < 1e-5)
    }

    @Test
    fun `outside the linear region each decade is a fixed distance`() {
        val decades = listOf(1.0, 10.0, 100.0, 1000.0).map { transform.forward(it) }
        decades.zipWithNext { a, b -> assertEquals(1.0, b - a, 1e-9) }
    }

    @Test
    fun `the mapping inverts across the whole range`() {
        listOf(-1000.0, -12.0, -0.4, 0.0, 0.4, 12.0, 1000.0).forEach { value ->
            assertEquals(value, transform.inverse(transform.forward(value)), 1e-6)
        }
    }

    @Test
    fun `negative values have real positions`() {
        assertTrue(scale.scale(-500.0).isFinite())
        assertTrue(scale.scale(-500.0) < scale.scale(0.0))
    }

    @Test
    fun `ticks always include zero`() {
        val ticks = transform.ticks(NumericDomain(-1000.0, 1000.0), count = 7)
        assertTrue(ticks.contains(0.0))
    }

    @Test
    fun `ticks reach into both tails`() {
        val ticks = transform.ticks(NumericDomain(-1000.0, 1000.0), count = 7)
        assertTrue(ticks.any { it < -1.0 })
        assertTrue(ticks.any { it > 1.0 })
    }

    @Test
    fun `a domain entirely inside the linear region is labelled with round numbers`() {
        val ticks = transform.ticks(NumericDomain(-0.5, 0.5), count = 5)
        assertTrue("got $ticks", ticks.size >= 3)
        assertTrue(ticks.contains(0.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive threshold is rejected`() {
        SymlogTransform(linearThreshold = 0.0)
    }

    @Test
    fun `a linear scale is unchanged by the transform machinery`() {
        // The regression that matters: everything that already worked must keep
        // working when the identity transform is in play.
        val linear = LinearScale(NumericDomain(0.0, 100.0), 0f, 200f)
        assertEquals(0f, linear.scale(0.0), 1e-4f)
        assertEquals(100f, linear.scale(50.0), 1e-4f)
        assertEquals(200f, linear.scale(100.0), 1e-4f)
        assertEquals(50.0, linear.invert(100f), 1e-9)
    }
}
