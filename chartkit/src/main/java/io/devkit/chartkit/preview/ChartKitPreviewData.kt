package io.devkit.chartkit.preview

/**
 * Deterministic sample data for Compose previews, demos and tests.
 *
 * Fixed values, no randomness: a preview that redraws differently on every
 * recomposition is useless for judging a layout, and a screenshot test built on
 * one is useless full stop.
 *
 * Public because previews in a consumer's own module need it too, and because
 * it is the fastest way to see what a `ChartKitTheme` override looks like
 * before wiring real data. It carries no stability promise beyond that — treat
 * the numbers as illustrative, not as an API.
 */
object ChartKitPreviewData {

    /** One month's figure. The shape most charts in the wild actually have. */
    data class MonthlyValue(val month: String, val amount: Double)

    /** One quarter, split between two cohorts. */
    data class QuarterlySplit(val quarter: String, val newCustomers: Double, val returning: Double)

    /** A reading at an instant, for time-axis examples. */
    data class Reading(val atMillis: Long, val value: Double?)

    val revenue: List<MonthlyValue> = listOf(
        MonthlyValue("Jan", 24_000.0),
        MonthlyValue("Feb", 31_500.0),
        MonthlyValue("Mar", 28_200.0),
        MonthlyValue("Apr", 39_800.0),
        MonthlyValue("May", 44_100.0),
        MonthlyValue("Jun", 41_600.0),
    )

    val expenses: List<MonthlyValue> = listOf(
        MonthlyValue("Jan", 18_400.0),
        MonthlyValue("Feb", 21_100.0),
        MonthlyValue("Mar", 22_700.0),
        MonthlyValue("Apr", 25_300.0),
        MonthlyValue("May", 24_900.0),
        MonthlyValue("Jun", 27_800.0),
    )

    /** Deliberately includes negatives, so previews cover the baseline case. */
    val netMargin: List<MonthlyValue> = listOf(
        MonthlyValue("Jan", 5_600.0),
        MonthlyValue("Feb", 10_400.0),
        MonthlyValue("Mar", -2_300.0),
        MonthlyValue("Apr", 14_500.0),
        MonthlyValue("May", -1_800.0),
        MonthlyValue("Jun", 13_800.0),
    )

    val cohorts: List<QuarterlySplit> = listOf(
        QuarterlySplit("Q1", 320.0, 480.0),
        QuarterlySplit("Q2", 410.0, 520.0),
        QuarterlySplit("Q3", 280.0, 610.0),
        QuarterlySplit("Q4", 500.0, 590.0),
    )

    val newCustomers: List<MonthlyValue> =
        cohorts.map { MonthlyValue(it.quarter, it.newCustomers) }

    val returningCustomers: List<MonthlyValue> =
        cohorts.map { MonthlyValue(it.quarter, it.returning) }

    /** Long category names, for testing axis label overlap handling. */
    val productLines: List<MonthlyValue> = listOf(
        MonthlyValue("Professional services", 82_000.0),
        MonthlyValue("Platform subscriptions", 141_000.0),
        MonthlyValue("Hardware resale", 37_500.0),
        MonthlyValue("Support contracts", 64_200.0),
        MonthlyValue("Training", 18_900.0),
    )

    /**
     * Hourly readings with a gap, to exercise the missing-value policy.
     *
     * The `null` is the point: the default is to break the line there rather
     * than to invent a zero or a straight line through the absence.
     */
    val sensorReadings: List<Reading> = run {
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

    /** Every value the same, which is the case that divides by a zero span. */
    val constantSeries: List<MonthlyValue> =
        listOf("Jan", "Feb", "Mar", "Apr").map { MonthlyValue(it, 42.0) }

    /** A part-to-whole breakdown, for pie and donut charts. */
    data class Share(val category: String, val amount: Double)

    /** A metric measured against a range, for radial bars. */
    data class Metric(val name: String, val value: Double)

    val expenseShares: List<Share> = listOf(
        Share("Rent", 1_800.0),
        Share("Food", 720.0),
        Share("Transport", 540.0),
        Share("Utilities", 360.0),
        Share("Other", 180.0),
    )

    /** Deliberately mixes a negative and a zero, which a pie cannot represent. */
    val invalidShares: List<Share> = listOf(
        Share("Valid", 40.0),
        Share("Negative", -10.0),
        Share("Zero", 0.0),
        Share("Also valid", 60.0),
    )

    val systemMetrics: List<Metric> = listOf(
        Metric("CPU", 72.0),
        Metric("Memory", 46.0),
        Metric("Disk", 88.0),
        Metric("Network", 31.0),
    )

    // ---- statistical ----------------------------------------------------

    /** Two measurements of one thing, for scatter and bubble examples. */
    data class Observation(val height: Double, val weight: Double, val age: Double)

    /** A named distribution, for box plots and violins. */
    data class Distribution(val name: String, val samples: List<Double>)

    /** One cell of a two-way grid. */
    data class GridCell(val day: String, val hour: String, val requests: Double?)

    /** A dated count, for calendar heatmaps. */
    data class DatedCount(val dateMillis: Long, val count: Double)

    /** One metric of a profile, for radar charts. */
    data class Score(val skill: String, val value: Double)

    /** One period of a price series. */
    data class Candle(
        val timeMillis: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
    )

    /**
     * A cloud with a visible relationship in it, generated deterministically.
     *
     * Not random: a preview that redraws differently every recomposition is
     * useless for judging a layout. The waveform is the same every run and on
     * every device.
     */
    val observations: List<Observation> = List(120) { index ->
        val t = index / 119.0
        val wobble = kotlin.math.sin(index * 2.4) * 6.0
        Observation(
            height = 150.0 + t * 45.0 + wobble,
            weight = 48.0 + t * 42.0 + kotlin.math.cos(index * 1.7) * 5.0,
            age = 18.0 + (index % 47),
        )
    }

    /** Response times per endpoint, with one clearly heavier tail. */
    val latencies: List<Distribution> = listOf(
        Distribution("/search", latencySamples(seed = 3, centre = 210.0, spread = 55.0, outliers = 3)),
        Distribution("/checkout", latencySamples(seed = 11, centre = 340.0, spread = 90.0, outliers = 5)),
        Distribution("/profile", latencySamples(seed = 23, centre = 120.0, spread = 25.0, outliers = 1)),
        Distribution("/feed", latencySamples(seed = 41, centre = 180.0, spread = 140.0, outliers = 2)),
    )

    /** The raw response times behind [latencies], flattened. */
    val responseTimes: List<Double> = latencies.flatMap { it.samples }

    /** Weekday-by-hour request volume, with two genuinely absent cells. */
    val trafficGrid: List<GridCell> = run {
        val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val hours = listOf("00", "04", "08", "12", "16", "20")
        buildList {
            days.forEachIndexed { dayIndex, day ->
                hours.forEachIndexed { hourIndex, hour ->
                    // Saturday and Sunday at 04:00 were never sampled — which
                    // is not the same as having been zero.
                    val missing = dayIndex >= 5 && hourIndex == 1
                    val weekday = if (dayIndex < 5) 1.0 else 0.45
                    val shape = kotlin.math.sin((hourIndex + 1) / 6.5 * Math.PI)
                    add(
                        GridCell(
                            day = day,
                            hour = hour,
                            requests = if (missing) null else (120.0 + 900.0 * shape * weekday),
                        ),
                    )
                }
            }
        }
    }

    /** A year of daily activity, with quiet weekends and a fortnight of silence. */
    val dailyActivity: List<DatedCount> = run {
        val start = 1_704_067_200_000L // 2024-01-01T00:00:00Z
        val day = 86_400_000L
        (0 until 365).mapNotNull { index ->
            // A fortnight away in July, absent rather than zero.
            if (index in 190..203) return@mapNotNull null
            val weekend = (index + 1) % 7 < 2
            val base = if (weekend) 1.0 else 6.0
            val wave = kotlin.math.abs(kotlin.math.sin(index / 9.0)) * 8.0
            DatedCount(start + index * day, (base + wave).toInt().toDouble())
        }
    }

    /** Two profiles over the same five metrics, for a radar comparison. */
    val profileQ1: List<Score> = listOf(
        Score("Speed", 78.0),
        Score("Reliability", 92.0),
        Score("Coverage", 61.0),
        Score("Cost", 45.0),
        Score("Support", 70.0),
    )

    val profileQ2: List<Score> = listOf(
        Score("Speed", 84.0),
        Score("Reliability", 88.0),
        Score("Coverage", 74.0),
        Score("Cost", 58.0),
        Score("Support", 66.0),
    )

    /**
     * A daily price series with a weekend gap every week.
     *
     * The gaps are the point: a financial chart draws them as absences rather
     * than fabricating flat candles for days nothing traded.
     */
    val prices: List<Candle> = run {
        val start = 1_704_067_200_000L
        val day = 86_400_000L
        var close = 182.0
        buildList {
            var index = 0
            var emitted = 0
            while (emitted < 120) {
                val weekday = (index + 1) % 7
                if (weekday >= 2) {
                    val drift = kotlin.math.sin(emitted / 11.0) * 3.2 +
                        kotlin.math.cos(emitted / 4.0) * 1.1
                    val open = close
                    close = (open + drift).coerceAtLeast(20.0)
                    val high = maxOf(open, close) + kotlin.math.abs(drift) * 0.6 + 0.4
                    val low = minOf(open, close) - kotlin.math.abs(drift) * 0.5 - 0.3
                    add(
                        Candle(
                            timeMillis = start + index * day,
                            open = open,
                            high = high,
                            low = low,
                            close = close,
                            volume = 1_800_000.0 + kotlin.math.abs(drift) * 900_000.0,
                        ),
                    )
                    emitted++
                }
                index++
            }
        }
    }

    /**
     * Deterministic pseudo-samples with a controlled centre, spread and tail.
     *
     * A fixed linear congruential sequence rather than `Random`: identical on
     * every platform and every run, which is what a preview and a screenshot
     * test both need.
     */
    private fun latencySamples(
        seed: Int,
        centre: Double,
        spread: Double,
        outliers: Int,
    ): List<Double> {
        var value = seed.toLong()
        fun next(): Double {
            value = (value * 1_103_515_245L + 12_345L) and 0x7FFFFFFFL
            return value / 0x7FFFFFFF.toDouble()
        }
        // The sum of three uniforms is roughly bell-shaped, which is what a
        // latency distribution looks like without needing a normal generator.
        val body = List(80) {
            centre + ((next() + next() + next()) / 3.0 - 0.5) * spread * 4.0
        }
        val tail = List(outliers) { centre + spread * (4.0 + next() * 3.0) }
        return (body + tail).map { kotlin.math.round(it * 10) / 10.0 }
    }

    /** A dense series, for checking marker thresholds and drawing cost. */
    fun dense(count: Int): List<MonthlyValue> = List(count) { index ->
        // A fixed waveform rather than a random walk: deterministic, and it
        // exercises curvature and sign changes.
        val angle = index / 12.0
        MonthlyValue(
            month = index.toString(),
            amount = 50.0 + 30.0 * kotlin.math.sin(angle) + 10.0 * kotlin.math.cos(angle * 3),
        )
    }

    /**
     * A dense time series of [count] readings, one per minute.
     *
     * Deterministic and cheap to generate, so the large-dataset demo can build
     * a hundred thousand points on demand without shipping them. The waveform
     * carries several scales of detail plus occasional single-sample spikes —
     * which is what makes the difference between downsampling strategies
     * visible rather than theoretical.
     */
    fun denseReadings(count: Int, startMillis: Long = 1_700_000_000_000L): List<Reading> =
        List(count) { index ->
            val t = index.toDouble()
            val slow = kotlin.math.sin(t / 900.0) * 40.0
            val medium = kotlin.math.sin(t / 90.0) * 12.0
            val fast = kotlin.math.sin(t / 7.0) * 3.0
            // A spike every 3,001 samples: rare enough to be lost by a naive
            // sampler, and the reason min/max sampling exists.
            val spike = if (index % 3_001 == 0 && index > 0) 55.0 else 0.0
            Reading(startMillis + index * 60_000L, 100.0 + slow + medium + fast + spike)
        }
}
