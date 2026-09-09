package io.devkit.chartkit.charts

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.annotation.AnnotationOrder
import io.devkit.chartkit.axis.AxisLabelOverflow
import io.devkit.chartkit.axis.AxisPosition
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartGrid
import io.devkit.chartkit.axis.MeasuredAxis
import io.devkit.chartkit.axis.MeasuredAxisLabel
import io.devkit.chartkit.axis.AxisDiagnostic
import io.devkit.chartkit.axis.AxisRegistry
import io.devkit.chartkit.axis.AxisStyleMode
import io.devkit.chartkit.axis.AxisTickAlignment
import io.devkit.chartkit.axis.ChartAxisId
import io.devkit.chartkit.axis.ChartUnit
import io.devkit.chartkit.axis.selectLabelIndices
import io.devkit.chartkit.coordinate.CartesianCoordinates
import io.devkit.chartkit.coordinate.DomainAxis
import io.devkit.chartkit.data.VisibleRange
import io.devkit.chartkit.data.resolve
import io.devkit.chartkit.formatter.ChartDateFormatters
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.BarGrouping
import io.devkit.chartkit.geometry.BarStacking
import io.devkit.chartkit.geometry.ChartInsets
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.geometry.LinePoint
import io.devkit.chartkit.geometry.OhlcPoint
import io.devkit.chartkit.geometry.ScatterPoint
import io.devkit.chartkit.geometry.ScatterShape
import io.devkit.chartkit.geometry.computeBarSlices
import io.devkit.chartkit.geometry.isXOrdered
import io.devkit.chartkit.geometry.periodWidth
import io.devkit.chartkit.geometry.segmentLine
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.annotation.AnnotationLayer
import io.devkit.chartkit.layer.annotation.ResolvedAnnotation
import io.devkit.chartkit.layer.bar.BarLayer
import io.devkit.chartkit.layer.bar.BarSeriesGeometry
import io.devkit.chartkit.layer.comparison.BulletEntry
import io.devkit.chartkit.layer.comparison.BulletLayer
import io.devkit.chartkit.layer.comparison.ConnectorMarkEntry
import io.devkit.chartkit.layer.comparison.ConnectorMarkKind
import io.devkit.chartkit.layer.comparison.ConnectorMarkLayer
import io.devkit.chartkit.layer.comparison.WaterfallLayer
import io.devkit.chartkit.layer.comparison.lollipopBaseline
import io.devkit.chartkit.layer.custom.CustomCartesianLayer
import io.devkit.chartkit.layer.custom.CustomLayerRenderer
import io.devkit.chartkit.layer.timeline.IntervalLabels
import io.devkit.chartkit.layer.timeline.IntervalLayer
import io.devkit.chartkit.layer.crosshair.CrosshairLayer
import io.devkit.chartkit.layer.financial.CandleGeometry
import io.devkit.chartkit.layer.financial.CandleLayer
import io.devkit.chartkit.layer.financial.PriceMarkStyle
import io.devkit.chartkit.layer.financial.VolumeGeometry
import io.devkit.chartkit.layer.financial.VolumeLayer
import io.devkit.chartkit.layer.grid.GridLayer
import io.devkit.chartkit.layer.heatmap.HeatmapCell
import io.devkit.chartkit.layer.heatmap.HeatmapCellLabels
import io.devkit.chartkit.layer.heatmap.HeatmapLayer
import io.devkit.chartkit.layer.label.ValueLabelLayer
import io.devkit.chartkit.layer.label.barLabelAnchors
import io.devkit.chartkit.layer.line.LineLayer
import io.devkit.chartkit.layer.line.LineSeriesGeometry
import io.devkit.chartkit.layer.range.RangeSelectionLayer
import io.devkit.chartkit.layer.scatter.ScatterLayer
import io.devkit.chartkit.layer.scatter.ScatterSeriesGeometry
import io.devkit.chartkit.layer.scatter.ScatterStyle
import io.devkit.chartkit.layer.statistical.BoxEntry
import io.devkit.chartkit.layer.statistical.BoxPlotLayer
import io.devkit.chartkit.layer.statistical.HistogramLayer
import io.devkit.chartkit.layer.statistical.ViolinEntry
import io.devkit.chartkit.layer.statistical.ViolinLayer
import io.devkit.chartkit.layer.statistical.ViolinOverlay
import io.devkit.chartkit.layout.AxisMetrics
import io.devkit.chartkit.layout.computeChartLayout
import io.devkit.chartkit.model.ChartX
import io.devkit.chartkit.model.ChartXAxisKind
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.model.resolveOrDefault
import io.devkit.chartkit.model.MissingValuePolicy
import io.devkit.chartkit.model.PlotData
import io.devkit.chartkit.model.PlotSeries
import io.devkit.chartkit.scale.CategoryScale
import io.devkit.chartkit.scale.ColorScale
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.scale.LinearScale
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.scale.SizeScale
import io.devkit.chartkit.scale.TickGenerator
import io.devkit.chartkit.scale.TimeScale
import io.devkit.chartkit.scale.apply
import io.devkit.chartkit.stats.BoxStatistics
import io.devkit.chartkit.stats.DensityCurve
import io.devkit.chartkit.stats.HistogramBin
import io.devkit.chartkit.stats.HistogramMetric
import io.devkit.chartkit.timeline.TimelineModel
import io.devkit.chartkit.transform.WaterfallStep
import io.devkit.chartkit.transform.WaterfallTransform
import io.devkit.chartkit.theme.ChartDimensions
import io.devkit.chartkit.theme.ChartTypography
import io.devkit.chartkit.viewport.ChartViewport
import java.util.Locale

/**
 * A layer after its data has been normalised but before it has been positioned.
 *
 * The split exists because positioning needs the plot area, which needs the
 * axis labels, which need the domains, which need every layer's data. So all
 * layers are normalised first, merged into one set of scales, and only then
 * turned into pixels — which is also the mechanism that makes a combined
 * bar-and-line chart share one coordinate system rather than two that happen to
 * agree.
 *
 * ### Series-shaped and not
 *
 * Lines, areas, bars and scatters are built from [io.devkit.chartkit.model.ChartSeries]
 * and carry a [PlotData]; histograms, box plots, violins, heatmaps and price
 * marks are not series at all — a histogram's data is bins, a box plot's is
 * five numbers per category — and carry their own. Both kinds answer the same
 * three questions the builder asks: which axis kind, which categories, and what
 * interval do you need. Everything downstream of those answers is shared.
 */
internal sealed class ResolvedLayer {

    abstract val key: String

    /** How this layer wants the domain axis built. */
    abstract val axisKind: ChartXAxisKind

    /**
     * Which value axis this layer is measured against, by name.
     *
     * Explicit, never inferred from the magnitudes — see
     * [io.devkit.chartkit.axis.ValueAxisBinding] for why a chart that guessed
     * would be wrong intermittently and invisibly, and
     * [io.devkit.chartkit.axis.ChartAxisId] for why it is a name rather than an
     * index.
     */
    open val valueAxisId: ChartAxisId get() = ChartAxisId.DefaultY

    /**
     * Units the layer's series declared, for the axis mismatch check.
     *
     * Empty when the caller stated none, which is not an error — see
     * [io.devkit.chartkit.charts.validateSeriesUnits].
     */
    open val declaredUnits: Set<ChartUnit> get() = emptySet()

    /** Normalised series data, for the layers that have any. */
    open val seriesData: PlotData? get() = null

    /** Labels this layer contributes to a banded axis, in order. */
    open fun categoryLabels(): List<String> =
        seriesData?.let { data -> data.series.flatMap { s -> s.points.map { it.x.label() } } }
            ?: emptyList()

    /** The interval this layer needs on a continuous domain axis. */
    open fun domainExtent(): NumericDomain? = seriesData?.xDomain

    /**
     * The interval it needs on the value axis.
     *
     * `null` from [Bars] alone: bar extents depend on stacking, which depends
     * on the chart's merged category order, so they are computed once in the
     * builder after the categories are known rather than twice.
     */
    open fun valueExtent(): NumericDomain? = seriesData?.yDomain

    /** True when the layer has nothing to draw. */
    open val isEmpty: Boolean get() = seriesData?.isEmpty ?: true

    /** Legend rows, before their colours are resolved from the theme. */
    open fun legendRows(): List<LegendSeries> = seriesData?.series?.map { series ->
        LegendSeries(
            seriesId = series.id,
            name = series.name,
            paletteIndex = series.paletteIndex,
            colorOverride = series.color,
            visible = series.visible,
        )
    } ?: emptyList()

    class Line(
        override val key: String,
        val data: PlotData,
        val interpolation: io.devkit.chartkit.geometry.LineInterpolation,
        val style: io.devkit.chartkit.layer.line.LineStyle,
        val fill: io.devkit.chartkit.layer.line.AreaFill?,
        val pointMode: io.devkit.chartkit.layer.line.PointMode,
        val lineWidth: androidx.compose.ui.unit.Dp?,
        val valueLabels: Boolean,
        val pointMarkerThreshold: Int,
        val missingValuePolicy: MissingValuePolicy,
        val performance: ChartPerformance,
        override val valueAxisId: ChartAxisId = ChartAxisId.DefaultY,
        override val declaredUnits: Set<ChartUnit> = emptySet(),
    ) : ResolvedLayer() {
        override val axisKind: ChartXAxisKind get() = data.xAxisKind
        override val seriesData: PlotData get() = data
    }

    class Bars(
        override val key: String,
        val data: PlotData,
        val grouping: BarGrouping,
        val cornerRadius: androidx.compose.ui.unit.Dp?,
        val categoryPadding: Double,
        val groupPadding: Double,
        val valueLabels: Boolean,
        override val valueAxisId: ChartAxisId = ChartAxisId.DefaultY,
        override val declaredUnits: Set<ChartUnit> = emptySet(),
    ) : ResolvedLayer() {
        override val axisKind: ChartXAxisKind get() = data.xAxisKind
        override val seriesData: PlotData get() = data

        /** Computed from the merged categories in the builder; see the base class. */
        override fun valueExtent(): NumericDomain? = null
    }

    /**
     * Scatter or bubble marks.
     *
     * @param sizes the size-encoding value per series and source index, or
     *   `null` for a plain scatter. Parallel to each series' points.
     * @param sizeScale resolved from [sizes] once per data change, so every
     *   series in the layer is measured against the same size domain — a bubble
     *   chart whose series were each scaled to their own maximum would be
     *   comparing nothing.
     */
    class Scatter(
        override val key: String,
        val data: PlotData,
        val shape: ScatterShape,
        val style: ScatterStyle,
        val sizes: List<List<Double?>>?,
        val sizeScale: SizeScale?,
        val pointRadius: androidx.compose.ui.unit.Dp?,
        val performance: ChartPerformance,
        override val valueAxisId: ChartAxisId = ChartAxisId.DefaultY,
        override val declaredUnits: Set<ChartUnit> = emptySet(),
    ) : ResolvedLayer() {
        override val axisKind: ChartXAxisKind get() = data.xAxisKind
        override val seriesData: PlotData get() = data
    }

    /** Histogram bars over a continuous axis. */
    class Histogram(
        override val key: String,
        val bins: List<HistogramBin>,
        val metric: HistogramMetric,
        val seriesId: String,
        val seriesName: String,
        val items: List<Any?>,
        val paletteIndex: Int,
        val colorOverride: Int?,
        val cornerRadius: androidx.compose.ui.unit.Dp?,
    ) : ResolvedLayer() {
        override val axisKind: ChartXAxisKind get() = ChartXAxisKind.Numeric
        override val isEmpty: Boolean get() = bins.isEmpty()

        override fun domainExtent(): NumericDomain? =
            if (bins.isEmpty()) null else NumericDomain(bins.first().start, bins.last().end)

        override fun valueExtent(): NumericDomain? =
            if (bins.isEmpty()) null else NumericDomain(0.0, bins.maxOf { it.value })
    }

    /** Box-and-whisker marks, one per category. */
    class Box(
        override val key: String,
        val entries: List<BoxEntry>,
        val seriesId: String,
        val seriesName: String,
    ) : ResolvedLayer() {
        override val axisKind: ChartXAxisKind get() = ChartXAxisKind.Category
        override val isEmpty: Boolean get() = entries.none { it.statistics.isValid }
        override fun categoryLabels(): List<String> = entries.map { it.label }

        override fun valueExtent(): NumericDomain? {
            val ranges = entries.filter { it.statistics.isValid }.map { it.statistics.displayRange }
            if (ranges.isEmpty()) return null
            return NumericDomain(ranges.minOf { it.start }, ranges.maxOf { it.endInclusive })
        }
    }

    /** Violin marks, one per category. */
    class Violin(
        override val key: String,
        val entries: List<ViolinEntry>,
        val seriesId: String,
        val seriesName: String,
        val overlay: ViolinOverlay,
    ) : ResolvedLayer() {
        override val axisKind: ChartXAxisKind get() = ChartXAxisKind.Category
        override val isEmpty: Boolean get() = entries.all { it.curve.isEmpty }
        override fun categoryLabels(): List<String> = entries.map { it.label }

        override fun valueExtent(): NumericDomain? {
            val curves = entries.map { it.curve }.filter { !it.isEmpty }
            if (curves.isEmpty()) return null
            return NumericDomain(
                curves.minOf { it.positions.first() },
                curves.maxOf { it.positions.last() },
            )
        }
    }

    /**
     * A grid of coloured cells.
     *
     * The rows sit at integer positions on the **value** axis — see
     * [HeatmapLayer] for why that beats generalising the coordinate system —
     * so the value extent is the row count and the axis is labelled by the
     * chart through [ChartAxis.ticks].
     */
    class Heatmap(
        override val key: String,
        val cells: List<HeatmapCell>,
        val columnLabels: List<String>,
        val rowLabels: List<String>,
        val colorScale: ColorScale,
        val items: List<Any?>,
        val seriesId: String,
        val seriesName: String,
        val cellLabels: HeatmapCellLabels,
        val showMissing: Boolean,
        val cornerRadius: androidx.compose.ui.unit.Dp?,
    ) : ResolvedLayer() {
        override val axisKind: ChartXAxisKind get() = ChartXAxisKind.Category
        override val isEmpty: Boolean get() = cells.none { it.value != null }
        override fun categoryLabels(): List<String> = columnLabels

        override fun valueExtent(): NumericDomain =
            NumericDomain(-0.5, (rowLabels.size - 1).coerceAtLeast(0) + 0.5)
    }

    /** Candlestick or OHLC marks over a continuous axis. */
    class Candles(
        override val key: String,
        val points: List<OhlcPoint>,
        val xValues: List<ChartX>,
        val markStyle: PriceMarkStyle,
        val seriesId: String,
        val seriesName: String,
        val items: List<Any?>,
        override val axisKind: ChartXAxisKind,
        override val valueAxisId: ChartAxisId = ChartAxisId.DefaultY,
    ) : ResolvedLayer() {
        override val isEmpty: Boolean get() = points.isEmpty()

        override fun domainExtent(): NumericDomain? =
            NumericDomain.of(points.map { it.domainValue })

        override fun valueExtent(): NumericDomain? {
            if (points.isEmpty()) return null
            return NumericDomain(points.minOf { it.low }, points.maxOf { it.high })
        }

        override fun legendRows(): List<LegendSeries> = listOf(
            LegendSeries(seriesId, seriesName, 0, null, visible = true),
        )
    }

    /**
     * Volume bars over a continuous axis, coloured by price direction.
     *
     * @param points the periods, carrying both the volume and the direction it
     *   is coloured by. Volume travels on the candle rather than in a parallel
     *   list precisely so the two cannot fall out of step — a bar coloured from
     *   a separately-indexed price series is one dropped period away from being
     *   coloured wrongly.
     */
    class Volume(
        override val key: String,
        val points: List<OhlcPoint>,
        val xValues: List<ChartX>,
        val seriesId: String,
        val seriesName: String,
        val items: List<Any?>,
        override val axisKind: ChartXAxisKind,
        override val valueAxisId: ChartAxisId = ChartAxisId.DefaultY,
    ) : ResolvedLayer() {
        override val isEmpty: Boolean get() = points.none { it.volume != null }

        override fun domainExtent(): NumericDomain? =
            NumericDomain.of(points.map { it.domainValue })

        override fun valueExtent(): NumericDomain? {
            val volumes = points.mapNotNull { it.volume }
            return if (volumes.isEmpty()) null else NumericDomain(0.0, volumes.max())
        }

        override fun legendRows(): List<LegendSeries> = emptyList()
    }

    /** A running total: bars anchored to what came before them. */
    class Waterfall(
        override val key: String,
        val steps: List<WaterfallStep>,
        val seriesId: String,
        val seriesName: String,
        val showConnectors: Boolean,
        val cornerRadius: androidx.compose.ui.unit.Dp?,
        override val valueAxisId: ChartAxisId,
        override val declaredUnits: Set<ChartUnit>,
    ) : ResolvedLayer() {
        override val axisKind: ChartXAxisKind get() = ChartXAxisKind.Category
        override val isEmpty: Boolean get() = steps.isEmpty()
        override fun categoryLabels(): List<String> = steps.map { it.label }

        override fun valueExtent(): NumericDomain? {
            if (steps.isEmpty()) return null
            val extent = WaterfallTransform.extentOf(steps)
            return NumericDomain(extent.start, extent.endInclusive)
        }

        override fun legendRows(): List<LegendSeries> = emptyList()
    }

    /** Dumbbell or lollipop marks, one per category. */
    class ConnectorMarks(
        override val key: String,
        val entries: List<ConnectorMarkEntry>,
        val kind: ConnectorMarkKind,
        val seriesId: String,
        val seriesName: String,
        val startLabel: String,
        val endLabel: String,
        override val valueAxisId: ChartAxisId,
        override val declaredUnits: Set<ChartUnit>,
    ) : ResolvedLayer() {
        override val axisKind: ChartXAxisKind get() = ChartXAxisKind.Category
        override val isEmpty: Boolean get() = entries.isEmpty()
        override fun categoryLabels(): List<String> = entries.map { it.label }

        override fun valueExtent(): NumericDomain? {
            if (entries.isEmpty()) return null
            val values = entries.flatMap { listOfNotNull(it.start, it.end) }
            // A lollipop's stem rises from the baseline, so the baseline has to
            // be on the axis or every stem is clipped at the plot's edge.
            val withBaseline = if (kind == ConnectorMarkKind.Lollipop) values + 0.0 else values
            return NumericDomain.of(withBaseline)
        }

        override fun legendRows(): List<LegendSeries> = emptyList()
    }

    /** A measure against a target, on qualitative ranges. */
    class Bullet(
        override val key: String,
        val entries: List<BulletEntry>,
        val seriesId: String,
        val seriesName: String,
        val targetLabel: String,
        override val valueAxisId: ChartAxisId,
        override val declaredUnits: Set<ChartUnit>,
    ) : ResolvedLayer() {
        override val axisKind: ChartXAxisKind get() = ChartXAxisKind.Category
        override val isEmpty: Boolean get() = entries.isEmpty()
        override fun categoryLabels(): List<String> = entries.map { it.label }

        override fun valueExtent(): NumericDomain? {
            if (entries.isEmpty()) return null
            val values = buildList {
                add(0.0)
                entries.forEach { entry ->
                    add(entry.actual)
                    entry.target?.let(::add)
                    entry.ranges.forEach { add(it.low); add(it.high) }
                }
            }
            return NumericDomain.of(values)
        }

        override fun legendRows(): List<LegendSeries> = emptyList()
    }

    /**
     * Events and intervals in lanes.
     *
     * The rows sit at integer positions on the **value** axis, exactly as a
     * heatmap's do — see [io.devkit.chartkit.layer.timeline.IntervalLayer] for
     * why that beats a fourth coordinate system.
     */
    class Interval(
        override val key: String,
        val model: TimelineModel,
        val seriesId: String,
        val seriesName: String,
        val labels: IntervalLabels,
        val showProgress: Boolean,
        val showDependencies: Boolean,
        override val axisKind: ChartXAxisKind,
    ) : ResolvedLayer() {
        override val isEmpty: Boolean get() = model.isEmpty

        override fun domainExtent(): NumericDomain? {
            val extent = model.extent() ?: return null
            return NumericDomain(extent.first.toDouble(), extent.last.toDouble())
        }

        override fun valueExtent(): NumericDomain =
            NumericDomain(-0.5, (model.rowCount - 1).coerceAtLeast(0) + 0.5)

        override fun legendRows(): List<LegendSeries> = emptyList()
    }

    /**
     * A layer the caller wrote.
     *
     * Reports [ChartXAxisKind.Numeric] and contributes no domain of its own, so
     * it never overrides the axis a data layer asked for: a custom layer beside
     * a category chart gets the category axis, and one beside a time chart gets
     * the time axis. Alone, it gets a numeric one.
     */
    class Custom(
        override val key: String,
        val spec: CustomCartesianLayer,
    ) : ResolvedLayer() {
        override val axisKind: ChartXAxisKind get() = ChartXAxisKind.Numeric
        override val valueAxisId: ChartAxisId get() = spec.valueAxisId

        /** Never empty: a chart whose only layer is custom still has content. */
        override val isEmpty: Boolean get() = false

        override fun domainExtent(): NumericDomain? = null
        override fun valueExtent(): NumericDomain? = null
        override fun categoryLabels(): List<String> = emptyList()

        override fun legendRows(): List<LegendSeries> = spec.legendEntries.map { entry ->
            LegendSeries(
                seriesId = entry.id,
                name = entry.label,
                paletteIndex = entry.paletteIndex,
                colorOverride = entry.colorOverride,
                visible = true,
            )
        }
    }
}

/** Everything one frame needs, computed once per layout rather than per frame. */
internal class CartesianGeometry(
    val coordinates: CartesianCoordinates,
    val renderers: List<ChartLayerRenderer>,
    val hitTestable: List<ChartLayerRenderer>,
    val domainAxis: MeasuredAxis?,
    /**
     * The chart's primary value axis — the one a tooltip, a crosshair readout
     * and an unqualified annotation are written by.
     */
    val valueAxis: MeasuredAxis?,
    /**
     * Every visible value axis, in declaration order.
     *
     * One entry for an ordinary chart; three for a combo chart with rainfall,
     * temperature and pressure. Drawn in this order, each at the offset the
     * layout engine measured for it.
     */
    val valueAxes: List<MeasuredAxis> = listOfNotNull(valueAxis),
    /** The coordinate system for layers bound to the legacy second value axis. */
    val secondaryCoordinates: CartesianCoordinates? = null,
    /**
     * One coordinate system per value axis, keyed by name.
     *
     * All share the plot area and the domain axis and differ only in their
     * value scale, which is the whole of "independent scales, shared plot
     * area". A layer is handed the one for the axis it named, resolved once
     * before drawing — see [io.devkit.chartkit.axis.AxisRegistry].
     */
    val axisCoordinates: Map<io.devkit.chartkit.axis.ChartAxisId, CartesianCoordinates> = emptyMap(),
    /** Each axis' own number formatter, for tooltips and crosshair readouts. */
    val axisFormatters: Map<io.devkit.chartkit.axis.ChartAxisId, ChartValueFormatter> = emptyMap(),
    /** Each axis' declaration, for units, titles and accessibility. */
    val axisSpecs: Map<io.devkit.chartkit.axis.ChartAxisId, io.devkit.chartkit.axis.ChartAxisSpec> = emptyMap(),
    /**
     * The unit each axis writes after its numbers.
     *
     * [io.devkit.chartkit.axis.ChartUnit.None] where the caller supplied their
     * own formatter — see [PreparedValueAxis.labelUnit]. Separate from the
     * declared unit in [axisSpecs], which stays what the axis *measures*.
     */
    val axisLabelUnits: Map<io.devkit.chartkit.axis.ChartAxisId, io.devkit.chartkit.axis.ChartUnit> = emptyMap(),
    /** What the axis layer could not do as asked. Empty for almost every chart. */
    val axisDiagnostics: List<io.devkit.chartkit.axis.AxisDiagnostic> = emptyList(),
    /**
     * Which value axis each renderer is measured against, by renderer id.
     *
     * Layers, not renderers, declare a binding — see the comment where this is
     * built. This is how the chart gets from a renderer back to the axis whose
     * coordinates, formatter and hit test it should be given.
     */
    private val rendererAxes: Map<String, io.devkit.chartkit.axis.ChartAxisId> = emptyMap(),
    /**
     * The gutters this chart needs around its plot, before alignment padding.
     *
     * Published so a [io.devkit.chartkit.state.ChartPlotAlignment] can take the
     * maximum across a stack of charts and hand each one back the difference.
     */
    val naturalPlotInsets: ChartInsets = ChartInsets.Zero,
    val legendSeries: List<LegendSeries>,
    val summaries: List<ChartLayerSummary>,
    val valueFormatter: ChartValueFormatter,
    val isEmpty: Boolean,
    /**
     * The interval the data occupies before the viewport narrows it.
     *
     * Published so a viewport state can turn its fractional window back into
     * real values: "which dates am I showing" is answered from here, not from
     * the visible domain, which is already the answer.
     */
    val fullDomain: NumericDomain? = null,
    val categoryCount: Int = 0,
    val viewport: ChartViewport = ChartViewport.Full,
    /** Formats a full-domain fraction the way the axis labels it. */
    val domainLabeller: (Double) -> String = { it.toString() },
    /** Formats a domain value the way the axis does, for tooltips and chips. */
    val formatDomainValue: (ChartX) -> String = { it.label() },
    /**
     * The pixel position of a domain value, or `null` when it is not on the
     * axis.
     *
     * What a shared crosshair is positioned through: a linked chart publishes a
     * domain **value**, and each chart asks its own geometry where that value
     * sits. Two charts over different datasets therefore put their guides on
     * the same date rather than on the same pixel.
     */
    val domainPositionOf: (ChartX) -> Float? = { null },
    /** Turns a full-domain fraction into the domain value it names. */
    private val domainValueAt: (Double) -> ChartX = { ChartX.Numeric(it) },
    /** The caller's items whose domain position falls inside a fraction range. */
    private val itemsBetween: (Double, Double) -> List<Any?> = { _, _ -> emptyList() },
    /** A domain value's position as a fraction of the full domain. */
    private val fractionOfDomain: (ChartX) -> Double? = { null },
) {
    /**
     * Which value axis [renderer] is measured against.
     *
     * The recorded binding when the renderer came from a layer, and the
     * renderer's own when it did not — an annotation layer or a custom layer
     * states it directly.
     */
    fun axisOf(renderer: ChartLayerRenderer): io.devkit.chartkit.axis.ChartAxisId =
        rendererAxes[renderer.id] ?: renderer.valueAxisId

    /** The coordinate system layers on [axisId] are measured in. */
    fun coordinatesFor(axisId: io.devkit.chartkit.axis.ChartAxisId): CartesianCoordinates =
        axisCoordinates[axisId] ?: coordinates

    /** How axis [axisId] writes a number, falling back to the chart's own. */
    fun formatterFor(axisId: io.devkit.chartkit.axis.ChartAxisId): ChartValueFormatter =
        axisFormatters[axisId] ?: valueFormatter

    /** How axis [axisId] labels a value, unit included. */
    fun labelFor(axisId: io.devkit.chartkit.axis.ChartAxisId, value: Double): String {
        val text = formatterFor(axisId).format(value)
        return axisLabelUnits[axisId]?.label(text) ?: text
    }

    /** How axis [axisId] announces a value, with the unit spelled out. */
    fun spokenFor(axisId: io.devkit.chartkit.axis.ChartAxisId, value: Double): String {
        val text = formatterFor(axisId).format(value)
        return axisLabelUnits[axisId]?.spoken(text) ?: text
    }

    /** The full-domain fraction under a pixel, for range selection. */
    fun domainFractionAt(position: ChartOffset): Double {
        val plot = coordinates.plotArea
        if (plot.isEmpty) return 0.0
        val vertical = coordinates.orientation.isVertical
        val extent = if (vertical) plot.width else plot.height
        val origin = if (vertical) plot.left else plot.top
        val along = if (vertical) position.x else position.y
        if (extent <= 0f) return 0.0
        val withinViewport = ((along - origin) / extent).toDouble().coerceIn(0.0, 1.0)
        // Back out through the viewport: the plot shows a window of the domain,
        // and a range is stored against the whole of it so it survives a zoom.
        return (viewport.start + withinViewport * viewport.width).coerceIn(0.0, 1.0)
    }

    /** The domain value at a full-domain fraction. */
    fun domainValueAtFraction(fraction: Double): ChartX = domainValueAt(fraction)

    /** The caller's items between two full-domain fractions. */
    fun itemsInRange(startFraction: Double, endFraction: Double): List<Any?> =
        itemsBetween(minOf(startFraction, endFraction), maxOf(startFraction, endFraction))

    /** Where [value] sits as a fraction of the full domain, for linking charts. */
    fun fractionOf(value: ChartX): Double? = fractionOfDomain(value)

    companion object {
        fun empty(): CartesianGeometry = CartesianGeometry(
            coordinates = CartesianCoordinates(
                plotArea = ChartRect.Zero,
                domainAxis = DomainAxis.Continuous(LinearScale(NumericDomain.Default, 0f, 0f)),
                valueScale = LinearScale(NumericDomain.Default, 0f, 0f),
                orientation = ChartOrientation.Vertical,
            ),
            renderers = emptyList(),
            hitTestable = emptyList(),
            domainAxis = null,
            valueAxis = null,
            legendSeries = emptyList(),
            summaries = emptyList(),
            valueFormatter = ChartValueFormatter.Raw,
            isEmpty = true,
        )
    }
}

/**
 * Turns normalised layers and a size into everything needed to draw a frame.
 *
 * Pure apart from text measurement: no composition, no state reads, no side
 * effects. Called from a `remember` keyed on the inputs, which is what stops a
 * tooltip appearing from re-deriving the domain of ten thousand points.
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
internal fun buildCartesianGeometry(
    bounds: ChartRect,
    layers: List<ResolvedLayer>,
    orientation: ChartOrientation,
    domainAxisConfig: ChartAxis,
    valueAxisConfig: ChartAxis,
    grid: ChartGrid,
    valueDomainPolicy: DomainPolicy,
    crosshair: CrosshairConfig,
    viewport: ChartViewport,
    rangeSelectable: Boolean,
    density: Density,
    textMeasurer: TextMeasurer,
    typography: ChartTypography,
    dimensions: ChartDimensions,
    locale: Locale,
    accessibility: ChartAccessibility,
    annotations: List<ResolvedAnnotation> = emptyList(),
    secondaryValueAxisConfig: ChartAxis? = null,
    customLayers: List<CustomCartesianLayer> = emptyList(),
    xResolver: ChartXResolver = ChartXResolver.Default,
    alignmentInsets: ChartInsets = ChartInsets.Zero,
    /**
     * Every axis this chart has.
     *
     * `null` for a chart that never mentioned axis ids, which is most of them:
     * one is built from [domainAxisConfig], [valueAxisConfig] and
     * [secondaryValueAxisConfig] so that a `LineChart` and a three-axis combo
     * chart go through the same code rather than through a simple path and a
     * general one that drift.
     */
    axisRegistry: AxisRegistry? = null,
    tickAlignment: AxisTickAlignment = AxisTickAlignment.Independent,
    axisDensity: io.devkit.chartkit.axis.AxisDensity = io.devkit.chartkit.axis.AxisDensity.Auto,
): CartesianGeometry {
    if (bounds.isEmpty || layers.isEmpty()) return CartesianGeometry.empty()

    val registry = axisRegistry ?: defaultRegistry(
        orientation = orientation,
        domainAxis = domainAxisConfig,
        valueAxis = valueAxisConfig,
        secondaryValueAxis = secondaryValueAxisConfig,
        valueDomainPolicy = valueDomainPolicy,
    )
    // Checked here rather than at declaration, because a layer names an axis
    // before the chart has finished being described. A binding to an axis that
    // was never registered has no correct fallback — see [AxisRegistry].
    layers.forEach { layer ->
        registry.requireAxis(
            id = layer.valueAxisId,
            requestedBy = "Layer \"${layer.key}\"",
            dimension = io.devkit.chartkit.axis.AxisDimension.Y,
        )
    }
    // Stacking is only meaningful within one scale: two stacked bar layers on
    // different axes occupy the same category bands and read as one stack
    // measured in two units, which is a chart that cannot be right. Rejected
    // rather than drawn, because the picture gives no clue that it happened.
    val stackedAxes = layers.filterIsInstance<ResolvedLayer.Bars>()
        .filter { it.grouping != BarGrouping.Grouped }
        .map { it.valueAxisId }
        .distinct()
    if (stackedAxes.size > 1) {
        throw io.devkit.chartkit.axis.ChartAxisException(
            "Stacked bar layers are bound to ${stackedAxes.size} different value axes " +
                "(${stackedAxes.joinToString { "\"${it.value}\"" }}). Stacked bars share a " +
                "baseline and a scale; stacking across axes would draw one pile of segments " +
                "measured in two units.",
        )
    }
    layers.filterIsInstance<ResolvedLayer.Bars>()
        .filter { it.grouping != BarGrouping.Grouped && it.declaredUnits.size > 1 }
        .forEach { layer ->
            throw io.devkit.chartkit.axis.ChartAxisException(
                "Layer \"${layer.key}\" stacks series declaring " +
                    "${layer.declaredUnits.size} different units " +
                    "(${layer.declaredUnits.joinToString { it.symbol ?: it.toString() }}). " +
                    "A stack adds its segments together, which only means something when they " +
                    "measure the same thing.",
            )
        }

    val diagnostics = mutableListOf<AxisDiagnostic>()
    diagnostics += validateSeriesUnits(registry, layers)

    // ---- merge every layer's data into one domain ---------------------------

    val axisKind = layers.map { it.axisKind }.let { kinds ->
        // A category anywhere forces the whole axis categorical: a numeric axis
        // has nowhere to put a value that is not a number.
        when {
            kinds.any { it == ChartXAxisKind.Category } -> ChartXAxisKind.Category
            kinds.any { it == ChartXAxisKind.Time } -> ChartXAxisKind.Time
            else -> ChartXAxisKind.Numeric
        }
    }

    val categories: List<String> = if (axisKind == ChartXAxisKind.Category) {
        LinkedHashSet<String>().apply { layers.forEach { addAll(it.categoryLabels()) } }.toList()
    } else {
        emptyList()
    }

    val barBounds = layers.filterIsInstance<ResolvedLayer.Bars>().associateWith { layer ->
        val aligned = layer.data.visibleSeries.map { series ->
            alignToCategories(series, categories)
        }
        BarStacking.bounds(aligned.map { it.values }, layer.grouping) to aligned
    }

    // One interval per axis, never merged. A layer bound to the pressure axis
    // must not widen the rainfall one — that is the whole point of having more
    // than one — so extents are collected per axis id and stay separate all the
    // way to the scales.
    fun valueExtentsFor(axisId: ChartAxisId): List<NumericDomain> = buildList {
        layers.filter { it.valueAxisId == axisId }.forEach { layer ->
            when (layer) {
                is ResolvedLayer.Bars -> barBounds[layer]?.first
                    ?.let { BarStacking.domainOf(it) }
                    ?.let { add(it) }

                else -> layer.valueExtent()?.let { add(it) }
            }
        }
    }

    // An annotation that names a threshold above every observed value is
    // invisible unless the axis is widened to it — and a reader who cannot see
    // the target cannot see the gap to it. Each annotation widens the axis it
    // is stated against, which for an unqualified one is the primary.
    fun annotationExtentsFor(axisId: ChartAxisId): List<NumericDomain> = buildList {
        annotations.forEach { resolved ->
            val annotation = resolved.annotation
            if (!annotation.extendsDomain) return@forEach
            val target = annotation.valueAxis ?: registry.primaryY?.id ?: return@forEach
            if (target != axisId) return@forEach
            when (annotation) {
                is io.devkit.chartkit.annotation.ChartAnnotation.HorizontalRule ->
                    add(NumericDomain(annotation.value, annotation.value))
                is io.devkit.chartkit.annotation.ChartAnnotation.ValueRange ->
                    add(NumericDomain(minOf(annotation.from, annotation.to), maxOf(annotation.from, annotation.to)))
                is io.devkit.chartkit.annotation.ChartAnnotation.Region ->
                    add(
                        NumericDomain(
                            minOf(annotation.valueFrom, annotation.valueTo),
                            maxOf(annotation.valueFrom, annotation.valueTo),
                        ),
                    )
                is io.devkit.chartkit.annotation.ChartAnnotation.EventMarker ->
                    annotation.value?.let { add(NumericDomain(it, it)) }
                is io.devkit.chartkit.annotation.ChartAnnotation.Callout ->
                    annotation.value?.let { add(NumericDomain(it, it)) }
                is io.devkit.chartkit.annotation.ChartAnnotation.LabelBox ->
                    annotation.value?.let { add(NumericDomain(it, it)) }
                is io.devkit.chartkit.annotation.ChartAnnotation.Arrow ->
                    add(
                        NumericDomain(
                            minOf(annotation.fromValue, annotation.toValue),
                            maxOf(annotation.fromValue, annotation.toValue),
                        ),
                    )
                else -> Unit
            }
        }
    }

    val dataDomains: Map<ChartAxisId, NumericDomain?> = registry.yAxes.associate { spec ->
        spec.id to (valueExtentsFor(spec.id) + annotationExtentsFor(spec.id))
            .reduceOrNull { a, b -> NumericDomain(minOf(a.min, b.min), maxOf(a.max, b.max)) }
    }

    // An axis with no visible layer on it is measuring nothing; see
    // [AxisVisibility.Auto]. Counted from the layers rather than from the
    // series, so a legend toggle that empties a layer empties its axis.
    val boundLayerCounts: Map<ChartAxisId, Int> = registry.yAxes.associate { spec ->
        spec.id to layers.count { it.valueAxisId == spec.id && !it.isEmpty }
    }

    // The colour an axis borrows under [AxisStyleMode.MatchSeries]: the first
    // series drawn against it, so the tick labels match the line the reader is
    // trying to trace back to them.
    val axisAccents: Map<ChartAxisId, Int> = buildMap {
        layers.forEach { layer ->
            if (containsKey(layer.valueAxisId)) return@forEach
            layer.legendRows().firstOrNull()?.let { put(layer.valueAxisId, it.paletteIndex) }
        }
    }

    val compactAxes = axisDensity.isCompact(
        availableWidth = if (orientation.isVertical) bounds.width else bounds.height,
        axisCount = registry.yAxes.count { boundLayerCounts[it.id] != 0 },
        widthPerAxis = with(density) { dimensions.compactAxisWidth.toPx() },
    )

    val preparedAxes = prepareValueAxes(
        registry = registry,
        orientation = orientation,
        dataDomains = dataDomains,
        boundLayerCounts = boundLayerCounts,
        accentPaletteIndices = axisAccents,
        defaultPolicy = valueDomainPolicy,
        tickAlignment = tickAlignment,
        compact = compactAxes,
        textMeasurer = textMeasurer,
        typography = typography,
        locale = locale,
    )
    diagnostics += preparedAxes.diagnostics

    // The chart's own value axis: the one a tooltip, a crosshair readout and an
    // unqualified annotation are written by. Layers on other axes still use
    // their own formatters — see [PreparedValueAxis.formatter].
    val primaryAxis = preparedAxes.primary
    val valueDomain = primaryAxis?.domain ?: valueDomainPolicy.apply(null)
    val valueFormatter = primaryAxis?.formatter ?: ChartValueFormatter.Raw

    val xDataDomain = if (axisKind == ChartXAxisKind.Category) {
        null
    } else {
        layers.mapNotNull { it.domainExtent() }
            .reduceOrNull { a, b -> NumericDomain(minOf(a.min, b.min), maxOf(a.max, b.max)) }
    }
    val fullXDomain = (domainAxisConfig.domain ?: DomainPolicy.Auto(padding = 0.0)).apply(xDataDomain)

    // The viewport narrows the domain the scales map, which is the whole of
    // how zoom works. Everything downstream — ticks, labels, geometry, hit
    // testing, the crosshair, the range overlay — is derived from `xDomain`
    // and is therefore correct at any zoom without knowing zoom exists.
    val xDomain = if (axisKind == ChartXAxisKind.Category) {
        fullXDomain
    } else {
        viewport.visibleDomain(fullXDomain)
    }

    val isEmpty = layers.all { it.isEmpty }

    // ---- tick values and their labels, before any pixels exist --------------

    // Value-axis ticks, labels and formatters were resolved above, per axis, by
    // [prepareValueAxes] — ticks come from each axis' own scale kind, so a
    // logarithmic axis is labelled in powers and a linear one in round numbers
    // without the axis renderer knowing which it is drawing.

    val domainTickValues: List<Double>
    val domainLabels: List<String>
    when (axisKind) {
        ChartXAxisKind.Category -> {
            domainTickValues = categories.indices.map(Int::toDouble)
            val transform = domainAxisConfig.categoryFormatter
            domainLabels = categories.map { transform?.invoke(it) ?: it }
        }
        ChartXAxisKind.Time -> {
            // Ticks over the *visible* interval, so zooming into an hour
            // relabels the axis in minutes rather than keeping the year's ticks
            // and drawing five of them off-screen.
            val timeScale = TimeScale(xDomain, 0f, 1f)
            val ticks = domainAxisConfig.ticks?.map { it.toLong() }
                ?: timeScale.ticks(domainAxisConfig.tickCount)
            domainTickValues = ticks.map(Long::toDouble)
            val formatter = domainAxisConfig.timeFormatter
                ?: ChartDateFormatters.pattern(patternForSpan(xDomain.span), locale)
            domainLabels = ticks.map(formatter::format)
        }
        ChartXAxisKind.Numeric -> {
            domainTickValues = domainAxisConfig.ticks
                ?: TickGenerator.ticks(xDomain, domainAxisConfig.tickCount)
            val formatter = domainAxisConfig.valueFormatter
                ?: ChartNumberFormatters.forTicks(domainTickValues, locale)
            domainLabels = domainTickValues.map(formatter::format)
        }
    }

    // ---- measure, then lay out ---------------------------------------------

    val labelStyle = typography.axisLabel
    val titleStyle = typography.axisTitle
    val measuredDomainLabels = if (domainAxisConfig.showLabels && domainAxisConfig.visible) {
        domainLabels.map { textMeasurer.measure(it, labelStyle) }
    } else {
        emptyList()
    }

    val domainPosition = domainAxisConfig.positionOr(
        if (orientation.isVertical) AxisPosition.Bottom else AxisPosition.Start,
    )

    val domainTitle = domainAxisConfig.title
        ?.takeIf { it.isNotBlank() && domainAxisConfig.visible }
        ?.let { textMeasurer.measure(it, titleStyle) }

    val tickLength = with(density) { dimensions.tickLength.toPx() }
    val labelPadding = with(density) { dimensions.labelPadding.toPx() }

    val rotateDomain = domainPosition.isHorizontal &&
        domainAxisConfig.labelOverflow == AxisLabelOverflow.Rotate &&
        measuredDomainLabels.isNotEmpty()

    val domainLabelExtent = if (rotateDomain) {
        // A 45° label occupies roughly its own width in height; using its
        // measured height instead would clip every rotated label.
        measuredDomainLabels.maxOf { it.size.width }.toFloat() * ROTATED_LABEL_FACTOR
    } else if (domainPosition.isHorizontal) {
        measuredDomainLabels.maxOfOrNull { it.size.height }?.toFloat() ?: 0f
    } else {
        measuredDomainLabels.maxOfOrNull { it.size.width }?.toFloat() ?: 0f
    }

    // The domain axis first, then every value axis in declaration order. Order
    // is what the layout engine stacks by, so the first axis declared on a side
    // is the one against the plot.
    val axisMetrics = buildList {
        add(
            AxisMetrics(
                position = domainPosition,
                visible = domainAxisConfig.visible,
                labelExtent = domainLabelExtent,
                tickLength = if (domainAxisConfig.showTicks) tickLength else 0f,
                labelPadding = if (domainAxisConfig.showLabels) labelPadding else 0f,
                titleExtent = domainTitle?.let { it.size.height + labelPadding }?.toFloat() ?: 0f,
                id = registry.primaryX?.id ?: ChartAxisId.DefaultX,
            ),
        )
        preparedAxes.axes.forEach { add(it.metrics(density, dimensions)) }
    }

    val contentPadding = with(density) { dimensions.contentPadding.toPx() }
    val overhang = if (domainPosition.isHorizontal && measuredDomainLabels.isNotEmpty() && !rotateDomain) {
        measuredDomainLabels.maxOf { it.size.width }.toFloat() / 2f
    } else {
        0f
    }

    val layout = computeChartLayout(
        bounds = bounds,
        // Alignment padding is added to the content padding rather than to the
        // axis gutters, so it widens the space outside the plot without
        // changing where the axis thinks its labels go.
        contentPadding = ChartInsets(
            left = contentPadding,
            top = contentPadding,
            right = contentPadding,
            bottom = contentPadding,
        ) + alignmentInsets,
        axes = axisMetrics,
        labelOverhang = overhang,
    )
    val plot = layout.plotArea
    if (plot.isEmpty) return CartesianGeometry.empty()

    // A plot squeezed into a sliver by its own axes is legible in no sense that
    // matters, and compaction has already done what it can by this point. The
    // chart still draws — refusing to would be worse — but it says so, because
    // a caller who put four axes on a phone should find out from a diagnostic
    // rather than from a screenshot.
    val plotShare = if (orientation.isVertical) {
        if (bounds.width > 0f) plot.width / bounds.width else 1f
    } else {
        if (bounds.height > 0f) plot.height / bounds.height else 1f
    }
    if (plotShare < MIN_PLOT_SHARE && preparedAxes.axes.count { it.visible } > 1) {
        diagnostics += AxisDiagnostic(
            axisId = null,
            message = "The axes take ${((1f - plotShare) * 100).toInt()}% of the chart's width; " +
                "the plot has ${(plotShare * 100).toInt()}% left. Consider fewer axes, shorter " +
                "tick labels, or AxisDensity.Compact.",
        )
    }

    // The gutters this chart needs on its own, before any alignment padding —
    // which is what a group of stacked charts takes the maximum of.
    val naturalInsets = ChartInsets(
        left = plot.left - bounds.left - alignmentInsets.left,
        top = plot.top - bounds.top - alignmentInsets.top,
        right = bounds.right - plot.right - alignmentInsets.right,
        bottom = bounds.bottom - plot.bottom - alignmentInsets.bottom,
    )

    // ---- scales, now that the plot area is known ----------------------------

    // One scale per value axis, all mapping into the *same* vertical extent:
    // rainfall's 0..250, temperature's -10..40 and pressure's 980..1040 each
    // run from `plot.bottom` to `plot.top`. That is what "independent scales,
    // shared plot area" means in one expression.
    //
    // The scales run "backwards" in pixels — larger values at smaller y on a
    // vertical chart — and building them inverted is the only place that fact
    // is encoded; nothing downstream flips a sign.
    val axisScales: Map<ChartAxisId, LinearScale> = preparedAxes.axes.associate { axis ->
        axis.id to axis.scaleFor(plot, orientation)
    }
    val valueScale = primaryAxis?.let { axisScales.getValue(it.id) }
        ?: if (orientation.isVertical) {
            LinearScale(valueDomain, plot.bottom, plot.top)
        } else {
            LinearScale(valueDomain, plot.left, plot.right)
        }

    val domainStart = if (orientation.isVertical) plot.left else plot.top
    val domainEnd = if (orientation.isVertical) plot.right else plot.bottom

    val categoryPadding = layers.filterIsInstance<ResolvedLayer.Bars>()
        .firstOrNull()?.categoryPadding ?: CategoryScale.DEFAULT_CATEGORY_PADDING

    val domainAxisModel: DomainAxis = when (axisKind) {
        // A category axis zooms by stretching the *whole* band run across a
        // virtual extent and showing a window of it. Narrowing the category
        // list instead would drop off-screen bands entirely, which breaks a
        // line at the viewport edge rather than letting it run out of it — and
        // would renumber every band on every pan.
        ChartXAxisKind.Category -> {
            val virtualExtent = (domainEnd - domainStart) / viewport.width.toFloat()
            val virtualStart = domainStart - (viewport.start * virtualExtent).toFloat()
            DomainAxis.Categories(
                CategoryScale(
                    categories = categories,
                    rangeStart = virtualStart,
                    rangeEnd = virtualStart + virtualExtent,
                    categoryPadding = categoryPadding,
                ),
            )
        }
        // A log domain axis is meaningful — a scatter of latency against load,
        // say — and costs nothing extra: the transform reshapes the same linear
        // mapping. A time axis stays linear, because a logarithmic date has no
        // reading.
        ChartXAxisKind.Numeric -> DomainAxis.Continuous(
            LinearScale(
                domain = xDomain,
                rangeStart = domainStart,
                rangeEnd = domainEnd,
                transform = domainAxisConfig.scale.transform(),
            ),
        )
        else -> DomainAxis.Continuous(LinearScale(xDomain, domainStart, domainEnd))
    }

    val coordinates = CartesianCoordinates(plot, domainAxisModel, valueScale, orientation)

    // One coordinate system per value axis, differing from the chart's in
    // exactly one thing: the value scale. A layer bound to the pressure axis is
    // built and drawn against its own, and needs to know nothing about the
    // arrangement — which is what keeps "how many axes" out of every layer's
    // implementation and is why bars, lines, candles and custom layers all
    // gained multi-axis support without being touched.
    val axisCoordinates: Map<ChartAxisId, CartesianCoordinates> = axisScales.mapValues { (_, scale) ->
        coordinatesFor(plot, domainAxisModel, scale, orientation)
    }
    val secondaryCoordinates = axisCoordinates[ChartAxisId.SecondaryY]

    fun coordinatesOf(axisId: ChartAxisId): CartesianCoordinates =
        axisCoordinates[axisId] ?: coordinates

    fun formatterOf(axisId: ChartAxisId): ChartValueFormatter =
        preparedAxes.find(axisId)?.formatter ?: valueFormatter

    // ---- tick positions and label thinning ----------------------------------

    val valueTickPositions = primaryAxis?.tickValues?.map(valueScale::scale).orEmpty()
    val domainTickPositions = when (val axis = domainAxisModel) {
        is DomainAxis.Categories -> categories.indices.map(axis.scale::positionAt)
        is DomainAxis.Continuous -> domainTickValues.map { axis.scale.scale(it) }
    }

    val domainAvailable = domainEnd - domainStart
    val domainLabelSpacing = if (measuredDomainLabels.isEmpty()) {
        0f
    } else if (rotateDomain) {
        // Rotated labels need only their own height's worth of run, which is
        // what buys the extra density over horizontal text.
        measuredDomainLabels.maxOf { it.size.height }.toFloat() * ROTATED_SPACING_FACTOR
    } else if (domainPosition.isHorizontal) {
        measuredDomainLabels.maxOf { it.size.width }.toFloat() + labelPadding * 2f
    } else {
        measuredDomainLabels.maxOf { it.size.height }.toFloat() + labelPadding
    }

    // On a zoomed category axis only the bands inside the window can be
    // labelled; thinning is then applied to those rather than to all of them,
    // so a zoomed-in chart shows more labels, not the same few.
    val candidateDomainIndices: List<Int> = if (axisKind == ChartXAxisKind.Category) {
        viewport.visibleCategoryRange(measuredDomainLabels.size).toList()
    } else {
        measuredDomainLabels.indices.toList()
    }

    val keptDomainIndices = when (domainAxisConfig.labelOverflow) {
        AxisLabelOverflow.None -> candidateDomainIndices
        else -> selectLabelIndices(
            count = candidateDomainIndices.size,
            available = domainAvailable,
            labelExtent = domainLabelSpacing,
            maxLabels = domainAxisConfig.maxLabels,
        ).mapNotNull { candidateDomainIndices.getOrNull(it) }
    }

    val measuredDomainAxis = MeasuredAxis(
        position = domainPosition,
        config = domainAxisConfig,
        ticks = domainTickPositions,
        labels = keptDomainIndices.mapNotNull { index ->
            val layout1 = measuredDomainLabels.getOrNull(index) ?: return@mapNotNull null
            val at = domainTickPositions.getOrNull(index) ?: return@mapNotNull null
            MeasuredAxisLabel(layout1, at)
        },
        title = domainTitle,
        rotated = rotateDomain,
        id = registry.primaryX?.id ?: ChartAxisId.DefaultX,
    )

    // Every value axis, positioned and thinned. The offset each one is drawn at
    // came out of the layout engine, which stacked them per side — nothing here
    // knows how many there are.
    val measuredValueAxes: List<MeasuredAxis> = preparedAxes.axes.mapNotNull { axis ->
        if (!axis.visible) return@mapNotNull null
        axis.measured(
            plot = plot,
            orientation = orientation,
            scale = axisScales.getValue(axis.id),
            offset = layout.offsetOf(axis.id),
            labelPadding = labelPadding,
        )
    }

    // One formatter for the domain, shared by the crosshair chip, the range
    // readout and the accessibility announcements. A chip that wrote a date
    // differently from the axis beneath it would read as two quantities.
    val formatDomain: (ChartX) -> String = when (axisKind) {
        ChartXAxisKind.Category -> { value -> value.label() }
        // The lambda is bound to a name before being returned: a lambda
        // literal written straight after a `val` initialiser parses as a
        // trailing-lambda call on that initialiser instead.
        ChartXAxisKind.Time -> run {
            val formatter = domainAxisConfig.timeFormatter
                ?: ChartDateFormatters.pattern(patternForSpan(fullXDomain.span), locale)
            val format: (ChartX) -> String = { value ->
                when (value) {
                    is ChartX.Time -> formatter.format(value.epochMillis)
                    else -> value.label()
                }
            }
            format
        }
        ChartXAxisKind.Numeric -> run {
            val formatter = domainAxisConfig.valueFormatter
                ?: ChartNumberFormatters.forTicks(domainTickValues, locale)
            val format: (ChartX) -> String = { value ->
                when (value) {
                    is ChartX.Numeric -> formatter.format(value.value)
                    else -> value.label()
                }
            }
            format
        }
    }

    // Where a domain *value* sits, which is what a shared crosshair and the
    // annotation layers both need. Kept here rather than in each of them, so
    // an annotation and a linked guide at the same date land on the same pixel.
    val positionOfDomain: (ChartX) -> Float? = { value ->
        when (val axis = domainAxisModel) {
            is DomainAxis.Categories -> {
                val index = categories.indexOf(value.label())
                if (index < 0) null else axis.scale.positionAt(index)
            }
            is DomainAxis.Continuous -> when (value) {
                is ChartX.Numeric -> axis.scale.scale(value.value)
                is ChartX.Time -> axis.scale.scale(value.epochMillis.toDouble())
                is ChartX.Category -> null
            }
        }
    }

    /**
     * The pixel position of a domain value stated the way the caller's data
     * states it.
     *
     * What a custom layer is handed: it passes `"Mar"` or `releaseMillis` and
     * gets a position, resolved through the chart's own resolver so the same
     * lambda works on a category, numeric or time axis.
     */
    val positionOfDomainValue: (Any?) -> Float? = { value ->
        positionOfDomain(xResolver.resolveOrDefault(value))
    }

    // ---- layers -------------------------------------------------------------

    val renderers = ArrayList<ChartLayerRenderer>()
    val hitTestable = ArrayList<ChartLayerRenderer>()
    val summaries = ArrayList<ChartLayerSummary>()
    val legendSeries = ArrayList<LegendSeries>()

    // Grid rows come from the axes that own them, not from every axis. Three
    // interleaved sets of horizontal lines at unrelated intervals is a moiré in
    // which every line looks meaningful and only a third of them are for any
    // one series — see [AxisGridMode].
    val gridValuePositions = preparedAxes.axes
        .filter { it.ownsGrid && it.visible }
        .flatMap { axis -> axis.tickValues.map(axisScales.getValue(axis.id)::scale) }
        .ifEmpty { valueTickPositions }
    renderers += GridLayer(grid, domainTickPositions, gridValuePositions)
    // Behind the data: a range band drawn over the lines would hide what the
    // reader selected it to look at.
    if (rangeSelectable) renderers += RangeSelectionLayer()

    // One annotation layer per value axis, because an annotation's value only
    // means something on the axis it was stated against: a rule at `30` is 30°C
    // on the temperature axis and 30mm on the rainfall one. Grouping here is
    // what lets the layer itself keep asking its coordinates unqualified.
    val defaultAnnotationAxis = registry.primaryY?.id ?: ChartAxisId.DefaultY
    fun annotationsByAxis(order: AnnotationOrder): Map<ChartAxisId, List<ResolvedAnnotation>> =
        annotations.filter { it.annotation.order == order }
            .groupBy { it.annotation.valueAxis ?: defaultAnnotationAxis }

    annotationsByAxis(AnnotationOrder.Behind).forEach { (axisId, group) ->
        renderers += AnnotationLayer(
            id = "annotations-behind-${axisId.value}",
            annotations = group,
            order = AnnotationOrder.Behind,
            positionOfDomain = positionOfDomain,
            valueFormatter = formatterOf(axisId),
            valueAxisId = axisId,
        )
    }

    val plotExtent = if (orientation.isVertical) plot.width else plot.height

    // Which axis each renderer is measured against, by renderer id.
    //
    // A layer produces one or more renderers — a bar layer and its value
    // labels, say — and none of them carries the binding itself: making every
    // renderer class take an axis id would be forty constructors changed to
    // carry something only the chart uses. Recording it here instead keeps the
    // layer model exactly as it was and still lets the chart hand each renderer
    // the coordinates, formatter and hit test of its own axis.
    val rendererAxes = HashMap<String, ChartAxisId>()

    layers.forEachIndexed { layerIndex, layer ->
        val layerId = "${layer.key}-$layerIndex"
        val renderersBefore = renderers.size
        val hitTestableBefore = hitTestable.size
        // The coordinate system this layer is measured in: the one built over
        // the axis it named. Resolved once, here, so the draw loop holds a
        // direct reference to a scale rather than looking an id up per point.
        val coords = coordinatesOf(layer.valueAxisId)
        // And the formatter that axis writes numbers with, so a value label on
        // a temperature bar reads `14.2 °C` and one on a pressure line reads
        // `1,018 hPa`.
        val layerFormatter = formatterOf(layer.valueAxisId)
        when (layer) {
            is ResolvedLayer.Bars -> {
                val (bounds1, aligned) = barBounds[layer] ?: return@forEachIndexed
                val categoryScale = coords.categories ?: return@forEachIndexed
                val slices = computeBarSlices(
                    values = aligned.map { it.values },
                    pointIndices = aligned.map { it.sourceIndices },
                    paletteIndices = layer.data.visibleSeries.map { it.paletteIndex },
                    bounds = bounds1,
                    categoryScale = categoryScale,
                    valueScale = coords.valueScale,
                    orientation = orientation,
                    grouping = layer.grouping,
                    plotArea = plot,
                    groupPadding = layer.groupPadding,
                )
                val seriesGeometry = layer.data.visibleSeries.mapIndexed { index, series ->
                    BarSeriesGeometry(
                        seriesId = series.id,
                        seriesName = series.name,
                        seriesIndex = index,
                        paletteIndex = series.paletteIndex,
                        colorOverride = series.color,
                        items = series.items,
                        categoryLabels = categories,
                        values = aligned[index].values,
                        sourceIndices = aligned[index].sourceIndices,
                    )
                }
                val barLayer = BarLayer(
                    id = layerId,
                    series = seriesGeometry,
                    slices = slices,
                    cornerRadiusOverride = layer.cornerRadius,
                    baseline = coords.baseline,
                )
                renderers += barLayer
                hitTestable += barLayer
                summaries += barLayer.describe()
                if (layer.valueLabels) {
                    renderers += ValueLabelLayer(
                        id = "$layerId-labels",
                        anchors = barLabelAnchors(
                            slices = slices,
                            seriesIds = seriesGeometry.map { it.seriesId },
                            vertical = orientation.isVertical,
                        ),
                        formatter = valueFormatter,
                    )
                }
            }

            is ResolvedLayer.Line -> {
                val geometry = layer.data.visibleSeries.mapIndexed { index, series ->
                    lineGeometry(
                        series = series,
                        seriesIndex = index,
                        coordinates = coords,
                        data = layer.data,
                        categories = categories,
                        missingValuePolicy = layer.missingValuePolicy,
                        visibleDomain = if (axisKind == ChartXAxisKind.Category) null else xDomain,
                        performance = layer.performance,
                        plotExtent = plotExtent,
                    )
                }
                val lineLayer = LineLayer(
                    id = layerId,
                    series = geometry,
                    interpolation = layer.interpolation,
                    style = layer.style,
                    fill = layer.fill,
                    pointMode = layer.pointMode,
                    lineWidthOverride = layer.lineWidth,
                    pointMarkerThreshold = layer.pointMarkerThreshold,
                )
                renderers += lineLayer
                hitTestable += lineLayer
                summaries += lineLayer.describe()
                if (layer.valueLabels) {
                    renderers += ValueLabelLayer(
                        id = "$layerId-labels",
                        anchors = geometry.flatMap { s ->
                            s.presentPoints.map { point ->
                                io.devkit.chartkit.layer.label.ValueLabelAnchor(
                                    seriesId = s.seriesId,
                                    position = point.position,
                                    value = point.value,
                                    placement = io.devkit.chartkit.layer.label.LabelPlacement.Above,
                                )
                            }
                        },
                        formatter = valueFormatter,
                    )
                }
            }

            is ResolvedLayer.Scatter -> {
                val radius = with(density) {
                    (layer.pointRadius ?: dimensions.scatterPointRadius).toPx()
                }
                val geometry = layer.data.visibleSeries.mapIndexed { index, series ->
                    scatterGeometry(
                        series = series,
                        seriesIndex = index,
                        coordinates = coords,
                        data = layer.data,
                        categories = categories,
                        sizes = layer.sizes?.getOrNull(index),
                        sizeScale = layer.sizeScale,
                        defaultRadius = radius,
                        visibleDomain = if (axisKind == ChartXAxisKind.Category) null else xDomain,
                        cull = layer.performance.cullToViewport && !viewport.isFullyZoomedOut,
                    )
                }
                val scatterLayer = ScatterLayer(
                    id = layerId,
                    series = geometry,
                    shape = layer.shape,
                    style = layer.style,
                    sizeEncoded = layer.sizeScale != null,
                )
                renderers += scatterLayer
                hitTestable += scatterLayer
                summaries += scatterLayer.describe()
            }

            is ResolvedLayer.Histogram -> {
                val histogramLayer = HistogramLayer(
                    id = layerId,
                    bins = layer.bins,
                    seriesId = layer.seriesId,
                    seriesName = layer.seriesName,
                    metric = layer.metric,
                    items = layer.items,
                    paletteIndex = layer.paletteIndex,
                    colorOverride = layer.colorOverride,
                    cornerRadiusOverride = layer.cornerRadius,
                )
                renderers += histogramLayer
                hitTestable += histogramLayer
                summaries += histogramLayer.describe()
            }

            is ResolvedLayer.Box -> {
                val boxLayer = BoxPlotLayer(
                    id = layerId,
                    entries = layer.entries,
                    seriesId = layer.seriesId,
                    seriesName = layer.seriesName,
                    valueFormatter = layerFormatter,
                )
                renderers += boxLayer
                hitTestable += boxLayer
                summaries += boxLayer.describe()
            }

            is ResolvedLayer.Violin -> {
                val violinLayer = ViolinLayer(
                    id = layerId,
                    entries = layer.entries,
                    seriesId = layer.seriesId,
                    seriesName = layer.seriesName,
                    overlay = layer.overlay,
                    valueFormatter = layerFormatter,
                )
                renderers += violinLayer
                hitTestable += violinLayer
                summaries += violinLayer.describe()
            }

            is ResolvedLayer.Heatmap -> {
                val heatmapLayer = HeatmapLayer(
                    id = layerId,
                    cells = layer.cells,
                    columnLabels = layer.columnLabels,
                    rowLabels = layer.rowLabels,
                    colorScale = layer.colorScale,
                    items = layer.items,
                    seriesId = layer.seriesId,
                    seriesName = layer.seriesName,
                    cellLabels = layer.cellLabels,
                    valueFormatter = layerFormatter,
                    showMissing = layer.showMissing,
                    cornerRadiusOverride = layer.cornerRadius,
                )
                renderers += heatmapLayer
                hitTestable += heatmapLayer
                summaries += heatmapLayer.describe()
            }

            is ResolvedLayer.Candles -> {
                val positions = layer.points.map { point ->
                    coords.positionOfDomain(point.domainValue) ?: Float.NaN
                }
                val width = periodWidth(
                    positions = positions.toFloatArray(),
                    fraction = dimensions.candleBodyFraction,
                    minimum = with(density) { dimensions.candleMinBodyWidth.toPx() },
                    fallback = plotExtent,
                )
                val candles = layer.points.mapIndexedNotNull { index, point ->
                    val position = positions[index]
                    if (!position.isFinite()) null else CandleGeometry(point, position)
                }
                val candleLayer = CandleLayer(
                    id = layerId,
                    candles = candles,
                    style = layer.markStyle,
                    seriesId = layer.seriesId,
                    seriesName = layer.seriesName,
                    items = layer.items,
                    bodyWidth = width,
                    valueFormatter = layerFormatter,
                    domainLabel = formatDomain,
                    xValues = layer.xValues,
                )
                renderers += candleLayer
                hitTestable += candleLayer
                summaries += candleLayer.describe()
            }

            is ResolvedLayer.Waterfall -> {
                val waterfallLayer = WaterfallLayer(
                    id = layerId,
                    steps = layer.steps,
                    seriesId = layer.seriesId,
                    seriesName = layer.seriesName,
                    showConnectors = layer.showConnectors,
                    cornerRadius = layer.cornerRadius,
                    valueFormatter = layerFormatter,
                )
                renderers += waterfallLayer
                hitTestable += waterfallLayer
                summaries += waterfallLayer.describe()
            }

            is ResolvedLayer.ConnectorMarks -> {
                val markLayer = ConnectorMarkLayer(
                    id = layerId,
                    entries = layer.entries,
                    kind = layer.kind,
                    seriesId = layer.seriesId,
                    seriesName = layer.seriesName,
                    startLabel = layer.startLabel,
                    endLabel = layer.endLabel,
                    baseline = lollipopBaseline(coords),
                    valueFormatter = layerFormatter,
                )
                renderers += markLayer
                hitTestable += markLayer
                summaries += markLayer.describe()
            }

            is ResolvedLayer.Bullet -> {
                val bulletLayer = BulletLayer(
                    id = layerId,
                    entries = layer.entries,
                    seriesId = layer.seriesId,
                    seriesName = layer.seriesName,
                    targetLabel = layer.targetLabel,
                    valueFormatter = layerFormatter,
                )
                renderers += bulletLayer
                hitTestable += bulletLayer
                summaries += bulletLayer.describe()
            }

            is ResolvedLayer.Interval -> {
                val intervalLayer = IntervalLayer(
                    id = layerId,
                    model = layer.model,
                    seriesId = layer.seriesId,
                    seriesName = layer.seriesName,
                    labels = layer.labels,
                    showProgress = layer.showProgress,
                    showDependencies = layer.showDependencies,
                    timeFormatter = domainAxisConfig.timeFormatter
                        ?: ChartDateFormatters.pattern(patternForSpan(fullXDomain.span), locale),
                )
                renderers += intervalLayer
                hitTestable += intervalLayer
                summaries += intervalLayer.describe()
            }

            is ResolvedLayer.Custom -> {
                val customLayer = CustomLayerRenderer(layer.spec, positionOfDomainValue)
                renderers += customLayer
                hitTestable += customLayer
            }

            is ResolvedLayer.Volume -> {
                val positions = layer.points.map { point ->
                    coords.positionOfDomain(point.domainValue) ?: Float.NaN
                }
                val width = periodWidth(
                    positions = positions.toFloatArray(),
                    fraction = dimensions.candleBodyFraction,
                    minimum = with(density) { dimensions.candleMinBodyWidth.toPx() },
                    fallback = plotExtent,
                )
                val bars = layer.points.mapIndexedNotNull { index, point ->
                    val volume = point.volume ?: return@mapIndexedNotNull null
                    val position = positions[index]
                    if (!position.isFinite()) {
                        null
                    } else {
                        VolumeGeometry(point.sourceIndex, position, volume, point.direction)
                    }
                }
                val volumeLayer = VolumeLayer(
                    id = layerId,
                    bars = bars,
                    seriesId = layer.seriesId,
                    seriesName = layer.seriesName,
                    items = layer.items,
                    barWidth = width,
                    valueFormatter = layerFormatter,
                    domainLabel = formatDomain,
                    xValues = layer.xValues,
                )
                renderers += volumeLayer
                hitTestable += volumeLayer
                summaries += volumeLayer.describe()
            }
        }
        // Everything this layer just added measures against this layer's axis.
        for (index in renderersBefore until renderers.size) {
            rendererAxes[renderers[index].id] = layer.valueAxisId
        }
        for (index in hitTestableBefore until hitTestable.size) {
            rendererAxes[hitTestable[index].id] = layer.valueAxisId
        }
    }

    // Custom layers supplied as a chart parameter rather than through the DSL
    // draw last, over the data. A caller who wants one underneath declares it
    // in the layer list, where the order is theirs.
    customLayers.forEach { spec ->
        val customLayer = CustomLayerRenderer(spec, positionOfDomainValue)
        renderers += customLayer
        hitTestable += customLayer
    }

    annotationsByAxis(AnnotationOrder.Above).forEach { (axisId, group) ->
        val annotationLayer = AnnotationLayer(
            id = "annotations-above-${axisId.value}",
            annotations = group,
            order = AnnotationOrder.Above,
            positionOfDomain = positionOfDomain,
            valueFormatter = formatterOf(axisId),
            valueAxisId = axisId,
        )
        renderers += annotationLayer
        hitTestable += annotationLayer
        summaries += annotationLayer.describe()
    }

    renderers += CrosshairLayer(
        config = crosshair,
        domainLabel = { selection -> formatDomain(selection.x) },
        valueLabel = valueFormatter::format,
        positionOfDomain = positionOfDomain,
        formatDomain = formatDomain,
    )

    // Colours are resolved in composition, not here: the palette comes from
    // the theme, and baking it into cached geometry would leave a chart showing
    // yesterday's colours after a theme change until its data moved.
    layers.forEach { legendSeries += it.legendRows() }

    // `accessibility` shapes only how much detail the summary carries; the
    // summaries themselves are the layers' own factual description.
    val effectiveSummaries = if (accessibility.includeDataPoints) {
        summaries
    } else {
        summaries.map { summary ->
            val present = summary.entries.mapNotNull { it.value }
            summary.copy(
                entries = emptyList(),
                valueRange = summary.valueRange
                    ?: present.takeIf { it.isNotEmpty() }?.let { it.min()..it.max() },
                missingCount = if (summary.entries.isEmpty()) {
                    summary.missingCount
                } else {
                    summary.pointCount - present.size
                },
            )
        }
    }

    // Turning a full-domain fraction back into the value it names, and into
    // the caller's own items. Range selection is stated in domain values
    // rather than pixels, and this is where that conversion lives — once,
    // rather than in each chart that offers it.
    val domainValueAtFraction: (Double) -> ChartX = when (axisKind) {
        ChartXAxisKind.Category -> { fraction ->
            val index = (fraction * categories.size).toInt().coerceIn(0, (categories.size - 1).coerceAtLeast(0))
            ChartX.Category(categories.getOrNull(index).orEmpty())
        }
        ChartXAxisKind.Time -> { fraction ->
            ChartX.Time((fullXDomain.min + fullXDomain.span * fraction).toLong())
        }
        ChartXAxisKind.Numeric -> { fraction ->
            ChartX.Numeric(fullXDomain.min + fullXDomain.span * fraction)
        }
    }

    val fractionOfDomainValue: (ChartX) -> Double? = { value ->
        when (axisKind) {
            ChartXAxisKind.Category -> {
                val index = categories.indexOf(value.label())
                if (index < 0 || categories.isEmpty()) null else (index + 0.5) / categories.size
            }
            else -> {
                val numeric = when (value) {
                    is ChartX.Numeric -> value.value
                    is ChartX.Time -> value.epochMillis.toDouble()
                    is ChartX.Category -> null
                }
                if (numeric == null || fullXDomain.span <= 0.0) {
                    null
                } else {
                    ((numeric - fullXDomain.min) / fullXDomain.span).coerceIn(0.0, 1.0)
                }
            }
        }
    }

    val itemsBetween: (Double, Double) -> List<Any?> = { from, to ->
        buildList {
            layers.forEach { layer ->
                val data = layer.seriesData ?: return@forEach
                data.visibleSeries.forEach { series ->
                    series.points.forEach { point ->
                        val fraction = when (axisKind) {
                            ChartXAxisKind.Category ->
                                if (categories.isEmpty()) {
                                    0.0
                                } else {
                                    (categories.indexOf(point.x.label()) + 0.5) / categories.size
                                }
                            else -> {
                                val value = data.continuousX(point)
                                if (fullXDomain.span <= 0.0) {
                                    0.0
                                } else {
                                    (value - fullXDomain.min) / fullXDomain.span
                                }
                            }
                        }
                        if (fraction in from..to) series.itemAt(point.sourceIndex)?.let(::add)
                    }
                }
            }
            // Price marks are not series-shaped but a range over them is
            // exactly as meaningful — "what happened between these two dates"
            // is the question a financial chart is most often asked.
            layers.filterIsInstance<ResolvedLayer.Candles>().forEach { layer ->
                layer.points.forEach { point ->
                    val fraction = if (fullXDomain.span <= 0.0) {
                        0.0
                    } else {
                        (point.domainValue - fullXDomain.min) / fullXDomain.span
                    }
                    if (fraction in from..to) layer.items.getOrNull(point.sourceIndex)?.let(::add)
                }
            }
        }
    }

    return CartesianGeometry(
        coordinates = coordinates,
        renderers = renderers,
        hitTestable = hitTestable,
        domainAxis = measuredDomainAxis.takeIf { domainAxisConfig.visible },
        valueAxis = measuredValueAxes.firstOrNull { it.id == primaryAxis?.id } ?: measuredValueAxes.firstOrNull(),
        valueAxes = measuredValueAxes,
        secondaryCoordinates = secondaryCoordinates,
        axisCoordinates = axisCoordinates,
        axisFormatters = preparedAxes.axes.associate { it.id to it.formatter },
        axisSpecs = preparedAxes.axes.associate { it.id to it.spec },
        axisLabelUnits = preparedAxes.axes.associate { it.id to it.labelUnit },
        rendererAxes = rendererAxes,
        axisDiagnostics = diagnostics.toList(),
        naturalPlotInsets = naturalInsets,
        legendSeries = legendSeries.distinctBy { it.seriesId },
        summaries = effectiveSummaries,
        valueFormatter = valueFormatter,
        isEmpty = isEmpty,
        fullDomain = if (axisKind == ChartXAxisKind.Category) null else fullXDomain,
        categoryCount = categories.size,
        viewport = viewport,
        domainLabeller = { fraction -> formatDomain(domainValueAtFraction(fraction)) },
        formatDomainValue = formatDomain,
        domainPositionOf = positionOfDomain,
        domainValueAt = domainValueAtFraction,
        itemsBetween = itemsBetween,
        fractionOfDomain = fractionOfDomainValue,
    )
}

/** Below this share of the chart's width, the axes have crowded out the plot. */
private const val MIN_PLOT_SHARE: Float = 0.35f

/** A legend row before its colour has been resolved from the theme. */
internal data class LegendSeries(
    val seriesId: String,
    val name: String,
    val paletteIndex: Int,
    val colorOverride: Int?,
    val visible: Boolean,
)

/** A series' values placed into the chart's global category order. */
private class AlignedSeries(val values: List<Double?>, val sourceIndices: List<Int>)

private fun alignToCategories(series: PlotSeries, categories: List<String>): AlignedSeries {
    val values = arrayOfNulls<Double>(categories.size)
    val indices = IntArray(categories.size) { -1 }
    series.points.forEach { point ->
        val index = categories.indexOf(point.x.label())
        if (index >= 0) {
            // Last value wins for a repeated category, matching what a reader
            // sees in the list: the later row is the more recent statement.
            values[index] = point.y
            indices[index] = point.sourceIndex
        }
    }
    return AlignedSeries(values.toList(), indices.toList())
}

/**
 * Which source indices a dense series actually draws.
 *
 * ```text
 * source        ──────────────────────────────────────────
 * cull                     ├── viewport + overscan ──┤
 * downsample               ·  ·  ·   ·  ·   ·  ·  ·  ·
 * ```
 *
 * Both steps are skipped entirely when they would not help, which is the common
 * case: a two-hundred-point chart does no work here at all and gets `null`,
 * meaning "draw everything".
 *
 * Culling requires an ordered domain, and so does every downsampler — an
 * unordered series has no window to cut and no buckets to reduce, so it is
 * drawn in full whatever the configuration says.
 */
private fun drawnIndices(
    domainValues: DoubleArray?,
    values: List<Double?>,
    visibleDomain: NumericDomain?,
    performance: ChartPerformance,
    plotExtent: Float,
): IntArray? {
    if (domainValues == null || domainValues.size < 2) return null
    if (!VisibleRange.isAscending(domainValues)) return null

    val window = if (performance.cullToViewport && visibleDomain != null) {
        val visibleCount = VisibleRange.of(domainValues, visibleDomain.min, visibleDomain.max)
        val overscan = VisibleRange.overscanFor(visibleCount.size, performance.overscanFraction)
        VisibleRange.of(domainValues, visibleDomain.min, visibleDomain.max, overscan)
    } else {
        io.devkit.chartkit.data.IndexRange(0, domainValues.size - 1)
    }
    if (window.isEmpty) return IntArray(0)

    val sampler = performance.downsampling.resolve(window.size, plotExtent)
    if (sampler == null) {
        // Nothing to sample. Only report a subset when culling actually
        // narrowed the range, so the common case allocates nothing.
        return if (window.first == 0 && window.last == domainValues.size - 1) {
            null
        } else {
            IntArray(window.size) { window.first + it }
        }
    }

    val (downsampler, target) = sampler
    val windowX = DoubleArray(window.size) { domainValues[window.first + it] }
    // A missing value is `NaN` to the sampler, which every implementation is
    // required to leave out of its arithmetic rather than treat as a number.
    val windowY = DoubleArray(window.size) { values.getOrNull(window.first + it) ?: Double.NaN }
    val sampled = downsampler.sample(windowX, windowY, target)
    return IntArray(sampled.size) { window.first + sampled[it] }
}

/** The continuous domain position of every point, or `null` on a banded axis. */
private fun continuousDomainValues(series: PlotSeries, data: PlotData): DoubleArray? {
    if (data.xAxisKind == ChartXAxisKind.Category) return null
    return DoubleArray(series.points.size) { data.continuousX(series.points[it]) }
}

@Suppress("LongParameterList")
internal fun lineGeometry(
    series: PlotSeries,
    seriesIndex: Int,
    coordinates: CartesianCoordinates,
    data: PlotData,
    categories: List<String>,
    missingValuePolicy: MissingValuePolicy,
    visibleDomain: NumericDomain? = null,
    performance: ChartPerformance = ChartPerformance.Exact,
    plotExtent: Float = 0f,
): LineSeriesGeometry {
    val domainValues = continuousDomainValues(series, data)
    val values = series.points.map { it.y }
    val retained = drawnIndices(domainValues, values, visibleDomain, performance, plotExtent)

    fun positionOf(point: io.devkit.chartkit.model.PlotPoint): LinePoint? {
        val value = point.y ?: return null
        val domainPosition = when (val axis = coordinates.domainAxis) {
            is DomainAxis.Categories -> {
                val index = categories.indexOf(point.x.label())
                if (index < 0) return null else axis.scale.positionAt(index)
            }
            is DomainAxis.Continuous -> axis.scale.scale(data.continuousX(point))
        }
        val valuePosition = coordinates.positionOfValue(value)
        val offset = coordinates.pointAt(domainPosition, valuePosition)
        return if (!offset.isFinite) null else LinePoint(offset, point.sourceIndex, value)
    }

    val drawn: List<LinePoint?> = if (retained == null) {
        series.points.map(::positionOf)
    } else {
        retained.map { index -> series.points.getOrNull(index)?.let(::positionOf) }
    }

    val present = drawn.filterNotNull()
    // `Connect` is the one policy that changes the *path* rather than the
    // values: the gap is closed by joining the surrounding points, so the
    // missing entries are simply absent from what is segmented. `Break` keeps
    // them as holes, which is what splits the line.
    val forSegmentation: List<LinePoint?> =
        if (missingValuePolicy == MissingValuePolicy.Connect) present else drawn
    return LineSeriesGeometry(
        seriesId = series.id,
        seriesName = series.name,
        seriesIndex = seriesIndex,
        paletteIndex = series.paletteIndex,
        colorOverride = series.color,
        points = drawn,
        segments = segmentLine(forSegmentation),
        presentPoints = present,
        sortedByDomain = isXOrdered(present),
        items = series.items,
        xValues = series.points.map { it.x },
        values = values,
        domainValues = domainValues?.takeIf { VisibleRange.isAscending(it) },
    )
}

/**
 * One scatter series' screen geometry.
 *
 * Culled to the visible domain when the chart is zoomed, and never downsampled:
 * a scatter's individual observations *are* its content, and dropping some of
 * them would change what the chart claims. A dense scatter is instead made
 * legible by translucency and by the marker size, both of which the caller
 * controls.
 */
@Suppress("LongParameterList")
private fun scatterGeometry(
    series: PlotSeries,
    seriesIndex: Int,
    coordinates: CartesianCoordinates,
    data: PlotData,
    categories: List<String>,
    sizes: List<Double?>?,
    sizeScale: SizeScale?,
    defaultRadius: Float,
    visibleDomain: NumericDomain?,
    cull: Boolean,
): ScatterSeriesGeometry {
    val points = ArrayList<ScatterPoint>(series.points.size)
    series.points.forEach { point ->
        val value = point.y ?: return@forEach
        val domainPosition = when (val axis = coordinates.domainAxis) {
            is DomainAxis.Categories -> {
                val index = categories.indexOf(point.x.label())
                if (index < 0) return@forEach else axis.scale.positionAt(index)
            }
            is DomainAxis.Continuous -> {
                val continuous = data.continuousX(point)
                if (cull && visibleDomain != null &&
                    (continuous < visibleDomain.min || continuous > visibleDomain.max)
                ) {
                    return@forEach
                }
                axis.scale.scale(continuous)
            }
        }
        val offset = coordinates.pointAt(domainPosition, coordinates.positionOfValue(value))
        if (!offset.isFinite) return@forEach
        val radius = sizeScale?.size(sizes?.getOrNull(point.sourceIndex)) ?: defaultRadius
        points += ScatterPoint(offset, point.sourceIndex, radius)
    }

    return ScatterSeriesGeometry(
        seriesId = series.id,
        seriesName = series.name,
        seriesIndex = seriesIndex,
        paletteIndex = series.paletteIndex,
        colorOverride = series.color,
        points = points,
        items = series.items,
        xValues = series.points.map { it.x },
        values = series.points.map { it.y },
        sizeValues = sizes ?: emptyList(),
    )
}

internal fun ChartX.label(): String = when (this) {
    is ChartX.Category -> label
    is ChartX.Numeric -> value.toString()
    is ChartX.Time -> epochMillis.toString()
}

/**
 * A date pattern proportionate to the span the axis covers.
 *
 * The narrowest tier exists for live charts: a rolling ten-second window
 * labelled to the minute prints the same string on every tick, which is an axis
 * that says nothing.
 */
private fun patternForSpan(spanMillis: Double): String = when {
    spanMillis < 5 * 60 * 1000.0 -> "HH:mm:ss"
    spanMillis < 2 * 60 * 60 * 1000.0 -> "HH:mm"
    spanMillis < 3 * 24 * 60 * 60 * 1000.0 -> "d MMM HH:mm"
    spanMillis < 200L * 24 * 60 * 60 * 1000.0 -> "d MMM"
    else -> "MMM yyyy"
}

/** A 45° label's height is about 0.75 of its width, plus room for the descender. */
private const val ROTATED_LABEL_FACTOR = 0.78f

/** Rotated labels can sit about this fraction of their height apart. */
private const val ROTATED_SPACING_FACTOR = 1.6f
