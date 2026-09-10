package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.model.AnyChartRangeSelection
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.AnyChartTooltipData
import io.devkit.chartkit.model.ChartRangeSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.state.ChartState

/**
 * Bridges between a typed chart and the type-erased engine.
 *
 * The engine cannot be generic: a combined chart holds layers over different
 * `T`s, so one selection type has to cover them all. The high-level charts are
 * generic, because a caller selecting a bar wants their own `Revenue` back and
 * not an `Any?`. These casts are where the two meet.
 *
 * They are sound: the only objects the engine ever puts into a selection came
 * out of the very `List<T>` the same chart handed it.
 */
@Suppress("UNCHECKED_CAST")
internal fun <T> ChartState<T>.asErased(): ChartState<Any?> = this as ChartState<Any?>

@Suppress("UNCHECKED_CAST")
internal fun <T> AnyChartSelection.asTyped(): ChartSelection<T> = this as ChartSelection<T>

@Suppress("UNCHECKED_CAST")
internal fun <T> AnyChartTooltipData.asTypedTooltip(): ChartTooltipData<T> =
    this as ChartTooltipData<T>

@Suppress("UNCHECKED_CAST")
internal fun <T> AnyChartRangeSelection.asTypedRange(): ChartRangeSelection<T> =
    this as ChartRangeSelection<T>

/** Wraps a single list as the one-series case, so both APIs share a code path. */
internal fun <T> singleSeries(
    data: List<T>,
    id: String,
    name: String,
): List<ChartSeries<T>> = listOf(ChartSeries(id = id, name = name, data = data))

/** Shared defaults, so a caller can reference them when overriding one. */
object ChartDefaults {

    /** The id given to the series of a single-series chart. */
    const val SINGLE_SERIES_ID: String = "series"

    /**
     * The guide drawn through a selection when no full crosshair was asked for.
     *
     * A crosshair with its axis chips turned off — which is exactly what the
     * guide line always was. Expressing it as a [CrosshairConfig] rather than
     * as a separate concept is what let the selection guide and the crosshair
     * become one layer.
     */
    val SelectionGuide: CrosshairConfig = CrosshairConfig(
        enabled = true,
        vertical = true,
        horizontal = false,
        showAxisLabels = false,
    )

    /**
     * The default tooltip.
     *
     * A value rather than a hardcoded call inside each chart, so a caller can
     * wrap it — `tooltip = { ChartDefaults.Tooltip(it); Badge() }` — instead of
     * having to rewrite it to change one thing.
     */
    @Composable
    fun <T> Tooltip(
        data: ChartTooltipData<T>,
        modifier: Modifier = Modifier,
        valueFormatter: ChartValueFormatter? = null,
        showSeriesNames: Boolean = data.isMultiSeries,
    ) {
        io.devkit.chartkit.components.tooltip.ChartTooltip(
            data = data,
            modifier = modifier,
            valueFormatter = valueFormatter,
            showSeriesNames = showSeriesNames,
        )
    }

    /**
     * The default tooltip for a true X/Y/Z chart: all three values, each
     * through its own axis formatter.
     *
     * Separate from [Tooltip] rather than a branch inside it, because a caller
     * wrapping the 3D one is wrapping something with three lines and a caller
     * wrapping the flat one is wrapping something with an x and a value. It
     * falls back to [Tooltip] for a selection that carries no third coordinate.
     */
    @Composable
    fun <T> Scatter3DTooltip(
        data: ChartTooltipData<T>,
        modifier: Modifier = Modifier,
        showSeriesName: Boolean = data.isMultiSeries,
    ) {
        io.devkit.chartkit.components.tooltip.Chart3DTooltip(
            data = data,
            modifier = modifier,
            showSeriesName = showSeriesName,
        )
    }
}
