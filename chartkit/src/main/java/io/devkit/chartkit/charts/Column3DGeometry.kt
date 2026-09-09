package io.devkit.chartkit.charts

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.coordinate.CartesianCoordinates
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.BarStacking
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.layer.three.Column3DAxisFurniture
import io.devkit.chartkit.layer.three.Column3DLayer
import io.devkit.chartkit.layer.three.Column3DSeriesInfo
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.theme.ChartDimensions
import io.devkit.chartkit.theme.ChartTypography
import kotlin.math.ceil
import kotlin.math.max

/**
 * The 3D column layer's half of the geometry builder.
 *
 * Kept beside [CartesianGeometry]'s other per-layer construction rather than
 * inside it, because a 3D column needs one thing no other layer does — its own
 * measured axis furniture — and folding that into the shared builder would put
 * a hundred lines about label gutters in a function that is already the longest
 * in the library.
 */

/**
 * Every stack's value extent, merged.
 *
 * Merged and not maximised across stacks: two stacks standing side by side both
 * have to fit on one axis, so the axis has to reach the taller of them. The
 * per-stack call is what makes them *separate* piles rather than one — see
 * [BarStacking], which is called once per stack and is the only place stacking
 * happens in ChartKit.
 */
internal fun columns3DValueDomain(
    layer: ResolvedLayer.Columns3D,
    categories: List<String>,
): NumericDomain? {
    val byStack = layer.data.visibleSeries.groupBy { layer.stackOf(it.id) }
    val domains = byStack.values.mapNotNull { members ->
        val aligned = members.map { alignToCategories(it, categories).values }
        BarStacking.domainOf(BarStacking.bounds(aligned, layer.grouping))
    }
    if (domains.isEmpty()) return null
    return NumericDomain(
        min = domains.minOf { it.min },
        max = domains.maxOf { it.max },
    )
}

/**
 * The renderer for one 3D column layer, with its axis labels already measured.
 *
 * Returns `null` when the chart has no category axis to stand on, which is the
 * first frame before anything has been laid out.
 */
@Suppress("LongParameterList")
internal fun buildColumns3DLayer(
    id: String,
    layer: ResolvedLayer.Columns3D,
    categories: List<String>,
    coords: CartesianCoordinates,
    plot: ChartRect,
    valueFormatter: ChartValueFormatter,
    density: Density,
    textMeasurer: TextMeasurer,
    typography: ChartTypography,
    dimensions: ChartDimensions,
): Column3DLayer? {
    val categoryScale = coords.categories ?: return null
    val aligned = layer.data.visibleSeries.map { alignToCategories(it, categories) }

    val info = layer.data.visibleSeries.mapIndexed { index, series ->
        Column3DSeriesInfo(
            seriesId = series.id,
            seriesName = series.name,
            paletteIndex = series.paletteIndex,
            colorOverride = series.color,
            stackId = layer.stackOf(series.id),
            values = aligned[index].values,
            items = series.items,
            sourceIndices = aligned[index].sourceIndices,
        )
    }

    val furniture = measureColumns3DAxes(
        layer = layer,
        categories = categories,
        coords = coords,
        plot = plot,
        valueFormatter = valueFormatter,
        density = density,
        textMeasurer = textMeasurer,
        typography = typography,
        dimensions = dimensions,
    )

    return Column3DLayer(
        id = id,
        categories = categories,
        series = info,
        grouping = layer.grouping,
        arrangement = layer.arrangement,
        depth = layer.depth,
        groupPadding = layer.groupPadding,
        depthGap = layer.depthGap,
        // The chart's own scale, transform and domain, as a function of a
        // value. A logarithmic 3D chart therefore needs no code in the 3D
        // package at all: the fraction it returns is already in log space.
        valueFraction = { value -> coords.valueScale.fraction(value) },
        categoryCentres = List(categories.size) { categoryScale.positionAt(it) },
        bandWidth = categoryScale.innerBandWidth,
        cameraProvider = layer.camera,
        projection = layer.projection,
        lighting = layer.lighting,
        frame = layer.frame,
        axisFurniture = furniture,
        labelPlacement = layer.labels,
        formatter = valueFormatter,
        onDiagnostics = layer.onDiagnostics,
        debug = layer.debug,
        valueAxisId = layer.valueAxisId,
    )
}

/**
 * The axis labels a 3D chart writes for itself, measured once per layout.
 *
 * ### Why the flat axis renderer is not used
 *
 * A 2D axis is a straight line down the side of the plot, and its labels sit at
 * pixel heights the value scale produced. Under a camera, the height a value
 * occupies on screen depends on where in the scene it is — the plot's left edge
 * is no longer vertical, and the value `50` is at a different screen row at the
 * front of the chart than at the back. Drawing the flat axis anyway would put
 * every label a few pixels away from the thing it names: close enough to look
 * right and far enough to be wrong, which is the worst available outcome.
 *
 * So the ticks are placed in *world* space and projected with everything else,
 * and only the text is drawn upright. The values, the tick count, the formatter
 * and the titles all still come from the caller's own [ChartAxis].
 */
@Suppress("LongParameterList")
private fun measureColumns3DAxes(
    layer: ResolvedLayer.Columns3D,
    categories: List<String>,
    coords: CartesianCoordinates,
    plot: ChartRect,
    valueFormatter: ChartValueFormatter,
    density: Density,
    textMeasurer: TextMeasurer,
    typography: ChartTypography,
    dimensions: ChartDimensions,
): Column3DAxisFurniture {
    val gap = with(density) { dimensions.chart3DLabelGap.toPx() }
    val labelPadding = with(density) { dimensions.labelPadding.toPx() }

    val valueTicks = if (layer.valueAxis.visible && layer.valueAxis.showLabels) {
        (layer.valueAxis.ticks ?: coords.valueScale.ticks(layer.valueAxis.tickCount))
            .filter { it.isFinite() }
    } else {
        emptyList()
    }
    val valueLabels = valueTicks.map { textMeasurer.measure(valueFormatter.format(it), typography.axisLabel) }
    val valueFractions = valueTicks.map { coords.valueScale.fraction(it) }

    val categoryFormatter = layer.categoryAxis.categoryFormatter
    val measuredCategories = if (layer.categoryAxis.visible && layer.categoryAxis.showLabels) {
        categories.map { textMeasurer.measure(categoryFormatter?.invoke(it) ?: it, typography.axisLabel) }
    } else {
        emptyList()
    }

    // Thinned by measurement, not by a guess at how many fit. The band a label
    // has is the plot's width over the category count; a label wider than that
    // collides with its neighbour whatever the chart's size, and showing every
    // n-th is the same answer the 2D axis reaches for the same reason.
    val widest = measuredCategories.maxOfOrNull { it.size.width }?.toFloat() ?: 0f
    val perCategory = if (categories.isEmpty()) 0f else plot.width / categories.size
    val step = if (perCategory <= 0f || widest <= 0f) {
        1
    } else {
        max(1, ceil((widest + labelPadding) / perCategory).toInt())
    }
    val cap = layer.categoryAxis.maxLabels
    val effectiveStep = if (cap != null && categories.isNotEmpty()) {
        max(step, ceil(categories.size.toDouble() / cap).toInt())
    } else {
        step
    }
    // Every n-th band, then the last one — which is what tells a reader where
    // the axis ends. Keeping the last unconditionally is not enough: with a
    // step of two over twenty bands it lands one band after the previous kept
    // label and the two collide, which is exactly the overlap the thinning
    // exists to prevent. So the neighbour it would collide with is dropped
    // first, and the last label always survives.
    val kept = HashSet<Int>()
    if (measuredCategories.isNotEmpty()) {
        var index = 0
        while (index < measuredCategories.size) {
            kept += index
            index += effectiveStep
        }
        val last = measuredCategories.lastIndex
        kept.removeAll { it > last - effectiveStep && it != last }
        kept += last
    }
    val categoryLabels: List<TextLayoutResult?> = measuredCategories.mapIndexed { index, label ->
        if (index in kept) label else null
    }

    val valueTitle = layer.valueAxis.title
        ?.takeIf { it.isNotBlank() && layer.valueAxis.visible }
        ?.let { textMeasurer.measure(it, typography.axisTitle) }
    val categoryTitle = layer.categoryAxis.title
        ?.takeIf { it.isNotBlank() && layer.categoryAxis.visible }
        ?.let { textMeasurer.measure(it, typography.axisTitle) }

    // An axis title on a 3D chart is written upright, beside the numbers, so
    // what it costs the gutter is its **width** and not its line height. The
    // flat axis renderer rotates its titles and reserves the height instead;
    // reserving the height here would leave a long title nowhere to go and it
    // would end up printed over the tick labels.
    val valueLabelExtent = (valueLabels.maxOfOrNull { it.size.width }?.toFloat() ?: 0f) + gap
    val categoryLabelExtent =
        (categoryLabels.filterNotNull().maxOfOrNull { it.size.height }?.toFloat() ?: 0f) + gap
    val valueGutter = valueLabelExtent + (valueTitle?.let { it.size.width + gap } ?: 0f)
    val categoryGutter = categoryLabelExtent + (categoryTitle?.let { it.size.height + gap } ?: 0f)

    return Column3DAxisFurniture(
        valueFractions = valueFractions,
        valueLabels = valueLabels,
        categoryLabels = categoryLabels,
        valueTitle = valueTitle,
        categoryTitle = categoryTitle,
        valueGutter = valueGutter,
        categoryGutter = categoryGutter,
        valueLabelExtent = valueLabelExtent,
        categoryLabelExtent = categoryLabelExtent,
    )
}
