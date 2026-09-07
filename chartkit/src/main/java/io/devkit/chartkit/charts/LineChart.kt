package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.animation.rememberAnimatedSeriesValues
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartGrid
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.LineInterpolation
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.line.AreaFill
import io.devkit.chartkit.layer.line.LineStyle
import io.devkit.chartkit.layer.line.PointMode
import io.devkit.chartkit.model.ChartRangeSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.model.ChartXAxisKind
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.model.MissingValuePolicy
import io.devkit.chartkit.model.normalizeSeries
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.ChartViewportState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.state.rememberChartViewportState

/**
 * A line chart over the caller's own data.
 *
 * ```kotlin
 * data class Revenue(val month: String, val amount: Double)
 *
 * LineChart(
 *     data = revenue,
 *     x = { it.month },
 *     y = { it.amount },
 *     modifier = Modifier.fillMaxWidth().height(240.dp),
 * )
 * ```
 *
 * No conversion step: `data` is a `List<Revenue>` and stays one. The `x` lambda
 * may return a number, a string, an enum or a `java.util.Date`, and the axis
 * shapes itself accordingly — see [ChartXResolver] for the rules and for how to
 * teach ChartKit a domain type of your own.
 *
 * @param x reads the horizontal position from an item. Typed `Any?` for the
 *   reason set out in [ChartXResolver]; every other lambda here is typed.
 * @param y reads the value. `null` means missing, handled per
 *   [missingValuePolicy] — never silently turned into zero.
 * @param interpolation how points are joined. [LineInterpolation.Smooth] is a
 *   monotone cubic that cannot overshoot the data.
 * @param pointMode when markers are drawn. [PointMode.Auto] hides them once a
 *   series is too dense for them to mean anything.
 * @param valueFormatter formats values on the axis, in labels and in the
 *   tooltip. Defaults to a locale-aware formatter derived from the tick values.
 * @param state hoisted interaction state. Omit it and the chart remembers its
 *   own.
 * @param tooltip replaces the default tooltip. The selection carries the
 *   caller's own item, so a custom tooltip reads its fields directly.
 * @param accessibilitySummary replaces ChartKit's generated screen-reader
 *   description entirely.
 */
@Composable
fun <T> LineChart(
    data: List<T>,
    x: (T) -> Any?,
    y: (T) -> Number?,
    modifier: Modifier = Modifier,
    seriesName: String = "",
    interpolation: LineInterpolation = LineInterpolation.Linear,
    lineStyle: LineStyle = LineStyle.Solid,
    pointMode: PointMode = PointMode.Auto,
    lineWidth: Dp? = null,
    xAxis: ChartAxis = ChartAxis.Default,
    yAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = ChartGrid.Horizontal,
    valueDomain: DomainPolicy = DomainPolicy.Default,
    valueLabels: Boolean = false,
    valueFormatter: ChartValueFormatter? = null,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.Default,
    crosshair: CrosshairConfig = ChartDefaults.SelectionGuide,
    sharedTooltip: Boolean = crosshair.enabled && crosshair.showAxisLabels,
    viewportState: ChartViewportState = rememberChartViewportState(),
    missingValuePolicy: MissingValuePolicy = MissingValuePolicy.Break,
    dataOrder: ChartDataOrder = ChartDataOrder.InputOrder,
    xResolver: ChartXResolver = ChartXResolver.Default,
    xAxisKind: ChartXAxisKind? = null,
    performance: ChartPerformance = ChartPerformance.Default,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    onRangeSelectionChanged: ((ChartRangeSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter)
    },
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    LineChart(
        series = remember(data, seriesName) {
            singleSeries(data, ChartDefaults.SINGLE_SERIES_ID, seriesName)
        },
        x = x,
        y = y,
        modifier = modifier,
        interpolation = interpolation,
        lineStyle = lineStyle,
        pointMode = pointMode,
        lineWidth = lineWidth,
        fill = null,
        xAxis = xAxis,
        yAxis = yAxis,
        grid = grid,
        legend = LegendPosition.None,
        legendTogglesSeries = false,
        valueDomain = valueDomain,
        valueLabels = valueLabels,
        valueFormatter = valueFormatter,
        animation = animation,
        interaction = interaction,
        crosshair = crosshair,
        sharedTooltip = sharedTooltip,
        viewportState = viewportState,
        missingValuePolicy = missingValuePolicy,
        dataOrder = dataOrder,
        xResolver = xResolver,
        xAxisKind = xAxisKind,
        performance = performance,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        state = state,
        onSelectionChanged = onSelectionChanged,
        onRangeSelectionChanged = onRangeSelectionChanged,
        tooltip = tooltip,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
    )
}

/**
 * A multi-series line chart.
 *
 * ```kotlin
 * LineChart(
 *     series = listOf(
 *         ChartSeries(id = "revenue", name = "Revenue", data = revenue),
 *         ChartSeries(id = "expenses", name = "Expenses", data = expenses),
 *     ),
 *     x = { it.month },
 *     y = { it.amount },
 * )
 * ```
 *
 * Series ids are load-bearing: they are what animation matches on across a data
 * change and what the legend toggles.
 *
 * @param fill fills the area beneath each line. `null` for a plain line chart;
 *   [AreaChart] is this function with a fill.
 */
@JvmName("LineChartSeries")
@Composable
fun <T> LineChart(
    series: List<ChartSeries<T>>,
    x: (T) -> Any?,
    y: (T) -> Number?,
    modifier: Modifier = Modifier,
    interpolation: LineInterpolation = LineInterpolation.Linear,
    lineStyle: LineStyle = LineStyle.Solid,
    pointMode: PointMode = PointMode.Auto,
    lineWidth: Dp? = null,
    fill: AreaFill? = null,
    xAxis: ChartAxis = ChartAxis.Default,
    yAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = ChartGrid.Horizontal,
    legend: LegendPosition = if (series.size > 1) LegendPosition.Bottom else LegendPosition.None,
    legendTogglesSeries: Boolean = false,
    valueDomain: DomainPolicy = DomainPolicy.Default,
    valueLabels: Boolean = false,
    valueFormatter: ChartValueFormatter? = null,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.Default,
    crosshair: CrosshairConfig = ChartDefaults.SelectionGuide,
    sharedTooltip: Boolean = crosshair.enabled && crosshair.showAxisLabels,
    viewportState: ChartViewportState = rememberChartViewportState(),
    missingValuePolicy: MissingValuePolicy = MissingValuePolicy.Break,
    dataOrder: ChartDataOrder = ChartDataOrder.InputOrder,
    xResolver: ChartXResolver = ChartXResolver.Default,
    xAxisKind: ChartXAxisKind? = null,
    performance: ChartPerformance = ChartPerformance.Default,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    onRangeSelectionChanged: ((ChartRangeSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter)
    },
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    val visibleSeries = remember(series, state.hiddenSeriesIds) {
        series.map { it.copy(visible = it.visible && state.isSeriesVisible(it.id)) }
    }

    val plotData = remember(visibleSeries, x, y, xResolver, missingValuePolicy, xAxisKind, dataOrder) {
        normalizeSeries(visibleSeries, x, y, xResolver, missingValuePolicy, xAxisKind)
            .let { if (dataOrder == ChartDataOrder.SortedByX) it.sortedByX() else it }
    }

    // Values are lifted out and animated separately so a data change moves the
    // line rather than redrawing it — see `rememberAnimatedSeriesValues`.
    val targetValues = remember(plotData) {
        plotData.series.map { s -> s.points.map { it.y } }
    }
    val totalPoints = remember(targetValues) { targetValues.sumOf { it.size } }
    val animatedValues = rememberAnimatedSeriesValues(
        target = targetValues,
        seriesIds = remember(plotData) { plotData.series.map { it.id } },
        animation = if (totalPoints <= performance.maxAnimatedPoints) animation else ChartAnimation.None,
    )

    val animatedData = remember(plotData, animatedValues) {
        plotData.withValues(animatedValues)
    }

    val layers = remember(
        animatedData, interpolation, lineStyle, fill, pointMode, lineWidth, valueLabels,
        performance, missingValuePolicy,
    ) {
        listOf(
            ResolvedLayer.Line(
                key = "line",
                data = animatedData,
                interpolation = interpolation,
                style = lineStyle,
                fill = fill,
                pointMode = pointMode,
                lineWidth = lineWidth,
                valueLabels = valueLabels,
                pointMarkerThreshold = performance.pointMarkerThreshold,
                missingValuePolicy = missingValuePolicy,
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
        legend = legend,
        legendTogglesSeries = legendTogglesSeries,
        animation = animation,
        interaction = interaction,
        crosshair = crosshair,
        hitTestMode = HitTestMode.NearestDomain,
        sharedTooltip = sharedTooltip,
        state = state.asErased(),
        viewportState = viewportState,
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
 * An area chart: a line with the region beneath it filled.
 *
 * Not a separate renderer. It calls [LineChart] with a [fill], because an area
 * chart *is* a line chart whose path is closed to the baseline — and the
 * baseline is the zero line, not the bottom of the composable, so a chart whose
 * axis starts above zero still shades a region that means something.
 */
@Composable
fun <T> AreaChart(
    data: List<T>,
    x: (T) -> Any?,
    y: (T) -> Number?,
    modifier: Modifier = Modifier,
    seriesName: String = "",
    fill: AreaFill = AreaFill.Default,
    interpolation: LineInterpolation = LineInterpolation.Linear,
    pointMode: PointMode = PointMode.Auto,
    lineWidth: Dp? = null,
    xAxis: ChartAxis = ChartAxis.Default,
    yAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = ChartGrid.Horizontal,
    // Areas read as a magnitude from the baseline, so unlike a plain line they
    // usually want zero in view. Still overridable: an area chart of a value
    // that never approaches zero is better off without it.
    valueDomain: DomainPolicy = DomainPolicy.Baseline,
    valueFormatter: ChartValueFormatter? = null,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.Default,
    crosshair: CrosshairConfig = ChartDefaults.SelectionGuide,
    sharedTooltip: Boolean = crosshair.enabled && crosshair.showAxisLabels,
    viewportState: ChartViewportState = rememberChartViewportState(),
    missingValuePolicy: MissingValuePolicy = MissingValuePolicy.Break,
    dataOrder: ChartDataOrder = ChartDataOrder.InputOrder,
    xResolver: ChartXResolver = ChartXResolver.Default,
    xAxisKind: ChartXAxisKind? = null,
    performance: ChartPerformance = ChartPerformance.Default,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    onRangeSelectionChanged: ((ChartRangeSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter)
    },
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    LineChart(
        series = remember(data, seriesName) {
            singleSeries(data, ChartDefaults.SINGLE_SERIES_ID, seriesName)
        },
        x = x,
        y = y,
        modifier = modifier,
        interpolation = interpolation,
        pointMode = pointMode,
        lineWidth = lineWidth,
        fill = fill,
        xAxis = xAxis,
        yAxis = yAxis,
        grid = grid,
        legend = LegendPosition.None,
        valueDomain = valueDomain,
        valueFormatter = valueFormatter,
        animation = animation,
        interaction = interaction,
        crosshair = crosshair,
        sharedTooltip = sharedTooltip,
        viewportState = viewportState,
        missingValuePolicy = missingValuePolicy,
        dataOrder = dataOrder,
        xResolver = xResolver,
        xAxisKind = xAxisKind,
        performance = performance,
        accessibility = accessibility,
        state = state,
        onSelectionChanged = onSelectionChanged,
        onRangeSelectionChanged = onRangeSelectionChanged,
        tooltip = tooltip,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
    )
}

/**
 * A multi-series area chart.
 *
 * Overlapping rather than stacked: each series is filled from the baseline
 * independently, which is the honest reading when the series are alternatives
 * — revenue against forecast — rather than parts of a whole. Stacked areas are
 * not supported; see the README's limitations.
 */
@JvmName("AreaChartSeries")
@Composable
fun <T> AreaChart(
    series: List<ChartSeries<T>>,
    x: (T) -> Any?,
    y: (T) -> Number?,
    modifier: Modifier = Modifier,
    fill: AreaFill = AreaFill.Default,
    interpolation: LineInterpolation = LineInterpolation.Linear,
    pointMode: PointMode = PointMode.Auto,
    xAxis: ChartAxis = ChartAxis.Default,
    yAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = ChartGrid.Horizontal,
    legend: LegendPosition = if (series.size > 1) LegendPosition.Bottom else LegendPosition.None,
    legendTogglesSeries: Boolean = false,
    valueDomain: DomainPolicy = DomainPolicy.Baseline,
    valueFormatter: ChartValueFormatter? = null,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.Default,
    crosshair: CrosshairConfig = ChartDefaults.SelectionGuide,
    sharedTooltip: Boolean = crosshair.enabled && crosshair.showAxisLabels,
    viewportState: ChartViewportState = rememberChartViewportState(),
    missingValuePolicy: MissingValuePolicy = MissingValuePolicy.Break,
    xResolver: ChartXResolver = ChartXResolver.Default,
    performance: ChartPerformance = ChartPerformance.Default,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    onRangeSelectionChanged: ((ChartRangeSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter)
    },
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    LineChart(
        series = series,
        x = x,
        y = y,
        modifier = modifier,
        interpolation = interpolation,
        pointMode = pointMode,
        fill = fill,
        xAxis = xAxis,
        yAxis = yAxis,
        grid = grid,
        legend = legend,
        legendTogglesSeries = legendTogglesSeries,
        valueDomain = valueDomain,
        valueFormatter = valueFormatter,
        animation = animation,
        interaction = interaction,
        crosshair = crosshair,
        sharedTooltip = sharedTooltip,
        viewportState = viewportState,
        missingValuePolicy = missingValuePolicy,
        xResolver = xResolver,
        performance = performance,
        accessibility = accessibility,
        state = state,
        onSelectionChanged = onSelectionChanged,
        onRangeSelectionChanged = onRangeSelectionChanged,
        tooltip = tooltip,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
    )
}
