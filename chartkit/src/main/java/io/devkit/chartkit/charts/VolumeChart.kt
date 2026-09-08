package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.annotation.ChartAnnotation
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartGrid
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.OhlcPolicy
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.model.ChartRangeSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.model.ChartXAxisKind
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.state.ChartSharedCrosshairState
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.ChartViewportState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.state.rememberChartViewportState

/**
 * Volume bars, coloured by the direction of the period they belong to.
 *
 * ```kotlin
 * val group = rememberChartInteractionGroup()
 *
 * Column {
 *     CandlestickChart(
 *         data = prices, x = { it.time },
 *         open = { it.open }, high = { it.high }, low = { it.low }, close = { it.close },
 *         viewportState = group.viewport,
 *         sharedCrosshair = group.crosshair,
 *         modifier = Modifier.fillMaxWidth().height(280.dp),
 *     )
 *     VolumeChart(
 *         data = prices, x = { it.time }, volume = { it.volume },
 *         open = { it.open }, close = { it.close },
 *         viewportState = group.viewport,
 *         sharedCrosshair = group.crosshair,
 *         modifier = Modifier.fillMaxWidth().height(90.dp),
 *     )
 * }
 * ```
 *
 * Zooming, panning or scrubbing either chart moves both, because they are given
 * the same two pieces of state — not because either knows about the other.
 *
 * ### Why the price accessors are here
 *
 * Supplying `open` and `close` is what lets a volume bar be coloured by whether
 * its period rose or fell, which is the only reason a volume chart is coloured
 * at all. They are optional: without them every bar is drawn neutral, which is
 * honest — a volume with no price attached has no direction, and guessing one
 * from the volumes themselves would be inventing a fact.
 *
 * ### Volume is a length from zero
 *
 * Unlike a price, a volume bar encodes a magnitude, so its axis includes zero.
 * A volume chart whose axis started at the smallest bar would make a quiet day
 * look like no trading at all.
 */
@Suppress("LongParameterList")
@Composable
fun <T> VolumeChart(
    data: List<T>,
    x: (T) -> Any?,
    volume: (T) -> Number?,
    modifier: Modifier = Modifier,
    open: ((T) -> Number?)? = null,
    close: ((T) -> Number?)? = null,
    seriesName: String = "",
    xAxis: ChartAxis = ChartAxis.Default,
    yAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = ChartGrid.Horizontal,
    valueFormatter: ChartValueFormatter? = null,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.Explorable,
    crosshair: CrosshairConfig = ChartDefaults.SelectionGuide,
    viewportState: ChartViewportState = rememberChartViewportState(),
    sharedCrosshair: ChartSharedCrosshairState? = null,
    annotations: List<ChartAnnotation> = emptyList(),
    xResolver: ChartXResolver = ChartXResolver.Time,
    xAxisKind: ChartXAxisKind = ChartXAxisKind.Time,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    onRangeSelectionChanged: ((ChartRangeSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter, showSeriesNames = true)
    },
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    // The volume travels on the normalised candle, so a bar and the price that
    // colours it cannot fall out of step. Without price accessors, the open and
    // close are the volume itself — equal, therefore neutral.
    val resolved = rememberOhlc(
        data = data,
        x = x,
        open = open ?: volume,
        high = { maxOf(open?.invoke(it)?.toDouble() ?: 0.0, close?.invoke(it)?.toDouble() ?: 0.0) },
        low = { minOf(open?.invoke(it)?.toDouble() ?: 0.0, close?.invoke(it)?.toDouble() ?: 0.0) },
        close = close ?: volume,
        volume = volume,
        policy = OhlcPolicy.Repair,
        xResolver = xResolver,
    )

    val layers = remember(resolved, seriesName, data, xAxisKind) {
        listOf(
            ResolvedLayer.Volume(
                key = "volume",
                points = resolved.points,
                xValues = resolved.xValues,
                seriesId = ChartDefaults.SINGLE_SERIES_ID,
                seriesName = seriesName.ifBlank { "Volume" },
                items = data,
                axisKind = xAxisKind,
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
        valueDomainPolicy = yAxis.domain ?: DomainPolicy.Baseline,
        legend = io.devkit.chartkit.components.legend.LegendPosition.None,
        legendTogglesSeries = false,
        animation = animation,
        interaction = interaction,
        crosshair = crosshair,
        hitTestMode = HitTestMode.NearestDomain,
        sharedTooltip = true,
        state = state.asErased(),
        viewportState = viewportState,
        sharedCrosshair = sharedCrosshair,
        annotations = remember(annotations, xResolver) { resolveAnnotations(annotations, xResolver) },
        onSelectionChanged = onSelectionChanged?.let { callback ->
            { erased -> callback(erased?.asTyped()) }
        },
        onRangeSelectionChanged = onRangeSelectionChanged?.let { callback ->
            { erased -> callback(erased?.asTypedRange()) }
        },
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
