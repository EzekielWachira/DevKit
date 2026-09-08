package io.devkit.chartdemo

import io.devkit.chartkit.model.ChartSeries

/**
 * The sample's own domain types, charted directly.
 *
 * The point of this file is what it does *not* contain: no conversion into a
 * ChartKit entry type. `Revenue` and `Cohort` are ordinary application data
 * classes, and the charts read them through lambdas.
 */
object ChartDemoData {

    data class Revenue(val month: String, val amount: Double)

    data class Cohort(val quarter: String, val segment: String, val customers: Double)

    data class Reading(val atMillis: Long, val celsius: Double?)

    val revenue: List<Revenue> = listOf(
        Revenue("Jan", 24_000.0),
        Revenue("Feb", 31_500.0),
        Revenue("Mar", 28_200.0),
        Revenue("Apr", 39_800.0),
        Revenue("May", 44_100.0),
        Revenue("Jun", 41_600.0),
    )

    val expenses: List<Revenue> = listOf(
        Revenue("Jan", 18_400.0),
        Revenue("Feb", 21_100.0),
        Revenue("Mar", 22_700.0),
        Revenue("Apr", 25_300.0),
        Revenue("May", 24_900.0),
        Revenue("Jun", 27_800.0),
    )

    val forecast: List<Revenue> = listOf(
        Revenue("Jan", 22_000.0),
        Revenue("Feb", 27_000.0),
        Revenue("Mar", 32_000.0),
        Revenue("Apr", 37_000.0),
        Revenue("May", 42_000.0),
        Revenue("Jun", 47_000.0),
    )

    /** Mixed signs, so the baseline and negative bars are always on show. */
    val netMargin: List<Revenue> = listOf(
        Revenue("Jan", 5_600.0),
        Revenue("Feb", 10_400.0),
        Revenue("Mar", -2_300.0),
        Revenue("Apr", 14_500.0),
        Revenue("May", -1_800.0),
        Revenue("Jun", 13_800.0),
    )

    /** Long labels, which is what forces the axis to thin or rotate. */
    val productLines: List<Revenue> = listOf(
        Revenue("Professional services", 82_000.0),
        Revenue("Platform subscriptions", 141_000.0),
        Revenue("Hardware resale", 37_500.0),
        Revenue("Support contracts", 64_200.0),
        Revenue("Training", 18_900.0),
    )

    val newCustomers: List<Cohort> = listOf(
        Cohort("Q1", "New", 320.0),
        Cohort("Q2", "New", 410.0),
        Cohort("Q3", "New", 280.0),
        Cohort("Q4", "New", 500.0),
    )

    val returningCustomers: List<Cohort> = listOf(
        Cohort("Q1", "Returning", 480.0),
        Cohort("Q2", "Returning", 520.0),
        Cohort("Q3", "Returning", 610.0),
        Cohort("Q4", "Returning", 590.0),
    )

    val churnedCustomers: List<Cohort> = listOf(
        Cohort("Q1", "Churned", 90.0),
        Cohort("Q2", "Churned", 140.0),
        Cohort("Q3", "Churned", 75.0),
        Cohort("Q4", "Churned", 160.0),
    )

    /** A deliberate `null`, so the missing-value policy has something to do. */
    val readings: List<Reading> = run {
        val start = 1_700_000_000_000L
        val hour = 60 * 60 * 1000L
        listOf(
            Reading(start, 12.4),
            Reading(start + hour, 13.1),
            Reading(start + 2 * hour, 15.8),
            Reading(start + 3 * hour, null),
            Reading(start + 4 * hour, 14.2),
            Reading(start + 5 * hour, 16.9),
            Reading(start + 6 * hour, 18.3),
        )
    }

    fun revenueSeries(): List<ChartSeries<Revenue>> = listOf(
        ChartSeries(id = "revenue", name = "Revenue", data = revenue),
        ChartSeries(id = "expenses", name = "Expenses", data = expenses),
        ChartSeries(id = "forecast", name = "Forecast", data = forecast),
    )

    fun cohortSeries(): List<ChartSeries<Cohort>> = listOf(
        ChartSeries(id = "new", name = "New", data = newCustomers),
        ChartSeries(id = "returning", name = "Returning", data = returningCustomers),
        ChartSeries(id = "churned", name = "Churned", data = churnedCustomers),
    )

    /** A part-to-whole breakdown, for pie and donut charts. */
    data class Expense(val category: String, val amount: Double)

    /** A metric measured against a range, for radial bars. */
    data class Metric(val name: String, val value: Double, val unit: String)

    val expenseBreakdown: List<Expense> = listOf(
        Expense("Rent", 1_800.0),
        Expense("Food", 720.0),
        Expense("Transport", 540.0),
        Expense("Utilities", 360.0),
        Expense("Savings", 300.0),
        Expense("Other", 180.0),
    )

    val expenseTotal: Double = expenseBreakdown.sumOf { it.amount }

    val systemMetrics: List<Metric> = listOf(
        Metric("CPU", 72.0, "%"),
        Metric("Memory", 46.0, "%"),
        Metric("Disk", 88.0, "%"),
        Metric("Network", 31.0, "%"),
    )

    /**
     * Two years of daily readings — enough that the whole series is a smear at
     * full extent and only becomes readable once zoomed.
     */
    val dailyReadings: List<Reading> = run {
        val start = 1_700_000_000_000L
        val day = 24 * 60 * 60 * 1000L
        List(730) { index ->
            val seasonal = 12.0 * kotlin.math.sin(index / 58.0)
            val weekly = 3.0 * kotlin.math.sin(index / 1.1)
            Reading(start + index * day, 18.0 + seasonal + weekly)
        }
    }

    /** A dense, deterministic waveform for the performance demonstration. */
    fun dense(count: Int): List<Revenue> = List(count) { index ->
        val angle = index / 40.0
        Revenue(
            month = index.toString(),
            amount = 50.0 + 30.0 * kotlin.math.sin(angle) + 12.0 * kotlin.math.cos(angle * 3),
        )
    }

    // ---- statistical --------------------------------------------------------

    /** One person measured twice, for scatter and bubble demonstrations. */
    data class Person(val heightCm: Double, val weightKg: Double, val ageYears: Double, val group: String)

    /** A named set of observations. */
    data class Endpoint(val path: String, val latencies: List<Double>)

    /** One cell of a weekday-by-hour grid. */
    data class Traffic(val day: String, val hour: String, val requests: Double?)

    /** A dated count, for the calendar heatmap. */
    data class Activity(val dateMillis: Long, val commits: Double)

    /** One axis of a profile, for the radar chart. */
    data class Rating(val aspect: String, val score: Double)

    /** One trading period. */
    data class Candle(
        val timeMillis: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
    )

    val people: List<Person> = List(160) { index ->
        val t = index / 159.0
        val group = if (index % 2 == 0) "Cohort A" else "Cohort B"
        val lift = if (group == "Cohort A") 0.0 else 6.0
        Person(
            heightCm = 152.0 + t * 44.0 + kotlin.math.sin(index * 2.3) * 5.0,
            weightKg = 47.0 + t * 40.0 + lift + kotlin.math.cos(index * 1.9) * 6.0,
            ageYears = 18.0 + (index % 52),
            group = group,
        )
    }

    val endpoints: List<Endpoint> = listOf(
        Endpoint("/search", latencies(seed = 7, centre = 210.0, spread = 55.0, tail = 3)),
        Endpoint("/checkout", latencies(seed = 19, centre = 340.0, spread = 95.0, tail = 5)),
        Endpoint("/profile", latencies(seed = 31, centre = 118.0, spread = 22.0, tail = 1)),
        Endpoint("/feed", latencies(seed = 53, centre = 185.0, spread = 145.0, tail = 2)),
    )

    /** Every response time, for the histogram. */
    val responseTimes: List<Double> = endpoints.flatMap { it.latencies }

    val trafficGrid: List<Traffic> = run {
        val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val hours = listOf("00", "03", "06", "09", "12", "15", "18", "21")
        buildList {
            days.forEachIndexed { dayIndex, day ->
                hours.forEachIndexed { hourIndex, hour ->
                    // Two cells nobody measured — deliberately absent rather
                    // than zero, which is the distinction the chart draws.
                    val missing = dayIndex >= 5 && hourIndex == 1
                    val weekend = dayIndex >= 5
                    val shape = kotlin.math.sin((hourIndex + 0.5) / hours.size * Math.PI)
                    add(
                        Traffic(
                            day = day,
                            hour = hour,
                            requests = when {
                                missing -> null
                                else -> 90.0 + 1_100.0 * shape * (if (weekend) 0.4 else 1.0)
                            },
                        ),
                    )
                }
            }
        }
    }

    /** A year of activity with weekends quiet and a fortnight genuinely absent. */
    val dailyActivity: List<Activity> = run {
        val start = 1_704_067_200_000L // 2024-01-01T00:00:00Z
        val day = 86_400_000L
        (0 until 366).mapNotNull { index ->
            if (index in 190..203) return@mapNotNull null
            val weekend = (index + 1) % 7 < 2
            val base = if (weekend) 0.0 else 5.0
            val wave = kotlin.math.abs(kotlin.math.sin(index / 8.0)) * 9.0
            Activity(start + index * day, (base + wave).toInt().toDouble())
        }
    }

    val profileThisQuarter: List<Rating> = listOf(
        Rating("Speed", 78.0),
        Rating("Reliability", 92.0),
        Rating("Coverage", 61.0),
        Rating("Cost", 45.0),
        Rating("Support", 70.0),
        Rating("Docs", 83.0),
    )

    val profileLastQuarter: List<Rating> = listOf(
        Rating("Speed", 64.0),
        Rating("Reliability", 88.0),
        Rating("Coverage", 52.0),
        Rating("Cost", 58.0),
        Rating("Support", 74.0),
        Rating("Docs", 61.0),
    )

    /**
     * Six months of daily prices, with the weekends missing.
     *
     * The gaps are deliberate: a financial chart draws them as the absences
     * they are rather than inventing flat candles for days nothing traded.
     */
    val prices: List<Candle> = run {
        val start = 1_704_067_200_000L
        val day = 86_400_000L
        var close = 182.0
        buildList {
            var index = 0
            var emitted = 0
            while (emitted < 130) {
                if ((index + 1) % 7 >= 2) {
                    val drift = kotlin.math.sin(emitted / 13.0) * 3.4 +
                        kotlin.math.cos(emitted / 4.5) * 1.4
                    val open = close
                    close = (open + drift).coerceAtLeast(20.0)
                    add(
                        Candle(
                            timeMillis = start + index * day,
                            open = open,
                            high = maxOf(open, close) + kotlin.math.abs(drift) * 0.7 + 0.5,
                            low = minOf(open, close) - kotlin.math.abs(drift) * 0.6 - 0.4,
                            close = close,
                            volume = 1_600_000.0 + kotlin.math.abs(drift) * 1_100_000.0,
                        ),
                    )
                    emitted++
                }
                index++
            }
        }
    }

    /** The closing prices, for a moving-average overlay. */
    val closes: List<Double?> = prices.map { it.close }

    /**
     * A dense minute-by-minute series of [count] readings.
     *
     * Generated rather than stored: a hundred thousand points is the whole
     * point of the large-dataset demo, and shipping them in the APK would not
     * be.
     */
    fun denseReadings(count: Int): List<Reading> {
        val start = 1_700_000_000_000L
        return List(count) { index ->
            val t = index.toDouble()
            val slow = kotlin.math.sin(t / 1_100.0) * 40.0
            val medium = kotlin.math.sin(t / 95.0) * 13.0
            val fast = kotlin.math.sin(t / 6.0) * 3.0
            // A one-sample spike every 3,001 readings: rare enough that a naive
            // sampler loses it, which is what makes the strategies comparable.
            val spike = if (index > 0 && index % 3_001 == 0) 60.0 else 0.0
            Reading(start + index * 60_000L, 100.0 + slow + medium + fast + spike)
        }
    }

    /**
     * Deterministic pseudo-samples with a controlled centre, spread and tail.
     *
     * A fixed linear congruential sequence, so the demo looks identical on
     * every run and on every device — which is what makes a screenshot of it
     * worth anything.
     */
    private fun latencies(seed: Int, centre: Double, spread: Double, tail: Int): List<Double> {
        var value = seed.toLong()
        fun next(): Double {
            value = (value * 1_103_515_245L + 12_345L) and 0x7FFFFFFFL
            return value / 0x7FFFFFFF.toDouble()
        }
        val body = List(90) { centre + ((next() + next() + next()) / 3.0 - 0.5) * spread * 4.0 }
        val outliers = List(tail) { centre + spread * (4.0 + next() * 3.0) }
        return (body + outliers).map { kotlin.math.round(it * 10) / 10.0 }
    }
}
