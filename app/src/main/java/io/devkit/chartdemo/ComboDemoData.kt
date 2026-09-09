package io.devkit.chartdemo

/**
 * Illustrative data for the multi-axis combo demos.
 *
 * Written here rather than fetched, and invented rather than copied: the point
 * of a reference-parity demo is to show that ChartKit can express the *shape*
 * of a multi-unit chart, not to reproduce anybody else's numbers. The weather
 * figures are a plausible temperate year; the business ones a plausible SaaS
 * quarter.
 */
object ComboDemoData {

    /** One month of weather in three units. */
    data class WeatherMonth(
        val month: String,
        /** Millimetres. */
        val rainfall: Double,
        /** Degrees Celsius. */
        val temperature: Double,
        /** Hectopascals. */
        val pressure: Double,
    )

    val weather: List<WeatherMonth> = listOf(
        WeatherMonth("Jan", 89.0, 4.2, 1016.0),
        WeatherMonth("Feb", 64.0, 4.6, 1019.0),
        WeatherMonth("Mar", 58.0, 6.9, 1015.0),
        WeatherMonth("Apr", 46.0, 9.4, 1014.0),
        WeatherMonth("May", 51.0, 12.8, 1013.0),
        WeatherMonth("Jun", 47.0, 15.9, 1012.0),
        WeatherMonth("Jul", 44.0, 18.1, 1013.0),
        WeatherMonth("Aug", 55.0, 17.8, 1014.0),
        WeatherMonth("Sep", 62.0, 15.1, 1016.0),
        WeatherMonth("Oct", 87.0, 11.4, 1015.0),
        WeatherMonth("Nov", 92.0, 7.5, 1017.0),
        WeatherMonth("Dec", 96.0, 5.1, 1018.0),
    )

    /** One month of a booking business, in three units. */
    data class BusinessMonth(
        val month: String,
        /** A count. */
        val bookings: Double,
        /** Pounds. */
        val revenue: Double,
        /** Percent. */
        val conversion: Double,
    )

    val business: List<BusinessMonth> = listOf(
        BusinessMonth("Jan", 1_240.0, 86_400.0, 2.8),
        BusinessMonth("Feb", 1_310.0, 94_100.0, 3.1),
        BusinessMonth("Mar", 1_680.0, 121_500.0, 3.6),
        BusinessMonth("Apr", 1_520.0, 110_200.0, 3.3),
        BusinessMonth("May", 1_790.0, 138_700.0, 3.9),
        BusinessMonth("Jun", 2_040.0, 162_300.0, 4.4),
        BusinessMonth("Jul", 1_960.0, 155_900.0, 4.1),
        BusinessMonth("Aug", 1_720.0, 131_400.0, 3.5),
        BusinessMonth("Sep", 2_180.0, 178_600.0, 4.6),
    )

    /** A quarter of profit and margin change, both crossing zero. */
    data class ChangeMonth(
        val month: String,
        /** Thousands of pounds, positive and negative. */
        val profitChange: Double,
        /** Percentage points, positive and negative. */
        val marginChange: Double,
    )

    val changes: List<ChangeMonth> = listOf(
        ChangeMonth("Jan", -42.0, -1.8),
        ChangeMonth("Feb", -18.0, -0.7),
        ChangeMonth("Mar", 27.0, 1.1),
        ChangeMonth("Apr", 64.0, 2.4),
        ChangeMonth("May", 31.0, 0.9),
        ChangeMonth("Jun", -9.0, -0.4),
        ChangeMonth("Jul", 78.0, 2.9),
        ChangeMonth("Aug", 96.0, 3.6),
    )

    /** One trading day: a candle, a moving average and a volume. */
    data class TradingDay(
        val at: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
    )

    /**
     * Thirty sessions of a synthetic instrument.
     *
     * Generated from a fixed arithmetic walk rather than from a random seed, so
     * the demo — and the screenshot test over it — draws the same picture every
     * run. A chart whose sample data moved would make a visual regression
     * impossible to tell from a real one.
     */
    val trading: List<TradingDay> = buildList {
        val day = 86_400_000L
        val start = 1_714_521_600_000L
        var close = 128.0
        repeat(30) { index ->
            // A deterministic wobble: two out-of-phase triangle waves, so the
            // series has both short swings and a slow drift without randomness.
            val swing = ((index % 7) - 3) * 1.4
            val drift = ((index % 11) - 5) * 0.6
            val open = close
            val settle = (open + swing + drift).coerceAtLeast(80.0)
            val high = maxOf(open, settle) + 1.2 + (index % 3)
            val low = minOf(open, settle) - 1.1 - (index % 4) * 0.5
            add(
                TradingDay(
                    at = start + index * day,
                    open = open,
                    high = high,
                    low = low,
                    close = settle,
                    volume = 240_000.0 + (index % 6) * 55_000.0 + (index % 4) * 31_000.0,
                ),
            )
            close = settle
        }
    }

    /** A twenty-session simple moving average of [trading]'s closes. */
    fun movingAverage(window: Int = 5): List<TradingDay> =
        trading.mapIndexed { index, day ->
            val from = (index - window + 1).coerceAtLeast(0)
            val mean = trading.subList(from, index + 1).map { it.close }.average()
            day.copy(close = mean)
        }
}
