package io.devkit.chartkit

import io.devkit.chartkit.charts.ChartPerformance
import io.devkit.chartkit.charts.lineGeometry
import io.devkit.chartkit.coordinate.CartesianCoordinates
import io.devkit.chartkit.coordinate.DomainAxis
import io.devkit.chartkit.data.ChartDownsampling
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.model.MissingValuePolicy
import io.devkit.chartkit.model.normalizeSeries
import io.devkit.chartkit.scale.LinearScale
import io.devkit.chartkit.scale.NumericDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

private data class Sample(val x: Double, val y: Double?)

/**
 * Culling and downsampling, at the level where they actually happen.
 *
 * `lineGeometry` is pure — coordinates, scales and normalisation involve no
 * Compose — so the whole path from a hundred thousand source points to the
 * handful that get drawn is verifiable on the JVM, including the property that
 * matters most: what is drawn changes, and what can be selected does not.
 */
class LargeSeriesGeometryTest {

    private val plot = ChartRect(0f, 0f, 1000f, 500f)

    private fun coordinates(domain: NumericDomain) = CartesianCoordinates(
        plotArea = plot,
        domainAxis = DomainAxis.Continuous(LinearScale(domain, plot.left, plot.right)),
        valueScale = LinearScale(NumericDomain(-120.0, 120.0), plot.bottom, plot.top),
        orientation = ChartOrientation.Vertical,
    )

    private fun data(count: Int, gapAt: IntRange? = null): List<Sample> = List(count) { index ->
        Sample(
            x = index.toDouble(),
            y = if (gapAt != null && index in gapAt) null else sin(index / 40.0) * 100,
        )
    }

    private fun geometryOf(
        samples: List<Sample>,
        visibleDomain: NumericDomain?,
        performance: ChartPerformance,
    ) = run {
        val plotData = normalizeSeries(
            series = listOf(ChartSeries(id = "s", name = "Series", data = samples)),
            x = { it.x },
            y = { it.y },
            xResolver = ChartXResolver.Default,
            missingValuePolicy = MissingValuePolicy.Break,
            xAxisKind = null,
        )
        val domain = plotData.xDomain ?: NumericDomain.Default
        lineGeometry(
            series = plotData.series.single(),
            seriesIndex = 0,
            coordinates = coordinates(visibleDomain ?: domain),
            data = plotData,
            categories = emptyList(),
            missingValuePolicy = MissingValuePolicy.Break,
            visibleDomain = visibleDomain,
            performance = performance,
            plotExtent = plot.width,
        )
    }

    @Test
    fun `a small series is drawn in full and nothing is sampled`() {
        val geometry = geometryOf(data(200), null, ChartPerformance.Default)
        assertEquals(200, geometry.points.size)
        assertEquals(200, geometry.values.size)
    }

    @Test
    fun `a dense series is sampled down to roughly the plot's width`() {
        val geometry = geometryOf(data(100_000), null, ChartPerformance.Default)
        // Auto at two points per pixel over a 1000px plot.
        assertTrue("drew ${geometry.points.size}", geometry.points.size <= 2100)
        assertTrue(geometry.points.size >= 500)
    }

    @Test
    fun `sampling changes what is drawn and not what exists`() {
        val samples = data(100_000)
        val geometry = geometryOf(samples, null, ChartPerformance.Default)
        // The source values, the x values and the caller's items stay whole, so
        // a tooltip or an accessibility announcement still refers to the
        // original observation.
        assertEquals(100_000, geometry.values.size)
        assertEquals(100_000, geometry.xValues.size)
        assertEquals(100_000, geometry.items.size)
        assertNotNull(geometry.domainValues)
        assertEquals(100_000, geometry.domainValues!!.size)
    }

    @Test
    fun `the exact profile draws every point`() {
        val geometry = geometryOf(data(20_000), null, ChartPerformance.Exact)
        assertEquals(20_000, geometry.points.size)
    }

    @Test
    fun `culling keeps only the visible window plus overscan`() {
        val samples = data(10_000)
        val performance = ChartPerformance(downsampling = ChartDownsampling.None)
        val geometry = geometryOf(samples, NumericDomain(5_000.0, 5_100.0), performance)
        // About a hundred visible points, plus 15% overscan either side.
        assertTrue("drew ${geometry.points.size}", geometry.points.size in 100..150)
    }

    @Test
    fun `culling can be turned off`() {
        val samples = data(10_000)
        val performance = ChartPerformance(
            downsampling = ChartDownsampling.None,
            cullToViewport = false,
        )
        val geometry = geometryOf(samples, NumericDomain(5_000.0, 5_100.0), performance)
        assertEquals(10_000, geometry.points.size)
    }

    @Test
    fun `drawn points keep their original source indices`() {
        val geometry = geometryOf(data(50_000), null, ChartPerformance.Default)
        val indices = geometry.presentPoints.map { it.sourceIndex }
        assertTrue(indices.zipWithNext().all { (a, b) -> b > a })
        assertEquals(0, indices.first())
        assertEquals(49_999, indices.last())
    }

    @Test
    fun `a gap survives sampling rather than being closed over`() {
        val geometry = geometryOf(data(20_000, gapAt = 8_000..9_000), null, ChartPerformance.Default)
        // The line is split by the missing run rather than drawn straight
        // across it.
        assertTrue("segments: ${geometry.segments.size}", geometry.segments.size >= 2)
    }

    @Test
    fun `an unordered series is drawn in full because there is no window to cut`() {
        val shuffled = data(5_000).shuffled(kotlin.random.Random(4))
        val geometry = geometryOf(shuffled, NumericDomain(100.0, 200.0), ChartPerformance.Default)
        assertEquals(5_000, geometry.points.size)
        // And it is not claimed to be sorted, so hit testing falls back to a
        // scan rather than binary-searching data that is not ordered.
        assertTrue(geometry.domainValues == null || !geometry.sortedByDomain)
    }

    @Test
    fun `min-max sampling keeps the extremes of a dense series`() {
        val samples = data(50_000).toMutableList()
        samples[31_415] = Sample(31_415.0, 9_999.0)
        val geometry = geometryOf(
            samples,
            null,
            ChartPerformance(downsampling = ChartDownsampling.MinMax(1_000)),
        )
        assertTrue(geometry.presentPoints.any { it.sourceIndex == 31_415 })
    }
}
