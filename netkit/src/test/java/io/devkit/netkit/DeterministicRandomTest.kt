package io.devkit.netkit

import io.devkit.netkit.scenario.LatencyRange
import io.devkit.netkit.scenario.Probability
import io.devkit.netkit.scenario.random.RandomPurpose
import io.devkit.netkit.scenario.random.RunRandomSource
import io.devkit.netkit.scenario.random.SeedGenerator
import io.devkit.netkit.scenario.random.SeededNetKitRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The foundation everything else in 0.3 rests on.
 *
 * Every assertion here is exact rather than statistical. That is the point: a
 * test that asserts "roughly 30% of draws pass" is a test that fails on Tuesdays,
 * and the whole release exists to replace *roughly* with *exactly*.
 */
class DeterministicRandomTest {

    @Test
    fun `the same seed produces the same sequence`() {
        val first = SeededNetKitRandom(842_137_293)
        val second = SeededNetKitRandom(842_137_293)

        repeat(100) {
            assertEquals(first.nextLong(), second.nextLong())
        }
    }

    @Test
    fun `a different seed produces a different sequence`() {
        val first = SeededNetKitRandom(1)
        val second = SeededNetKitRandom(2)

        val a = List(20) { first.nextLong() }
        val b = List(20) { second.nextLong() }

        assertNotEquals(a, b)
    }

    @Test
    fun `doubles stay inside zero to one`() {
        val random = SeededNetKitRandom(99)
        repeat(2_000) {
            val value = random.nextDouble()
            assertTrue("$value out of range", value >= 0.0 && value < 1.0)
        }
    }

    @Test
    fun `ints stay inside the bound`() {
        val random = SeededNetKitRandom(7)
        repeat(2_000) {
            val value = random.nextInt(10)
            assertTrue("$value out of range", value in 0..9)
        }
    }

    @Test
    fun `longs stay inside the inclusive range`() {
        val random = SeededNetKitRandom(11)
        repeat(2_000) {
            val value = random.nextLong(500, 3_000)
            assertTrue("$value out of range", value in 500..3_000)
        }
    }

    @Test
    fun `a single-value range needs no draw`() {
        val random = SeededNetKitRandom(3)
        assertEquals(750L, random.nextLong(750, 750))
    }

    // ---- the derived-stream design ------------------------------------------

    /**
     * The property that makes concurrency survivable: evaluation #17 draws the
     * same values whatever order the other evaluations ran in.
     */
    @Test
    fun `a stream depends only on the seed, the index and the purpose`() {
        val source = RunRandomSource(842_137_293)

        val forward = (1L..50L).map {
            source.streamFor(it, RandomPurpose.PROBABILITY).nextDouble()
        }
        // Same indices, drawn in the opposite order — a stand-in for two threads
        // reaching the engine in a different sequence.
        val backward = (50L downTo 1L).map {
            source.streamFor(it, RandomPurpose.PROBABILITY).nextDouble()
        }.reversed()

        assertEquals(forward, backward)
    }

    @Test
    fun `purposes draw independently`() {
        val source = RunRandomSource(500)

        val probability = source.streamFor(1, RandomPurpose.PROBABILITY).nextDouble()
        val latency = source.streamFor(1, RandomPurpose.LATENCY).nextDouble()
        val outcome = source.streamFor(1, RandomPurpose.OUTCOME).nextDouble()

        assertNotEquals(probability, latency, 0.0)
        assertNotEquals(latency, outcome, 0.0)
        assertNotEquals(probability, outcome, 0.0)
    }

    /**
     * Two rules with identical probabilities must decide independently, or a
     * scenario with three 50% rules would fire all three or none.
     */
    @Test
    fun `keys draw independently within one evaluation`() {
        val source = RunRandomSource(321)

        val first = source.streamFor(4, RandomPurpose.PROBABILITY, "rule-a").nextDouble()
        val second = source.streamFor(4, RandomPurpose.PROBABILITY, "rule-b").nextDouble()

        assertNotEquals(first, second, 0.0)
    }

    @Test
    fun `a keyed stream is stable across sources with the same seed`() {
        val a = RunRandomSource(777).streamFor(9, RandomPurpose.OUTCOME, "rule-x").nextDouble()
        val b = RunRandomSource(777).streamFor(9, RandomPurpose.OUTCOME, "rule-x").nextDouble()

        // An exact delta: these must be bit-identical, not merely close.
        assertEquals(a, b, 0.0)
    }

    // ---- probability ---------------------------------------------------------

    @Test
    fun `zero probability never fires and needs no draw`() {
        val random = SeededNetKitRandom(1)
        repeat(200) { assertFalse(Probability.NEVER.draw(random)) }
        // The stream was never advanced, so a fresh generator agrees with it.
        assertEquals(SeededNetKitRandom(1).nextLong(), random.nextLong())
    }

    @Test
    fun `full probability always fires and needs no draw`() {
        val random = SeededNetKitRandom(1)
        repeat(200) { assertTrue(Probability.ALWAYS.draw(random)) }
        assertEquals(SeededNetKitRandom(1).nextLong(), random.nextLong())
    }

    @Test
    fun `an intermediate probability is deterministic for a seed`() {
        val chance = Probability(0.3)

        val first = (1L..40L).map {
            chance.draw(RunRandomSource(4242).streamFor(it, RandomPurpose.PROBABILITY))
        }
        val second = (1L..40L).map {
            chance.draw(RunRandomSource(4242).streamFor(it, RandomPurpose.PROBABILITY))
        }

        assertEquals(first, second)
        // Not a distribution assertion — just that 30% is doing something at all,
        // which would catch a gate wired to a constant.
        assertTrue(first.any { it })
        assertTrue(first.any { !it })
    }

    @Test
    fun `a different seed changes which requests pass the gate`() {
        val chance = Probability(0.3)
        val a = (1L..40L).map {
            chance.draw(RunRandomSource(1).streamFor(it, RandomPurpose.PROBABILITY))
        }
        val b = (1L..40L).map {
            chance.draw(RunRandomSource(2).streamFor(it, RandomPurpose.PROBABILITY))
        }

        assertNotEquals(a, b)
    }

    @Test
    fun `probability rejects values outside zero to one`() {
        listOf(-0.1, 1.1, Double.NaN).forEach { value ->
            try {
                Probability(value)
                error("Expected $value to be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("between 0.0 and 1.0"))
            }
        }
    }

    @Test
    fun `percentages round-trip`() {
        assertEquals(30, Probability.ofPercent(30).percent)
        assertEquals("30%", Probability.ofPercent(30).percentLabel)
        assertEquals(Probability.ALWAYS, Probability.ofPercent(100))
        assertEquals(Probability.NEVER, Probability.ofPercent(0))
    }

    // ---- latency -------------------------------------------------------------

    @Test
    fun `a latency range picks deterministically`() {
        val range = LatencyRange(500, 3_000)

        val first = (1L..30L).map { range.pick(RunRandomSource(9).streamFor(it, RandomPurpose.LATENCY)) }
        val second = (1L..30L).map { range.pick(RunRandomSource(9).streamFor(it, RandomPurpose.LATENCY)) }

        assertEquals(first, second)
        assertTrue(first.all { it in 500..3_000 })
        assertTrue("a range should vary", first.distinct().size > 1)
    }

    @Test
    fun `a fixed latency range never draws`() {
        val random = SeededNetKitRandom(5)
        assertEquals(2_500L, LatencyRange.fixed(2_500).pick(random))
        assertEquals(SeededNetKitRandom(5).nextLong(), random.nextLong())
    }

    @Test
    fun `an inverted latency range is rejected`() {
        try {
            LatencyRange(3_000, 500)
            error("Expected an inverted range to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("inverted"))
        }
    }

    @Test
    fun `a negative latency is rejected`() {
        try {
            LatencyRange(-1, 500)
            error("Expected a negative latency to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("negative"))
        }
    }

    // ---- seeds ----------------------------------------------------------------

    @Test
    fun `generated seeds stay in the readable range`() {
        repeat(200) {
            val seed = SeedGenerator.next()
            assertTrue("$seed out of range", seed in SeedGenerator.MIN..SeedGenerator.MAX)
        }
    }

    @Test
    fun `normalising is total, including for the extreme value`() {
        listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L, 12L).forEach { raw ->
            val seed = SeedGenerator.normalize(raw)
            assertTrue("$raw normalised to $seed", seed in SeedGenerator.MIN..SeedGenerator.MAX)
        }
    }

    @Test
    fun `seeds are parsed the way people paste them`() {
        assertEquals(843_921_773L, SeedGenerator.parse("843921773"))
        assertEquals(843_921_773L, SeedGenerator.parse("  843921773 "))
        assertEquals(843_921_773L, SeedGenerator.parse("Seed: 843921773"))
        assertEquals(843_921_773L, SeedGenerator.parse("843 921 773"))
        assertNull(SeedGenerator.parse(""))
        assertNull(SeedGenerator.parse("not a seed"))
    }
}
