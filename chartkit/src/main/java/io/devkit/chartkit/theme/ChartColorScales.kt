package io.devkit.chartkit.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import io.devkit.chartkit.scale.ColorScale
import io.devkit.chartkit.scale.NumericDomain

/**
 * Colour scales built from the enclosing [ChartKitTheme].
 *
 * The bridge between the theme and [ColorScale]: a `ColorScale` is a plain
 * value with explicit colours in it, which is what makes it testable and
 * passable around — and these are how a chart gets one that matches the app
 * without the caller naming any colours.
 *
 * A `Heatmap` given no scale calls [continuous] with its own data's domain,
 * which is why an unconfigured heatmap already reads correctly in light and
 * dark.
 */
object ChartColorScales {

    /**
     * A ramp from the theme's low colour to its high one, across [domain].
     *
     * Two stops rather than three: a two-stop ramp within one hue has no
     * desaturated middle to pass through, and the theme's defaults are exactly
     * that. A caller wanting a diverging or multi-hue ramp builds a
     * [ColorScale.Continuous] with the stops they want.
     */
    @Composable
    @ReadOnlyComposable
    fun continuous(domain: NumericDomain): ColorScale.Continuous {
        val colors = ChartKitTheme.colors.heatmap
        return ColorScale.Continuous(domain, listOf(colors.low, colors.high))
    }

    /** The same ramp, cut into [steps] bands. */
    @Composable
    @ReadOnlyComposable
    fun quantized(domain: NumericDomain, steps: Int): ColorScale.Quantized {
        val colors = ChartKitTheme.colors.heatmap
        return ColorScale.Quantized(domain, listOf(colors.low, colors.high), steps)
    }

    /**
     * Bands at [thresholds], shaded along the theme's ramp.
     *
     * ```kotlin
     * val severity = ChartColorScales.threshold(
     *     thresholds = listOf(20.0, 50.0, 80.0),
     *     labels = listOf("ok", "elevated", "high", "critical"),
     * )
     * ```
     */
    @Composable
    @ReadOnlyComposable
    fun threshold(
        thresholds: List<Double>,
        labels: List<String> = emptyList(),
    ): ColorScale.Threshold {
        val colors = ChartKitTheme.colors.heatmap
        val bands = thresholds.size + 1
        val ramp = listOf(colors.low, colors.high)
        return ColorScale.Threshold(
            thresholds = thresholds,
            colors = List(bands) { index ->
                io.devkit.chartkit.scale.sampleRamp(ramp, (index + 0.5f) / bands)
            },
            labels = labels,
        )
    }

    /**
     * Bands holding roughly equal numbers of *observations*, over [values].
     *
     * ```kotlin
     * val scale = ChartColorScales.quantile(counties.map { it.revenue })
     * ```
     *
     * Takes the data rather than a domain, because that is what a quantile is
     * computed from. See [ColorScale.Quantile] for when it beats an equal-width
     * scale and what it costs.
     */
    @Composable
    @ReadOnlyComposable
    fun quantile(
        values: List<Double?>,
        groups: Int = ColorScale.DEFAULT_QUANTILE_GROUPS,
    ): ColorScale.Quantile {
        val colors = ChartKitTheme.colors.heatmap
        return ColorScale.Quantile(values, listOf(colors.low, colors.high), groups)
    }

    /** One colour per key, taken from the theme's series palette in order. */
    @Composable
    @ReadOnlyComposable
    fun categorical(keys: List<String>): ColorScale.Categorical {
        val palette = ChartKitTheme.colors.palette
        return ColorScale.Categorical(keys, palette)
    }

    /** The theme's "no measurement" colour, for a cell a scale could not place. */
    @Composable
    @ReadOnlyComposable
    fun missingColor(): Color = ChartKitTheme.colors.heatmap.missing
}
