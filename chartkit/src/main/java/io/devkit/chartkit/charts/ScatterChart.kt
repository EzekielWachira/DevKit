package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.annotation.ChartAnnotation
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartGrid
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.ScatterShape
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.scatter.ScatterStyle
import io.devkit.chartkit.model.ChartRangeSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.model.ChartXAxisKind
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.model.MissingValuePolicy
import io.devkit.chartkit.model.normalizeSeries
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.scale.SizeScale
import io.devkit.chartkit.scale.apply
import io.devkit.chartkit.scale.SizeScaleMode
import io.devkit.chartkit.state.ChartSharedCrosshairState
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.ChartViewportState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.state.rememberChartViewportState

/**
 * A scatter chart over the caller's own data.
 *
 * ```kotlin
 * data class Observation(val height: Double, val weight: Double)
 *
 * ScatterChart(
 *     data = observations,
 *     x = { it.height },
 *     y = { it.weight },
 *     modifier = Modifier.fillMaxWidth().height(280.dp),
 * )
 * ```
 *
 * No conversion step and no point type: `data` is a `List<Observation>` and
 * stays one, exactly as it does for a line or a bar chart.
 *
 * ### Both axes are continuous
 *
 * Unlike a line chart, a scatter's x is a *measurement* rather than a position
 * in a sequence, so the default domain policy pads both ends and does not force
 * zero — a scatter of heights between 150 and 195 cm is about that interval,
 * not about the interval from zero.
 *
 * ### What it does not claim
 *
 * ChartKit draws no trend line and computes no correlation, here or in the
 * accessibility summary. Both are statistical assertions with assumptions
 * attached, and a charting library that produced them silently would be putting
 * a claim on screen that nobody checked.
 *
 * @param x reads the horizontal measurement.
 * @param y reads the vertical one. `null` drops the observation — a point with
 *   one coordinate cannot be placed.
 * @param shape the marker. Circles are what a reader judges most reliably.
 * @param pointRadius overrides the theme's marker size.
 */
@Suppress("LongParameterList")
@Composable
fun <T> ScatterChart(
    data: List<T>,
    x: (T) -> Any?,
    y: (T) -> Number?,
    modifier: Modifier = Modifier,
    seriesName: String = "",
    shape: ScatterShape = ScatterShape.Circle,
    style: ScatterStyle = ScatterStyle.Point,
    pointRadius: Dp? = null,
    xAxis: ChartAxis = ChartAxis.Default,
    yAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = ChartGrid.Both,
    xDomain: DomainPolicy = DomainPolicy.Default,
    valueDomain: DomainPolicy = DomainPolicy.Default,
    valueFormatter: ChartValueFormatter? = null,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.TapOnly,
    crosshair: CrosshairConfig = CrosshairConfig.None,
    viewportState: ChartViewportState = rememberChartViewportState(),
    annotations: List<ChartAnnotation> = emptyList(),
    sharedCrosshair: ChartSharedCrosshairState? = null,
    xResolver: ChartXResolver = ChartXResolver.Default,
    xAxisKind: ChartXAxisKind? = null,
    performance: ChartPerformance = ChartPerformance.Default,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter)
    },
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    ScatterChart(
        series = remember(data, seriesName) {
            singleSeries(data, ChartDefaults.SINGLE_SERIES_ID, seriesName)
        },
        x = x,
        y = y,
        modifier = modifier,
        shape = shape,
        style = style,
        pointRadius = pointRadius,
        xAxis = xAxis,
        yAxis = yAxis,
        grid = grid,
        legend = LegendPosition.None,
        xDomain = xDomain,
        valueDomain = valueDomain,
        valueFormatter = valueFormatter,
        animation = animation,
        interaction = interaction,
        crosshair = crosshair,
        viewportState = viewportState,
        annotations = annotations,
        sharedCrosshair = sharedCrosshair,
        xResolver = xResolver,
        xAxisKind = xAxisKind,
        performance = performance,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        state = state,
        onSelectionChanged = onSelectionChanged,
        tooltip = tooltip,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
    )
}

/**
 * A multi-series scatter chart.
 *
 * ```kotlin
 * ScatterChart(
 *     series = listOf(
 *         ChartSeries(id = "control", name = "Control", data = control),
 *         ChartSeries(id = "treated", name = "Treated", data = treated),
 *     ),
 *     x = { it.dose },
 *     y = { it.response },
 * )
 * ```
 *
 * Series keep their palette slot when another is hidden, so a legend toggle
 * never recolours the remaining groups — which on a scatter would make two
 * screenshots of the same data disagree about which group is which.
 */
@JvmName("ScatterChartSeries")
@Suppress("LongParameterList")
@Composable
fun <T> ScatterChart(
    series: List<ChartSeries<T>>,
    x: (T) -> Any?,
    y: (T) -> Number?,
    modifier: Modifier = Modifier,
    size: ((T) -> Number?)? = null,
    sizeDomain: DomainPolicy = DomainPolicy.Default,
    minPointSize: Dp? = null,
    maxPointSize: Dp? = null,
    sizeMode: SizeScaleMode = SizeScaleMode.Area,
    shape: ScatterShape = ScatterShape.Circle,
    style: ScatterStyle = ScatterStyle.Point,
    pointRadius: Dp? = null,
    xAxis: ChartAxis = ChartAxis.Default,
    yAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = ChartGrid.Both,
    legend: LegendPosition = if (series.size > 1) LegendPosition.Bottom else LegendPosition.None,
    legendTogglesSeries: Boolean = false,
    xDomain: DomainPolicy = DomainPolicy.Default,
    valueDomain: DomainPolicy = DomainPolicy.Default,
    valueFormatter: ChartValueFormatter? = null,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.TapOnly,
    crosshair: CrosshairConfig = CrosshairConfig.None,
    viewportState: ChartViewportState = rememberChartViewportState(),
    annotations: List<ChartAnnotation> = emptyList(),
    sharedCrosshair: ChartSharedCrosshairState? = null,
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
    val density = LocalDensity.current
    val theme = io.devkit.chartkit.theme.ChartKitTheme.current

    val visibleSeries = remember(series, state.hiddenSeriesIds) {
        series.map { it.copy(visible = it.visible && state.isSeriesVisible(it.id)) }
    }

    val plotData = remember(visibleSeries, x, y, xResolver, xAxisKind) {
        // Never `Zero`: a scatter observation whose y is missing has no
        // position, and placing it on the axis would invent a measurement.
        normalizeSeries(visibleSeries, x, y, xResolver, MissingValuePolicy.Break, xAxisKind)
    }

    val sizes = remember(visibleSeries, size) {
        size?.let { accessor -> visibleSeries.map { s -> s.data.map { accessor(it)?.toDouble() } } }
    }

    // One size scale across every series, so two series in a bubble chart are
    // measured against the same domain. Scaling each to its own maximum would
    // make the largest bubble of every group the same size, which is the one
    // thing a bubble chart must not do.
    val sizeScale = remember(sizes, sizeDomain, minPointSize, maxPointSize, sizeMode, density, theme) {
        sizes?.let { values ->
            val domain = sizeDomain.apply(
                NumericDomain.of(values.flatten().filterNotNull()),
            )
            SizeScale(
                domain = domain,
                minSize = with(density) { (minPointSize ?: theme.dimensions.bubbleMinRadius).toPx() },
                maxSize = with(density) { (maxPointSize ?: theme.dimensions.bubbleMaxRadius).toPx() },
                mode = sizeMode,
            )
        }
    }

    val layers = remember(plotData, shape, style, sizes, sizeScale, pointRadius, performance) {
        listOf(
            ResolvedLayer.Scatter(
                key = "scatter",
                data = plotData,
                shape = shape,
                style = style,
                sizes = sizes,
                sizeScale = sizeScale,
                pointRadius = pointRadius,
                performance = performance,
            ),
        )
    }

    CartesianChartCore(
        layers = layers,
        modifier = modifier,
        orientation = ChartOrientation.Vertical,
        domainAxis = if (xAxis.domain == null) xAxis.copy(domain = xDomain) else xAxis,
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
        // A scatter point is a target with area, and the reader is aiming at
        // one — not choosing an x the way they do on a time series.
        hitTestMode = HitTestMode.Contains,
        sharedTooltip = false,
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

/**
 * A bubble chart: a scatter whose marker size carries a third variable.
 *
 * ```kotlin
 * BubbleChart(
 *     data = companies,
 *     x = { it.revenue },
 *     y = { it.growth },
 *     size = { it.marketCap },
 * )
 * ```
 *
 * Not a separate renderer. It calls [ScatterChart] with a `size` accessor,
 * because a bubble chart *is* a scatter whose radius comes from the data — the
 * difference lives in [SizeScale], not in the drawing.
 *
 * ### Size means area
 *
 * The default is [SizeScaleMode.Area]: a value twice as large draws a bubble
 * occupying twice the **area**, which is what a reader perceives it as. Mapping
 * a value onto the radius instead — the obvious implementation, and the wrong
 * one — makes that bubble look four times as large. [sizeMode] can select the
 * radius mapping where the quantity genuinely is a radius.
 *
 * ### Bubbles are translucent and outlined
 *
 * [ScatterStyle.Bubble] by default, because bubbles overlap. Opaque bubbles
 * hide each other completely, and a reader cannot tell one large bubble from
 * three stacked ones.
 */
@Suppress("LongParameterList")
@Composable
fun <T> BubbleChart(
    data: List<T>,
    x: (T) -> Any?,
    y: (T) -> Number?,
    size: (T) -> Number?,
    modifier: Modifier = Modifier,
    seriesName: String = "",
    sizeDomain: DomainPolicy = DomainPolicy.Default,
    minBubbleSize: Dp? = null,
    maxBubbleSize: Dp? = null,
    sizeMode: SizeScaleMode = SizeScaleMode.Area,
    shape: ScatterShape = ScatterShape.Circle,
    style: ScatterStyle = ScatterStyle.Bubble,
    xAxis: ChartAxis = ChartAxis.Default,
    yAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = ChartGrid.Both,
    legend: LegendPosition = LegendPosition.None,
    xDomain: DomainPolicy = DomainPolicy.Default,
    valueDomain: DomainPolicy = DomainPolicy.Default,
    valueFormatter: ChartValueFormatter? = null,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.TapOnly,
    annotations: List<ChartAnnotation> = emptyList(),
    xResolver: ChartXResolver = ChartXResolver.Default,
    performance: ChartPerformance = ChartPerformance.Default,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter)
    },
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    ScatterChart(
        series = remember(data, seriesName) {
            singleSeries(data, ChartDefaults.SINGLE_SERIES_ID, seriesName)
        },
        x = x,
        y = y,
        modifier = modifier,
        size = size,
        sizeDomain = sizeDomain,
        minPointSize = minBubbleSize,
        maxPointSize = maxBubbleSize,
        sizeMode = sizeMode,
        shape = shape,
        style = style,
        xAxis = xAxis,
        yAxis = yAxis,
        grid = grid,
        legend = legend,
        xDomain = xDomain,
        valueDomain = valueDomain,
        valueFormatter = valueFormatter,
        animation = animation,
        interaction = interaction,
        annotations = annotations,
        xResolver = xResolver,
        performance = performance,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        state = state,
        onSelectionChanged = onSelectionChanged,
        tooltip = tooltip,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
    )
}
