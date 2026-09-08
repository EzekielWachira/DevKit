package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.annotation.ChartAnnotation
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartGrid
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.ChartViewportState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.state.rememberChartViewportState
import io.devkit.chartkit.stats.HistogramBinner
import io.devkit.chartkit.stats.HistogramBins
import io.devkit.chartkit.stats.HistogramMetric

/**
 * A histogram of the caller's own observations.
 *
 * ```kotlin
 * data class Request(val durationMs: Double)
 *
 * Histogram(
 *     data = requests,
 *     value = { it.durationMs },
 *     bins = HistogramBins.Count(20),
 * )
 * ```
 *
 * Raw observations go in; ChartKit does the binning. There is no bin type to
 * construct and no counting to do at the call site — which matters, because
 * "count these into twenty buckets" is the part everybody writes slightly
 * differently at the boundaries.
 *
 * ### The bars are not categories
 *
 * A histogram's bars sit on a **continuous** axis and their widths carry
 * meaning: under [HistogramBins.Custom] they are deliberately unequal. That is
 * why this is not `BarChart` with a preprocessing step, and why
 * [HistogramMetric.Density] exists — with unequal bins, a count bar makes a
 * wide bin look more populated simply for being wide.
 *
 * ### Edge cases
 *
 * An empty dataset, one entirely of `NaN`s, a single observation and a run of
 * identical values all produce a chart rather than a crash: the last draws one
 * bin around the shared value, which is the truthful picture, and the first two
 * draw the empty state.
 *
 * @param value reads the observation. `null` and non-finite values are dropped
 *   and do not contribute to any bin or to the total a percentage is taken of.
 * @param bins how the range is divided. See [HistogramBins].
 * @param metric what a bar's height means: a count, a share, or a density.
 */
@Suppress("LongParameterList")
@Composable
fun <T> Histogram(
    data: List<T>,
    value: (T) -> Number?,
    modifier: Modifier = Modifier,
    bins: HistogramBins = HistogramBins.Auto,
    metric: HistogramMetric = HistogramMetric.Count,
    seriesName: String = "",
    color: Int? = null,
    cornerRadius: Dp? = null,
    xAxis: ChartAxis = ChartAxis.Default,
    yAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = ChartGrid.Horizontal,
    valueFormatter: ChartValueFormatter? = null,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.TapOnly,
    crosshair: CrosshairConfig = ChartDefaults.SelectionGuide,
    viewportState: ChartViewportState = rememberChartViewportState(),
    annotations: List<ChartAnnotation> = emptyList(),
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    state: ChartState<List<T>> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<List<T>>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<List<T>>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter, showSeriesNames = true)
    },
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    val computed = remember(data, value, bins, metric) {
        HistogramBinner.bin(data.map { value(it)?.toDouble() }, bins, metric)
    }

    val layers = remember(computed, metric, seriesName, color, cornerRadius, data) {
        listOf(
            ResolvedLayer.Histogram(
                key = "histogram",
                bins = computed,
                metric = metric,
                seriesId = ChartDefaults.SINGLE_SERIES_ID,
                seriesName = seriesName,
                items = data,
                paletteIndex = 0,
                colorOverride = color,
                cornerRadius = cornerRadius,
            ),
        )
    }

    CartesianChartCore(
        layers = layers,
        modifier = modifier,
        orientation = ChartOrientation.Vertical,
        domainAxis = xAxis,
        valueAxis = if (valueFormatter != null && yAxis.valueFormatter == null) {
            yAxis.copy(valueFormatter = valueFormatter)
        } else {
            yAxis
        },
        grid = grid,
        // A count is a length from zero, so the axis has to contain zero for
        // the bar heights to be comparable at all.
        valueDomainPolicy = yAxis.domain ?: DomainPolicy.Baseline,
        legend = io.devkit.chartkit.components.legend.LegendPosition.None,
        legendTogglesSeries = false,
        animation = animation,
        interaction = interaction,
        crosshair = crosshair,
        hitTestMode = HitTestMode.Contains,
        sharedTooltip = false,
        state = state.asErased(),
        viewportState = viewportState,
        sharedCrosshair = null,
        annotations = remember(annotations) {
            resolveAnnotations(annotations, ChartXResolver.Default)
        },
        onSelectionChanged = onSelectionChanged?.let { callback ->
            { erased -> callback(erased?.asTyped()) }
        },
        onRangeSelectionChanged = null,
        tooltip = tooltip?.let { slot -> { erased -> slot(erased.asTypedTooltip()) } },
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
    )
}
