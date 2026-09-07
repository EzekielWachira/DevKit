package io.devkit.chartkit.charts

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.axis.AxisLabelOverflow
import io.devkit.chartkit.axis.AxisPosition
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartGrid
import io.devkit.chartkit.axis.MeasuredAxis
import io.devkit.chartkit.axis.MeasuredAxisLabel
import io.devkit.chartkit.axis.selectLabelIndices
import io.devkit.chartkit.coordinate.CartesianCoordinates
import io.devkit.chartkit.coordinate.DomainAxis
import io.devkit.chartkit.formatter.ChartDateFormatters
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.BarGrouping
import io.devkit.chartkit.geometry.BarStacking
import io.devkit.chartkit.geometry.ChartInsets
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.geometry.LinePoint
import io.devkit.chartkit.geometry.computeBarSlices
import io.devkit.chartkit.geometry.isXOrdered
import io.devkit.chartkit.geometry.segmentLine
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.bar.BarLayer
import io.devkit.chartkit.layer.bar.BarSeriesGeometry
import io.devkit.chartkit.layer.grid.GridLayer
import io.devkit.chartkit.layer.label.ValueLabelLayer
import io.devkit.chartkit.layer.label.barLabelAnchors
import io.devkit.chartkit.layer.line.LineLayer
import io.devkit.chartkit.layer.line.LineSeriesGeometry
import io.devkit.chartkit.layer.selection.SelectionLayer
import io.devkit.chartkit.layout.AxisMetrics
import io.devkit.chartkit.layout.computeChartLayout
import io.devkit.chartkit.model.ChartX
import io.devkit.chartkit.model.ChartXAxisKind
import io.devkit.chartkit.model.MissingValuePolicy
import io.devkit.chartkit.model.PlotData
import io.devkit.chartkit.model.PlotSeries
import io.devkit.chartkit.scale.CategoryScale
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.scale.LinearScale
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.scale.TickGenerator
import io.devkit.chartkit.scale.TimeScale
import io.devkit.chartkit.scale.apply
import io.devkit.chartkit.theme.ChartDimensions
import io.devkit.chartkit.theme.ChartTypography
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
 */
internal sealed class ResolvedLayer {

    abstract val data: PlotData
    abstract val key: String

    class Line(
        override val key: String,
        override val data: PlotData,
        val interpolation: io.devkit.chartkit.geometry.LineInterpolation,
        val style: io.devkit.chartkit.layer.line.LineStyle,
        val fill: io.devkit.chartkit.layer.line.AreaFill?,
        val pointMode: io.devkit.chartkit.layer.line.PointMode,
        val lineWidth: androidx.compose.ui.unit.Dp?,
        val valueLabels: Boolean,
        val pointMarkerThreshold: Int,
        val missingValuePolicy: MissingValuePolicy,
    ) : ResolvedLayer()

    class Bars(
        override val key: String,
        override val data: PlotData,
        val grouping: BarGrouping,
        val cornerRadius: androidx.compose.ui.unit.Dp?,
        val categoryPadding: Double,
        val groupPadding: Double,
        val valueLabels: Boolean,
    ) : ResolvedLayer()
}

/** Everything one frame needs, computed once per layout rather than per frame. */
internal class CartesianGeometry(
    val coordinates: CartesianCoordinates,
    val renderers: List<ChartLayerRenderer>,
    val hitTestable: List<ChartLayerRenderer>,
    val domainAxis: MeasuredAxis?,
    val valueAxis: MeasuredAxis?,
    val legendSeries: List<LegendSeries>,
    val summaries: List<ChartLayerSummary>,
    val valueFormatter: ChartValueFormatter,
    val isEmpty: Boolean,
) {
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
    density: Density,
    textMeasurer: TextMeasurer,
    typography: ChartTypography,
    dimensions: ChartDimensions,
    locale: Locale,
    accessibility: ChartAccessibility,
): CartesianGeometry {
    if (bounds.isEmpty || layers.isEmpty()) return CartesianGeometry.empty()

    // ---- merge every layer's data into one domain ---------------------------

    val axisKind = layers.map { it.data.xAxisKind }.let { kinds ->
        // A category anywhere forces the whole axis categorical: a numeric axis
        // has nowhere to put a value that is not a number.
        when {
            kinds.any { it == ChartXAxisKind.Category } -> ChartXAxisKind.Category
            kinds.any { it == ChartXAxisKind.Time } -> ChartXAxisKind.Time
            else -> ChartXAxisKind.Numeric
        }
    }

    val categories: List<String> = if (axisKind == ChartXAxisKind.Category) {
        LinkedHashSet<String>().apply {
            layers.forEach { layer ->
                layer.data.series.forEach { series ->
                    series.points.forEach { add(it.x.label()) }
                }
            }
        }.toList()
    } else {
        emptyList()
    }

    val barBounds = layers.filterIsInstance<ResolvedLayer.Bars>().associateWith { layer ->
        val aligned = layer.data.visibleSeries.map { series ->
            alignToCategories(series, categories)
        }
        BarStacking.bounds(aligned.map { it.values }, layer.grouping) to aligned
    }

    val valueDataDomain = buildList {
        layers.forEach { layer ->
            when (layer) {
                is ResolvedLayer.Bars -> barBounds[layer]?.first
                    ?.let { BarStacking.domainOf(it) }
                    ?.let { add(it) }

                is ResolvedLayer.Line -> layer.data.yDomain?.let { add(it) }
            }
        }
    }.reduceOrNull { a, b -> NumericDomain(minOf(a.min, b.min), maxOf(a.max, b.max)) }

    val valueDomain = valueDomainPolicy.apply(valueDataDomain)

    val xDataDomain = if (axisKind == ChartXAxisKind.Category) {
        null
    } else {
        layers.mapNotNull { it.data.xDomain }
            .reduceOrNull { a, b -> NumericDomain(minOf(a.min, b.min), maxOf(a.max, b.max)) }
    }
    val xDomain = (domainAxisConfig.domain ?: DomainPolicy.Auto(padding = 0.0)).apply(xDataDomain)

    val isEmpty = layers.all { it.data.isEmpty }

    // ---- tick values and their labels, before any pixels exist --------------

    val valueTickValues = TickGenerator.ticks(valueDomain, valueAxisConfig.tickCount)
    val valueFormatter = valueAxisConfig.valueFormatter
        ?: ChartNumberFormatters.forTicks(valueTickValues, locale)
    val valueLabels = valueTickValues.map(valueFormatter::format)

    val domainTickValues: List<Double>
    val domainLabels: List<String>
    when (axisKind) {
        ChartXAxisKind.Category -> {
            domainTickValues = categories.indices.map(Int::toDouble)
            val transform = domainAxisConfig.categoryFormatter
            domainLabels = categories.map { transform?.invoke(it) ?: it }
        }
        ChartXAxisKind.Time -> {
            val timeScale = TimeScale(xDomain, 0f, 1f)
            val ticks = timeScale.ticks(domainAxisConfig.tickCount)
            domainTickValues = ticks.map(Long::toDouble)
            val formatter = domainAxisConfig.timeFormatter
                ?: ChartDateFormatters.pattern(patternForSpan(xDomain.span), locale)
            domainLabels = ticks.map(formatter::format)
        }
        ChartXAxisKind.Numeric -> {
            domainTickValues = TickGenerator.ticks(xDomain, domainAxisConfig.tickCount)
            val formatter = domainAxisConfig.valueFormatter
                ?: ChartNumberFormatters.forTicks(domainTickValues, locale)
            domainLabels = domainTickValues.map(formatter::format)
        }
    }

    // ---- measure, then lay out ---------------------------------------------

    val labelStyle = typography.axisLabel
    val titleStyle = typography.axisTitle
    val measuredValueLabels = if (valueAxisConfig.showLabels && valueAxisConfig.visible) {
        valueLabels.map { textMeasurer.measure(it, labelStyle) }
    } else {
        emptyList()
    }
    val measuredDomainLabels = if (domainAxisConfig.showLabels && domainAxisConfig.visible) {
        domainLabels.map { textMeasurer.measure(it, labelStyle) }
    } else {
        emptyList()
    }

    val domainPosition = domainAxisConfig.positionOr(
        if (orientation.isVertical) AxisPosition.Bottom else AxisPosition.Start,
    )
    val valuePosition = valueAxisConfig.positionOr(
        if (orientation.isVertical) AxisPosition.Start else AxisPosition.Bottom,
    )

    val domainTitle = domainAxisConfig.title
        ?.takeIf { it.isNotBlank() && domainAxisConfig.visible }
        ?.let { textMeasurer.measure(it, titleStyle) }
    val valueTitle = valueAxisConfig.title
        ?.takeIf { it.isNotBlank() && valueAxisConfig.visible }
        ?.let { textMeasurer.measure(it, titleStyle) }

    val tickLength = with(density) { dimensions.tickLength.toPx() }
    val labelPadding = with(density) { dimensions.labelPadding.toPx() }

    fun extentAcross(position: AxisPosition, labels: List<TextLayoutResult>): Float =
        if (labels.isEmpty()) {
            0f
        } else if (position.isHorizontal) {
            labels.maxOf { it.size.height }.toFloat()
        } else {
            labels.maxOf { it.size.width }.toFloat()
        }

    val rotateDomain = domainPosition.isHorizontal &&
        domainAxisConfig.labelOverflow == AxisLabelOverflow.Rotate &&
        measuredDomainLabels.isNotEmpty()

    val domainLabelExtent = if (rotateDomain) {
        // A 45° label occupies roughly its own width in height; using its
        // measured height instead would clip every rotated label.
        measuredDomainLabels.maxOf { it.size.width }.toFloat() * ROTATED_LABEL_FACTOR
    } else {
        extentAcross(domainPosition, measuredDomainLabels)
    }

    val axisMetrics = listOf(
        AxisMetrics(
            position = domainPosition,
            visible = domainAxisConfig.visible,
            labelExtent = domainLabelExtent,
            tickLength = if (domainAxisConfig.showTicks) tickLength else 0f,
            labelPadding = if (domainAxisConfig.showLabels) labelPadding else 0f,
            titleExtent = domainTitle?.let { it.size.height + labelPadding }?.toFloat() ?: 0f,
        ),
        AxisMetrics(
            position = valuePosition,
            visible = valueAxisConfig.visible,
            labelExtent = extentAcross(valuePosition, measuredValueLabels),
            tickLength = if (valueAxisConfig.showTicks) tickLength else 0f,
            labelPadding = if (valueAxisConfig.showLabels) labelPadding else 0f,
            titleExtent = valueTitle?.let { it.size.height + labelPadding }?.toFloat() ?: 0f,
        ),
    )

    val contentPadding = with(density) { dimensions.contentPadding.toPx() }
    val overhang = if (domainPosition.isHorizontal && measuredDomainLabels.isNotEmpty() && !rotateDomain) {
        measuredDomainLabels.maxOf { it.size.width }.toFloat() / 2f
    } else {
        0f
    }

    val layout = computeChartLayout(
        bounds = bounds,
        contentPadding = ChartInsets(
            left = contentPadding,
            top = contentPadding,
            right = contentPadding,
            bottom = contentPadding,
        ),
        axes = axisMetrics,
        labelOverhang = overhang,
    )
    val plot = layout.plotArea
    if (plot.isEmpty) return CartesianGeometry.empty()

    // ---- scales, now that the plot area is known ----------------------------

    // The value axis runs "backwards" in pixels: larger values sit at smaller y
    // on a vertical chart. Building the scale inverted is the only place that
    // fact is encoded — nothing downstream flips a sign.
    val valueScale = if (orientation.isVertical) {
        LinearScale(valueDomain, plot.bottom, plot.top)
    } else {
        LinearScale(valueDomain, plot.left, plot.right)
    }

    val domainStart = if (orientation.isVertical) plot.left else plot.top
    val domainEnd = if (orientation.isVertical) plot.right else plot.bottom

    val categoryPadding = layers.filterIsInstance<ResolvedLayer.Bars>()
        .firstOrNull()?.categoryPadding ?: CategoryScale.DEFAULT_CATEGORY_PADDING

    val domainAxisModel: DomainAxis = when (axisKind) {
        ChartXAxisKind.Category -> DomainAxis.Categories(
            CategoryScale(categories, domainStart, domainEnd, categoryPadding),
        )
        else -> DomainAxis.Continuous(LinearScale(xDomain, domainStart, domainEnd))
    }

    val coordinates = CartesianCoordinates(plot, domainAxisModel, valueScale, orientation)

    // ---- tick positions and label thinning ----------------------------------

    val valueTickPositions = valueTickValues.map(valueScale::scale)
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

    val keptDomainIndices = when (domainAxisConfig.labelOverflow) {
        AxisLabelOverflow.None -> measuredDomainLabels.indices.toList()
        else -> selectLabelIndices(
            count = measuredDomainLabels.size,
            available = domainAvailable,
            labelExtent = domainLabelSpacing,
            maxLabels = domainAxisConfig.maxLabels,
        )
    }

    val valueSpacing = if (measuredValueLabels.isEmpty()) {
        0f
    } else {
        measuredValueLabels.maxOf { it.size.height }.toFloat() + labelPadding
    }
    val keptValueIndices = selectLabelIndices(
        count = measuredValueLabels.size,
        available = if (orientation.isVertical) plot.height else plot.width,
        labelExtent = if (orientation.isVertical) valueSpacing else {
            measuredValueLabels.maxOf { it.size.width }.toFloat() + labelPadding * 2f
        },
        maxLabels = valueAxisConfig.maxLabels,
    )

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
    )
    val measuredValueAxis = MeasuredAxis(
        position = valuePosition,
        config = valueAxisConfig,
        ticks = valueTickPositions,
        labels = keptValueIndices.mapNotNull { index ->
            val layout1 = measuredValueLabels.getOrNull(index) ?: return@mapNotNull null
            val at = valueTickPositions.getOrNull(index) ?: return@mapNotNull null
            MeasuredAxisLabel(layout1, at)
        },
        title = valueTitle,
        rotated = false,
    )

    // ---- layers -------------------------------------------------------------

    val renderers = ArrayList<ChartLayerRenderer>()
    val hitTestable = ArrayList<ChartLayerRenderer>()
    val summaries = ArrayList<ChartLayerSummary>()
    val legendSeries = ArrayList<LegendSeries>()

    renderers += GridLayer(grid, domainTickPositions, valueTickPositions)

    layers.forEachIndexed { layerIndex, layer ->
        when (layer) {
            is ResolvedLayer.Bars -> {
                val (bounds1, aligned) = barBounds[layer] ?: return@forEachIndexed
                val categoryScale = coordinates.categories ?: return@forEachIndexed
                val slices = computeBarSlices(
                    values = aligned.map { it.values },
                    pointIndices = aligned.map { it.sourceIndices },
                    paletteIndices = layer.data.visibleSeries.map { it.paletteIndex },
                    bounds = bounds1,
                    categoryScale = categoryScale,
                    valueScale = valueScale,
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
                    )
                }
                val barLayer = BarLayer(
                    id = "${layer.key}-$layerIndex",
                    series = seriesGeometry,
                    slices = slices,
                    cornerRadiusOverride = layer.cornerRadius,
                    baseline = coordinates.baseline,
                )
                renderers += barLayer
                hitTestable += barLayer
                summaries += barLayer.describe()
                if (layer.valueLabels) {
                    renderers += ValueLabelLayer(
                        id = "${layer.key}-$layerIndex-labels",
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
                        coordinates = coordinates,
                        data = layer.data,
                        categories = categories,
                        missingValuePolicy = layer.missingValuePolicy,
                    )
                }
                val lineLayer = LineLayer(
                    id = "${layer.key}-$layerIndex",
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
                        id = "${layer.key}-$layerIndex-labels",
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
        }
    }

    renderers += SelectionLayer()

    // Colours are resolved in composition, not here: the palette comes from
    // the theme, and baking it into cached geometry would leave a chart showing
    // yesterday's colours after a theme change until its data moved.
    layers.forEach { layer ->
        layer.data.series.forEach { series ->
            legendSeries += LegendSeries(
                seriesId = series.id,
                name = series.name,
                paletteIndex = series.paletteIndex,
                colorOverride = series.color,
                visible = series.visible,
            )
        }
    }

    // `accessibility` shapes only how much detail the summary carries; the
    // summaries themselves are the layers' own factual description.
    val effectiveSummaries = if (accessibility.includeDataPoints) summaries else summaries.map {
        it.copy(entries = emptyList())
    }

    return CartesianGeometry(
        coordinates = coordinates,
        renderers = renderers,
        hitTestable = hitTestable,
        domainAxis = measuredDomainAxis.takeIf { domainAxisConfig.visible },
        valueAxis = measuredValueAxis.takeIf { valueAxisConfig.visible },
        legendSeries = legendSeries.distinctBy { it.seriesId },
        summaries = effectiveSummaries,
        valueFormatter = valueFormatter,
        isEmpty = isEmpty,
    )
}

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

internal fun lineGeometry(
    series: PlotSeries,
    seriesIndex: Int,
    coordinates: CartesianCoordinates,
    data: PlotData,
    categories: List<String>,
    missingValuePolicy: MissingValuePolicy,
): LineSeriesGeometry {
    val points = series.points.map { point ->
        val value = point.y ?: return@map null
        val domainPosition = when (val axis = coordinates.domainAxis) {
            is DomainAxis.Categories -> {
                val index = categories.indexOf(point.x.label())
                if (index < 0) return@map null else axis.scale.positionAt(index)
            }
            is DomainAxis.Continuous -> axis.scale.scale(data.continuousX(point))
        }
        val valuePosition = coordinates.positionOfValue(value)
        val offset = coordinates.pointAt(domainPosition, valuePosition)
        if (!offset.isFinite) null else LinePoint(offset, point.sourceIndex, value)
    }
    val present = points.filterNotNull()
    // `Connect` is the one policy that changes the *path* rather than the
    // values: the gap is closed by joining the surrounding points, so the
    // missing entries are simply absent from what is segmented. `Break` keeps
    // them as holes, which is what splits the line.
    val forSegmentation: List<LinePoint?> =
        if (missingValuePolicy == MissingValuePolicy.Connect) present else points
    return LineSeriesGeometry(
        seriesId = series.id,
        seriesName = series.name,
        seriesIndex = seriesIndex,
        paletteIndex = series.paletteIndex,
        colorOverride = series.color,
        points = points,
        segments = segmentLine(forSegmentation),
        presentPoints = present,
        sortedByDomain = isXOrdered(present),
        items = series.items,
        xValues = series.points.map { it.x },
    )
}

internal fun ChartX.label(): String = when (this) {
    is ChartX.Category -> label
    is ChartX.Numeric -> value.toString()
    is ChartX.Time -> epochMillis.toString()
}

/** A date pattern proportionate to the span the axis covers. */
private fun patternForSpan(spanMillis: Double): String = when {
    spanMillis < 2 * 60 * 60 * 1000.0 -> "HH:mm"
    spanMillis < 3 * 24 * 60 * 60 * 1000.0 -> "d MMM HH:mm"
    spanMillis < 200L * 24 * 60 * 60 * 1000.0 -> "d MMM"
    else -> "MMM yyyy"
}

/** A 45° label's height is about 0.75 of its width, plus room for the descender. */
private const val ROTATED_LABEL_FACTOR = 0.78f

/** Rotated labels can sit about this fraction of their height apart. */
private const val ROTATED_SPACING_FACTOR = 1.6f
