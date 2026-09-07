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
}
