package io.devkit.chartdemo

import io.devkit.chartkit.gauge.GaugeMarker
import io.devkit.chartkit.gauge.GaugeMarkerShape
import io.devkit.chartkit.layer.polar.GaugeBand

/**
 * Deterministic sample readings for the gauge demos.
 *
 * Local and generated from a fixed walk rather than from a random seed or a
 * network call: the realtime demo has to draw the same sequence on every run,
 * or a screenshot of it can never be compared with another.
 */
object GaugeDemoData {

    /** The reference speedometer's thresholds, in km/h. */
    val speedBands: List<GaugeBand> = listOf(
        GaugeBand(0.0, 120.0, "Normal"),
        GaugeBand(120.0, 160.0, "Caution"),
        GaugeBand(160.0, 200.0, "Over limit"),
    )

    /** A national limit, marked on the dial without a needle of its own. */
    val speedLimit: List<GaugeMarker> = listOf(
        GaugeMarker(value = 112.0, label = "Limit", shape = GaugeMarkerShape.Triangle),
    )

    /** Server load thresholds, in percent. */
    val loadBands: List<GaugeBand> = listOf(
        GaugeBand(0.0, 60.0, "Healthy"),
        GaugeBand(60.0, 85.0, "Busy"),
        GaugeBand(85.0, 100.0, "Saturated"),
    )

    /** Cabin temperature bands, in degrees Celsius, including a negative range. */
    val temperatureBands: List<GaugeBand> = listOf(
        GaugeBand(-20.0, 0.0, "Freezing"),
        GaugeBand(0.0, 18.0, "Cold"),
        GaugeBand(18.0, 24.0, "Comfortable"),
        GaugeBand(24.0, 40.0, "Warm"),
    )

    /** Quarterly attainment against a target, in percent. */
    val attainmentBands: List<GaugeBand> = listOf(
        GaugeBand(0.0, 70.0, "Behind"),
        GaugeBand(70.0, 100.0, "On track"),
        GaugeBand(100.0, 140.0, "Ahead"),
    )

    /**
     * A minute of plausible speed readings, one per tick.
     *
     * Two out-of-phase triangle waves rather than a random walk: the series has
     * both short changes and a slow drift, and it is identical on every run —
     * which is what lets the realtime demo be screenshotted and compared.
     */
    val speedTrace: List<Double> = List(TRACE_LENGTH) { index ->
        val swing = ((index % 13) - 6) * 6.0
        val drift = ((index % 31) - 15) * 3.4
        (96.0 + swing + drift).coerceIn(0.0, 200.0)
    }

    /** The same walk over a `0..100` load domain. */
    val loadTrace: List<Double> = List(TRACE_LENGTH) { index ->
        val swing = ((index % 7) - 3) * 7.0
        val drift = ((index % 17) - 8) * 2.6
        (54.0 + swing + drift).coerceIn(0.0, 100.0)
    }

    private const val TRACE_LENGTH = 120
}
