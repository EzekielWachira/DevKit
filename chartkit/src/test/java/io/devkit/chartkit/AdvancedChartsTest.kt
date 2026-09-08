package io.devkit.chartkit

import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.accessibility.boxPlotDataTable
import io.devkit.chartkit.accessibility.buildChartSummary
import io.devkit.chartkit.accessibility.chartDataTable
import io.devkit.chartkit.accessibility.ohlcDataTable
import io.devkit.chartkit.annotation.AnnotationOrder
import io.devkit.chartkit.annotation.ChartAnnotation
import io.devkit.chartkit.annotation.eventMarker
import io.devkit.chartkit.annotation.horizontalRule
import io.devkit.chartkit.annotation.region
import io.devkit.chartkit.annotation.valueRange
import io.devkit.chartkit.annotation.verticalRule
import io.devkit.chartkit.charts.buildHeatmapGrid
import io.devkit.chartkit.charts.resolveAnnotations
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.geometry.ScatterIndex
import io.devkit.chartkit.geometry.ScatterPoint
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.model.ChartX
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.stats.BoxStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private data class Cell(val day: String, val hour: String, val requests: Int?)

/** The heatmap grid: every combination accounted for, missing distinct from zero. */
class HeatmapGridTest {

    private val data = listOf(
        Cell("Mon", "09", 10),
        Cell("Mon", "10", 20),
        Cell("Tue", "09", 0),
        // Tue/10 is absent — not zero.
    )

    private fun grid() = buildHeatmapGrid(data, { it.day }, { it.hour }, { it.requests })

    @Test
    fun `columns and rows come from the data in input order`() {
        val grid = grid()
        assertEquals(listOf("Mon", "Tue"), grid.columnLabels)
        // Rows are reversed so the first listed appears at the top of the plot.
        assertEquals(listOf("10", "09"), grid.rowLabels)
    }

    @Test
    fun `every combination is materialised, present or not`() {
        val grid = grid()
        assertEquals(4, grid.cells.size)
    }

    @Test
    fun `a zero cell and a missing cell are different`() {
        val grid = grid()
        val tueNine = grid.cells.single { it.column == 1 && it.row == 1 }
        val tueTen = grid.cells.single { it.column == 1 && it.row == 0 }
        assertEquals(0.0, tueNine.value!!, 1e-9)
        assertNull(tueTen.value)
        assertEquals(-1, tueTen.sourceIndex)
    }

    @Test
    fun `cells carry the source position of the observation behind them`() {
        val grid = grid()
        assertEquals(0, grid.cells.single { it.column == 0 && it.row == 1 }.sourceIndex)
    }

    @Test
    fun `a non-finite measurement counts as missing`() {
        val grid = buildHeatmapGrid(
            listOf(Cell("a", "b", null)),
            { it.day },
            { it.hour },
            { it.requests },
        )
        assertNull(grid.cells.single().value)
    }

    @Test
    fun `an empty dataset produces an empty grid`() {
        val grid = buildHeatmapGrid(emptyList<Cell>(), { it.day }, { it.hour }, { it.requests })
        assertTrue(grid.cells.isEmpty())
    }
}

/** The spatial index scatter hit testing goes through. */
class ScatterIndexTest {

    private val bounds = ChartRect(0f, 0f, 1000f, 1000f)

    private fun index(points: List<ScatterPoint>) = ScatterIndex(points, bounds)

    @Test
    fun `the nearest point is found within the tolerance`() {
        val points = listOf(
            ScatterPoint(ChartOffset(100f, 100f), 0, 4f),
            ScatterPoint(ChartOffset(500f, 500f), 1, 4f),
            ScatterPoint(ChartOffset(900f, 900f), 2, 4f),
        )
        assertEquals(1, index(points).nearest(ChartOffset(505f, 505f), 50f))
    }

    @Test
    fun `nothing is returned beyond the tolerance`() {
        val points = listOf(ScatterPoint(ChartOffset(10f, 10f), 0, 4f))
        assertEquals(-1, index(points).nearest(ChartOffset(900f, 900f), 20f))
    }

    @Test
    fun `an empty index finds nothing`() {
        assertEquals(-1, index(emptyList()).nearest(ChartOffset(10f, 10f), 100f))
    }

    @Test
    fun `the index agrees with a brute-force scan on a dense cloud`() {
        val random = kotlin.random.Random(19)
        val points = List(2000) {
            ScatterPoint(
                ChartOffset(random.nextFloat() * 1000f, random.nextFloat() * 1000f),
                it,
                3f,
            )
        }
        val indexed = index(points)
        repeat(50) {
            val query = ChartOffset(random.nextFloat() * 1000f, random.nextFloat() * 1000f)
            val brute = points.minByOrNull { point ->
                val dx = point.position.x - query.x
                val dy = point.position.y - query.y
                dx * dx + dy * dy
            }!!
            assertEquals(brute.sourceIndex, indexed.nearest(query, 2000f))
        }
    }

    @Test
    fun `a point outside the plot is not indexed`() {
        val points = listOf(ScatterPoint(ChartOffset(-50f, -50f), 0, 4f))
        assertEquals(-1, index(points).nearest(ChartOffset(-50f, -50f), 100f))
    }

    @Test
    fun `a non-finite query finds nothing`() {
        val points = listOf(ScatterPoint(ChartOffset(10f, 10f), 0, 4f))
        assertEquals(-1, index(points).nearest(ChartOffset(Float.NaN, 10f), 100f))
    }
}

/** Annotations: resolution of their domain positions, and their ordering. */
class AnnotationTest {

    @Test
    fun `a category rule resolves to a category value`() {
        val resolved = resolveAnnotations(
            listOf(verticalRule(at = "Mar", label = "Launch")),
            ChartXResolver.Default,
        ).single()
        assertEquals(ChartX.Category("Mar"), resolved.domainStart)
    }

    @Test
    fun `a time rule resolves through the time resolver`() {
        val resolved = resolveAnnotations(
            listOf(verticalRule(at = 1_700_000_000_000L)),
            ChartXResolver.Time,
        ).single()
        assertEquals(ChartX.Time(1_700_000_000_000L), resolved.domainStart)
    }

    @Test
    fun `a domain range resolves both edges`() {
        val resolved = resolveAnnotations(
            listOf(io.devkit.chartkit.annotation.domainRange(from = 10, to = 20)),
            ChartXResolver.Default,
        ).single()
        assertEquals(ChartX.Numeric(10.0), resolved.domainStart)
        assertEquals(ChartX.Numeric(20.0), resolved.domainEnd)
    }

    @Test
    fun `a region resolves its domain edges and keeps its values`() {
        val annotation = region(
            domainFrom = "Feb", domainTo = "Apr",
            valueFrom = 10.0, valueTo = 90.0,
            label = "Target zone",
        )
        val resolved = resolveAnnotations(listOf(annotation), ChartXResolver.Default).single()
        assertEquals(ChartX.Category("Feb"), resolved.domainStart)
        assertEquals(ChartX.Category("Apr"), resolved.domainEnd)
        assertEquals(10.0, (resolved.annotation as ChartAnnotation.Region).valueFrom, 1e-9)
    }

    @Test
    fun `value-space annotations have no domain position to resolve`() {
        val resolved = resolveAnnotations(
            listOf(horizontalRule(100.0), valueRange(10.0, 20.0)),
            ChartXResolver.Default,
        )
        assertTrue(resolved.all { it.domainStart == null })
    }

    @Test
    fun `rules and markers default above the data and regions behind it`() {
        assertEquals(AnnotationOrder.Above, horizontalRule(1.0).order)
        assertEquals(AnnotationOrder.Above, verticalRule("a").order)
        assertEquals(AnnotationOrder.Above, eventMarker("a").order)
        assertEquals(AnnotationOrder.Behind, valueRange(1.0, 2.0).order)
        assertEquals(AnnotationOrder.Behind, region("a", "b", 1.0, 2.0).order)
    }

    @Test
    fun `a threshold widens the axis by default and a marker does not`() {
        assertTrue(horizontalRule(1.0).extendsDomain)
        assertTrue(!eventMarker("a").extendsDomain)
        assertTrue(!verticalRule("a").extendsDomain)
    }

    @Test
    fun `annotations keep their identity across a rebuild`() {
        assertEquals(horizontalRule(100.0).id, horizontalRule(100.0).id)
        assertTrue(horizontalRule(100.0).id != horizontalRule(200.0).id)
    }
}

/** The accessible table representations. */
class ChartDataTableTest {

    @Test
    fun `a single series table has a category and a value column`() {
        val table = chartDataTable(
            data = listOf("Jan" to 10.0, "Feb" to 20.0),
            category = { it.first },
            value = { it.second },
        )
        assertEquals(listOf("Category", "Value"), table.columns)
        assertEquals(2, table.rows.size)
        assertEquals("Jan", table.rows.first().first())
    }

    @Test
    fun `a multi-series table names the series in each row`() {
        val table = chartDataTable(
            series = listOf(
                ChartSeries(id = "a", name = "Revenue", data = listOf("Jan" to 10.0)),
                ChartSeries(id = "b", name = "Costs", data = listOf("Jan" to 4.0)),
            ),
            category = { it.first },
            value = { it.second },
        )
        assertEquals(listOf("Category", "Series", "Value"), table.columns)
        assertEquals(listOf("Jan", "Revenue", "10"), table.rows.first())
    }

    @Test
    fun `a missing value is stated, not blank`() {
        val table = chartDataTable(
            data = listOf("Jan" to null),
            category = { it.first },
            value = { it.second },
        )
        assertEquals("no value", table.rows.single()[1])
    }

    @Test
    fun `an OHLC table carries all four prices and optional volume`() {
        data class Bar(val d: String, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)
        val table = ohlcDataTable(
            data = listOf(Bar("Mon", 1.0, 3.0, 0.5, 2.0, 100.0)),
            date = { it.d },
            open = { it.o },
            high = { it.h },
            low = { it.l },
            close = { it.c },
            volume = { it.v },
        )
        assertEquals(listOf("Date", "Open", "High", "Low", "Close", "Volume"), table.columns)
        assertEquals(6, table.rows.single().size)
    }

    @Test
    fun `a box table carries the five numbers and the outlier count`() {
        val table = boxPlotDataTable(
            data = listOf("api" to BoxStatistics(1.0, 2.0, 3.0, 4.0, 5.0, listOf(9.0))),
            label = { it.first },
            statistics = { it.second },
        )
        assertEquals(7, table.columns.size)
        assertEquals("1", table.rows.single()[1])
        assertEquals("1", table.rows.single()[6])
    }

    @Test
    fun `the text form repeats each column name so a value reads out of context`() {
        val table = chartDataTable(
            data = listOf("Jan" to 10.0),
            category = { it.first },
            value = { it.second },
            caption = "Monthly revenue",
        )
        val text = table.asText()
        assertTrue(text.startsWith("Monthly revenue"))
        assertTrue(text.contains("Category: Jan"))
        assertTrue(text.contains("Value: 10"))
    }
}

/** Accessibility summaries for the advanced chart types. */
class AdvancedAccessibilityTest {

    private val formatter = ChartValueFormatter.Raw

    @Test
    fun `a layer's own phrasing is used when it has one`() {
        val summary = buildChartSummary(
            ChartAccessibility.Auto,
            listOf(
                ChartLayerSummary(
                    seriesId = "s",
                    seriesName = "Response time",
                    pointCount = 1,
                    entries = listOf(
                        ChartLayerEntry(
                            label = "api",
                            value = 210.0,
                            detail = "api: minimum 100, first quartile 160, median 210, " +
                                "third quartile 280, maximum 430, 3 outliers",
                        ),
                    ),
                ),
            ),
            formatter,
        )
        assertTrue(summary.contains("first quartile 160"))
        assertTrue(summary.contains("3 outliers"))
    }

    @Test
    fun `a very large series is summarised by range rather than by listing`() {
        val summary = buildChartSummary(
            ChartAccessibility.Auto,
            listOf(
                ChartLayerSummary(
                    seriesId = "s",
                    seriesName = "Readings",
                    pointCount = 50_000,
                    entries = emptyList(),
                    valueRange = 1.0..99.0,
                    missingCount = 12,
                ),
            ),
            formatter,
        )
        assertTrue(summary.contains("50000 data points"))
        assertTrue(summary.contains("12 missing"))
        assertTrue(summary.contains("Values from 1 to 99"))
    }

    @Test
    fun `nothing is interpreted, only stated`() {
        val summary = buildChartSummary(
            ChartAccessibility.Auto,
            listOf(
                ChartLayerSummary(
                    seriesId = "s",
                    seriesName = "Growth",
                    pointCount = 3,
                    entries = listOf(
                        ChartLayerEntry("Jan", 1.0),
                        ChartLayerEntry("Feb", 2.0),
                        ChartLayerEntry("Mar", 3.0),
                    ),
                ),
            ),
            formatter,
        )
        listOf("trend", "rising", "increas", "correlat").forEach {
            assertTrue("summary must not interpret: $it", !summary.lowercase().contains(it))
        }
    }
}
