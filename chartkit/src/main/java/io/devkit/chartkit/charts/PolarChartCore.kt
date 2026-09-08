package io.devkit.chartkit.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.accessibility.buildChartSummary
import io.devkit.chartkit.accessibility.describeSelection
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.animation.rememberChartReveal
import io.devkit.chartkit.components.legend.ChartLegend
import io.devkit.chartkit.components.legend.ChartLegendEntry
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.components.overlay.ChartOverlay
import io.devkit.chartkit.coordinate.PolarCoordinates
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartInsets
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.geometry.PolarDirection
import io.devkit.chartkit.geometry.PolarGeometry
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.layout.computePolarLayout
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.AnyChartTooltipData
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.theme.ChartKitTheme

/**
 * One legend row of a polar chart, before its colour is resolved.
 *
 * Polar charts legend their **slices**, not their series: a pie has one series
 * and the reader wants to know which wedge is rent. That is why
 * [io.devkit.chartkit.components.legend.ChartLegendEntry] is keyed by an
 * arbitrary id rather than by a series id — one legend model serves a
 * multi-series line chart and a single-series pie.
 */
internal class PolarLegendItem(
    val id: String,
    val label: String,
    val paletteIndex: Int,
    val colorOverride: Int?,
)

/**
 * The polar chart engine, shared by pie, donut and radial bar.
 *
 * The sibling of [CartesianChartCore], and deliberately *not* a copy of it.
 * Everything that is not coordinate-specific comes from the same place as it
 * does for a line chart: the theme, the animation clock, the legend, the
 * overlay positioning, the selection state, the accessibility summary. What is
 * different here is one layout call, one coordinate construction and one hit
 * test — which is the amount of code a second coordinate system should cost.
 *
 * @param centerContent arbitrary Compose content placed in the middle of the
 *   ring. Laid out by Compose, not rasterised onto the canvas, so it can hold
 *   any composable — and it does not receive pointer input, so a slice under it
 *   is never stolen from.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun PolarChartCore(
    layers: (PolarCoordinates) -> List<ChartLayerRenderer>,
    modifier: Modifier,
    innerRadiusRatio: Float,
    startAngle: Float,
    sweepAngle: Float,
    direction: PolarDirection,
    legend: LegendPosition,
    legendItems: List<PolarLegendItem>,
    animation: ChartAnimation,
    tapSelects: Boolean,
    clearOnTapOutside: Boolean,
    state: ChartState<Any?>,
    valueFormatter: ChartValueFormatter,
    onSelectionChanged: ((AnyChartSelection?) -> Unit)?,
    tooltip: (@Composable (AnyChartTooltipData) -> Unit)?,
    accessibility: ChartAccessibility,
    accessibilitySummary: (() -> String)?,
    isEmpty: Boolean,
    isLoading: Boolean,
    error: Throwable?,
    loadingContent: @Composable () -> Unit,
    emptyContent: @Composable () -> Unit,
    errorContent: @Composable (Throwable) -> Unit,
    centerContent: (@Composable () -> Unit)?,
    radiusInset: Float = 0f,
) {
    val theme = ChartKitTheme.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val reveal = rememberChartReveal(animation)

    val legendSlot: @Composable (Modifier, Boolean) -> Unit = { legendModifier, vertical ->
        val entries = remember(legendItems, theme.colors) {
            legendItems.map { item ->
                ChartLegendEntry(
                    seriesId = item.id,
                    name = item.label,
                    color = item.colorOverride?.let { Color(it) }
                        ?: theme.colors.seriesColor(item.paletteIndex),
                    visible = true,
                )
            }
        }
        // Display-only: hiding one slice of a part-to-whole chart would
        // renormalise the rest, so the remaining shares would change to
        // percentages of a different total. That is a different chart, not a
        // filtered one.
        ChartLegend(entries = entries, modifier = legendModifier, vertical = vertical)
    }

    Column(modifier.defaultMinSize(minHeight = theme.dimensions.defaultChartHeight)) {
        if (legend == LegendPosition.Top) {
            legendSlot(Modifier.fillMaxWidth().padding(bottom = theme.dimensions.labelPadding), false)
        }
        Row(Modifier.weight(1f, fill = true)) {
            if (legend == LegendPosition.Start) {
                legendSlot(Modifier.padding(end = theme.dimensions.legendItemSpacing), true)
            }
            Box(Modifier.weight(1f, fill = true)) {
                when {
                    error != null -> errorContent(error)
                    isLoading -> loadingContent()
                    isEmpty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        emptyContent()
                    }
                    else -> PolarPlot(
                        layers = layers,
                        innerRadiusRatio = innerRadiusRatio,
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        direction = direction,
                        reveal = reveal,
                        tapSelects = tapSelects,
                        clearOnTapOutside = clearOnTapOutside,
                        state = state,
                        valueFormatter = valueFormatter,
                        onSelectionChanged = onSelectionChanged,
                        tooltip = tooltip,
                        accessibility = accessibility,
                        accessibilitySummary = accessibilitySummary,
                        centerContent = centerContent,
                        radiusInset = radiusInset,
                        density = density,
                        textMeasurer = textMeasurer,
                    )
                }
            }
            if (legend == LegendPosition.End) {
                legendSlot(Modifier.padding(start = theme.dimensions.legendItemSpacing), true)
            }
        }
        if (legend == LegendPosition.Bottom) {
            legendSlot(Modifier.fillMaxWidth().padding(top = theme.dimensions.labelPadding), false)
        }
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun PolarPlot(
    layers: (PolarCoordinates) -> List<ChartLayerRenderer>,
    innerRadiusRatio: Float,
    startAngle: Float,
    sweepAngle: Float,
    direction: PolarDirection,
    reveal: Float,
    tapSelects: Boolean,
    clearOnTapOutside: Boolean,
    state: ChartState<Any?>,
    valueFormatter: ChartValueFormatter,
    onSelectionChanged: ((AnyChartSelection?) -> Unit)?,
    tooltip: (@Composable (AnyChartTooltipData) -> Unit)?,
    accessibility: ChartAccessibility,
    accessibilitySummary: (() -> String)?,
    centerContent: (@Composable () -> Unit)?,
    radiusInset: Float,
    density: androidx.compose.ui.unit.Density,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    val theme = ChartKitTheme.current
    var size by remember { mutableStateOf(IntSize.Zero) }

    // Coordinates and layers are built once per layout, not per frame — the
    // same caching rule the Cartesian engine follows, for the same reason.
    val coordinates = remember(
        size, innerRadiusRatio, startAngle, sweepAngle, direction, theme, density, radiusInset,
    ) {
        val bounds = ChartRect.fromSize(size.width.toFloat(), size.height.toFloat())
        val padding = with(density) { theme.dimensions.polarPadding.toPx() }
        val layout = computePolarLayout(
            bounds = bounds,
            contentPadding = ChartInsets(padding, padding, padding, padding),
        )
        val plot = layout.plotArea
        // A chart that writes labels outside its own ring has to reserve the
        // room first, and the reserve is *measured* by the chart rather than
        // guessed at — the same rule the Cartesian axes follow. The plot stays
        // the full square, the labels live in the gutter, and only the ring
        // shrinks.
        //
        // Floored at a fraction of the available radius: a pathologically long
        // metric name should cost its own label, not the whole chart.
        val available = PolarGeometry.radiusWithin(plot)
        val outer = (available - radiusInset.coerceAtLeast(0f))
            .coerceAtLeast(available * MIN_RADIUS_FRACTION)
        PolarCoordinates(
            plotArea = plot,
            center = ChartOffset(plot.centerX, plot.centerY),
            innerRadius = outer * innerRadiusRatio.coerceIn(0f, MAX_INNER_RATIO),
            outerRadius = outer,
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            direction = direction,
        )
    }

    val renderers = remember(coordinates, layers) { layers(coordinates) }
    val selection = state.selection

    val renderContext = ChartRenderContext(
        coordinates = coordinates,
        colors = theme.colors,
        typography = theme.typography,
        dimensions = theme.dimensions,
        density = density,
        textMeasurer = textMeasurer,
        reveal = reveal,
        selection = selection,
    )

    val summaries = remember(renderers) { renderers.flatMap { it.describe() } }
    val summary = remember(summaries, accessibilitySummary, accessibility) {
        accessibilitySummary?.invoke() ?: buildChartSummary(accessibility, summaries, valueFormatter)
    }
    val selectionText = selection?.let {
        val polar = it.polar
        if (polar == null) {
            describeSelection(it.seriesName, it.xLabel, it.y, valueFormatter, multiSeries = false)
        } else {
            // The share is what a part-to-whole chart communicates, so it is
            // what a screen reader hears alongside the raw value.
            "${polar.label}: ${valueFormatter.format(it.y)}, " +
                io.devkit.chartkit.layer.polar.percentage(polar.fraction) + "."
        }
    }

    val tooltipData: AnyChartTooltipData? = remember(selection, renderers) {
        selection?.let { selected ->
            val entries = renderers.flatMap { it.tooltipEntriesAt(selected, renderContext) }
            ChartTooltipData(
                selection = selected,
                entries = entries.ifEmpty {
                    listOf(
                        ChartTooltipEntry(
                            seriesId = selected.seriesId,
                            seriesName = selected.seriesName,
                            value = selected.y,
                            item = selected.item,
                            paletteIndex = selected.pointIndex,
                        ),
                    )
                },
                anchor = selected.position,
                xLabel = selected.polar?.label ?: selected.xLabel,
                valueFormatter = valueFormatter,
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (!tapSelects) {
                        Modifier
                    } else {
                        Modifier.pointerInput(coordinates, renderers) {
                            detectTapGestures { offset ->
                                val point = ChartOffset(offset.x, offset.y)
                                val hit = renderers.firstNotNullOfOrNull {
                                    it.hitTest(point, renderContext, HitTestMode.Contains)
                                }
                                when {
                                    hit != null && hit != state.selection -> {
                                        state.selection = hit
                                        onSelectionChanged?.invoke(hit)
                                    }
                                    hit == null && clearOnTapOutside -> {
                                        state.clearSelection()
                                        onSelectionChanged?.invoke(null)
                                    }
                                }
                            }
                        }
                    },
                )
                .clearAndSetSemantics {
                    contentDescription = listOfNotNull(summary, selectionText).joinToString(" ")
                    if (selectionText != null) liveRegion = LiveRegionMode.Polite
                },
        ) {
            if (!coordinates.isDrawable) return@Canvas
            renderers.forEach { it.draw(this, renderContext) }
        }

        if (centerContent != null) {
            CenterContent(coordinates = coordinates, density = density, content = centerContent)
        }

        if (tooltipData != null && tooltip != null) {
            ChartOverlay(
                anchor = tooltipData.anchor,
                bounds = coordinates.plotArea,
                gap = with(density) { theme.dimensions.tooltipPadding.toPx() },
            ) {
                tooltip(tooltipData)
            }
        }
    }
}

/**
 * Arbitrary Compose content in the middle of a donut.
 *
 * Laid out by Compose inside a box the size of the hole, so it can be anything
 * — a total, a percentage, an icon, a small KPI — and so it wraps and scales
 * like the rest of the app's text rather than being rasterised at a fixed size
 * onto the canvas.
 *
 * It does **not** take pointer input. The hole belongs to no slice, so nothing
 * is stolen from the chart; and content that captured taps would make the
 * middle of a donut a dead zone for the chart's own gestures.
 */
@Composable
private fun CenterContent(
    coordinates: PolarCoordinates,
    density: androidx.compose.ui.unit.Density,
    content: @Composable () -> Unit,
) {
    // The largest square inside the hole. A circle of radius r contains a
    // square of side r√2, and staying inside it is what keeps a long total
    // from spilling over the ring.
    val side = with(density) { (coordinates.innerRadius * INSCRIBED_SQUARE).toDp() }
    if (side <= 0.dp) return

    // The ring is centred in the plot and the plot is centred in this box, so
    // centring the content is enough — no offset arithmetic, and nothing to
    // get wrong when the chart is resized.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(side), contentAlignment = Alignment.Center) { content() }
    }
}

/** A donut's hole never exceeds this fraction of the outer radius. */
private const val MAX_INNER_RATIO = 0.95f

/** Side of the largest square inscribed in a circle of radius 1, times 2. */
private const val INSCRIBED_SQUARE = 1.41421356f

/** However much a chart reserves for labels, this much ring always remains. */
private const val MIN_RADIUS_FRACTION = 0.45f
