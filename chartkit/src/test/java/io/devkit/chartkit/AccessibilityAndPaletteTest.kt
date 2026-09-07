package io.devkit.chartkit

import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.accessibility.buildChartSummary
import io.devkit.chartkit.accessibility.describeSelection
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.theme.ChartPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

class ChartAccessibilityTest {

    private val formatter = ChartNumberFormatters.integer(Locale.UK)

    private fun summary(vararg entries: Pair<String, Double?>, name: String = "Revenue") =
        ChartLayerSummary(
            seriesId = name.lowercase(),
            seriesName = name,
            pointCount = entries.size,
            entries = entries.map { ChartLayerEntry(it.first, it.second) },
        )

    @Test
    fun `the title and description lead the announcement`() {
        val text = buildChartSummary(
            ChartAccessibility(title = "Monthly revenue", description = "In shillings"),
            listOf(summary("Jan" to 24_000.0)),
            formatter,
        )
        assertTrue(text.startsWith("Monthly revenue In shillings"))
    }

    @Test
    fun `values are read out for a small series`() {
        val text = buildChartSummary(
            ChartAccessibility.Auto,
            listOf(summary("Jan" to 24_000.0, "Feb" to 31_000.0)),
            formatter,
        )
        assertTrue(text.contains("Jan: 24,000"))
        assertTrue(text.contains("Feb: 31,000"))
    }

    @Test
    fun `a long series is summarised by range rather than read out in full`() {
        val entries = (1..100).map { "$it" to it.toDouble() }.toTypedArray()
        val text = buildChartSummary(ChartAccessibility.Auto, listOf(summary(*entries)), formatter)
        assertTrue(text.contains("100 data points"))
        assertTrue(text.contains("Values from 1 to 100"))
        assertFalse("100 numbers must not be read aloud", text.contains("47: 47"))
    }

    @Test
    fun `missing values are counted and named, not silently dropped`() {
        val text = buildChartSummary(
            ChartAccessibility.Auto,
            listOf(summary("Jan" to 1.0, "Feb" to null)),
            formatter,
        )
        assertTrue(text.contains("1 missing"))
        assertTrue(text.contains("Feb: no value"))
    }

    @Test
    fun `multiple series are named up front`() {
        val text = buildChartSummary(
            ChartAccessibility.Auto,
            listOf(summary("Jan" to 1.0, name = "Revenue"), summary("Jan" to 2.0, name = "Expenses")),
            formatter,
        )
        assertTrue(text.contains("2 series: Revenue, Expenses"))
    }

    @Test
    fun `the announcement makes no statistical claims`() {
        val rising = (1..10).map { "$it" to it.toDouble() * 10 }.toTypedArray()
        val text = buildChartSummary(ChartAccessibility.Auto, listOf(summary(*rising)), formatter)
        // Factual only. A trend claim ChartKit has not computed would be an
        // assertion a reader who cannot see the chart has no way to check.
        listOf("trend", "increas", "decreas", "correlat", "strong").forEach {
            assertFalse("summary should not claim '$it': $text", text.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun `an empty chart says so`() {
        assertTrue(buildChartSummary(ChartAccessibility.Auto, emptyList(), formatter).contains("No data"))
    }

    @Test
    fun `turning off data points still reports the series and its count`() {
        val text = buildChartSummary(
            ChartAccessibility(includeDataPoints = false),
            listOf(summary("Jan" to 1.0, "Feb" to 2.0)),
            formatter,
        )
        assertTrue(text.contains("2 data points"))
        assertFalse(text.contains("Jan: 1"))
    }

    @Test
    fun `a selection announcement names the series only when there are several`() {
        val single = describeSelection("Revenue", "Jan", 24_000.0, formatter, multiSeries = false)
        assertEquals("Jan: 24,000", single)

        val multi = describeSelection("Revenue", "Jan", 24_000.0, formatter, multiSeries = true)
        assertEquals("Revenue, Jan: 24,000", multi)
    }
}

class ChartPaletteTest {

    private val blue = 0xFF1565C0.toInt()

    @Test
    fun `the requested number of colours is produced`() {
        assertEquals(12, ChartPalette.derive(blue, isDark = false, count = 12).size)
        assertTrue(ChartPalette.derive(blue, isDark = false, count = 0).isEmpty())
    }

    @Test
    fun `adjacent series are far apart in hue`() {
        val palette = ChartPalette.derive(blue, isDark = false, count = 6)
        palette.zipWithNext { a, b ->
            val separation = hueSeparation(a, b)
            assertTrue("adjacent colours only $separation apart", separation > 30f)
        }
    }

    @Test
    fun `every colour is distinct`() {
        val palette = ChartPalette.derive(blue, isDark = false, count = 12)
        assertEquals(palette.size, palette.distinct().size)
    }

    @Test
    fun `light and dark palettes differ, because contrast targets differ`() {
        val light = ChartPalette.derive(blue, isDark = false, count = 4)
        val dark = ChartPalette.derive(blue, isDark = true, count = 4)
        assertNotEquals(light, dark)
    }

    @Test
    fun `the palette follows the seed, so it inherits the app's brand`() {
        val fromBlue = ChartPalette.derive(blue, isDark = false, count = 1).single()
        val fromOrange = ChartPalette.derive(0xFFE65100.toInt(), isDark = false, count = 1).single()
        assertTrue(hueSeparation(fromBlue, fromOrange) > 20f)
    }

    @Test
    fun `a greyscale seed still produces a usable palette`() {
        val palette = ChartPalette.derive(0xFF808080.toInt(), isDark = false, count = 6)
        assertEquals(6, palette.distinct().size)
    }

    @Test
    fun `every colour is fully opaque`() {
        ChartPalette.derive(blue, isDark = true, count = 12).forEach {
            assertEquals(0xFF, (it ushr 24) and 0xFF)
        }
    }

    @Test
    fun `more series than hues still yields distinct colours`() {
        val palette = ChartPalette.derive(blue, isDark = false, count = 20)
        assertEquals(20, palette.distinct().size)
    }

    @Test
    fun `hsl round-trips`() {
        listOf(blue, 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF00FF7F.toInt()).forEach { colour ->
            val hsl = ChartPalette.rgbToHsl(colour)
            val back = ChartPalette.hslToRgb(hsl[0], hsl[1], hsl[2])
            listOf(16, 8, 0).forEach { shift ->
                val expected = (colour shr shift) and 0xFF
                val actual = (back shr shift) and 0xFF
                assertTrue("channel drift of ${abs(expected - actual)}", abs(expected - actual) <= 2)
            }
        }
    }

    private fun hueSeparation(a: Int, b: Int): Float {
        val difference = abs(ChartPalette.rgbToHsl(a)[0] - ChartPalette.rgbToHsl(b)[0])
        return min(difference, 360f - difference)
    }
}
