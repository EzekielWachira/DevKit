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
import io.devkit.chartkit.geometry.normalizeOhlc
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.financial.PriceMarkStyle
import io.devkit.chartkit.model.ChartRangeSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.model.ChartX
import io.devkit.chartkit.model.ChartXAxisKind
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.model.resolveOrDefault
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.scene.ChartSceneState
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.state.ChartPlotAlignment
import io.devkit.chartkit.state.ChartSharedCrosshairState
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.ChartViewportState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.state.rememberChartViewportState

/**
 * A candlestick chart over the caller's own price data.
 *
 * ```kotlin
 * data class Candle(
 *     val time: Long,
 *     val open: Double, val high: Double, val low: Double, val close: Double,
 *     val volume: Double,
 * )
 *
 * CandlestickChart(
 *     data = prices,
 *     x = { it.time },
 *     open = { it.open },
 *     high = { it.high },
 *     low = { it.low },
 *     close = { it.close },
 * )
 * ```
 *
 * Five lambdas over the caller's own type, exactly as a line chart takes two.
 * There is no candle type to convert into.
 *
 * ### Colour is semantic, not green and red
 *
 * The layer asks the theme for
 * [io.devkit.chartkit.theme.ChartFinancialColors.increase] and `decrease` by
 * name. The rising-is-green convention is not universal — several East Asian
 * markets colour rising prices red — and it is invisible to a reader with
 * red-green colour vision deficiency, for whom the two most important colours
 * on the chart are the same colour. An application swaps two theme values
 * rather than forking a renderer.
 *
 * ### Gaps stay gaps
 *
 * Weekends, holidays and halted sessions are absences. Nothing here fabricates
 * a flat candle for them, which would put a price on a day nothing traded. A
 * time axis draws them as the gaps they are.
 *
 * ### Inconsistent prices
 *
 * A period whose high is below its own close describes something that did not
 * happen, and drawing it gives a body sticking out of its own wick. [ohlcPolicy]
 * says what happens: [OhlcPolicy.Repair] widens the extremes to contain the
 * open and close — the smallest change that makes the four numbers consistent —
 * while [OhlcPolicy.Skip] drops the period and [OhlcPolicy.Reject] throws.
 *
 * ### Linked charts
 *
 * Pass the same `viewportState` and `sharedCrosshair` to a [VolumeChart] below
 * and the two zoom, pan and scrub together. See
 * [io.devkit.chartkit.state.ChartInteractionGroup].
 *
 * @param x reads the period. A `Long` of epoch millis with
 *   [ChartXResolver.Time], or anything [ChartXResolver] handles.
 * @param volume optional; supplying it here rather than to a separate chart is
 *   what lets a linked volume chart colour its bars by the price direction of
 *   the period they belong to.
 */
@Suppress("LongParameterList")
@Composable
fun <T> CandlestickChart(
    data: List<T>,
    x: (T) -> Any?,
    open: (T) -> Number?,
    high: (T) -> Number?,
    low: (T) -> Number?,
    close: (T) -> Number?,
    modifier: Modifier = Modifier,
    volume: ((T) -> Number?)? = null,
    markStyle: PriceMarkStyle = PriceMarkStyle.Candle,
    ohlcPolicy: OhlcPolicy = OhlcPolicy.Repair,
    seriesName: String = "",
    xAxis: ChartAxis = ChartAxis.Default,
    yAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = ChartGrid.Horizontal,
    // Prices are read as positions, not as lengths from zero. Forcing zero onto
    // a chart of a stock trading between 180 and 190 flattens the whole series
    // into a line at the top of the plot.
    valueDomain: DomainPolicy = DomainPolicy.Default,
    valueFormatter: ChartValueFormatter? = null,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.Explorable,
    crosshair: CrosshairConfig = CrosshairConfig.Both,
    viewportState: ChartViewportState = rememberChartViewportState(),
    sharedCrosshair: ChartSharedCrosshairState? = null,
    annotations: List<ChartAnnotation> = emptyList(),
    xResolver: ChartXResolver = ChartXResolver.Time,
    xAxisKind: ChartXAxisKind = ChartXAxisKind.Time,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    plotAlignment: ChartPlotAlignment? = null,
    sceneState: ChartSceneState? = null,
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
    val resolved = rememberOhlc(data, x, open, high, low, close, volume, ohlcPolicy, xResolver)

    val layers = remember(resolved, markStyle, seriesName, data, xAxisKind) {
        listOf(
            ResolvedLayer.Candles(
                key = "candles",
                points = resolved.points,
                xValues = resolved.xValues,
                markStyle = markStyle,
                seriesId = ChartDefaults.SINGLE_SERIES_ID,
                seriesName = seriesName.ifBlank { "Price" },
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
        valueDomainPolicy = yAxis.domain ?: valueDomain,
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
        renderMode = renderMode,
        staticOptions = staticOptions,
        plotAlignment = plotAlignment,
        sceneState = sceneState,
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

/**
 * An OHLC bar chart: the same four numbers, drawn as bars instead of candles.
 *
 * ```kotlin
 * OhlcChart(
 *     data = prices,
 *     x = { it.time },
 *     open = { it.open }, high = { it.high }, low = { it.low }, close = { it.close },
 * )
 * ```
 *
 * Not a separate implementation, and not a separate engine. It is
 * [CandlestickChart] with [PriceMarkStyle.OhlcBar], because the positioning,
 * the validation, the hit testing, the tooltip and the accessibility summary
 * are identical — only the strokes differ. OHLC bars stay legible at widths
 * where a candle body collapses to a line, which is the reason to offer them.
 */
@Suppress("LongParameterList")
@Composable
fun <T> OhlcChart(
    data: List<T>,
    x: (T) -> Any?,
    open: (T) -> Number?,
    high: (T) -> Number?,
    low: (T) -> Number?,
    close: (T) -> Number?,
    modifier: Modifier = Modifier,
    ohlcPolicy: OhlcPolicy = OhlcPolicy.Repair,
    seriesName: String = "",
    xAxis: ChartAxis = ChartAxis.Default,
    yAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = ChartGrid.Horizontal,
    valueDomain: DomainPolicy = DomainPolicy.Default,
    valueFormatter: ChartValueFormatter? = null,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.Explorable,
    crosshair: CrosshairConfig = CrosshairConfig.Both,
    viewportState: ChartViewportState = rememberChartViewportState(),
    sharedCrosshair: ChartSharedCrosshairState? = null,
    annotations: List<ChartAnnotation> = emptyList(),
    xResolver: ChartXResolver = ChartXResolver.Time,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    plotAlignment: ChartPlotAlignment? = null,
    sceneState: ChartSceneState? = null,
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter, showSeriesNames = true)
    },
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    CandlestickChart(
        data = data,
        x = x,
        open = open,
        high = high,
        low = low,
        close = close,
        modifier = modifier,
        markStyle = PriceMarkStyle.OhlcBar,
        ohlcPolicy = ohlcPolicy,
        seriesName = seriesName,
        xAxis = xAxis,
        yAxis = yAxis,
        grid = grid,
        valueDomain = valueDomain,
        valueFormatter = valueFormatter,
        animation = animation,
        interaction = interaction,
        crosshair = crosshair,
        viewportState = viewportState,
        sharedCrosshair = sharedCrosshair,
        annotations = annotations,
        xResolver = xResolver,
        accessibility = accessibility,
        state = state,
        renderMode = renderMode,
        staticOptions = staticOptions,
        plotAlignment = plotAlignment,
        sceneState = sceneState,
        onSelectionChanged = onSelectionChanged,
        tooltip = tooltip,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
    )
}

/** Normalised prices and the domain values they were resolved from. */
internal class ResolvedOhlc(
    val points: List<io.devkit.chartkit.geometry.OhlcPoint>,
    val xValues: List<ChartX>,
)

/**
 * Normalises the caller's five lambdas once per data change.
 *
 * Shared by the candlestick and volume charts so a linked pair reads the same
 * periods from the same list — the alternative is two normalisations that agree
 * until one of them drops a bad tick.
 */
@Suppress("LongParameterList")
@Composable
internal fun <T> rememberOhlc(
    data: List<T>,
    x: (T) -> Any?,
    open: (T) -> Number?,
    high: (T) -> Number?,
    low: (T) -> Number?,
    close: (T) -> Number?,
    volume: ((T) -> Number?)?,
    policy: OhlcPolicy,
    xResolver: ChartXResolver,
): ResolvedOhlc = remember(data, x, open, high, low, close, volume, policy, xResolver) {
    val xValues = data.map { xResolver.resolveOrDefault(x(it)) }
    val domainValues = xValues.map { value ->
        when (value) {
            is ChartX.Numeric -> value.value
            is ChartX.Time -> value.epochMillis.toDouble()
            is ChartX.Category -> Double.NaN
        }
    }
    ResolvedOhlc(
        points = normalizeOhlc(
            count = data.size,
            domainValue = { domainValues[it] },
            open = { open(data[it])?.toDouble() },
            high = { high(data[it])?.toDouble() },
            low = { low(data[it])?.toDouble() },
            close = { close(data[it])?.toDouble() },
            volume = volume?.let { accessor -> { index: Int -> accessor(data[index])?.toDouble() } },
            policy = policy,
        ),
        xValues = xValues,
    )
}
