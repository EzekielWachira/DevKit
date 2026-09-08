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
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.statistical.BoxEntry
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.state.ChartPlotAlignment
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.ChartViewportState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.state.rememberChartViewportState
import io.devkit.chartkit.stats.BoxStatistics
import io.devkit.chartkit.stats.OutlierPolicy

/**
 * A box plot computed from raw samples.
 *
 * ```kotlin
 * data class Endpoint(val name: String, val latencies: List<Double>)
 *
 * BoxPlot(
 *     data = endpoints,
 *     label = { it.name },
 *     values = { it.latencies },
 * )
 * ```
 *
 * ChartKit computes the minimum, quartiles, median, maximum and outliers by the
 * method documented on [io.devkit.chartkit.stats.ChartStatistics.quantile] —
 * named, not merely implemented, because published quartile definitions
 * disagree by a visible amount on small samples and a box plot nobody can
 * reconcile with their own analysis is worse than none.
 *
 * An application that **already has** its statistics uses the [BoxStatistics]
 * overload instead, which recomputes nothing.
 *
 * @param values the observations for a category. Non-finite entries are
 *   dropped; a category with nothing usable draws no box, and its band stays on
 *   the axis rather than the categories shifting.
 * @param outlierPolicy how points beyond the whiskers are classified.
 *   [OutlierPolicy.Tukey] at the conventional 1.5 × IQR by default.
 */
@Suppress("LongParameterList")
@Composable
fun <T> BoxPlot(
    data: List<T>,
    label: (T) -> String,
    values: (T) -> List<Number>,
    modifier: Modifier = Modifier,
    outlierPolicy: OutlierPolicy = OutlierPolicy.Default,
    orientation: ChartOrientation = ChartOrientation.Vertical,
    seriesName: String = "",
    color: ((T) -> Int?)? = null,
    categoryAxis: ChartAxis = ChartAxis.Default,
    valueAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = if (orientation.isVertical) ChartGrid.Horizontal else ChartGrid.Vertical,
    valueDomain: DomainPolicy = DomainPolicy.Default,
    valueFormatter: ChartValueFormatter? = null,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.TapOnly,
    crosshair: CrosshairConfig = CrosshairConfig.None,
    viewportState: ChartViewportState = rememberChartViewportState(),
    annotations: List<ChartAnnotation> = emptyList(),
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    plotAlignment: ChartPlotAlignment? = null,
    accessibilitySummary: (() -> String)? = null,
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
    val computed = remember(data, values, outlierPolicy) {
        data.map { item -> BoxStatistics.from(values(item).map { it.toDouble() }, outlierPolicy) }
    }
    BoxPlotInternal(
        data = data,
        label = label,
        statistics = { index, _ -> computed.getOrNull(index) },
        modifier = modifier,
        orientation = orientation,
        seriesName = seriesName,
        color = color,
        categoryAxis = categoryAxis,
        valueAxis = valueAxis,
        grid = grid,
        valueDomain = valueDomain,
        valueFormatter = valueFormatter,
        animation = animation,
        interaction = interaction,
        crosshair = crosshair,
        viewportState = viewportState,
        annotations = annotations,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        state = state,
        renderMode = renderMode,
        staticOptions = staticOptions,
        plotAlignment = plotAlignment,
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
 * A box plot from statistics that already exist.
 *
 * ```kotlin
 * data class LatencySummary(
 *     val endpoint: String,
 *     val p0: Double, val p25: Double, val p50: Double, val p75: Double, val p100: Double,
 * )
 *
 * BoxPlot(
 *     data = summaries,
 *     label = { it.endpoint },
 *     statistics = {
 *         BoxStatistics(
 *             minimum = it.p0, q1 = it.p25, median = it.p50, q3 = it.p75, maximum = it.p100,
 *         )
 *     },
 * )
 * ```
 *
 * Nothing is recomputed and no outlier rule is applied: these numbers came from
 * somewhere else, under whatever definition that place uses, and re-deriving
 * them here would silently overwrite a decision ChartKit was not party to. It
 * is also the only way to chart a distribution whose raw samples are a billion
 * rows in a warehouse and were never going to reach the device.
 */
@JvmName("BoxPlotPrecomputed")
@Suppress("LongParameterList")
@Composable
fun <T> BoxPlot(
    data: List<T>,
    label: (T) -> String,
    statistics: (T) -> BoxStatistics?,
    modifier: Modifier = Modifier,
    orientation: ChartOrientation = ChartOrientation.Vertical,
    seriesName: String = "",
    color: ((T) -> Int?)? = null,
    categoryAxis: ChartAxis = ChartAxis.Default,
    valueAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = if (orientation.isVertical) ChartGrid.Horizontal else ChartGrid.Vertical,
    valueDomain: DomainPolicy = DomainPolicy.Default,
    valueFormatter: ChartValueFormatter? = null,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.TapOnly,
    crosshair: CrosshairConfig = CrosshairConfig.None,
    viewportState: ChartViewportState = rememberChartViewportState(),
    annotations: List<ChartAnnotation> = emptyList(),
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    plotAlignment: ChartPlotAlignment? = null,
    accessibilitySummary: (() -> String)? = null,
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
    BoxPlotInternal(
        data = data,
        label = label,
        statistics = { _, item -> statistics(item) },
        modifier = modifier,
        orientation = orientation,
        seriesName = seriesName,
        color = color,
        categoryAxis = categoryAxis,
        valueAxis = valueAxis,
        grid = grid,
        valueDomain = valueDomain,
        valueFormatter = valueFormatter,
        animation = animation,
        interaction = interaction,
        crosshair = crosshair,
        viewportState = viewportState,
        annotations = annotations,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        state = state,
        renderMode = renderMode,
        staticOptions = staticOptions,
        plotAlignment = plotAlignment,
        onSelectionChanged = onSelectionChanged,
        tooltip = tooltip,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
    )
}

/** The shared body of both overloads; only where the five numbers come from differs. */
@Suppress("LongParameterList")
@Composable
private fun <T> BoxPlotInternal(
    data: List<T>,
    label: (T) -> String,
    statistics: (Int, T) -> BoxStatistics?,
    modifier: Modifier,
    orientation: ChartOrientation,
    seriesName: String,
    color: ((T) -> Int?)?,
    categoryAxis: ChartAxis,
    valueAxis: ChartAxis,
    grid: ChartGrid,
    valueDomain: DomainPolicy,
    valueFormatter: ChartValueFormatter?,
    animation: ChartAnimation,
    interaction: ChartInteraction,
    crosshair: CrosshairConfig,
    viewportState: ChartViewportState,
    annotations: List<ChartAnnotation>,
    accessibility: ChartAccessibility,
    renderMode: ChartRenderMode,
    staticOptions: ChartStaticOptions,
    plotAlignment: ChartPlotAlignment?,
    accessibilitySummary: (() -> String)?,
    state: ChartState<T>,
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)?,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)?,
    isLoading: Boolean,
    error: Throwable?,
    loadingContent: @Composable () -> Unit,
    emptyContent: @Composable () -> Unit,
    errorContent: @Composable (Throwable) -> Unit,
) {
    val entries = remember(data, label, statistics, color) {
        data.mapIndexedNotNull { index, item ->
            val summary = statistics(index, item) ?: return@mapIndexedNotNull null
            BoxEntry(
                label = label(item),
                statistics = summary,
                item = item,
                paletteIndex = index,
                colorOverride = color?.invoke(item),
            )
        }
    }

    val layers = remember(entries, seriesName) {
        listOf(
            ResolvedLayer.Box(
                key = "box",
                entries = entries,
                seriesId = ChartDefaults.SINGLE_SERIES_ID,
                seriesName = seriesName,
            ),
        )
    }

    CartesianChartCore(
        layers = layers,
        modifier = modifier,
        orientation = orientation,
        domainAxis = categoryAxis,
        valueAxis = if (valueFormatter != null && valueAxis.valueFormatter == null) {
            valueAxis.copy(valueFormatter = valueFormatter)
        } else {
            valueAxis
        },
        grid = grid,
        // Not `Baseline`: a box plot encodes positions, not lengths from zero,
        // and forcing zero onto a chart of latencies between 180 and 240 ms
        // flattens the only variation worth seeing.
        valueDomainPolicy = valueAxis.domain ?: valueDomain,
        legend = io.devkit.chartkit.components.legend.LegendPosition.None,
        legendTogglesSeries = false,
        animation = animation,
        interaction = interaction,
        crosshair = crosshair,
        hitTestMode = HitTestMode.Contains,
        sharedTooltip = true,
        state = state.asErased(),
        viewportState = viewportState,
        sharedCrosshair = null,
        annotations = remember(annotations) {
            resolveAnnotations(annotations, ChartXResolver.Default)
        },
        renderMode = renderMode,
        staticOptions = staticOptions,
        plotAlignment = plotAlignment,
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
