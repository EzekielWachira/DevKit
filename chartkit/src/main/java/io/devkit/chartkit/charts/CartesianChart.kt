package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartGrid
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.geometry.BarGrouping
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.DEFAULT_GROUP_PADDING
import io.devkit.chartkit.geometry.LineInterpolation
import io.devkit.chartkit.interaction.ChartSelectionBehaviour
import io.devkit.chartkit.interaction.ChartSelectionMode
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.line.AreaFill
import io.devkit.chartkit.layer.line.LineStyle
import io.devkit.chartkit.layer.line.PointMode
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.model.ChartXAxisKind
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.model.MissingValuePolicy
import io.devkit.chartkit.model.normalizeSeries
import io.devkit.chartkit.scale.CategoryScale
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChartState

/**
 * Declares the layers of a [CartesianChart].
 *
 * ```kotlin
 * CartesianChart {
 *     bars(series = actuals, category = { it.month }, value = { it.amount })
 *     line(series = forecast, x = { it.month }, y = { it.amount })
 * }
 * ```
 *
 * Each call normalises its own data; the chart then merges every layer's domain
 * into **one** pair of scales. That is what makes the line and the bars line up
 * — and what makes a tap select across both, since selection is resolved
 * against all layers at once rather than by each layer separately.
 */
@ExperimentalChartKitApi
class CartesianChartScope internal constructor(
    private val hiddenSeriesIds: Set<String>,
) {
    internal val layers = mutableListOf<ResolvedLayer>()

    /**
     * Series declared so far, across every layer.
     *
     * Used as each new layer's palette offset, so a bar layer and a line layer
     * in one chart are not both drawn in the theme's first colour.
     */
    private var declaredSeries = 0

    /** A line layer. */
    fun <T> line(
        series: List<ChartSeries<T>>,
        x: (T) -> Any?,
        y: (T) -> Number?,
        interpolation: LineInterpolation = LineInterpolation.Linear,
        style: LineStyle = LineStyle.Solid,
        pointMode: PointMode = PointMode.Auto,
        lineWidth: Dp? = null,
        fill: AreaFill? = null,
        valueLabels: Boolean = false,
        missingValuePolicy: MissingValuePolicy = MissingValuePolicy.Break,
        xResolver: ChartXResolver = ChartXResolver.Default,
        xAxisKind: ChartXAxisKind? = null,
        dataOrder: ChartDataOrder = ChartDataOrder.InputOrder,
        performance: ChartPerformance = ChartPerformance.Default,
    ) {
        val data = normalizeSeries(
            series = series.applyVisibility(),
            x = x,
            y = y,
            xResolver = xResolver,
            missingValuePolicy = missingValuePolicy,
            xAxisKind = xAxisKind,
        ).let { if (dataOrder == ChartDataOrder.SortedByX) it.sortedByX() else it }
            .withPaletteOffset(declaredSeries)
        declaredSeries += series.size

        layers += ResolvedLayer.Line(
            key = "line${layers.size}",
            data = data,
            interpolation = interpolation,
            style = style,
            fill = fill,
            pointMode = pointMode,
            lineWidth = lineWidth,
            valueLabels = valueLabels,
            pointMarkerThreshold = performance.pointMarkerThreshold,
            missingValuePolicy = missingValuePolicy,
        )
    }

    /** An area layer: a line with the region beneath it filled. */
    fun <T> area(
        series: List<ChartSeries<T>>,
        x: (T) -> Any?,
        y: (T) -> Number?,
        fill: AreaFill = AreaFill.Default,
        interpolation: LineInterpolation = LineInterpolation.Linear,
        pointMode: PointMode = PointMode.Auto,
        lineWidth: Dp? = null,
        missingValuePolicy: MissingValuePolicy = MissingValuePolicy.Break,
        xResolver: ChartXResolver = ChartXResolver.Default,
        xAxisKind: ChartXAxisKind? = null,
    ) = line(
        series = series,
        x = x,
        y = y,
        interpolation = interpolation,
        pointMode = pointMode,
        lineWidth = lineWidth,
        fill = fill,
        missingValuePolicy = missingValuePolicy,
        xResolver = xResolver,
        xAxisKind = xAxisKind,
    )

    /** A bar layer. */
    fun <T> bars(
        series: List<ChartSeries<T>>,
        category: (T) -> Any?,
        value: (T) -> Number?,
        grouping: BarGrouping = BarGrouping.Grouped,
        cornerRadius: Dp? = null,
        categoryPadding: Double = CategoryScale.DEFAULT_CATEGORY_PADDING,
        groupPadding: Double = DEFAULT_GROUP_PADDING,
        valueLabels: Boolean = false,
        missingValuePolicy: MissingValuePolicy = MissingValuePolicy.Break,
        xResolver: ChartXResolver = ChartXResolver.Default,
    ) {
        val data = normalizeSeries(
            series = series.applyVisibility(),
            x = category,
            y = value,
            xResolver = xResolver,
            missingValuePolicy = missingValuePolicy,
            xAxisKind = ChartXAxisKind.Category,
        ).withPaletteOffset(declaredSeries)
        declaredSeries += series.size

        layers += ResolvedLayer.Bars(
            key = "bars${layers.size}",
            data = data,
            grouping = grouping,
            cornerRadius = cornerRadius,
            categoryPadding = categoryPadding,
            groupPadding = groupPadding,
            valueLabels = valueLabels,
        )
    }

    private fun <T> List<ChartSeries<T>>.applyVisibility(): List<ChartSeries<T>> =
        map { it.copy(visible = it.visible && it.id !in hiddenSeriesIds) }
}

/**
 * A Cartesian chart composed of layers.
 *
 * The engine `LineChart`, `AreaChart` and `BarChart` are built on, exposed
 * directly. Reach for it when a chart needs more than one kind of mark:
 *
 * ```kotlin
 * @OptIn(ExperimentalChartKitApi::class)
 * CartesianChart(modifier = Modifier.fillMaxWidth().height(240.dp)) {
 *     bars(series = listOf(actualSeries), category = { it.month }, value = { it.amount })
 *     line(series = listOf(targetSeries), x = { it.month }, y = { it.amount })
 * }
 * ```
 *
 * Marked [ExperimentalChartKitApi]: the layer grammar is where a real
 * visualisation DSL — annotations, second axes, custom marks — will want room
 * to move before 1.0. The high-level charts are not experimental and are not
 * expected to change.
 *
 * ### What is shared
 *
 * One plot area, one domain axis, one value axis, one hit test, one animation
 * clock. Layers cannot disagree about where a value sits, and a selection made
 * on one is visible to all — which is exactly what separate canvases could
 * never provide.
 *
 * @param content declares the layers. Runs during composition, so it must be
 *   cheap and free of side effects.
 */
@ExperimentalChartKitApi
@Suppress("LongParameterList")
@Composable
fun CartesianChart(
    modifier: Modifier = Modifier,
    orientation: ChartOrientation = ChartOrientation.Vertical,
    domainAxis: ChartAxis = ChartAxis.Default,
    valueAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = if (orientation.isVertical) ChartGrid.Horizontal else ChartGrid.Vertical,
    valueDomain: DomainPolicy = DomainPolicy.Baseline,
    legend: LegendPosition = LegendPosition.Bottom,
    legendTogglesSeries: Boolean = false,
    animation: ChartAnimation = ChartAnimation.Default,
    selectionMode: ChartSelectionMode = ChartSelectionMode.TapAndScrub,
    selectionBehaviour: ChartSelectionBehaviour = ChartSelectionBehaviour.Default,
    hitTestMode: HitTestMode = HitTestMode.NearestDomain,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    state: ChartState<Any?> = rememberChartState(),
    onSelectionChanged: ((AnyChartSelection?) -> Unit)? = null,
    tooltip: (@Composable (AnyChartSelection) -> Unit)? = { ChartDefaults.Tooltip(it) },
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
    content: CartesianChartScope.() -> Unit,
) {
    val hidden = state.hiddenSeriesIds
    val layers = remember(content, hidden) {
        CartesianChartScope(hidden).apply(content).layers
    }

    CartesianChartCore(
        layers = layers,
        modifier = modifier,
        orientation = orientation,
        domainAxis = domainAxis,
        valueAxis = valueAxis,
        grid = grid,
        valueDomainPolicy = valueAxis.domain ?: valueDomain,
        legend = legend,
        legendTogglesSeries = legendTogglesSeries,
        animation = animation,
        selectionMode = selectionMode,
        selectionBehaviour = selectionBehaviour,
        hitTestMode = hitTestMode,
        state = state,
        onSelectionChanged = onSelectionChanged,
        tooltip = tooltip,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
    )
}
