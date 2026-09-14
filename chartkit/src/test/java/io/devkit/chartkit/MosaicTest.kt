package io.devkit.chartkit

import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.geometry.MosaicColumnSpec
import io.devkit.chartkit.geometry.MosaicLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Where a mosaic's columns and cells land, and what their proportions claim. */
class MosaicLayoutTest {

    private val bounds = ChartRect(0f, 0f, 400f, 200f)

    private val columns = listOf("EMEA", "AMER", "APAC").map { MosaicColumnSpec(it) }

    // Column totals: 100, 300, 100 — so AMER should be three times as wide.
    private val values = listOf(
        listOf(60.0, 150.0, 20.0),
        listOf(40.0, 150.0, 80.0),
    )

    private fun layout(
        values: List<List<Double?>> = this.values,
        columnGap: Float = 0f,
        cellGap: Float = 0f,
        bounds: ChartRect = this.bounds,
    ) = MosaicLayout.layout(columns, values, bounds, columnGap, cellGap)

    @Test
    fun `a column's width is its share of the total`() {
        val geometry = layout()
        val total = 500.0

        geometry.columns.forEach { column ->
            assertEquals(column.total / total, column.fraction, 1e-9)
            assertEquals((400.0 * column.fraction).toFloat(), column.bounds.width, 1e-3f)
        }
    }

    @Test
    fun `the columns tile the plot with no slack`() {
        val geometry = layout()
        val covered = geometry.columns.sumOf { it.bounds.width.toDouble() }

        assertEquals(400.0, covered, 1e-3)
        assertEquals(0f, geometry.columns.first().bounds.left, 1e-3f)
        assertEquals(400f, geometry.columns.last().bounds.right, 1e-3f)
    }

    @Test
    fun `a cell's height is its share of its own column`() {
        val geometry = layout()
        // EMEA is 60/40, so the first cell takes 60% of the height.
        val emea = geometry.cells.filter { it.columnIndex == 0 }

        assertEquals(120f, emea[0].bounds.height, 1e-3f)
        assertEquals(80f, emea[1].bounds.height, 1e-3f)
    }

    @Test
    fun `every column fills the height`() {
        val geometry = layout()
        geometry.columns.forEach { column ->
            assertEquals(200f, column.bounds.height, 1e-3f)
        }
    }

    @Test
    fun `a cell's area is its value, wherever it sits`() {
        // The chart's defining property, and the reason column heights are
        // fixed: width carries the column total and height carries the share of
        // it, so the two cancel and only the value is left.
        val geometry = layout()
        val perUnit = geometry.cells.map { (it.bounds.width * it.bounds.height) / it.value }

        perUnit.zipWithNext { a, b ->
            assertTrue("area per unit differs: \$a vs \$b", abs(a - b) < 1e-2)
        }
    }

    @Test
    fun `two cells of equal value cover equal area in different columns`() {
        val equalValues = listOf(
            listOf(50.0, 50.0, 50.0),
            listOf(10.0, 250.0, 50.0),
        )
        val geometry = layout(equalValues)
        val fifties = geometry.cells.filter { it.seriesIndex == 0 }
            .map { it.bounds.width * it.bounds.height }

        fifties.zipWithNext { a, b -> assertEquals(a.toDouble(), b.toDouble(), 1.0) }
    }

    @Test
    fun `cells stack without gaps when no spacing is asked for`() {
        val geometry = layout()
        val emea = geometry.cells.filter { it.columnIndex == 0 }

        assertEquals(0f, emea.first().bounds.top, 1e-3f)
        assertEquals(emea[0].bounds.bottom, emea[1].bounds.top, 1e-3f)
        assertEquals(200f, emea.last().bounds.bottom, 1e-3f)
    }

    @Test
    fun `gaps come out of the plot rather than being added to it`() {
        // Otherwise the columns would no longer be proportional to their
        // totals, which is the one claim the chart makes.
        val geometry = layout(columnGap = 10f)
        val covered = geometry.columns.sumOf { it.bounds.width.toDouble() }

        assertEquals(400.0 - 2 * 10.0, covered, 1e-3)
        assertTrue(geometry.columns.last().bounds.right <= 400f + 1e-3f)
    }

    @Test
    fun `a missing value leaves the series out of that column`() {
        val withGap = listOf(
            listOf(60.0, null, 20.0),
            listOf(40.0, 150.0, 80.0),
        )
        val geometry = layout(withGap)

        assertTrue(geometry.cells.none { it.columnIndex == 1 && it.seriesIndex == 0 })
        // And the remaining series takes the whole column, because a column is
        // read against itself.
        val amer = geometry.cells.single { it.columnIndex == 1 }
        assertEquals(200f, amer.bounds.height, 1e-3f)
    }

    @Test
    fun `non-positive and non-finite values are left out entirely`() {
        val poisoned = listOf(
            listOf(60.0, -5.0, Double.NaN),
            listOf(40.0, 150.0, 80.0),
        )
        val geometry = layout(poisoned)

        assertTrue(geometry.cells.none { it.seriesIndex == 0 && it.columnIndex == 1 })
        assertTrue(geometry.cells.none { it.seriesIndex == 0 && it.columnIndex == 2 })
        // A negative contributes nothing to its column's width either.
        assertEquals(150.0, geometry.columns[1].total, 1e-9)
    }

    @Test
    fun `an empty plot lays out nothing rather than dividing by zero`() {
        val geometry = layout(bounds = ChartRect(0f, 0f, 0f, 0f))

        assertTrue(geometry.columns.isEmpty())
        assertTrue(geometry.cells.isEmpty())
    }

    @Test
    fun `data that is entirely non-positive lays out nothing`() {
        val geometry = layout(listOf(listOf(0.0, 0.0, 0.0)))

        assertTrue(geometry.columns.isEmpty())
        assertTrue(geometry.cells.isEmpty())
    }

    @Test
    fun `a cell is found at its own centre and nowhere else`() {
        val geometry = layout()
        geometry.cells.forEach { cell ->
            val found = geometry.cellAt(ChartOffset(cell.bounds.centerX, cell.bounds.centerY))
            assertNotNull(found)
            assertEquals(cell.columnIndex, found!!.columnIndex)
            assertEquals(cell.seriesIndex, found.seriesIndex)
        }
        assertNull(geometry.cellAt(ChartOffset(1000f, 1000f)))
    }

    @Test
    fun `a column is found by x alone`() {
        val geometry = layout()
        geometry.columns.forEach { column ->
            assertEquals(column.index, geometry.columnAt(column.bounds.centerX)?.index)
        }
    }

    @Test
    fun `the layout is deterministic`() {
        val first = layout(columnGap = 3f, cellGap = 2f)
        val second = layout(columnGap = 3f, cellGap = 2f)

        first.cells.zip(second.cells).forEach { (a, b) ->
            assertEquals(a.bounds, b.bounds)
        }
    }

    @Test
    fun `the caller's column order is preserved`() {
        val geometry = layout()

        assertEquals(listOf("EMEA", "AMER", "APAC"), geometry.columns.map { it.label })
    }
}
