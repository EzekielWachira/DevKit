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
import io.devkit.chartkit.axis.ChartAxisId
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.comparison.BulletEntry
import io.devkit.chartkit.layer.comparison.BulletRange
import io.devkit.chartkit.layer.comparison.ConnectorMarkEntry
import io.devkit.chartkit.layer.comparison.ConnectorMarkKind
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.state.ChartPlotAlignment
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.ChartViewportState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.state.rememberChartViewportState
import io.devkit.chartkit.transform.WaterfallStepKind
import io.devkit.chartkit.transform.WaterfallTransform

/**
 * A waterfall chart: contributions that accumulate into a running total.
 *
 * ```kotlin
 * WaterfallChart(
 *     data = movements,
 *     label = { it.name },
 *     value = { it.amount },
 *     kind = { WaterfallTransform.signedKind(it.amount) },
 *     modifier = Modifier.fillMaxWidth().height(240.dp),
 * )
 * ```
 *
 * ```text
 *          ┌──┐
 *  ┌───┐   │  │╌╌┌──┐            ┌────┐
 *  │   │╌╌╌┘  │  │  │╌╌┌──┐╌╌╌╌╌╌│    │
 *  └───┘      └──┘  └──┘  └──┘   └────┘
 *  Start     Rev   Cost  Tax     Total
 * ```
 *
 * Built on the same engine as every bar chart: the axes, the grid, the
 * viewport, the crosshair, the annotations and the tooltip are shared. What is
 * different is where a bar starts.
 *
 * ### Sign comes from the kind
 *
 * `WaterfallStepKind.Decrease` with a value of `20` and one with `-20` both
 * fall by twenty. Requiring the caller to negate their own decreases is the
 * kind of convention that produces a chart wrong in exactly one bar.
 *
 * @param kind the step's role. A caller whose data is already signed passes
 *   `{ WaterfallTransform.signedKind(it.amount) }`.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun <T> WaterfallChart(
    data: List<T>,
    label: (T) -> String,
    value: (T) -> Number?,
    modifier: Modifier = Modifier,
    kind: (T) -> WaterfallStepKind = { WaterfallTransform.signedKind(value(it)) },
    orientation: ChartOrientation = ChartOrientation.Vertical,
    showConnectors: Boolean = true,
    cornerRadius: Dp? = null,
    categoryAxis: ChartAxis = ChartAxis.Default,
    valueAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = if (orientation.isVertical) ChartGrid.Horizontal else ChartGrid.Vertical,
    valueDomain: DomainPolicy = DomainPolicy.Baseline,
    legend: LegendPosition = LegendPosition.None,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.TapOnly,
    annotations: List<ChartAnnotation> = emptyList(),
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    plotAlignment: ChartPlotAlignment? = null,
    viewportState: ChartViewportState = rememberChartViewportState(),
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Tooltip(it, showSeriesNames = false)
    },
    seriesId: String = "waterfall",
    seriesName: String = "Waterfall",
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    val layer = remember(data, label, value, kind, showConnectors, cornerRadius) {
        ResolvedLayer.Waterfall(
            key = "waterfall",
            steps = WaterfallTransform.resolve(data, label, value, kind),
            seriesId = seriesId,
            seriesName = seriesName,
            showConnectors = showConnectors,
            cornerRadius = cornerRadius,
            valueAxisId = ChartAxisId.DefaultY,
            declaredUnits = emptySet(),
        )
    }

    CartesianChartCore(
        layers = listOf(layer),
        modifier = modifier,
        orientation = orientation,
        domainAxis = categoryAxis,
        valueAxis = valueAxis,
        grid = grid,
        valueDomainPolicy = valueAxis.domain ?: valueDomain,
        legend = legend,
        legendTogglesSeries = false,
        animation = animation,
        interaction = interaction,
        crosshair = ChartDefaults.SelectionGuide,
        hitTestMode = HitTestMode.Contains,
        sharedTooltip = false,
        state = state.asErased(),
        viewportState = viewportState,
        sharedCrosshair = null,
        annotations = remember(annotations) { resolveAnnotations(annotations, ChartXResolver.Default) },
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

/**
 * A dumbbell chart: two values per category, joined by a bar.
 *
 * ```kotlin
 * DumbbellChart(
 *     data = teams,
 *     category = { it.name },
 *     start = { it.lastYear },
 *     end = { it.thisYear },
 *     startLabel = "2024",
 *     endLabel = "2025",
 * )
 * ```
 *
 * ```text
 * Android   ○───────●
 * iOS         ○──●
 * Web       ●──────────○
 * ```
 *
 * The **distance** is the reading. Two bars side by side show the same numbers
 * and leave the reader to compute the gap; a dumbbell draws it.
 *
 * Useful for before against after, actual against target, and any two
 * comparable measurements of the same thing.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun <T> DumbbellChart(
    data: List<T>,
    category: (T) -> String,
    start: (T) -> Number?,
    end: (T) -> Number?,
    modifier: Modifier = Modifier,
    startLabel: String = "Before",
    endLabel: String = "After",
    orientation: ChartOrientation = ChartOrientation.Horizontal,
    color: ((T) -> Int?)? = null,
    categoryAxis: ChartAxis = ChartAxis.Default,
    valueAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = if (orientation.isVertical) ChartGrid.Horizontal else ChartGrid.Vertical,
    valueDomain: DomainPolicy = DomainPolicy.Default,
    legend: LegendPosition = LegendPosition.None,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.TapOnly,
    annotations: List<ChartAnnotation> = emptyList(),
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    plotAlignment: ChartPlotAlignment? = null,
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = { ChartDefaults.Tooltip(it) },
    seriesId: String = "dumbbell",
    seriesName: String = "Change",
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    ConnectorMarkChart(
        data = data,
        category = category,
        start = start,
        end = end,
        kind = ConnectorMarkKind.Dumbbell,
        startLabel = startLabel,
        endLabel = endLabel,
        modifier = modifier,
        orientation = orientation,
        color = color,
        categoryAxis = categoryAxis,
        valueAxis = valueAxis,
        grid = grid,
        valueDomain = valueDomain,
        legend = legend,
        animation = animation,
        interaction = interaction,
        annotations = annotations,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        renderMode = renderMode,
        staticOptions = staticOptions,
        plotAlignment = plotAlignment,
        state = state,
        onSelectionChanged = onSelectionChanged,
        tooltip = tooltip,
        seriesId = seriesId,
        seriesName = seriesName,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
    )
}

/**
 * A lollipop chart: a stem from the baseline to a marker, per category.
 *
 * ```text
 * Android   ─────────●
 * iOS       ──────●
 * Web       ────────────●
 * ```
 *
 * A bar chart with the ink taken out. Better than bars when the categories are
 * many and the values are close together, where a row of wide bars is mostly
 * fill and only the tops are being compared.
 *
 * Not a separate renderer: a lollipop is a dumbbell whose first value is the
 * baseline, and the two share an implementation for exactly that reason.
 */
@Suppress("LongParameterList")
@Composable
fun <T> LollipopChart(
    data: List<T>,
    category: (T) -> String,
    value: (T) -> Number?,
    modifier: Modifier = Modifier,
    orientation: ChartOrientation = ChartOrientation.Horizontal,
    color: ((T) -> Int?)? = null,
    categoryAxis: ChartAxis = ChartAxis.Default,
    valueAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = if (orientation.isVertical) ChartGrid.Horizontal else ChartGrid.Vertical,
    valueDomain: DomainPolicy = DomainPolicy.Baseline,
    legend: LegendPosition = LegendPosition.None,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.TapOnly,
    annotations: List<ChartAnnotation> = emptyList(),
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    plotAlignment: ChartPlotAlignment? = null,
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Tooltip(it, showSeriesNames = false)
    },
    seriesId: String = "lollipop",
    seriesName: String = "",
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    ConnectorMarkChart(
        data = data,
        category = category,
        start = { null },
        end = value,
        kind = ConnectorMarkKind.Lollipop,
        startLabel = "",
        endLabel = "",
        modifier = modifier,
        orientation = orientation,
        color = color,
        categoryAxis = categoryAxis,
        valueAxis = valueAxis,
        grid = grid,
        valueDomain = valueDomain,
        legend = legend,
        animation = animation,
        interaction = interaction,
        annotations = annotations,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        renderMode = renderMode,
        staticOptions = staticOptions,
        plotAlignment = plotAlignment,
        state = state,
        onSelectionChanged = onSelectionChanged,
        tooltip = tooltip,
        seriesId = seriesId,
        seriesName = seriesName,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
    )
}

/** The shared body of [DumbbellChart] and [LollipopChart]. */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun <T> ConnectorMarkChart(
    data: List<T>,
    category: (T) -> String,
    start: (T) -> Number?,
    end: (T) -> Number?,
    kind: ConnectorMarkKind,
    startLabel: String,
    endLabel: String,
    modifier: Modifier,
    orientation: ChartOrientation,
    color: ((T) -> Int?)?,
    categoryAxis: ChartAxis,
    valueAxis: ChartAxis,
    grid: ChartGrid,
    valueDomain: DomainPolicy,
    legend: LegendPosition,
    animation: ChartAnimation,
    interaction: ChartInteraction,
    annotations: List<ChartAnnotation>,
    accessibility: ChartAccessibility,
    accessibilitySummary: (() -> String)?,
    renderMode: ChartRenderMode,
    staticOptions: ChartStaticOptions,
    plotAlignment: ChartPlotAlignment?,
    state: ChartState<T>,
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)?,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)?,
    seriesId: String,
    seriesName: String,
    isLoading: Boolean,
    error: Throwable?,
    loadingContent: @Composable () -> Unit,
    emptyContent: @Composable () -> Unit,
    errorContent: @Composable (Throwable) -> Unit,
) {
    val layer = remember(data, category, start, end, kind, color) {
        ResolvedLayer.ConnectorMarks(
            key = "marks",
            entries = data.mapNotNull { item ->
                val to = end(item)?.toDouble()?.takeIf { it.isFinite() } ?: return@mapNotNull null
                ConnectorMarkEntry(
                    label = category(item),
                    start = start(item)?.toDouble()?.takeIf { it.isFinite() },
                    end = to,
                    item = item,
                    paletteIndex = 0,
                    colorOverride = color?.invoke(item),
                )
            },
            kind = kind,
            seriesId = seriesId,
            seriesName = seriesName,
            startLabel = startLabel,
            endLabel = endLabel,
            valueAxisId = ChartAxisId.DefaultY,
            declaredUnits = emptySet(),
        )
    }

    CartesianChartCore(
        layers = listOf(layer),
        modifier = modifier,
        orientation = orientation,
        domainAxis = categoryAxis,
        valueAxis = valueAxis,
        grid = grid,
        valueDomainPolicy = valueAxis.domain ?: valueDomain,
        legend = legend,
        legendTogglesSeries = false,
        animation = animation,
        interaction = interaction,
        crosshair = ChartDefaults.SelectionGuide,
        hitTestMode = HitTestMode.Contains,
        sharedTooltip = kind == ConnectorMarkKind.Dumbbell,
        state = state.asErased(),
        viewportState = rememberChartViewportState(),
        sharedCrosshair = null,
        annotations = remember(annotations) { resolveAnnotations(annotations, ChartXResolver.Default) },
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

/**
 * A bullet graph: a measure against a target, on qualitative ranges.
 *
 * ```kotlin
 * BulletChart(
 *     data = metrics,
 *     label = { it.name },
 *     actual = { it.value },
 *     target = { it.target },
 *     ranges = { listOf(BulletRange(0.0, 50.0), BulletRange(50.0, 75.0), BulletRange(75.0, 100.0)) },
 * )
 * ```
 *
 * ```text
 * Revenue  ░░░░░▒▒▒▒▓▓▓▓
 *          ██████████│
 * ```
 *
 * Stephen Few's replacement for the gauge, which spends a whole circle on one
 * number. Several bullets stack into the space one dial would take and — because
 * they share an axis — can be compared with each other.
 *
 * ### Horizontal first
 *
 * Rows read left to right and their labels sit beside them, which is what makes
 * a stack of them legible. Vertical works — pass
 * [ChartOrientation.Vertical] — but the labels then compete for width with the
 * bars.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun <T> BulletChart(
    data: List<T>,
    label: (T) -> String,
    actual: (T) -> Number?,
    modifier: Modifier = Modifier,
    target: ((T) -> Number?)? = null,
    ranges: ((T) -> List<BulletRange>)? = null,
    targetLabel: String = "Target",
    orientation: ChartOrientation = ChartOrientation.Horizontal,
    color: ((T) -> Int?)? = null,
    categoryAxis: ChartAxis = ChartAxis.Default,
    valueAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = ChartGrid.None,
    valueDomain: DomainPolicy = DomainPolicy.Baseline,
    legend: LegendPosition = LegendPosition.None,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.TapOnly,
    annotations: List<ChartAnnotation> = emptyList(),
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    plotAlignment: ChartPlotAlignment? = null,
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = { ChartDefaults.Tooltip(it) },
    seriesId: String = "bullet",
    seriesName: String = "Measure",
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    val layer = remember(data, label, actual, target, ranges, color) {
        ResolvedLayer.Bullet(
            key = "bullet",
            entries = data.mapNotNull { item ->
                val measure = actual(item)?.toDouble()?.takeIf { it.isFinite() }
                    ?: return@mapNotNull null
                BulletEntry(
                    label = label(item),
                    actual = measure,
                    target = target?.invoke(item)?.toDouble()?.takeIf { it.isFinite() },
                    ranges = ranges?.invoke(item).orEmpty(),
                    item = item,
                    paletteIndex = 0,
                    colorOverride = color?.invoke(item),
                )
            },
            seriesId = seriesId,
            seriesName = seriesName,
            targetLabel = targetLabel,
            valueAxisId = ChartAxisId.DefaultY,
            declaredUnits = emptySet(),
        )
    }

    CartesianChartCore(
        layers = listOf(layer),
        modifier = modifier,
        orientation = orientation,
        domainAxis = categoryAxis,
        valueAxis = valueAxis,
        grid = grid,
        valueDomainPolicy = valueAxis.domain ?: valueDomain,
        legend = legend,
        legendTogglesSeries = false,
        animation = animation,
        interaction = interaction,
        crosshair = CrosshairConfig(enabled = false),
        hitTestMode = HitTestMode.Contains,
        sharedTooltip = true,
        state = state.asErased(),
        viewportState = rememberChartViewportState(),
        sharedCrosshair = null,
        annotations = remember(annotations) { resolveAnnotations(annotations, ChartXResolver.Default) },
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
