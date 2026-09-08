package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.annotation.ChartAnnotation
import io.devkit.chartkit.animation.rememberAnimatedSeriesValues
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartGrid
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.BarGrouping
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.DEFAULT_GROUP_PADDING
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.model.ChartRangeSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.model.ChartXAxisKind
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.model.MissingValuePolicy
import io.devkit.chartkit.model.normalizeSeries
import io.devkit.chartkit.scale.CategoryScale
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.state.ChartSharedCrosshairState
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.ChartViewportState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.state.rememberChartViewportState

/**
 * A bar chart over the caller's own data.
 *
 * ```kotlin
 * data class Sale(val product: String, val total: Double)
 *
 * BarChart(
 *     data = sales,
 *     category = { it.product },
 *     value = { it.total },
 * )
 * ```
 *
 * ### Why the value axis includes zero
 *
 * A bar encodes its value as a *length from a baseline*, so a bar chart whose
 * axis starts at 98 draws `[98, 100]` as one bar twice the height of the other
 * — a factual chart making a false claim. The default is therefore
 * [DomainPolicy.Baseline], and unlike a line chart it is not sensible to change
 * casually. It is still overridable, because a caller who understands the
 * trade-off may have a reason.
 *
 * @param category reads the band from an item. See [ChartXResolver] for the
 *   accepted types.
 * @param value reads the bar's value. Negative values draw below the baseline.
 */
@Composable
fun <T> BarChart(
    data: List<T>,
    category: (T) -> Any?,
    value: (T) -> Number?,
    modifier: Modifier = Modifier,
    seriesName: String = "",
    orientation: ChartOrientation = ChartOrientation.Vertical,
    cornerRadius: Dp? = null,
    categoryPadding: Double = CategoryScale.DEFAULT_CATEGORY_PADDING,
    categoryAxis: ChartAxis = ChartAxis.Default,
    valueAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = defaultBarGrid(orientation),
    valueDomain: DomainPolicy = DomainPolicy.Baseline,
    valueLabels: Boolean = false,
    valueFormatter: ChartValueFormatter? = null,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.TapOnly,
    crosshair: CrosshairConfig = ChartDefaults.SelectionGuide,
    sharedTooltip: Boolean = crosshair.enabled && crosshair.showAxisLabels,
    viewportState: ChartViewportState = rememberChartViewportState(),
    missingValuePolicy: MissingValuePolicy = MissingValuePolicy.Break,
    xResolver: ChartXResolver = ChartXResolver.Default,
    annotations: List<ChartAnnotation> = emptyList(),
    sharedCrosshair: ChartSharedCrosshairState? = null,
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
    BarChart(
        series = remember(data, seriesName) {
            singleSeries(data, ChartDefaults.SINGLE_SERIES_ID, seriesName)
        },
        category = category,
        value = value,
        modifier = modifier,
        grouping = BarGrouping.Grouped,
        orientation = orientation,
        cornerRadius = cornerRadius,
        categoryPadding = categoryPadding,
        categoryAxis = categoryAxis,
        valueAxis = valueAxis,
        grid = grid,
        legend = LegendPosition.None,
        valueDomain = valueDomain,
        valueLabels = valueLabels,
        valueFormatter = valueFormatter,
        animation = animation,
        interaction = interaction,
        crosshair = crosshair,
        sharedTooltip = sharedTooltip,
        viewportState = viewportState,
        missingValuePolicy = missingValuePolicy,
        xResolver = xResolver,
        annotations = annotations,
        sharedCrosshair = sharedCrosshair,
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
 * A multi-series bar chart: grouped, stacked or 100% stacked.
 *
 * ```kotlin
 * BarChart(
 *     series = listOf(
 *         ChartSeries(id = "new", name = "New", data = newCustomers),
 *         ChartSeries(id = "returning", name = "Returning", data = returning),
 *     ),
 *     category = { it.quarter },
 *     value = { it.count },
 *     grouping = BarGrouping.Stacked,
 * )
 * ```
 *
 * Grouped, stacked and 100% stacked are the same chart with a different
 * [grouping] — one geometry, one hit test, one animation — rather than three
 * components that would each need fixing separately.
 *
 * @param grouping how the series share a category.
 *   [BarGrouping.StackedPercent] normalises each category to its own total; a
 *   category totalling zero yields empty segments rather than a division by
 *   zero.
 */
@JvmName("BarChartSeries")
@Composable
fun <T> BarChart(
    series: List<ChartSeries<T>>,
    category: (T) -> Any?,
    value: (T) -> Number?,
    modifier: Modifier = Modifier,
    grouping: BarGrouping = BarGrouping.Grouped,
    orientation: ChartOrientation = ChartOrientation.Vertical,
    cornerRadius: Dp? = null,
    categoryPadding: Double = CategoryScale.DEFAULT_CATEGORY_PADDING,
    groupPadding: Double = DEFAULT_GROUP_PADDING,
    categoryAxis: ChartAxis = ChartAxis.Default,
    valueAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = defaultBarGrid(orientation),
    legend: LegendPosition = if (series.size > 1) LegendPosition.Bottom else LegendPosition.None,
    legendTogglesSeries: Boolean = false,
    valueDomain: DomainPolicy = DomainPolicy.Baseline,
    valueLabels: Boolean = false,
    valueFormatter: ChartValueFormatter? = null,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.TapOnly,
    crosshair: CrosshairConfig = ChartDefaults.SelectionGuide,
    sharedTooltip: Boolean = crosshair.enabled && crosshair.showAxisLabels,
    viewportState: ChartViewportState = rememberChartViewportState(),
    missingValuePolicy: MissingValuePolicy = MissingValuePolicy.Break,
    xResolver: ChartXResolver = ChartXResolver.Default,
    annotations: List<ChartAnnotation> = emptyList(),
    sharedCrosshair: ChartSharedCrosshairState? = null,
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

    val plotData = remember(visibleSeries, category, value, xResolver, missingValuePolicy) {
        normalizeSeries(
            series = visibleSeries,
            x = category,
            y = value,
            xResolver = xResolver,
            missingValuePolicy = missingValuePolicy,
            // Bars are always banded. A numeric category would give bars of
            // proportional spacing and no width, which is a scatter plot with
            // the wrong marks.
            xAxisKind = ChartXAxisKind.Category,
        )
    }

    val targetValues = remember(plotData) { plotData.series.map { s -> s.points.map { it.y } } }
    val animatedValues = rememberAnimatedSeriesValues(
        target = targetValues,
        seriesIds = remember(plotData) { plotData.series.map { it.id } },
        animation = animation,
    )
    val animatedData = remember(plotData, animatedValues) { plotData.withValues(animatedValues) }

    val layers = remember(
        animatedData, grouping, cornerRadius, categoryPadding, groupPadding, valueLabels,
    ) {
        listOf(
            ResolvedLayer.Bars(
                key = "bars",
                data = animatedData,
                grouping = grouping,
                cornerRadius = cornerRadius,
                categoryPadding = categoryPadding,
                groupPadding = groupPadding,
                valueLabels = valueLabels,
            ),
        )
    }

    // A 100% stacked chart is in fractions, so its axis is labelled in percent
    // unless the caller says otherwise — an axis reading 0.0 to 1.0 on a chart
    // presented as percentages is the sort of mismatch nobody notices in
    // review and everybody notices in production.
    val resolvedValueFormatter = valueFormatter ?: if (grouping == BarGrouping.StackedPercent) {
        io.devkit.chartkit.formatter.ChartNumberFormatters.fraction()
    } else {
        null
    }

    CartesianChartCore(
        layers = layers,
        modifier = modifier,
        orientation = orientation,
        domainAxis = categoryAxis,
        valueAxis = if (resolvedValueFormatter != null && valueAxis.valueFormatter == null) {
            valueAxis.copy(valueFormatter = resolvedValueFormatter)
        } else {
            valueAxis
        },
        grid = grid,
        valueDomainPolicy = valueAxis.domain ?: valueDomain,
        legend = legend,
        legendTogglesSeries = legendTogglesSeries,
        animation = animation,
        interaction = interaction,
        crosshair = crosshair,
        hitTestMode = HitTestMode.Contains,
        sharedTooltip = sharedTooltip,
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
 * A horizontal bar chart: categories up the side, values across.
 *
 * Delegates to [BarChart] with [ChartOrientation.Horizontal]. It is not a
 * separate renderer, and that is the point — bar geometry is expressed in
 * domain and value terms rather than x and y, so the orientation is a
 * parameter rather than a second implementation to keep in step.
 *
 * Useful whenever category names are long: a horizontal chart gives each label
 * a full line instead of a bar's width.
 */
@Composable
fun <T> HorizontalBarChart(
    data: List<T>,
    category: (T) -> Any?,
    value: (T) -> Number?,
    modifier: Modifier = Modifier,
    seriesName: String = "",
    cornerRadius: Dp? = null,
    categoryPadding: Double = CategoryScale.DEFAULT_CATEGORY_PADDING,
    categoryAxis: ChartAxis = ChartAxis.Default,
    valueAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = ChartGrid.Vertical,
    valueDomain: DomainPolicy = DomainPolicy.Baseline,
    valueLabels: Boolean = false,
    valueFormatter: ChartValueFormatter? = null,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.TapOnly,
    crosshair: CrosshairConfig = ChartDefaults.SelectionGuide,
    sharedTooltip: Boolean = crosshair.enabled && crosshair.showAxisLabels,
    viewportState: ChartViewportState = rememberChartViewportState(),
    annotations: List<ChartAnnotation> = emptyList(),
    sharedCrosshair: ChartSharedCrosshairState? = null,
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
    BarChart(
        data = data,
        category = category,
        value = value,
        modifier = modifier,
        seriesName = seriesName,
        orientation = ChartOrientation.Horizontal,
        cornerRadius = cornerRadius,
        categoryPadding = categoryPadding,
        categoryAxis = categoryAxis,
        valueAxis = valueAxis,
        grid = grid,
        valueDomain = valueDomain,
        valueLabels = valueLabels,
        valueFormatter = valueFormatter,
        animation = animation,
        interaction = interaction,
        crosshair = crosshair,
        sharedTooltip = sharedTooltip,
        viewportState = viewportState,
        annotations = annotations,
        sharedCrosshair = sharedCrosshair,
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
 * Grid lines across the value axis, whichever way the chart runs.
 *
 * [ChartGrid] names screen directions, so the value axis' grid is horizontal on
 * a vertical chart and vertical on a horizontal one. Getting this wrong draws
 * grid lines between categories, which measure nothing.
 */
private fun defaultBarGrid(orientation: ChartOrientation): ChartGrid =
    if (orientation.isVertical) ChartGrid.Horizontal else ChartGrid.Vertical
