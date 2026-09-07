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

    /** A dense, deterministic waveform for the performance demonstration. */
    fun dense(count: Int): List<Revenue> = List(count) { index ->
        val angle = index / 40.0
        Revenue(
            month = index.toString(),
            amount = 50.0 + 30.0 * kotlin.math.sin(angle) + 12.0 * kotlin.math.cos(angle * 3),
        )
    }
}
