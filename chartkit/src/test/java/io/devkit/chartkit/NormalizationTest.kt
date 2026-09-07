package io.devkit.chartkit

import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.model.ChartX
import io.devkit.chartkit.model.ChartXAxisKind
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.model.MissingValuePolicy
import io.devkit.chartkit.model.normalizeSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private data class Row(val label: Any?, val amount: Double?)

/**
 * The crossing point between the developer's own model and the chart engine.
 * These tests are the contract behind "chart your own data class".
 */
class NormalizationTest {

    private fun normalize(
        rows: List<Row>,
        policy: MissingValuePolicy = MissingValuePolicy.Break,
        resolver: ChartXResolver = ChartXResolver.Default,
        kind: ChartXAxisKind? = null,
    ) = normalizeSeries(
        series = listOf(ChartSeries("s", "S", rows)),
        x = { it.label },
        y = { it.amount },
        xResolver = resolver,
        missingValuePolicy = policy,
        xAxisKind = kind,
    )

    @Test
    fun `a string x produces a category axis`() {
        val data = normalize(listOf(Row("Jan", 1.0), Row("Feb", 2.0)))
        assertEquals(ChartXAxisKind.Category, data.xAxisKind)
        assertEquals(listOf("Jan", "Feb"), data.categories)
    }

    @Test
    fun `a numeric x produces a numeric axis`() {
        val data = normalize(listOf(Row(1, 1.0), Row(2, 2.0)))
        assertEquals(ChartXAxisKind.Numeric, data.xAxisKind)
        assertEquals(1.0, data.xDomain!!.min, 1e-9)
        assertEquals(2.0, data.xDomain!!.max, 1e-9)
    }

    @Test
    fun `a Date x produces a time axis`() {
        val data = normalize(listOf(Row(java.util.Date(1000L), 1.0), Row(java.util.Date(2000L), 2.0)))
        assertEquals(ChartXAxisKind.Time, data.xAxisKind)
    }

    @Test
    fun `an enum x becomes a category using its name`() {
        val data = normalizeSeries(
            series = listOf(ChartSeries("s", "S", listOf(Weekday.Mon, Weekday.Tue))),
            x = { it },
            y = { it.ordinal },
            xResolver = ChartXResolver.Default,
            missingValuePolicy = MissingValuePolicy.Break,
            xAxisKind = null,
        )
        assertEquals(listOf("Mon", "Tue"), data.categories)
    }

    @Test
    fun `the time resolver reads numbers as epoch millis`() {
        val data = normalize(listOf(Row(1000L, 1.0)), resolver = ChartXResolver.Time)
        assertEquals(ChartXAxisKind.Time, data.xAxisKind)
    }

    @Test
    fun `a custom resolver takes precedence and falls through on null`() {
        val resolver = ChartXResolver { value ->
            if (value == "special") ChartX.Category("SPECIAL") else null
        }
        val data = normalize(listOf(Row("special", 1.0), Row("Feb", 2.0)), resolver = resolver)
        assertEquals(listOf("SPECIAL", "Feb"), data.categories)
    }

    @Test
    fun `one category among numbers forces the whole axis categorical`() {
        val data = normalize(listOf(Row(1, 1.0), Row("two", 2.0)))
        assertEquals(ChartXAxisKind.Category, data.xAxisKind)
    }

    @Test
    fun `a null value stays missing under the default policy`() {
        val data = normalize(listOf(Row("A", null)))
        assertNull(data.series.single().points.single().y)
    }

    @Test
    fun `NaN and infinity are treated as missing, not plotted`() {
        val data = normalize(listOf(Row("A", Double.NaN), Row("B", Double.POSITIVE_INFINITY)))
        assertTrue(data.series.single().points.none { it.isPresent })
    }

    @Test
    fun `the Zero policy is opt-in, never the default`() {
        val broken = normalize(listOf(Row("A", null)), MissingValuePolicy.Break)
        assertNull(broken.series.single().points.single().y)

        val zeroed = normalize(listOf(Row("A", null)), MissingValuePolicy.Zero)
        assertEquals(0.0, zeroed.series.single().points.single().y!!, 1e-9)
    }

    @Test
    fun `input order is preserved`() {
        val data = normalize(listOf(Row("Zebra", 1.0), Row("Apple", 2.0), Row("Mango", 3.0)))
        assertEquals(listOf("Zebra", "Apple", "Mango"), data.categories)
    }

    @Test
    fun `sortedByX orders a continuous axis and leaves categories alone`() {
        val numeric = normalize(listOf(Row(3, 1.0), Row(1, 2.0), Row(2, 3.0))).sortedByX()
        assertEquals(listOf(1.0, 2.0, 3.0), numeric.series.single().points.map { numeric.continuousX(it) })

        val categorical = normalize(listOf(Row("C", 1.0), Row("A", 2.0))).sortedByX()
        assertEquals(listOf("C", "A"), categorical.categories)
    }

    @Test
    fun `sorting keeps every point pointing at its original item`() {
        val rows = listOf(Row(3, 30.0), Row(1, 10.0), Row(2, 20.0))
        val sorted = normalize(rows).sortedByX()
        val series = sorted.series.single()
        series.points.forEach { point ->
            assertEquals(rows[point.sourceIndex], series.itemAt(point.sourceIndex))
        }
    }

    @Test
    fun `an empty series is reported as empty, not as broken`() {
        val data = normalize(emptyList())
        assertTrue(data.isEmpty)
        assertNull(data.yDomain)
    }

    @Test
    fun `hidden series do not contribute to the domain`() {
        val data = normalizeSeries(
            series = listOf(
                ChartSeries("a", "A", listOf(Row("Jan", 10.0))),
                ChartSeries("b", "B", listOf(Row("Jan", 1000.0)), visible = false),
            ),
            x = { it.label },
            y = { it.amount },
            xResolver = ChartXResolver.Default,
            missingValuePolicy = MissingValuePolicy.Break,
            xAxisKind = null,
        )
        assertEquals(10.0, data.yDomain!!.max, 1e-9)
    }

    @Test
    fun `hidden series keep their palette slot`() {
        val data = normalizeSeries(
            series = listOf(
                ChartSeries("a", "A", listOf(Row("Jan", 1.0)), visible = false),
                ChartSeries("b", "B", listOf(Row("Jan", 2.0))),
            ),
            x = { it.label },
            y = { it.amount },
            xResolver = ChartXResolver.Default,
            missingValuePolicy = MissingValuePolicy.Break,
            xAxisKind = null,
        )
        // Hiding A must not recolour B by promoting it to slot 0.
        assertEquals(1, data.series[1].paletteIndex)
    }

    @Test
    fun `withValues replaces values without disturbing anything else`() {
        val data = normalize(listOf(Row("A", 1.0), Row("B", 2.0)))
        val updated = data.withValues(listOf(listOf(5.0, 6.0)))
        assertEquals(listOf(5.0, 6.0), updated.series.single().points.map { it.y })
        assertEquals(data.categories, updated.categories)
        assertEquals(data.yDomain, updated.yDomain)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate series ids are rejected`() {
        normalizeSeries(
            series = listOf(
                ChartSeries("same", "A", listOf(Row("Jan", 1.0))),
                ChartSeries("same", "B", listOf(Row("Jan", 2.0))),
            ),
            x = { it.label },
            y = { it.amount },
            xResolver = ChartXResolver.Default,
            missingValuePolicy = MissingValuePolicy.Break,
            xAxisKind = null,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a blank series id is rejected`() {
        ChartSeries("  ", "A", listOf(Row("Jan", 1.0)))
    }

    @Test
    fun `an unknown x type falls back to a category rather than throwing`() {
        val data = normalize(listOf(Row(Any(), 1.0)))
        assertEquals(ChartXAxisKind.Category, data.xAxisKind)
        assertNotNull(data.categories.singleOrNull())
    }

    private enum class Weekday { Mon, Tue }
}

/**
 * A combined chart normalises each layer separately, so palette slots have to
 * be offset or every layer starts again at the theme's first colour.
 */
class PaletteOffsetTest {

    private fun normalize(rows: List<Row>) = normalizeSeries(
        series = listOf(ChartSeries("a", "A", rows), ChartSeries("b", "B", rows)),
        x = { it.label },
        y = { it.amount },
        xResolver = ChartXResolver.Default,
        missingValuePolicy = MissingValuePolicy.Break,
        xAxisKind = null,
    )

    @Test
    fun `an offset shifts every slot`() {
        val shifted = normalize(listOf(Row("Jan", 1.0))).withPaletteOffset(2)
        assertEquals(listOf(2, 3), shifted.series.map { it.paletteIndex })
    }

    @Test
    fun `an offset of zero changes nothing`() {
        val data = normalize(listOf(Row("Jan", 1.0)))
        assertEquals(data, data.withPaletteOffset(0))
    }

    @Test
    fun `an offset preserves everything but the slot`() {
        val data = normalize(listOf(Row("Jan", 1.0), Row("Feb", 2.0)))
        val shifted = data.withPaletteOffset(3)
        assertEquals(data.categories, shifted.categories)
        assertEquals(data.yDomain, shifted.yDomain)
        assertEquals(
            data.series.map { it.points.map { point -> point.y } },
            shifted.series.map { it.points.map { point -> point.y } },
        )
    }
}
