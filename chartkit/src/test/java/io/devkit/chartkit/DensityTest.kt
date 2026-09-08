package io.devkit.chartkit

import io.devkit.chartkit.stats.ChartStatistics
import io.devkit.chartkit.stats.DensityEstimator
import io.devkit.chartkit.stats.KernelBandwidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Kernel density estimation.
 *
 * Deliberately not asserting exact densities against a reference
 * implementation: the properties that matter for a violin plot are that the
 * curve is finite, non-negative, integrates to about one, peaks where the data
 * is, and degrades honestly when there is no distribution to estimate.
 */
class DensityTest {

    @Test
    fun `the curve is finite and never negative`() {
        val random = Random(7)
        val curve = DensityEstimator.estimate(List(100) { random.nextDouble() * 10 })
        assertTrue(curve.positions.all { it.isFinite() })
        assertTrue(curve.densities.all { it.isFinite() && it >= 0.0 })
    }

    @Test
    fun `the curve integrates to about one`() {
        val random = Random(11)
        val samples = List(500) { random.nextDouble() * 100 }
        val curve = DensityEstimator.estimate(samples, resolution = 256)
        var area = 0.0
        for (index in 1 until curve.size) {
            val width = curve.positions[index] - curve.positions[index - 1]
            area += width * (curve.densities[index] + curve.densities[index - 1]) / 2.0
        }
        // The kernel is truncated at four standard deviations, so a little mass
        // is lost; a couple of percent is the expected shortfall.
        assertEquals(1.0, area, 0.05)
    }

    @Test
    fun `the peak sits near a tight cluster`() {
        val samples = List(200) { 50.0 } + List(10) { 5.0 }
        val curve = DensityEstimator.estimate(samples)
        val peakIndex = curve.densities.indices.maxByOrNull { curve.densities[it] }!!
        assertTrue(abs(curve.positions[peakIndex] - 50.0) < 5.0)
    }

    @Test
    fun `a constant sample is reported as degenerate rather than as a flat curve`() {
        val curve = DensityEstimator.estimate(List(30) { 7.0 })
        assertTrue(curve.isDegenerate)
        assertEquals(1, curve.size)
        assertEquals(7.0, curve.positions.first(), 1e-9)
    }

    @Test
    fun `a single observation is degenerate too`() {
        val curve = DensityEstimator.estimate(listOf(3.0))
        assertTrue(curve.isDegenerate)
        assertEquals(1, curve.sampleCount)
    }

    @Test
    fun `an empty sample yields the empty curve`() {
        assertTrue(DensityEstimator.estimate(emptyList()).isEmpty)
        assertTrue(DensityEstimator.estimate(listOf(Double.NaN)).isEmpty)
    }

    @Test
    fun `non-finite samples are dropped and the rest still estimated`() {
        val curve = DensityEstimator.estimate(
            listOf(1.0, Double.NaN, 2.0, Double.POSITIVE_INFINITY, 3.0, 4.0, 5.0),
        )
        assertEquals(5, curve.sampleCount)
        assertTrue(!curve.isDegenerate)
    }

    @Test
    fun `a very small variance still produces a usable bandwidth or reports degenerate`() {
        val curve = DensityEstimator.estimate(List(50) { 1.0 + it * 1e-12 })
        assertTrue(curve.isDegenerate || curve.densities.all { it.isFinite() })
    }

    @Test
    fun `a wider bandwidth gives a flatter curve`() {
        val random = Random(3)
        val samples = List(100) { random.nextDouble() * 20 }
        val narrow = DensityEstimator.estimate(samples, KernelBandwidth.Scaled(0.3))
        val wide = DensityEstimator.estimate(samples, KernelBandwidth.Scaled(3.0))
        assertTrue(wide.peak < narrow.peak)
    }

    @Test
    fun `an explicit bandwidth is used exactly`() {
        val curve = DensityEstimator.estimate(
            List(40) { it.toDouble() },
            KernelBandwidth.Fixed(2.5),
        )
        assertEquals(2.5, curve.bandwidth, 1e-9)
    }

    @Test
    fun `the automatic bandwidth is the documented Silverman rule`() {
        val sorted = ChartStatistics.finiteSorted(List(60) { it.toDouble() })
        val expected = ChartStatistics.silvermanBandwidth(sorted)
        val curve = DensityEstimator.estimate(sorted.toList())
        assertEquals(expected, curve.bandwidth, 1e-9)
        assertNotEquals(0.0, expected, 1e-12)
    }

    @Test
    fun `the same samples always give the same curve`() {
        val random = Random(5)
        val samples = List(80) { random.nextDouble() * 30 }
        assertEquals(DensityEstimator.estimate(samples), DensityEstimator.estimate(samples))
    }

    @Test
    fun `the evaluation range extends past the sample by the documented tails`() {
        val samples = List(50) { it.toDouble() }
        val curve = DensityEstimator.estimate(samples)
        val expectedLow = 0.0 - DensityEstimator.TAIL_BANDWIDTHS * curve.bandwidth
        assertEquals(expectedLow, curve.positions.first(), 1e-6)
    }
}
