package io.devkit.chartkit

import io.devkit.chartkit.geometry.AreaStackGeometry
import io.devkit.chartkit.geometry.AreaStacking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Where each series' band sits once the series are piled on one another. */
class AreaStackingTest {

    private val three = listOf(
        listOf(10.0, 20.0, 30.0),
        listOf(5.0, 5.0, 5.0),
        listOf(1.0, 2.0, 3.0),
    )

    private fun bounds(
        values: List<List<Double?>> = three,
        stacking: AreaStacking = AreaStacking.Stacked,
    ) = AreaStackGeometry.bounds(values, stacking)

    @Test
    fun `each series rests on the one below it`() {
        val result = bounds()

        assertEquals(0.0..10.0, result[0][0])
        assertEquals(10.0..15.0, result[1][0])
        assertEquals(15.0..16.0, result[2][0])
    }

    @Test
    fun `the top of the last band is the column's total`() {
        val result = bounds()
        (0..2).forEach { column ->
            val total = three.sumOf { it[column]!! }
            assertEquals(total, result.last()[column]!!.endInclusive, 1e-9)
        }
    }

    @Test
    fun `no gaps and no overlaps between neighbouring bands`() {
        // The property that makes a stacked area readable: the thickness of a
        // band is its value, and the bands tile the column exactly.
        val result = bounds()
        (0..2).forEach { column ->
            result.zipWithNext { lower, upper ->
                assertEquals(lower[column]!!.endInclusive, upper[column]!!.start, 1e-9)
            }
            result.forEachIndexed { series, bands ->
                val band = bands[column]!!
                assertEquals(three[series][column]!!, band.endInclusive - band.start, 1e-9)
            }
        }
    }

    @Test
    fun `None stacks nothing`() {
        assertTrue(bounds(stacking = AreaStacking.None).isEmpty())
        assertFalse(AreaStacking.None.isStacked)
    }

    @Test
    fun `Expand rescales every column to one`() {
        val result = bounds(stacking = AreaStacking.Expand)
        (0..2).forEach { column ->
            assertEquals(1.0, result.last()[column]!!.endInclusive, 1e-9)
            assertEquals(0.0, result.first()[column]!!.start, 1e-9)
        }
    }

    @Test
    fun `Expand hides the totals, which is the trade it makes`() {
        val doubled = three.map { series -> series.map { it!! * 2.0 } }
        val plain = bounds(three, AreaStacking.Expand)
        val scaled = bounds(doubled, AreaStacking.Expand)

        plain.indices.forEach { series ->
            plain[series].indices.forEach { column ->
                assertEquals(plain[series][column]!!.start, scaled[series][column]!!.start, 1e-9)
            }
        }
    }

    @Test
    fun `Stream centres each column on zero`() {
        val result = bounds(stacking = AreaStacking.Stream)
        (0..2).forEach { column ->
            val top = result.maxOf { it[column]!!.endInclusive }
            val bottom = result.minOf { it[column]!!.start }
            assertEquals(0.0, top + bottom, 1e-9)
        }
    }

    @Test
    fun `Stream moves the bands without changing their thickness`() {
        // The only quantity a stream graph claims to show.
        val stacked = bounds(stacking = AreaStacking.Stacked)
        val stream = bounds(stacking = AreaStacking.Stream)

        stacked.indices.forEach { series ->
            stacked[series].indices.forEach { column ->
                val before = stacked[series][column]!!
                val after = stream[series][column]!!
                assertEquals(
                    before.endInclusive - before.start,
                    after.endInclusive - after.start,
                    1e-9,
                )
            }
        }
    }

    @Test
    fun `a missing value leaves a hole rather than a zero-height band`() {
        // Missing is not zero, and a band of no height would read as a series
        // that was measured and found to be nothing.
        val withGap = listOf(
            listOf(10.0, null, 30.0),
            listOf(5.0, 5.0, 5.0),
        )
        val result = AreaStackGeometry.bounds(withGap, AreaStacking.Stacked)

        assertNull(result[0][1])
        assertNotNull(result[0][0])
    }

    @Test
    fun `the series above a missing one close over the space`() {
        // A stack has nothing else it can do with an absent part, and the
        // documentation says so rather than the behaviour being discovered.
        val withGap = listOf(
            listOf(10.0, null),
            listOf(5.0, 5.0),
        )
        val result = AreaStackGeometry.bounds(withGap, AreaStacking.Stacked)

        assertEquals(0.0..5.0, result[1][1])
    }

    @Test
    fun `negatives pile downward rather than cancelling`() {
        val mixed = listOf(
            listOf(3.0),
            listOf(-1.0),
            listOf(-2.0),
        )
        val result = AreaStackGeometry.bounds(mixed, AreaStacking.Stacked)

        assertEquals(0.0..3.0, result[0][0])
        assertEquals(-1.0..0.0, result[1][0])
        assertEquals(-3.0..-1.0, result[2][0])
    }

    @Test
    fun `the domain spans the whole pile, not the largest single value`() {
        // An axis fitted to the values alone would clip the stack it measures.
        val domain = AreaStackGeometry.domainOf(bounds())

        assertNotNull(domain)
        assertEquals(0.0, domain!!.min, 1e-9)
        assertEquals(38.0, domain.max, 1e-9)
    }

    @Test
    fun `the line is drawn on the side the series grew towards`() {
        val positive = 0.0..5.0
        assertEquals(5.0, AreaStackGeometry.lineEdge(positive, 5.0, AreaStacking.Stacked), 1e-9)
        assertEquals(0.0, AreaStackGeometry.fillEdge(positive, 5.0, AreaStacking.Stacked), 1e-9)

        val negative = -5.0..0.0
        assertEquals(-5.0, AreaStackGeometry.lineEdge(negative, -5.0, AreaStacking.Stacked), 1e-9)
        assertEquals(0.0, AreaStackGeometry.fillEdge(negative, -5.0, AreaStacking.Stacked), 1e-9)
    }

    @Test
    fun `a centred band is read from the top whatever its sign was`() {
        // Stream has already shifted the band off zero, so its sign no longer
        // says which way the series grew; reading every band from the top is
        // what keeps the layers looking stacked.
        val band = -4.0..2.0

        assertEquals(2.0, AreaStackGeometry.lineEdge(band, -6.0, AreaStacking.Stream), 1e-9)
        assertEquals(-4.0, AreaStackGeometry.fillEdge(band, -6.0, AreaStacking.Stream), 1e-9)
    }

    @Test
    fun `an empty input stacks to nothing rather than throwing`() {
        assertTrue(AreaStackGeometry.bounds(emptyList(), AreaStacking.Stacked).isEmpty())
        assertNull(AreaStackGeometry.domainOf(emptyList()))
    }

    @Test
    fun `a column every series is missing from does not poison the scale`() {
        val allMissing = listOf(
            listOf(10.0, null),
            listOf(5.0, null),
        )
        val result = AreaStackGeometry.bounds(allMissing, AreaStacking.Stream)

        assertNull(result[0][1])
        result[0][0]?.let { assertTrue(it.start.isFinite() && it.endInclusive.isFinite()) }
    }

    @Test
    fun `series of different lengths stack over the longest`() {
        val ragged = listOf(
            listOf(1.0, 2.0, 3.0),
            listOf(10.0),
        )
        val result = AreaStackGeometry.bounds(ragged, AreaStacking.Stacked)

        assertEquals(3, result[0].size)
        assertEquals(3, result[1].size)
        assertNull(result[1][1])
    }

    @Test
    fun `a non-finite value is treated as missing rather than drawn`() {
        val poisoned = listOf(
            listOf(10.0, Double.NaN),
            listOf(5.0, 5.0),
        )
        val result = AreaStackGeometry.bounds(poisoned, AreaStacking.Stacked)

        assertNull(result[0][1])
        assertTrue(result[1][1]!!.start.isFinite())
    }

    @Test
    fun `stacking is deterministic`() {
        val first = bounds(stacking = AreaStacking.Stream)
        val second = bounds(stacking = AreaStacking.Stream)

        first.indices.forEach { series ->
            first[series].indices.forEach { column ->
                assertTrue(
                    abs(first[series][column]!!.start - second[series][column]!!.start) < 1e-12,
                )
            }
        }
    }
}
