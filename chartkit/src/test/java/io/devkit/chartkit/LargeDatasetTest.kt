package io.devkit.chartkit

import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.LinePoint
import io.devkit.chartkit.geometry.nearestPointIndex
import io.devkit.chartkit.geometry.segmentLine
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.model.MissingValuePolicy
import io.devkit.chartkit.model.normalizeSeries
import io.devkit.chartkit.scale.LinearScale
import io.devkit.chartkit.scale.NumericDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * Sanity checks at the sizes ChartKit 0.1 claims to handle.
 *
 * Not benchmarks — the repository has no benchmarking infrastructure, and
 * introducing one for a single library would be a larger change than the
 * library. These assert only that the engine's per-frame work stays
 * *proportionate*: no quadratic scan, no per-point allocation storm, no linear
 * search where a binary one was promised. The bounds are deliberately loose so
 * they do not fail on a loaded CI machine; a regression that matters here would
 * be orders of magnitude, not percent.
 */
class LargeDatasetTest {

    private data class Sample(val index: Int, val value: Double)

    private fun samples(count: Int) = List(count) { Sample(it, kotlin.math.sin(it / 50.0) * 100) }

    @Test
    fun `normalising ten thousand points is prompt`() {
        val data = samples(10_000)
        val elapsed = measureTimeMillis {
            val plot = normalizeSeries(
                series = listOf(ChartSeries("s", "S", data)),
                x = { it.index },
                y = { it.value },
                xResolver = ChartXResolver.Default,
                missingValuePolicy = MissingValuePolicy.Break,
                xAxisKind = null,
            )
            assertEquals(10_000, plot.series.single().points.size)
        }
        assertTrue("normalising 10,000 points took ${elapsed}ms", elapsed < 2_000)
    }

    @Test
    fun `mapping ten thousand points through a scale is prompt`() {
        val scale = LinearScale(NumericDomain(0.0, 10_000.0), 0f, 1080f)
        val elapsed = measureTimeMillis {
            var sum = 0f
            for (index in 0 until 10_000) sum += scale.scale(index.toDouble())
            assertTrue(sum.isFinite())
        }
        assertTrue("scaling 10,000 points took ${elapsed}ms", elapsed < 500)
    }

    @Test
    fun `segmenting a dense series with gaps is linear`() {
        val points = List(10_000) { index ->
            if (index % 500 == 0) null else LinePoint(ChartOffset(index.toFloat(), 0f), index, 0.0)
        }
        val elapsed = measureTimeMillis {
            val segments = segmentLine(points)
            assertTrue(segments.isNotEmpty())
        }
        assertTrue("segmenting took ${elapsed}ms", elapsed < 500)
    }

    @Test
    fun `nearest-point search on sorted data does not scan`() {
        // The scrub path runs this on every pointer move. Binary search over
        // 10,000 points is fourteen comparisons; a scan is 10,000 — and this
        // asserts the difference is real rather than documented.
        val points = List(10_000) { LinePoint(ChartOffset(it.toFloat(), 0f), it, 0.0) }
        val queries = List(2_000) { (it * 5).toFloat() }

        val sorted = measureTimeMillis {
            queries.forEach { nearestPointIndex(points, it, sorted = true) }
        }
        val scanned = measureTimeMillis {
            queries.forEach { nearestPointIndex(points, it, sorted = false) }
        }
        assertTrue(
            "binary search (${sorted}ms) should beat a scan (${scanned}ms)",
            sorted <= scanned,
        )
        assertTrue("2,000 lookups took ${sorted}ms", sorted < 200)
    }

    @Test
    fun `results are correct at every size, not only fast`() {
        listOf(1_000, 5_000, 10_000).forEach { count ->
            val plot = normalizeSeries(
                series = listOf(ChartSeries("s", "S", samples(count))),
                x = { it.index },
                y = { it.value },
                xResolver = ChartXResolver.Default,
                missingValuePolicy = MissingValuePolicy.Break,
                xAxisKind = null,
            )
            assertEquals(count, plot.series.single().points.size)
            assertTrue(plot.yDomain!!.span > 0.0)
            assertTrue(plot.xDomain!!.max == (count - 1).toDouble())
        }
    }
}
