package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartSeries
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
     * The default tooltip.
     *
     * A value rather than a hardcoded call inside each chart, so a caller can
     * wrap it — `tooltip = { ChartDefaults.Tooltip(it); Badge() }` — instead of
     * having to rewrite it to change one thing.
     */
    @Composable
    fun <T> Tooltip(
        selection: ChartSelection<T>,
        modifier: Modifier = Modifier,
        valueFormatter: ChartValueFormatter? = null,
        showSeriesName: Boolean = true,
    ) {
        io.devkit.chartkit.components.tooltip.ChartTooltip(
            selection = selection,
            modifier = modifier,
            valueFormatter = valueFormatter,
            showSeriesName = showSeriesName,
        )
    }
}
