package io.devkit.chartkit

import io.devkit.chartkit.stats.HistogramBinner
import io.devkit.chartkit.stats.HistogramBins
import io.devkit.chartkit.stats.HistogramMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Binning: the part of a histogram where the interesting decisions are. */
class HistogramTest {

    private val simple = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0)

    @Test
    fun `a fixed count produces exactly that many bins`() {
        val bins = HistogramBinner.bin(simple, HistogramBins.Count(5))
        assertEquals(5, bins.size)
        assertEquals(10, bins.sumOf { it.count })
    }

    @Test
    fun `bins tile the range with no gaps and no overlap`() {
        val bins = HistogramBinner.bin(simple, HistogramBins.Count(4))
        bins.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start, 1e-9) }
        assertEquals(1.0, bins.first().start, 1e-9)
        assertEquals(10.0, bins.last().end, 1e-9)
    }

    @Test
    fun `the largest observation lands in the final bin rather than outside every one`() {
        val bins = HistogramBinner.bin(simple, HistogramBins.Count(4))
        assertEquals(10, bins.sumOf { it.count })
        assertTrue(bins.last().count >= 1)
    }

    @Test
    fun `a fixed width aligns boundaries to multiples of it`() {
        val bins = HistogramBinner.bin(listOf(37.0, 88.0, 140.0), HistogramBins.Width(50.0))
        assertEquals(0.0, bins.first().start, 1e-9)
        bins.forEach { assertEquals(50.0, it.width, 1e-9) }
    }

    @Test
    fun `custom boundaries are used exactly, unequal widths included`() {
        val bins = HistogramBinner.bin(
            listOf(10.0, 30.0, 120.0, 500.0),
            HistogramBins.Custom(listOf(0.0, 50.0, 200.0, 1000.0)),
        )
        assertEquals(3, bins.size)
        assertEquals(listOf(2, 1, 1), bins.map { it.count })
    }

    @Test
    fun `automatic binning picks a sensible count and never zero`() {
        assertTrue(HistogramBinner.suggestedBinCount(simple) in 2..20)
        assertEquals(1, HistogramBinner.suggestedBinCount(listOf(4.0)))
        assertEquals(1, HistogramBinner.suggestedBinCount(List(50) { 3.0 }))
    }

    @Test
    fun `a constant dataset yields one bin around its value`() {
        val bins = HistogramBinner.bin(List(20) { 5.0 })
        assertEquals(1, bins.size)
        assertEquals(20, bins.first().count)
        assertTrue(bins.first().width > 0.0)
    }

    @Test
    fun `a single observation still produces a drawable bin`() {
        val bins = HistogramBinner.bin(listOf(42.0))
        assertEquals(1, bins.size)
        assertTrue(bins.first().width > 0.0)
    }

    @Test
    fun `an empty or entirely non-finite dataset produces no bins`() {
        assertTrue(HistogramBinner.bin(emptyList()).isEmpty())
        assertTrue(HistogramBinner.bin(listOf(null, Double.NaN, Double.POSITIVE_INFINITY)).isEmpty())
    }

    @Test
    fun `negative ranges are binned like any other`() {
        val bins = HistogramBinner.bin(listOf(-10.0, -5.0, 0.0, 5.0), HistogramBins.Count(4))
        assertEquals(4, bins.size)
        assertEquals(4, bins.sumOf { it.count })
        assertEquals(-10.0, bins.first().start, 1e-9)
    }

    @Test
    fun `the percentage metric sums to one`() {
        val bins = HistogramBinner.bin(simple, HistogramBins.Count(5), HistogramMetric.Percentage)
        assertEquals(1.0, bins.sumOf { it.value }, 1e-9)
    }

    @Test
    fun `the density metric divides the share by the bin width`() {
        val bins = HistogramBinner.bin(
            listOf(10.0, 30.0, 120.0, 500.0),
            HistogramBins.Custom(listOf(0.0, 50.0, 200.0, 1000.0)),
            HistogramMetric.Density,
        )
        // Two of four observations in a 50-wide bin: 0.5 / 50.
        assertEquals(0.01, bins.first().value, 1e-9)
        // The wide bin holds one of four across 800 units, and is therefore the
        // *least* dense despite being no shorter than its neighbour by count.
        assertTrue(bins.last().value < bins.first().value)
    }

    @Test
    fun `bins carry the source positions of the observations in them`() {
        val bins = HistogramBinner.bin(
            listOf(1.0, 9.0, 2.0),
            HistogramBins.Custom(listOf(0.0, 5.0, 10.0)),
        )
        assertEquals(listOf(0, 2), bins.first().sourceIndices)
        assertEquals(listOf(1), bins.last().sourceIndices)
    }

    @Test
    fun `bin lookup is half-open upward and closed at the very top`() {
        val boundaries = listOf(0.0, 10.0, 20.0)
        assertEquals(0, HistogramBinner.binIndexOf(0.0, boundaries))
        assertEquals(0, HistogramBinner.binIndexOf(9.999, boundaries))
        assertEquals(1, HistogramBinner.binIndexOf(10.0, boundaries))
        assertEquals(1, HistogramBinner.binIndexOf(20.0, boundaries))
        assertEquals(-1, HistogramBinner.binIndexOf(-0.001, boundaries))
        assertEquals(-1, HistogramBinner.binIndexOf(20.001, boundaries))
    }

    @Test
    fun `an automatic bin count is capped`() {
        val bins = HistogramBinner.bin(
            (0 until 5000).map { it.toDouble() * 1e-6 },
            HistogramBins.Count(100_000),
        )
        assertTrue(bins.size <= HistogramBinner.MAX_AUTO_BINS)
    }
}
