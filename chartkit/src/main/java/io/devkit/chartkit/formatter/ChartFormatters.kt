package io.devkit.chartkit.formatter

import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Turns a value into the text drawn on an axis, a label or a tooltip.
 *
 * A `fun interface`, so the common case is a lambda:
 *
 * ```kotlin
 * valueFormatter = ChartValueFormatter { "%.1f kg".format(it) }
 * ```
 *
 * and the built-ins below cover the rest.
 */
fun interface ChartValueFormatter {

    fun format(value: Double): String

    companion object {

        /** As typed, with trailing zeros trimmed. Rarely the right choice. */
        val Raw: ChartValueFormatter = ChartValueFormatter { value ->
            if (!value.isFinite()) "" else DecimalFormat("0.##########").format(value)
        }
    }
}

/**
 * Number formatting that follows the device locale.
 *
 * Locale is read at construction rather than hardcoded, and defaults to
 * [Locale.getDefault]. A chart that renders `1,234.5` to a reader whose locale
 * writes `1.234,5` is displaying a number they will read wrongly, and grouping
 * separators are exactly where that goes unnoticed.
 *
 * The chart-facing formatters are created inside composition from the
 * composition's own locale, so a configuration change re-derives them.
 */
object ChartNumberFormatters {

    /** `1,235` — grouped, no fractional part. */
    fun integer(locale: Locale = Locale.getDefault()): ChartValueFormatter {
        val format = NumberFormat.getIntegerInstance(locale)
        return ChartValueFormatter { value ->
            if (!value.isFinite()) "" else format.format(value)
        }
    }

    /** `1,234.50` — grouped, with exactly [decimals] fractional digits. */
    fun decimal(
        decimals: Int = 2,
        locale: Locale = Locale.getDefault(),
    ): ChartValueFormatter {
        require(decimals in 0..15) { "decimals must be in 0..15, was $decimals" }
        val format = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }
        return ChartValueFormatter { value ->
            if (!value.isFinite()) "" else format.format(value)
        }
    }

    /**
     * The number of decimals an axis needs, chosen from its own ticks.
     *
     * Axis labels have to agree with each other — `0`, `0.5`, `1` reads as
     * three different precisions of the same axis — so the tick list decides,
     * not each value.
     */
    fun forTicks(
        ticks: List<Double>,
        locale: Locale = Locale.getDefault(),
    ): ChartValueFormatter = decimal(
        decimals = io.devkit.chartkit.scale.TickGenerator.suggestedDecimals(ticks),
        locale = locale,
    )

    /**
     * `1.2K`, `2.4M`, `3.1B` — short forms for axis labels with no room.
     *
     * The suffixes are ASCII and not localised, which is a real limitation: a
     * locale that abbreviates differently gets the English form. Localising
     * them properly needs `CompactDecimalFormat`, which is API 24 on Android
     * but whose behaviour varies by ICU version, so ChartKit states the limit
     * rather than shipping something that changes shape between devices. Consumers
     * needing localised compaction supply their own formatter.
     */
    fun compact(
        decimals: Int = 1,
        locale: Locale = Locale.getDefault(),
    ): ChartValueFormatter {
        val format = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = decimals
        }
        val whole = NumberFormat.getIntegerInstance(locale)
        return ChartValueFormatter { value ->
            if (!value.isFinite()) {
                ""
            } else {
                val magnitude = abs(value)
                when {
                    magnitude >= 1_000_000_000_000.0 ->
                        format.format(value / 1_000_000_000_000.0) + "T"
                    magnitude >= 1_000_000_000.0 -> format.format(value / 1_000_000_000.0) + "B"
                    magnitude >= 1_000_000.0 -> format.format(value / 1_000_000.0) + "M"
                    magnitude >= 1_000.0 -> format.format(value / 1_000.0) + "K"
                    else -> whole.format(value)
                }
            }
        }
    }

    /**
     * `42.5%` from a value already expressed in percent.
     *
     * Use [fraction] for values in `0..1`. Two formatters rather than a flag,
     * because a single one silently renders `0.42` as `0.4%` or `42%`
     * depending on a boolean somebody has to be right about.
     */
    fun percent(
        decimals: Int = 0,
        locale: Locale = Locale.getDefault(),
    ): ChartValueFormatter {
        val format = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }
        return ChartValueFormatter { value ->
            if (!value.isFinite()) "" else format.format(value) + "%"
        }
    }

    /** `42.5%` from a fraction in `0..1`. What 100% stacked charts label with. */
    fun fraction(
        decimals: Int = 0,
        locale: Locale = Locale.getDefault(),
    ): ChartValueFormatter {
        val format = NumberFormat.getPercentInstance(locale).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }
        return ChartValueFormatter { value ->
            if (!value.isFinite()) "" else format.format(value)
        }
    }

    /**
     * Currency in the given [currencyCode], formatted for [locale].
     *
     * No default currency. There is no sensible one — assuming USD is how a
     * library ends up labelling Kenyan shillings with a dollar sign — so the
     * caller names it.
     */
    fun currency(
        currencyCode: String,
        decimals: Int = 0,
        locale: Locale = Locale.getDefault(),
    ): ChartValueFormatter {
        require(currencyCode.isNotBlank()) { "A currency formatter needs a currency code" }
        val format = NumberFormat.getCurrencyInstance(locale).apply {
            runCatching { currency = java.util.Currency.getInstance(currencyCode) }
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }
        return ChartValueFormatter { value ->
            if (!value.isFinite()) "" else format.format(value)
        }
    }
}

/** Turns an epoch-millisecond instant into an axis or tooltip label. */
fun interface ChartTimeFormatter {

    fun format(epochMillis: Long): String
}

/**
 * Date and time labels, from a pattern and a locale.
 *
 * `java.text.SimpleDateFormat` rather than `java.time`: ChartKit's floor is
 * `minSdk` 24 and `DateTimeFormatter` is API 26, so using it would either raise
 * the floor or require every consumer to enable core-library desugaring. The
 * pattern syntax is the same for the subset charts need.
 *
 * Formatting runs in the device's default time zone unless one is supplied.
 * Charts of server-side data usually want to state one explicitly rather than
 * let the label move when the reader travels.
 */
object ChartDateFormatters {

    /** A formatter for [pattern], e.g. `"MMM"`, `"d MMM"`, `"HH:mm"`. */
    fun pattern(
        pattern: String,
        locale: Locale = Locale.getDefault(),
        timeZone: java.util.TimeZone = java.util.TimeZone.getDefault(),
    ): ChartTimeFormatter {
        require(pattern.isNotBlank()) { "A date formatter needs a pattern" }
        val format = java.text.SimpleDateFormat(pattern, locale).apply {
            this.timeZone = timeZone
        }
        return ChartTimeFormatter { millis -> format.format(Date(millis)) }
    }

    /** The locale's own short date form. */
    fun shortDate(locale: Locale = Locale.getDefault()): ChartTimeFormatter {
        val format = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, locale)
        return ChartTimeFormatter { millis -> format.format(Date(millis)) }
    }

    /** The locale's own short time form. */
    fun shortTime(locale: Locale = Locale.getDefault()): ChartTimeFormatter {
        val format = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, locale)
        return ChartTimeFormatter { millis -> format.format(Date(millis)) }
    }
}
