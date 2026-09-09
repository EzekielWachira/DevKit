package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.annotation.ChartAnnotation
import io.devkit.chartkit.axis.AxisDensity
import io.devkit.chartkit.axis.AxisDimension
import io.devkit.chartkit.axis.AxisGridMode
import io.devkit.chartkit.axis.AxisPosition
import io.devkit.chartkit.axis.AxisRegistry
import io.devkit.chartkit.axis.AxisStyleMode
import io.devkit.chartkit.axis.AxisTickAlignment
import io.devkit.chartkit.axis.AxisVisibility
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartAxisId
import io.devkit.chartkit.axis.ChartAxisSpec
import io.devkit.chartkit.axis.ChartGrid
import io.devkit.chartkit.axis.ChartUnit
import io.devkit.chartkit.axis.ValueAxisBinding
import io.devkit.chartkit.axis.axisId
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

    /** Axes declared through [yAxis] and [xAxis], in declaration order. */
    internal val declaredAxes = mutableListOf<ChartAxisSpec>()

    /**
     * Declares a value axis.
     *
     * A chart that never calls this has one value axis and does not need to
     * know it. Call it once per quantity the chart measures in a different
     * unit, then bind each layer to one by name:
     *
     * ```kotlin
     * CartesianChart {
     *     yAxis(Rainfall, position = AxisPosition.Start, title = "Rainfall",
     *         unit = ChartUnit.Custom("mm", "millimetres"))
     *     yAxis(Temperature, position = AxisPosition.End, title = "Temperature",
     *         unit = ChartUnit.Custom("°C", "degrees Celsius"))
     *
     *     bars(series = rain, category = { it.month }, value = { it.mm }, yAxis = Rainfall)
     *     line(series = temp, x = { it.month }, y = { it.celsius }, yAxis = Temperature)
     * }
     * ```
     *
     * ### On how many
     *
     * Two independent scales in one plot let the author choose where the lines
     * cross, which is a claim about the data that the data did not make.
     * ChartKit does not cap the number — a legitimate chart with three is easy
     * to think of, and a hard limit would be an engine restriction standing in
     * for editorial judgement — but two or three is the practical maximum for
     * something a reader can actually read.
     *
     * @param position which edge. `Start` and `End` on a vertical chart; two
     *   axes may share an edge, and the layout engine stacks them outward in
     *   declaration order.
     * @param unit what the axis measures in. Written after each tick label and
     *   spelled out for screen readers — see [ChartUnit].
     * @param domain how the axis picks its interval. Derived from **its own**
     *   layers only: a rainfall axis is never widened by a pressure series.
     * @param grid whether this axis owns the horizontal grid. One axis should:
     *   three sets of interleaved gridlines make every line look meaningful
     *   when only a third of them are.
     * @param primary the axis a tooltip, a crosshair readout and an unqualified
     *   annotation are written by. The first declared, when none says so.
     * @param alignZero whether this axis' zero shares a row with the other axes
     *   asking for it; only meaningful under [AxisTickAlignment.Aligned].
     */
    @Suppress("LongParameterList")
    fun yAxis(
        id: ChartAxisId,
        position: AxisPosition? = null,
        title: String? = null,
        unit: ChartUnit = ChartUnit.None,
        domain: DomainPolicy? = null,
        axis: ChartAxis = ChartAxis.Default,
        visibility: AxisVisibility = AxisVisibility.Auto,
        grid: AxisGridMode = AxisGridMode.Primary,
        primary: Boolean = false,
        offset: Dp? = null,
        style: AxisStyleMode = AxisStyleMode.Neutral,
        alignTicks: Boolean = true,
        alignZero: Boolean = false,
    ) {
        declaredAxes += ChartAxisSpec(
            id = id,
            dimension = AxisDimension.Y,
            position = position,
            axis = axis,
            title = title,
            unit = unit,
            domain = domain,
            visibility = visibility,
            grid = grid,
            primary = primary,
            offset = offset,
            style = style,
            alignTicks = alignTicks,
            alignZero = alignZero,
        )
    }

    /**
     * Declares a domain axis.
     *
     * Rarely needed: combo charts share one X domain — that is what makes them
     * comparable at all — and a chart that says nothing gets
     * [ChartAxisId.DefaultX]. It exists so the registry has no special case for
     * the domain, and so a later chart that genuinely needs a top axis has
     * somewhere to say so.
     */
    @Suppress("LongParameterList")
    fun xAxis(
        id: ChartAxisId = ChartAxisId.DefaultX,
        position: AxisPosition? = null,
        title: String? = null,
        unit: ChartUnit = ChartUnit.None,
        axis: ChartAxis = ChartAxis.Default,
        visibility: AxisVisibility = AxisVisibility.Visible,
        primary: Boolean = true,
        offset: Dp? = null,
    ) {
        declaredAxes += ChartAxisSpec(
            id = id,
            dimension = AxisDimension.X,
            position = position,
            axis = axis,
            title = title,
            unit = unit,
            visibility = visibility,
            primary = primary,
            offset = offset,
        )
    }

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
        yAxis: ChartAxisId? = null,
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
            valueAxisId = yAxis ?: valueAxis.axisId(),
            declaredUnits = series.units(),
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
        yAxis: ChartAxisId? = null,
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
        yAxis = yAxis,
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
        yAxis: ChartAxisId? = null,
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
            valueAxisId = yAxis ?: valueAxis.axisId(),
            declaredUnits = series.units(),
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
        yAxis: ChartAxisId? = null,
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
            valueAxisId = yAxis ?: valueAxis.axisId(),
            declaredUnits = series.units(),
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
        yAxis: ChartAxisId? = null,
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
            valueAxisId = yAxis ?: ChartAxisId.DefaultY,
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
        yAxis: ChartAxisId? = null,
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
            valueAxisId = yAxis ?: ChartAxisId.DefaultY,
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
        yAxis: ChartAxisId? = null,
    ) {
        declaredSeries += 1
        layers += ResolvedLayer.Waterfall(
            key = "waterfall${layers.size}",
            steps = WaterfallTransform.resolve(data, label, value, kind),
            seriesId = seriesId,
            seriesName = seriesName,
            showConnectors = showConnectors,
            cornerRadius = cornerRadius,
            valueAxisId = yAxis ?: valueAxis.axisId(),
            declaredUnits = emptySet(),
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
        yAxis: ChartAxisId? = null,
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
            valueAxisId = yAxis ?: valueAxis.axisId(),
            declaredUnits = emptySet(),
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
        yAxis: ChartAxisId? = null,
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
            valueAxisId = yAxis ?: valueAxis.axisId(),
            declaredUnits = emptySet(),
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
        yAxis: ChartAxisId? = null,
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
            valueAxisId = yAxis ?: valueAxis.axisId(),
            declaredUnits = emptySet(),
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
        yAxis: ChartAxisId? = null,
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
                valueAxisId = yAxis ?: valueAxis.axisId(),
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

    /** The units these series declared, for the axis mismatch check. */
    private fun <T> List<ChartSeries<T>>.units(): Set<ChartUnit> =
        mapNotNullTo(LinkedHashSet()) { series -> series.unit.takeIf { it != ChartUnit.None } }
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
    /**
     * Whether the value axes share tick rows.
     *
     * [AxisTickAlignment.Independent] by default: each axis picks its own round
     * numbers, which is honest but leaves the gridlines meaningful for one axis
     * only. [AxisTickAlignment.Aligned] widens each axis so corresponding ticks
     * land on the same screen row — see [AxisTickAlignment] for the trade.
     */
    tickAlignment: AxisTickAlignment = AxisTickAlignment.Independent,
    /**
     * How much room the axes may take on a narrow screen.
     *
     * Never removes an axis: it thins ticks and abbreviates numbers, so the
     * reader loses resolution rather than a quantity. See [AxisDensity].
     */
    axisDensity: AxisDensity = AxisDensity.Auto,
    /**
     * The order a shared tooltip lists its rows in.
     *
     * Declaration order by default. [io.devkit.chartkit.model.ChartTooltipOrder.ByAxis]
     * groups by quantity, which reads better once a chart has three of them.
     */
    tooltipOrder: io.devkit.chartkit.model.ChartTooltipOrder =
        io.devkit.chartkit.model.ChartTooltipOrder.Declaration,
    xResolver: ChartXResolver = ChartXResolver.Default,
    hitTestMode: HitTestMode = HitTestMode.NearestDomain,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    plotAlignment: ChartPlotAlignment? = null,
    sceneState: ChartSceneState? = null,
    accessibilitySummary: (() -> String)? = null,
    /**
     * Called with anything the axis layer could not do as asked.
     *
     * Empty for almost every chart. Non-empty when an axis was left out of a
     * tick alignment it asked for, when zero alignment was declined, or when
     * the axes have crowded out the plot. Reported rather than thrown: each of
     * these leaves a drawable chart, and each is worth knowing about while
     * developing it.
     */
    onAxisDiagnostics: ((List<io.devkit.chartkit.axis.AxisDiagnostic>) -> Unit)? = null,
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
    val scope = remember(content, hidden) { CartesianChartScope(hidden).apply(content) }
    val layers = scope.layers

    // The chart's axes: what the caller declared, plus the implicit ones a
    // chart that declared none still has. Built here rather than in the
    // geometry so a mis-declared axis is a composition-time error naming the
    // chart, not a mystery at the first draw.
    val registry = remember(scope.declaredAxes, orientation, domainAxis, valueAxis, secondaryValueAxis, valueDomain) {
        if (scope.declaredAxes.isEmpty()) {
            null
        } else {
            AxisRegistry.of(
                buildList {
                    // A caller who declared only value axes still gets a domain
                    // axis, configured from the `domainAxis` parameter every
                    // other Cartesian chart uses.
                    if (scope.declaredAxes.none { it.dimension == AxisDimension.X }) {
                        add(
                            ChartAxisSpec(
                                id = ChartAxisId.DefaultX,
                                dimension = AxisDimension.X,
                                position = domainAxis.position,
                                axis = domainAxis,
                                primary = true,
                            ),
                        )
                    }
                    addAll(scope.declaredAxes)
                },
                orientation = orientation,
            )
        }
    }

    CartesianChartCore(
        layers = layers,
        axisRegistry = registry,
        tickAlignment = tickAlignment,
        axisDensity = axisDensity,
        tooltipOrder = tooltipOrder,
        onAxisDiagnostics = onAxisDiagnostics,
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
