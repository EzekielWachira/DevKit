package io.devkit.chartkit

import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.scale.TickGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Ticks are asserted on the properties that make an axis readable — round
 * values, uniform spacing, inside the domain — rather than on an exact list,
 * which would pin the implementation without saying anything about the output.
 */
class TickGeneratorTest {

    @Test
    fun `produces round numbers for a round domain`() {
        val ticks = TickGenerator.ticks(NumericDomain(0.0, 100.0), 5)
        assertEquals(listOf(0.0, 20.0, 40.0, 60.0, 80.0, 100.0), ticks)
    }

    @Test
    fun `rounds an awkward domain to readable values`() {
        val ticks = TickGenerator.ticks(NumericDomain(0.0, 97.0), 5)
        assertUniformSpacing(ticks)
        // Every tick is a multiple of the step, and the step is round.
        val step = ticks[1] - ticks[0]
        assertTrue("step $step should be a round number", isRound(step))
    }

    @Test
    fun `handles a negative domain`() {
        val ticks = TickGenerator.ticks(NumericDomain(-100.0, -20.0), 5)
        assertTrue(ticks.isNotEmpty())
        assertTrue(ticks.all { it in -100.0..-20.0 })
        assertUniformSpacing(ticks)
    }

    @Test
    fun `handles a domain straddling zero and includes the baseline`() {
        val ticks = TickGenerator.ticks(NumericDomain(-40.0, 60.0), 5)
        assertTrue("a domain containing zero should tick at zero", ticks.any { abs(it) < 1e-9 })
    }

    @Test
    fun `does not emit negative zero`() {
        val ticks = TickGenerator.ticks(NumericDomain(-1.0, 1.0), 4)
        ticks.filter { abs(it) < 1e-9 }.forEach {
            assertEquals("0.0", it.toString())
        }
    }

    @Test
    fun `handles a small decimal range`() {
        val ticks = TickGenerator.ticks(NumericDomain(0.0, 0.05), 5)
        assertTrue(ticks.size >= 2)
        assertUniformSpacing(ticks)
        assertTrue(ticks.all { it in 0.0..0.05 })
    }

    @Test
    fun `handles very large values without losing precision`() {
        val ticks = TickGenerator.ticks(NumericDomain(0.0, 12_000_000_000.0), 4)
        assertUniformSpacing(ticks)
        assertTrue(ticks.all { it.isFinite() })
    }

    @Test
    fun `a constant domain still yields two distinct ticks`() {
        val ticks = TickGenerator.ticks(NumericDomain(5.0, 5.0), 5)
        assertTrue(ticks.size >= 2)
        assertTrue(ticks.first() < ticks.last())
    }

    @Test
    fun `tick count is respected approximately, never exactly promised`() {
        val ticks = TickGenerator.ticks(NumericDomain(0.0, 100.0), 3)
        // Three ticks over 0..100 would need a step of 50; four with a step of
        // 25 is rounder, and roundness wins. The contract is "about three".
        assertTrue("got ${ticks.size} ticks", ticks.size in 3..6)
    }

    @Test
    fun `an absurd tick count is clamped rather than looping`() {
        val ticks = TickGenerator.ticks(NumericDomain(0.0, 10.0), Int.MAX_VALUE)
        assertTrue("got ${ticks.size} ticks", ticks.size < 200)
    }

    @Test
    fun `ticks never leave the domain`() {
        val domain = NumericDomain(3.0, 97.0)
        TickGenerator.ticks(domain, 5).forEach {
            assertTrue("$it outside $domain", it >= domain.min - 1e-9 && it <= domain.max + 1e-9)
        }
    }

    @Test
    fun `niceDomain rounds outward`() {
        val nice = TickGenerator.niceDomain(NumericDomain(3.0, 97.0), 5)
        assertTrue(nice.min <= 3.0)
        assertTrue(nice.max >= 97.0)
    }

    @Test
    fun `decimal suggestion follows the step, not the values`() {
        assertEquals(0, TickGenerator.suggestedDecimals(listOf(0.0, 20.0, 40.0)))
        assertEquals(1, TickGenerator.suggestedDecimals(listOf(0.0, 0.5, 1.0)))
        assertEquals(2, TickGenerator.suggestedDecimals(listOf(0.0, 0.05, 0.10)))
    }

    private fun assertUniformSpacing(ticks: List<Double>) {
        assertTrue("need at least two ticks, got $ticks", ticks.size >= 2)
        val step = ticks[1] - ticks[0]
        for (index in 2 until ticks.size) {
            assertEquals(
                "ticks $ticks are not evenly spaced",
                step,
                ticks[index] - ticks[index - 1],
                abs(step) * 1e-6,
            )
        }
    }

    /** True when the value is 1, 2, 5 or 10 times a power of ten. */
    private fun isRound(step: Double): Boolean {
        var normalized = abs(step)
        while (normalized >= 10.0) normalized /= 10.0
        while (normalized < 1.0 && normalized > 0.0) normalized *= 10.0
        return listOf(1.0, 2.0, 5.0).any { abs(normalized - it) < 1e-6 }
    }
}
