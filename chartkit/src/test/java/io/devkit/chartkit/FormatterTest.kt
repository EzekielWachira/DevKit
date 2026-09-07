package io.devkit.chartkit

import io.devkit.chartkit.formatter.ChartDateFormatters
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.formatter.ChartValueFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * Formatting is asserted against an explicit locale throughout. Asserting
 * against the default would make these tests pass or fail depending on the
 * machine running them, which is the same bug the formatters exist to avoid.
 */
class FormatterTest {

    private val uk = Locale.UK
    private val germany = Locale.GERMANY

    @Test
    fun `integers are grouped`() {
        assertEquals("1,235", ChartNumberFormatters.integer(uk).format(1234.6))
    }

    @Test
    fun `grouping and decimal separators follow the locale`() {
        val value = 1234.5
        assertEquals("1,234.50", ChartNumberFormatters.decimal(2, uk).format(value))
        assertEquals("1.234,50", ChartNumberFormatters.decimal(2, germany).format(value))
    }

    @Test
    fun `compact form shortens large numbers`() {
        val compact = ChartNumberFormatters.compact(1, uk)
        assertEquals("1.2K", compact.format(1_200.0))
        assertEquals("2.4M", compact.format(2_400_000.0))
        assertEquals("3.1B", compact.format(3_100_000_000.0))
        assertEquals("999", compact.format(999.0))
    }

    @Test
    fun `compact form handles negatives`() {
        assertEquals("-1.2K", ChartNumberFormatters.compact(1, uk).format(-1_200.0))
    }

    @Test
    fun `percent formats a value already in percent`() {
        assertEquals("42.5%", ChartNumberFormatters.percent(1, uk).format(42.5))
    }

    @Test
    fun `fraction formats a value in zero to one`() {
        assertEquals("43%", ChartNumberFormatters.fraction(0, uk).format(0.4256))
        assertEquals("42.6%", ChartNumberFormatters.fraction(1, uk).format(0.4256))
    }

    @Test
    fun `currency uses the named currency, never a default one`() {
        val formatted = ChartNumberFormatters.currency("KES", 0, uk).format(1500.0)
        assertTrue("expected a KES marker in '$formatted'", formatted.contains("KES") || formatted.contains("KSh"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a blank currency code is rejected`() {
        ChartNumberFormatters.currency("")
    }

    @Test
    fun `non-finite values format as empty rather than as NaN`() {
        listOf(
            ChartNumberFormatters.integer(uk),
            ChartNumberFormatters.decimal(2, uk),
            ChartNumberFormatters.compact(1, uk),
            ChartNumberFormatters.percent(0, uk),
            ChartValueFormatter.Raw,
        ).forEach { formatter ->
            assertEquals("", formatter.format(Double.NaN))
            assertEquals("", formatter.format(Double.POSITIVE_INFINITY))
        }
    }

    @Test
    fun `axis precision is derived from the ticks so labels agree with each other`() {
        val formatter = ChartNumberFormatters.forTicks(listOf(0.0, 0.5, 1.0), uk)
        assertEquals("0.0", formatter.format(0.0))
        assertEquals("1.0", formatter.format(1.0))
    }

    @Test
    fun `dates format with the given pattern, locale and time zone`() {
        val utc = TimeZone.getTimeZone("UTC")
        val formatter = ChartDateFormatters.pattern("yyyy-MM-dd HH:mm", uk, utc)
        assertEquals("2023-11-14 22:13", formatter.format(1_700_000_000_000L))
    }

    @Test
    fun `time zone changes the rendered instant`() {
        val utc = ChartDateFormatters.pattern("HH", uk, TimeZone.getTimeZone("UTC"))
        val nairobi = ChartDateFormatters.pattern("HH", uk, TimeZone.getTimeZone("Africa/Nairobi"))
        assertTrue(utc.format(1_700_000_000_000L) != nairobi.format(1_700_000_000_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a blank date pattern is rejected`() {
        ChartDateFormatters.pattern("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an impossible decimal count is rejected`() {
        ChartNumberFormatters.decimal(decimals = 99)
    }
}
