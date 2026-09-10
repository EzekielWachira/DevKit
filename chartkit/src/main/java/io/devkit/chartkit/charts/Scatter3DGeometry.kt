package io.devkit.chartkit.charts

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartUnit
import io.devkit.chartkit.coordinate.CartesianCoordinates
import io.devkit.chartkit.coordinate.DomainAxis
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.layer.three.Scatter3DAxis
import io.devkit.chartkit.layer.three.Scatter3DAxisFurniture
import io.devkit.chartkit.layer.three.Scatter3DLayer
import io.devkit.chartkit.layer.three.Scatter3DPoint
import io.devkit.chartkit.layer.three.Scatter3DSeriesInfo
import io.devkit.chartkit.scale.LinearScale
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.scale.apply
import io.devkit.chartkit.theme.ChartDimensions
import io.devkit.chartkit.theme.ChartTypography

/**
 * The 3D scatter layer's half of the geometry builder.
 *
 * Beside [buildColumns3DLayer] and for the same reason: a 3D chart measures its
 * own axis furniture, and three axes' worth of that is a hundred lines nobody
 * wants inside [CartesianGeometry]'s main function.
 */

/**
 * The renderer for one 3D scatter layer, with its three axes already measured.
 *
 * ### Where each of the three scales comes from
 *
 * X and Y are the chart's **own** scales, handed in through [coords]. The chart
 * core has already applied their domain policies, their log or symlog
 * transforms, any viewport zoom and any annotation that widened them; asking
 * for the fraction of a value is asking the same object the 2D layers ask, so a
 * 3D scatter and a 2D one over the same data cannot disagree about where a
 * value sits.
 *
 * Z is built here, from the caller's Z axis configuration, using exactly the
 * same primitives — [io.devkit.chartkit.scale.DomainPolicy.apply], a
 * [LinearScale] carrying the axis' own [io.devkit.chartkit.scale.ScaleTransform],
 * and the transform's own tick generator. It is built here rather than by the
 * chart core because the core resolves a scale by mapping a domain onto a
 * *pixel range at an edge of the plot*, and there is no edge of a rectangle
 * that means "away from the reader". The domain arithmetic is shared; only the
 * range is different, and the range for Z is `0..1` because a fraction is all
 * the volume needs.
 *
 * Returns `null` when the chart has no continuous domain axis, which is the
 * first frame before anything has been laid out — and permanently for a caller
 * who somehow bound a scatter to a category axis, which has no fractions.
 */
@Suppress("LongParameterList", "LongMethod")
internal fun buildScatter3DLayer(
    id: String,
    layer: ResolvedLayer.Scatter3D,
    coords: CartesianCoordinates,
    plot: io.devkit.chartkit.geometry.ChartRect,
    valueFormatter: ChartValueFormatter,
    density: Density,
    textMeasurer: TextMeasurer,
    typography: ChartTypography,
    dimensions: ChartDimensions,
    markerRadiusPx: Float,
    locale: java.util.Locale,
): Scatter3DLayer? {
    val xScale = (coords.domainAxis as? DomainAxis.Continuous)?.scale ?: return null
    val yScale = coords.valueScale

    // The one scale the chart core has no place to build. Same policy, same
    // transform, same tick generator; a `0..1` range because the volume takes
    // fractions and not pixels.
    val zTransform = layer.zAxis.scale.transform()
    val zDomain = (layer.zAxis.domain ?: layer.zDomain).apply(
        NumericDomain.of(layer.visibleZValues()),
    )
    val zScale = LinearScale(
        domain = zDomain,
        rangeStart = 0f,
        rangeEnd = 1f,
        transform = zTransform,
    )

    val gap = with(density) { dimensions.chart3DLabelGap.toPx() }

    // X and Z fall back to the *same* generator the flat axes use: a decimal
    // formatter with as many places as this axis' own ticks actually need. The
    // raw formatter would be wrong twice over — an axis labelled
    // `78.6289791432` is unreadable, and a tooltip reporting a measurement to
    // ten decimal places claims a precision nobody measured. Y falls back to
    // the chart's own value formatter, which the core has already resolved.
    val x = measureScatter3DAxis(
        scale = xScale,
        config = layer.xAxis,
        unit = layer.xUnit,
        fallbackFormatter = null,
        locale = locale,
        textMeasurer = textMeasurer,
        typography = typography,
    )
    val y = measureScatter3DAxis(
        scale = yScale,
        config = layer.yAxis,
        unit = layer.yUnit,
        fallbackFormatter = valueFormatter,
        locale = locale,
        textMeasurer = textMeasurer,
        typography = typography,
    )
    val z = measureScatter3DAxis(
        scale = zScale,
        config = layer.zAxis,
        unit = layer.zUnit,
        fallbackFormatter = null,
        locale = locale,
        textMeasurer = textMeasurer,
        typography = typography,
    )

    // What each side of the plot has to give up to the labels. A title on a 3D
    // chart is written upright beside the numbers, so what it costs a vertical
    // gutter is its **width** and a horizontal one its height — the same
    // reasoning [measureColumns3DAxes] sets out, applied to three axes.
    val leftLabels = (y.labels.maxOfOrNull { it.size.width }?.toFloat() ?: 0f).let {
        if (it > 0f) it + gap else 0f
    }
    val bottomLabels = (x.labels.maxOfOrNull { it.size.height }?.toFloat() ?: 0f).let {
        if (it > 0f) it + gap else 0f
    }
    val rightLabels = (z.labels.maxOfOrNull { it.size.width }?.toFloat() ?: 0f).let {
        if (it > 0f) it + gap else 0f
    }

    // ### The side titles are turned, and that is what makes three axes fit
    //
    // Tick labels are drawn upright, because a number read at an angle is a
    // number read wrongly and §53 is right about that. A *title* is a word, and
    // a word turned through ninety degrees is still a word — which is exactly
    // what the flat axis renderer already does with its Start and End titles.
    //
    // It matters more here than it does there, because a 3D plot has titles on
    // three sides at once. Measured upright, "Income (£)" on the left and
    // "Satisfaction" on the right take between them the best part of half the
    // canvas, and the volume they label is drawn in what is left. Turned, each
    // costs a line height instead of a phrase width, and the chart gets its
    // space back without giving up a single label.
    //
    // The caps below are a backstop rather than the mechanism: a six-figure
    // tick label on a narrow tile can still ask for more than a fifth of the
    // width, and a title that would not fit even turned is dropped rather than
    // drawn over its own numbers.
    val sideCap = plot.width * MAX_SIDE_GUTTER
    val bottomCap = plot.height * MAX_BOTTOM_GUTTER
    val yTitleFits = y.title != null && leftLabels + y.title.size.height + gap <= sideCap
    val zTitleFits = z.title != null && rightLabels + z.title.size.height + gap <= sideCap
    val xTitleFits = x.title != null && bottomLabels + x.title.size.height + gap <= bottomCap

    val furniture = Scatter3DAxisFurniture(
        x = if (xTitleFits) x else x.withoutTitle(),
        y = if (yTitleFits) y else y.withoutTitle(),
        z = if (zTitleFits) z else z.withoutTitle(),
        leftGutter = minOf(
            leftLabels + (if (yTitleFits) y.title!!.size.height + gap else 0f),
            sideCap,
        ),
        bottomGutter = minOf(
            bottomLabels + (if (xTitleFits) x.title!!.size.height + gap else 0f),
            bottomCap,
        ),
        rightGutter = minOf(
            rightLabels + (if (zTitleFits) z.title!!.size.height + gap else 0f),
            sideCap,
        ),
        leftLabelExtent = leftLabels,
        bottomLabelExtent = bottomLabels,
        rightLabelExtent = rightLabels,
    )

    val info = layer.data.visibleSeries.map { series ->
        val zValues = layer.zValues[series.id].orEmpty()
        val sizes = layer.sizes?.get(series.id).orEmpty()
        val colorValues = layer.colorValues?.get(series.id).orEmpty()
        Scatter3DSeriesInfo(
            seriesId = series.id,
            seriesName = series.name,
            paletteIndex = series.paletteIndex,
            colorOverride = series.color,
            points = series.points.mapNotNull { point ->
                // A point missing any one of its three coordinates has no
                // position in the volume, and inventing one for it would put a
                // mark the reader will measure at a place nothing measured.
                val value = point.y ?: return@mapNotNull null
                val depth = zValues.getOrNull(point.sourceIndex) ?: return@mapNotNull null
                val across = (point.x as? io.devkit.chartkit.model.ChartX.Numeric)?.value
                    ?: return@mapNotNull null
                Scatter3DPoint(
                    sourceIndex = point.sourceIndex,
                    x = across,
                    y = value,
                    z = depth,
                    size = sizes.getOrNull(point.sourceIndex),
                    colorValue = colorValues.getOrNull(point.sourceIndex),
                    item = series.itemAt(point.sourceIndex),
                )
            },
        )
    }

    return Scatter3DLayer(
        id = id,
        series = info,
        xScale = xScale,
        yScale = yScale,
        zScale = zScale,
        sceneDepth = layer.sceneDepth,
        cameraProvider = layer.camera,
        projection = layer.projection,
        lighting = layer.lighting,
        frame = layer.frame,
        gridPlanes = layer.gridPlanes,
        fit = layer.fit,
        marker = layer.marker,
        markerRadiusPx = markerRadiusPx,
        sizeScale = layer.sizeScale,
        colorScale = layer.colorScale,
        renderMode = layer.renderMode,
        guides = layer.guides,
        axisFurniture = furniture,
        onDiagnostics = layer.onDiagnostics,
        debug = layer.debug,
        valueAxisId = layer.valueAxisId,
    )
}

/**
 * One axis' ticks, labels and title, measured once per layout.
 *
 * All three axes go through this, which is the point: laying out the Z axis
 * differently from X and Y is how a library ends up with a depth axis whose
 * numbers round differently from the ones beside it.
 */
@Suppress("LongParameterList")
private fun measureScatter3DAxis(
    scale: LinearScale,
    config: ChartAxis,
    unit: ChartUnit,
    fallbackFormatter: ChartValueFormatter?,
    locale: java.util.Locale,
    textMeasurer: TextMeasurer,
    typography: ChartTypography,
): Scatter3DAxis {
    // Ticks first: the fallback formatter is derived from them, because how
    // many decimal places an axis needs is a property of the interval it spans
    // and not of any one value on it.
    val allTicks = (config.ticks ?: scale.ticks(config.tickCount)).filter { it.isFinite() }
    val formatter = config.valueFormatter
        ?: fallbackFormatter
        ?: io.devkit.chartkit.formatter.ChartNumberFormatters.forTicks(allTicks, locale)
    val ticks = if (config.visible && config.showLabels) allTicks else emptyList()
    // The unit is written on the axis *title* and not repeated on every tick,
    // which is what the flat axes do — twelve labels each carrying "°C" is
    // eleven repetitions of a fact the title already stated.
    val labels = ticks.map { textMeasurer.measure(formatter.format(it), typography.axisLabel) }
    val name = config.title?.takeIf { it.isNotBlank() }
    val titleText = name?.let { base ->
        unit.symbol?.takeIf { it.isNotBlank() }?.let { "$base ($it)" } ?: base
    }
    return Scatter3DAxis(
        scale = scale,
        ticks = ticks,
        labels = labels,
        title = titleText
            ?.takeIf { config.visible }
            ?.let { textMeasurer.measure(it, typography.axisTitle) },
        displayName = name,
        formatter = formatter,
        unitSymbol = unit.symbol,
    )
}

/**
 * The share of the plot one side may give up to axis text.
 *
 * Two sides at this fraction leave the volume a little under half the width,
 * which at the angles a 3D plot is read from is enough — the silhouette of a
 * turned box is wider than it is tall, so the height is rarely the binding
 * constraint. Larger and the annotations start to dominate; smaller and a
 * six-figure tick label has nowhere to go.
 */
private const val MAX_SIDE_GUTTER = 0.2f

/** The same, below the plot, where the text is a line high rather than words wide. */
private const val MAX_BOTTOM_GUTTER = 0.22f
