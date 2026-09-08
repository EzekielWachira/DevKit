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
import io.devkit.chartkit.axis.ValueAxisBinding
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.geometry.BarGrouping
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.DEFAULT_GROUP_PADDING
import io.devkit.chartkit.geometry.LineInterpolation
import io.devkit.chartkit.geometry.OhlcPolicy
import io.devkit.chartkit.geometry.ScatterShape
import io.devkit.chartkit.geometry.normalizeOhlc
import io.devkit.chartkit.layer.financial.PriceMarkStyle
import io.devkit.chartkit.layer.comparison.BulletEntry
import io.devkit.chartkit.layer.comparison.BulletRange
import io.devkit.chartkit.layer.comparison.ConnectorMarkEntry
import io.devkit.chartkit.layer.comparison.ConnectorMarkKind
import io.devkit.chartkit.layer.custom.CartesianLayerContext
import io.devkit.chartkit.layer.custom.CartesianLayerScope
import io.devkit.chartkit.layer.custom.CustomCartesianLayer
import io.devkit.chartkit.layer.custom.CustomLayerHit
import io.devkit.chartkit.layer.custom.CustomLayerItem
import io.devkit.chartkit.layer.custom.CustomLayerLegendEntry
import io.devkit.chartkit.layer.scatter.ScatterStyle
import io.devkit.chartkit.layer.timeline.IntervalLabels
import io.devkit.chartkit.timeline.TimelineDependency
import io.devkit.chartkit.timeline.buildTimeline
import io.devkit.chartkit.transform.WaterfallStepKind
import io.devkit.chartkit.transform.WaterfallTransform
import io.devkit.chartkit.model.ChartX
import io.devkit.chartkit.model.resolveOrDefault
import io.devkit.chartkit.scale.SizeScale
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.line.AreaFill
import io.devkit.chartkit.layer.line.LineStyle
import io.devkit.chartkit.layer.line.PointMode
import io.devkit.chartkit.model.AnyChartRangeSelection
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.AnyChartTooltipData
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.model.ChartXAxisKind
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.model.MissingValuePolicy
import io.devkit.chartkit.model.normalizeSeries
import io.devkit.chartkit.scale.CategoryScale
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
        valueAxis: ValueAxisBinding = ValueAxisBinding.Primary,
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
            performance = performance,
            valueAxis = valueAxis,
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
        valueAxis: ValueAxisBinding = ValueAxisBinding.Primary,
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
        valueAxis = valueAxis,
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
        valueAxis: ValueAxisBinding = ValueAxisBinding.Primary,
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
            valueAxis = valueAxis,
        )
    }

    /**
     * A scatter or bubble layer.
     *
     * @param size an optional third variable driving the marker's size. The
     *   scale spans every series in the layer, so two series are measured
     *   against the same domain.
     */
    @Suppress("LongParameterList")
    fun <T> scatter(
        series: List<ChartSeries<T>>,
        x: (T) -> Any?,
        y: (T) -> Number?,
        size: ((T) -> Number?)? = null,
        sizeScale: SizeScale? = null,
        shape: ScatterShape = ScatterShape.Circle,
        style: ScatterStyle = if (size != null) ScatterStyle.Bubble else ScatterStyle.Point,
        pointRadius: Dp? = null,
        xResolver: ChartXResolver = ChartXResolver.Default,
        xAxisKind: ChartXAxisKind? = null,
        performance: ChartPerformance = ChartPerformance.Default,
        valueAxis: ValueAxisBinding = ValueAxisBinding.Primary,
    ) {
        val visible = series.applyVisibility()
        val data = normalizeSeries(
            series = visible,
            x = x,
            y = y,
            xResolver = xResolver,
            missingValuePolicy = MissingValuePolicy.Break,
            xAxisKind = xAxisKind,
        ).withPaletteOffset(declaredSeries)
        declaredSeries += series.size

        layers += ResolvedLayer.Scatter(
            key = "scatter${layers.size}",
            data = data,
            shape = shape,
            style = style,
            sizes = size?.let { accessor -> visible.map { s -> s.data.map { accessor(it)?.toDouble() } } },
            sizeScale = sizeScale,
            pointRadius = pointRadius,
            performance = performance,
            valueAxis = valueAxis,
        )
    }

    /**
     * A candlestick or OHLC layer.
     *
     * The layer a combined financial chart is built from: declare candles and a
     * moving-average line together and they share one plot area, one pair of
     * scales and one hit test.
     *
     * ```kotlin
     * CartesianChart {
     *     candles(data = prices, x = { it.time },
     *         open = { it.open }, high = { it.high }, low = { it.low }, close = { it.close })
     *     line(series = listOf(ChartSeries("ma20", "20-day MA", movingAverage)),
     *         x = { it.time }, y = { it.value })
     * }
     * ```
     */
    @Suppress("LongParameterList")
    fun <T> candles(
        data: List<T>,
        x: (T) -> Any?,
        open: (T) -> Number?,
        high: (T) -> Number?,
        low: (T) -> Number?,
        close: (T) -> Number?,
        volume: ((T) -> Number?)? = null,
        markStyle: PriceMarkStyle = PriceMarkStyle.Candle,
        policy: OhlcPolicy = OhlcPolicy.Repair,
        seriesId: String = "price",
        seriesName: String = "Price",
        xResolver: ChartXResolver = ChartXResolver.Time,
        xAxisKind: ChartXAxisKind = ChartXAxisKind.Time,
    ) {
        val resolved = resolveOhlcLayer(data, x, open, high, low, close, volume, policy, xResolver)
        declaredSeries += 1
        layers += ResolvedLayer.Candles(
            key = "candles${layers.size}",
            points = resolved.points,
            xValues = resolved.xValues,
            markStyle = markStyle,
            seriesId = seriesId,
            seriesName = seriesName,
            items = data,
            axisKind = xAxisKind,
        )
    }

    /**
     * A volume layer, coloured by each period's price direction.
     *
     * Sharing a value axis with a price layer makes volume unreadable — the two
     * quantities differ by orders of magnitude — so volume normally belongs in
     * its own chart, linked by a shared viewport. This layer exists for the
     * cases where the axis genuinely is shared: a volume-only chart built
     * through the DSL, or one combined with an indicator on the same scale.
     */
    @Suppress("LongParameterList")
    fun <T> volume(
        data: List<T>,
        x: (T) -> Any?,
        volume: (T) -> Number?,
        open: ((T) -> Number?)? = null,
        close: ((T) -> Number?)? = null,
        seriesId: String = "volume",
        seriesName: String = "Volume",
        xResolver: ChartXResolver = ChartXResolver.Time,
        xAxisKind: ChartXAxisKind = ChartXAxisKind.Time,
    ) {
        val resolved = resolveOhlcLayer(
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
        layers += ResolvedLayer.Volume(
            key = "volume${layers.size}",
            points = resolved.points,
            xValues = resolved.xValues,
            seriesId = seriesId,
            seriesName = seriesName,
            items = data,
            axisKind = xAxisKind,
        )
    }

    /**
     * A waterfall layer: contributions that accumulate into a running total.
     *
     * @param kind the step's role. A caller whose data is already signed passes
     *   `{ WaterfallTransform.signedKind(it.amount) }`.
     */
    fun <T> waterfall(
        data: List<T>,
        label: (T) -> String,
        value: (T) -> Number?,
        kind: (T) -> WaterfallStepKind = { WaterfallStepKind.Increase },
        showConnectors: Boolean = true,
        cornerRadius: Dp? = null,
        seriesId: String = "waterfall",
        seriesName: String = "Waterfall",
        valueAxis: ValueAxisBinding = ValueAxisBinding.Primary,
    ) {
        declaredSeries += 1
        layers += ResolvedLayer.Waterfall(
            key = "waterfall${layers.size}",
            steps = WaterfallTransform.resolve(data, label, value, kind),
            seriesId = seriesId,
            seriesName = seriesName,
            showConnectors = showConnectors,
            cornerRadius = cornerRadius,
            valueAxis = valueAxis,
        )
    }

    /**
     * A dumbbell layer: two values per category, joined by a bar.
     *
     * @param startLabel what the first value is called, in tooltips and
     *   announcements. "Before" and "After" by default, because that is the
     *   comparison a dumbbell is most often used for and an unlabelled pair of
     *   numbers is not a comparison.
     */
    @Suppress("LongParameterList")
    fun <T> dumbbell(
        data: List<T>,
        category: (T) -> String,
        start: (T) -> Number?,
        end: (T) -> Number?,
        startLabel: String = "Before",
        endLabel: String = "After",
        color: ((T) -> Int?)? = null,
        seriesId: String = "dumbbell",
        seriesName: String = "Change",
        valueAxis: ValueAxisBinding = ValueAxisBinding.Primary,
    ) {
        val offset = declaredSeries
        declaredSeries += 1
        layers += ResolvedLayer.ConnectorMarks(
            key = "dumbbell${layers.size}",
            entries = data.mapIndexedNotNull { index, item ->
                val to = end(item)?.toDouble()?.takeIf { it.isFinite() } ?: return@mapIndexedNotNull null
                ConnectorMarkEntry(
                    label = category(item),
                    start = start(item)?.toDouble()?.takeIf { it.isFinite() },
                    end = to,
                    item = item,
                    paletteIndex = offset,
                    colorOverride = color?.invoke(item),
                )
            },
            kind = ConnectorMarkKind.Dumbbell,
            seriesId = seriesId,
            seriesName = seriesName,
            startLabel = startLabel,
            endLabel = endLabel,
            valueAxis = valueAxis,
        )
    }

    /** A lollipop layer: a stem from the baseline to a marker, per category. */
    fun <T> lollipop(
        data: List<T>,
        category: (T) -> String,
        value: (T) -> Number?,
        color: ((T) -> Int?)? = null,
        seriesId: String = "lollipop",
        seriesName: String = "",
        valueAxis: ValueAxisBinding = ValueAxisBinding.Primary,
    ) {
        val offset = declaredSeries
        declaredSeries += 1
        layers += ResolvedLayer.ConnectorMarks(
            key = "lollipop${layers.size}",
            entries = data.mapIndexedNotNull { index, item ->
                val to = value(item)?.toDouble()?.takeIf { it.isFinite() }
                    ?: return@mapIndexedNotNull null
                ConnectorMarkEntry(
                    label = category(item),
                    start = null,
                    end = to,
                    item = item,
                    paletteIndex = offset,
                    colorOverride = color?.invoke(item),
                )
            },
            kind = ConnectorMarkKind.Lollipop,
            seriesId = seriesId,
            seriesName = seriesName,
            startLabel = "",
            endLabel = "",
            valueAxis = valueAxis,
        )
    }

    /** A bullet layer: a measure against a target, on qualitative ranges. */
    @Suppress("LongParameterList")
    fun <T> bullet(
        data: List<T>,
        label: (T) -> String,
        actual: (T) -> Number?,
        target: ((T) -> Number?)? = null,
        ranges: ((T) -> List<BulletRange>)? = null,
        color: ((T) -> Int?)? = null,
        targetLabel: String = "Target",
        seriesId: String = "bullet",
        seriesName: String = "Measure",
        valueAxis: ValueAxisBinding = ValueAxisBinding.Primary,
    ) {
        val offset = declaredSeries
        declaredSeries += 1
        layers += ResolvedLayer.Bullet(
            key = "bullet${layers.size}",
            entries = data.mapNotNull { item ->
                val measure = actual(item)?.toDouble()?.takeIf { it.isFinite() } ?: return@mapNotNull null
                BulletEntry(
                    label = label(item),
                    actual = measure,
                    target = target?.invoke(item)?.toDouble()?.takeIf { it.isFinite() },
                    ranges = ranges?.invoke(item).orEmpty(),
                    item = item,
                    paletteIndex = offset,
                    colorOverride = color?.invoke(item),
                )
            },
            seriesId = seriesId,
            seriesName = seriesName,
            targetLabel = targetLabel,
            valueAxis = valueAxis,
        )
    }

    /**
     * An interval layer: events, durations and milestones in lanes.
     *
     * Times are epoch milliseconds — see
     * [io.devkit.chartkit.timeline.TimelineEntry] for why `java.time` is absent.
     */
    @Suppress("LongParameterList")
    fun <T> intervals(
        data: List<T>,
        start: (T) -> Long,
        label: (T) -> String,
        end: ((T) -> Long?)? = null,
        lane: ((T) -> String)? = null,
        progress: ((T) -> Number?)? = null,
        milestone: ((T) -> Boolean)? = null,
        color: ((T) -> Int?)? = null,
        dependencies: List<TimelineDependency> = emptyList(),
        labels: IntervalLabels = IntervalLabels.Inside,
        showProgress: Boolean = true,
        seriesId: String = "timeline",
        seriesName: String = "Timeline",
    ) {
        declaredSeries += 1
        layers += ResolvedLayer.Interval(
            key = "intervals${layers.size}",
            model = buildTimeline(
                data = data,
                start = start,
                label = label,
                end = end,
                lane = lane,
                progress = progress,
                milestone = milestone,
                color = color,
                dependencies = dependencies,
            ),
            seriesId = seriesId,
            seriesName = seriesName,
            labels = labels,
            showProgress = showProgress,
            showDependencies = dependencies.isNotEmpty(),
            axisKind = ChartXAxisKind.Time,
        )
    }

    /**
     * A layer the caller draws themselves.
     *
     * ```kotlin
     * CartesianChart {
     *     line(series = readings, x = { it.at }, y = { it.value })
     *     customLayer(id = "sla") {
     *         val y = positionOfValue(200.0)
     *         drawLine(
     *             color = colors.annotation.line,
     *             start = Offset(plotArea.left, y),
     *             end = Offset(plotArea.right, y),
     *             strokeWidth = px(2.dp),
     *         )
     *     }
     * }
     * ```
     *
     * The layer sits in the render list where it was declared, so declaring it
     * before the data draws it underneath and after draws it on top. It shares
     * the chart's scales, viewport, theme and animation clock — which is the
     * whole point: a custom mark that computed its own positions could not stay
     * aligned with the data through a zoom.
     *
     * @param hitTest optional. Return a hit to make the layer selectable, with
     *   the caller's own object carried through to the tooltip.
     * @param describe optional accessibility items. Takes no geometry: what a
     *   reader needs to hear is a fact about the data, not about pixels.
     * @param legendEntries optional legend rows.
     */
    @Suppress("LongParameterList")
    fun customLayer(
        id: String,
        seriesName: String = id,
        clipToPlot: Boolean = true,
        valueAxis: ValueAxisBinding = ValueAxisBinding.Primary,
        hitTest: (CartesianLayerContext.(io.devkit.chartkit.geometry.ChartOffset) -> CustomLayerHit?)? = null,
        describe: (() -> List<CustomLayerItem>)? = null,
        legendEntries: List<CustomLayerLegendEntry> = emptyList(),
        draw: CartesianLayerScope.() -> Unit,
    ) {
        layers += ResolvedLayer.Custom(
            key = "custom${layers.size}",
            spec = CustomCartesianLayer(
                id = id,
                clipToPlot = clipToPlot,
                valueAxis = valueAxis,
                draw = draw,
                hitTest = hitTest,
                describe = describe,
                legendEntries = legendEntries,
                seriesName = seriesName,
            ),
        )
    }

    private fun <T> List<ChartSeries<T>>.applyVisibility(): List<ChartSeries<T>> =
        map { it.copy(visible = it.visible && it.id !in hiddenSeriesIds) }
}

/** The non-composable half of [rememberOhlc], for the layer DSL. */
@Suppress("LongParameterList")
private fun <T> resolveOhlcLayer(
    data: List<T>,
    x: (T) -> Any?,
    open: (T) -> Number?,
    high: (T) -> Number?,
    low: (T) -> Number?,
    close: (T) -> Number?,
    volume: ((T) -> Number?)?,
    policy: OhlcPolicy,
    xResolver: ChartXResolver,
): ResolvedOhlc {
    val xValues = data.map { xResolver.resolveOrDefault(x(it)) }
    val domainValues = xValues.map { value ->
        when (value) {
            is ChartX.Numeric -> value.value
            is ChartX.Time -> value.epochMillis.toDouble()
            is ChartX.Category -> Double.NaN
        }
    }
    return ResolvedOhlc(
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
    interaction: ChartInteraction = ChartInteraction.Default,
    crosshair: CrosshairConfig = ChartDefaults.SelectionGuide,
    sharedTooltip: Boolean = crosshair.enabled && crosshair.showAxisLabels,
    viewportState: ChartViewportState = rememberChartViewportState(),
    sharedCrosshair: ChartSharedCrosshairState? = null,
    annotations: List<ChartAnnotation> = emptyList(),
    /**
     * A second value axis, drawn on the opposite edge.
     *
     * Layers bind to it explicitly through `valueAxis = ValueAxisBinding.Secondary`.
     * Two independent scales in one plot let the author choose where the lines
     * cross, which is a claim the data did not make — so reach for it when the
     * quantities genuinely differ in kind, and prefer two linked charts when
     * they do not.
     */
    secondaryValueAxis: ChartAxis? = null,
    xResolver: ChartXResolver = ChartXResolver.Default,
    hitTestMode: HitTestMode = HitTestMode.NearestDomain,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    plotAlignment: ChartPlotAlignment? = null,
    sceneState: ChartSceneState? = null,
    accessibilitySummary: (() -> String)? = null,
    state: ChartState<Any?> = rememberChartState(),
    onSelectionChanged: ((AnyChartSelection?) -> Unit)? = null,
    onRangeSelectionChanged: ((AnyChartRangeSelection?) -> Unit)? = null,
    tooltip: (@Composable (AnyChartTooltipData) -> Unit)? = { ChartDefaults.Tooltip(it) },
    /**
     * Compose content laid out over the plot, positioned in chart coordinates.
     *
     * For anything an annotation cannot be drawn as — a card, an image, a
     * button, a badge. See [ChartOverlayScope].
     */
    overlay: (@Composable ChartOverlayScope.() -> Unit)? = null,
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
        interaction = interaction,
        crosshair = crosshair,
        hitTestMode = hitTestMode,
        sharedTooltip = sharedTooltip,
        state = state,
        viewportState = viewportState,
        sharedCrosshair = sharedCrosshair,
        annotations = remember(annotations, xResolver) { resolveAnnotations(annotations, xResolver) },
        secondaryValueAxis = secondaryValueAxis,
        xResolver = xResolver,
        overlay = overlay,
        renderMode = renderMode,
        staticOptions = staticOptions,
        plotAlignment = plotAlignment,
        sceneState = sceneState,
        onSelectionChanged = onSelectionChanged,
        onRangeSelectionChanged = onRangeSelectionChanged,
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
