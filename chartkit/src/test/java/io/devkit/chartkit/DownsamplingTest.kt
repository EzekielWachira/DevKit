package io.devkit.chartkit

import io.devkit.chartkit.data.ChartDownsampling
import io.devkit.chartkit.data.LttbDownsampler
import io.devkit.chartkit.data.MinMaxDownsampler
import io.devkit.chartkit.data.resolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Downsampling.
 *
 * The properties every strategy has to hold — endpoints kept, indices ascending
 * and in range, budget respected, source untouched — plus the one each is
 * chosen for: min/max cannot lose a spike, and LTTB keeps the shape.
 */
class DownsamplingTest {

    private val n = 1000
    private val xs = DoubleArray(n) { it.toDouble() }
    private val wave = DoubleArray(n) { sin(it / 30.0) * 100 }

    @Test
    fun `min-max keeps the first and last points`() {
        val indices = MinMaxDownsampler.sample(xs, wave, 100)
        assertEquals(0, indices.first())
        assertEquals(n - 1, indices.last())
    }

    @Test
    fun `lttb keeps the first and last points`() {
        val indices = LttbDownsampler.sample(xs, wave, 100)
        assertEquals(0, indices.first())
        assertEquals(n - 1, indices.last())
    }

    @Test
    fun `indices come back ascending and inside the data`() {
        listOf(MinMaxDownsampler.sample(xs, wave, 137), LttbDownsampler.sample(xs, wave, 137))
            .forEach { indices ->
                assertTrue(indices.toList().zipWithNext().all { (a, b) -> b > a })
                assertTrue(indices.all { it in 0 until n })
            }
    }

    @Test
    fun `lttb produces exactly the requested count`() {
        assertEquals(50, LttbDownsampler.sample(xs, wave, 50).size)
        assertEquals(311, LttbDownsampler.sample(xs, wave, 311).size)
    }

    @Test
    fun `min-max stays within its budget`() {
        val indices = MinMaxDownsampler.sample(xs, wave, 200)
        assertTrue("was ${indices.size}", indices.size <= 202)
    }

    @Test
    fun `a target larger than the data returns every index`() {
        val small = DoubleArray(10) { it.toDouble() }
        assertEquals(10, MinMaxDownsampler.sample(small, small, 100).size)
        assertEquals(10, LttbDownsampler.sample(small, small, 100).size)
    }

    @Test
    fun `an empty dataset samples to nothing`() {
        assertEquals(0, MinMaxDownsampler.sample(DoubleArray(0), DoubleArray(0), 10).size)
        assertEquals(0, LttbDownsampler.sample(DoubleArray(0), DoubleArray(0), 10).size)
    }

    @Test
    fun `min-max cannot lose a single-sample spike`() {
        val spiked = wave.copyOf()
        spiked[517] = 10_000.0
        val indices = MinMaxDownsampler.sample(xs, spiked, 100)
        assertTrue("the spike must survive", indices.contains(517))
    }

    @Test
    fun `min-max preserves the overall envelope`() {
        val indices = MinMaxDownsampler.sample(xs, wave, 100)
        val sampled = indices.map { wave[it] }
        assertEquals(wave.max(), sampled.max(), 1e-9)
        assertEquals(wave.min(), sampled.min(), 1e-9)
    }

    @Test
    fun `lttb keeps the shape close to the original`() {
        val indices = LttbDownsampler.sample(xs, wave, 100)
        // Every original point should be near the piecewise-linear line through
        // the retained ones; a naive every-nth sampler of this wave is not.
        var worst = 0.0
        for (index in 0 until n) {
            val after = indices.indexOfFirst { it >= index }.coerceAtLeast(1)
            val hi = indices[after.coerceAtMost(indices.size - 1)]
            val lo = indices[(after - 1).coerceAtLeast(0)]
            val t = if (hi == lo) 0.0 else (index - lo).toDouble() / (hi - lo)
            val interpolated = wave[lo] + (wave[hi] - wave[lo]) * t
            worst = maxOf(worst, kotlin.math.abs(interpolated - wave[index]))
        }
        assertTrue("worst deviation was $worst", worst < 25.0)
    }

    @Test
    fun `missing values do not poison the sampled result`() {
        val gappy = wave.copyOf()
        for (index in 400 until 450) gappy[index] = Double.NaN
        val lttb = LttbDownsampler.sample(xs, gappy, 100)
        val minMax = MinMaxDownsampler.sample(xs, gappy, 100)
        assertTrue(lttb.all { it in 0 until n })
        assertTrue(minMax.all { it in 0 until n })
    }

    @Test
    fun `sampling does not touch the source arrays`() {
        val x = xs.copyOf()
        val y = wave.copyOf()
        LttbDownsampler.sample(x, y, 100)
        MinMaxDownsampler.sample(x, y, 100)
        assertTrue(x.contentEquals(xs))
        assertTrue(y.contentEquals(wave))
    }

    @Test
    fun `the none strategy never samples`() {
        assertNull(ChartDownsampling.None.resolve(100_000, 1000f))
    }

    @Test
    fun `auto samples only when the data outruns the plot`() {
        val auto = ChartDownsampling.Auto(pointsPerPixel = 2f, minimumTarget = 256)
        assertNull(auto.resolve(500, 1000f))
        val resolved = auto.resolve(100_000, 1000f)
        assertNotNull(resolved)
        assertEquals(2000, resolved!!.second)
    }

    @Test
    fun `auto falls back to its floor when the plot has not been measured`() {
        val auto = ChartDownsampling.Auto(minimumTarget = 256)
        assertEquals(256, auto.resolve(100_000, 0f)!!.second)
    }

    @Test
    fun `an explicit strategy samples only past its own target`() {
        assertNull(ChartDownsampling.MinMax(500).resolve(400, 800f))
        assertEquals(500, ChartDownsampling.MinMax(500).resolve(5000, 800f)!!.second)
        assertNull(ChartDownsampling.Lttb(500).resolve(400, 800f))
    }
}
