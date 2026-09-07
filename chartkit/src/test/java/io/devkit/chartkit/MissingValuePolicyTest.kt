package io.devkit.chartkit

import io.devkit.chartkit.charts.lineGeometry
import io.devkit.chartkit.coordinate.CartesianCoordinates
import io.devkit.chartkit.coordinate.DomainAxis
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.model.MissingValuePolicy
import io.devkit.chartkit.model.normalizeSeries
import io.devkit.chartkit.scale.CategoryScale
import io.devkit.chartkit.scale.LinearScale
import io.devkit.chartkit.scale.NumericDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three missing-value policies have to produce three different results.
 *
 * The failure this guards against is an enum constant that does nothing: an
 * option named `Connect` that silently behaved like `Break` would be a promise
 * in the API surface with no implementation behind it.
 */
class MissingValuePolicyTest {

    private data class Point(val label: String, val value: Double?)

    private val rows = listOf(
        Point("A", 10.0),
        Point("B", null),
        Point("C", 30.0),
    )

    private fun geometry(policy: MissingValuePolicy) = run {
        val data = normalizeSeries(
            series = listOf(ChartSeries("s", "S", rows)),
            x = { it.label },
            y = { it.value },
            xResolver = ChartXResolver.Default,
            missingValuePolicy = policy,
            xAxisKind = null,
        )
        val coordinates = CartesianCoordinates(
            plotArea = ChartRect(0f, 0f, 300f, 200f),
            domainAxis = DomainAxis.Categories(
                CategoryScale(data.categories, 0f, 300f, categoryPadding = 0.0),
            ),
            valueScale = LinearScale(NumericDomain(0.0, 30.0), 200f, 0f),
            orientation = ChartOrientation.Vertical,
        )
        lineGeometry(
            series = data.series.single(),
            seriesIndex = 0,
            coordinates = coordinates,
            data = data,
            categories = data.categories,
            missingValuePolicy = policy,
        )
    }

    @Test
    fun `Break splits the line into two segments`() {
        val result = geometry(MissingValuePolicy.Break)
        assertEquals(2, result.segments.size)
        assertEquals(2, result.presentPoints.size)
    }

    @Test
    fun `Connect draws one unbroken segment through the gap`() {
        val result = geometry(MissingValuePolicy.Connect)
        assertEquals(1, result.segments.size)
        assertEquals(listOf(10.0, 30.0), result.segments.single().points.map { it.value })
    }

    @Test
    fun `Zero plots the gap as a real point at zero`() {
        val result = geometry(MissingValuePolicy.Zero)
        assertEquals(1, result.segments.size)
        assertEquals(listOf(10.0, 0.0, 30.0), result.segments.single().points.map { it.value })
    }

    @Test
    fun `Break and Connect both keep the value absent from the data`() {
        // Only the *path* differs. Neither invents a value, which is what
        // separates both of them from Zero.
        listOf(MissingValuePolicy.Break, MissingValuePolicy.Connect).forEach { policy ->
            val result = geometry(policy)
            assertNull(result.points[1])
            assertTrue(result.presentPoints.none { it.sourceIndex == 1 })
        }
    }

    @Test
    fun `every policy keeps selections pointing at the right source item`() {
        MissingValuePolicy.entries.forEach { policy ->
            geometry(policy).presentPoints.forEach { point ->
                assertEquals(rows[point.sourceIndex].label, geometry(policy).xValues[point.sourceIndex].let {
                    (it as io.devkit.chartkit.model.ChartX.Category).label
                })
            }
        }
    }
}
