package io.example.consumer

import io.devkit.chartkit.ChartKitVersion
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.scale.LinearScale
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.scale.TickGenerator
import io.devkit.core.DevKitDistribution
import java.util.Locale

/**
 * Release-safe usage of ChartKit, compiled against the **published** artifact.
 *
 * In `src/main`, so it is compiled into the release variant too. If ChartKit
 * were classified or published as debug-only, this file would not build — which
 * is the claim being verified, not merely asserted.
 *
 * It reaches into the engine — scales, ticks, series, formatters — rather than
 * only reading a version constant, so the AAR is proven to contain its classes
 * and not merely to resolve. Compose composables are deliberately not touched
 * here: this module has no Compose plugin, and driving the whole Compose
 * toolchain would test AGP rather than ChartKit's publication.
 */
object ChartKitUsage {

    fun describe(): String = ChartKitVersion.chartKit.qualifiedLabel

    /** ChartKit is a runtime library, and the artifact metadata says so. */
    fun isReleaseSafe(): Boolean =
        ChartKitVersion.chartKit.distribution == DevKitDistribution.RUNTIME

    /** A real series over a consumer's own type, with no conversion step. */
    data class Revenue(val month: String, val amount: Double)

    fun sampleSeries(): ChartSeries<Revenue> = ChartSeries(
        id = "revenue",
        name = "Revenue",
        data = listOf(Revenue("Jan", 24_000.0), Revenue("Feb", 31_500.0)),
    )

    /** Exercises the scale and tick engine end to end. */
    fun axisLabels(): List<String> {
        val domain = NumericDomain(0.0, 100.0)
        val scale = LinearScale(domain, rangeStart = 0f, rangeEnd = 800f)
        val formatter = ChartNumberFormatters.compact(locale = Locale.UK)
        return scale.ticks(5).map(formatter::format)
    }

    /** The tick generator is public engine surface too. */
    fun tickCount(): Int = TickGenerator.ticks(NumericDomain(0.0, 97.0), 5).size
}
