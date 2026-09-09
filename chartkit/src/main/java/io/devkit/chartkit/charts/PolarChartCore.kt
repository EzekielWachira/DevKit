package io.devkit.chartkit.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
 * One legend row of a chart whose key is not its series.
 *
 * Polar charts legend their **slices**, not their series: a pie has one series
 * and the reader wants to know which wedge is rent. A treemap, a Sankey diagram
 * and a funnel are the same — their key names categories, nodes or stages. That
 * is why [io.devkit.chartkit.components.legend.ChartLegendEntry] is keyed by an
 * arbitrary id rather than by a series id: one legend model serves a
 * multi-series line chart, a single-series pie and a flow diagram.
 */
internal class ChartKeyItem(
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
    legendItems: List<ChartKeyItem>,
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
    renderMode: io.devkit.chartkit.render.ChartRenderMode =
        io.devkit.chartkit.render.ChartRenderMode.Interactive,
    staticOptions: io.devkit.chartkit.render.ChartStaticOptions =
        io.devkit.chartkit.render.ChartStaticOptions.Default,
    onDoubleTap: ((AnyChartSelection?) -> Unit)? = null,
    ringThickness: Float? = null,
    /**
     * Whether the ring is sized and placed by the arc it actually draws.
     *
     * Off for a pie or a donut, which occupy the whole circle and belong in the
     * middle of their square. On for a gauge, whose sweep may use half of it:
     * a semicircular dial centred in the square its full circle would need
     * wastes the entire bottom half, and the fix is to fit the *arc's* box
     * rather than the circle's. See
     * [io.devkit.chartkit.gauge.GaugeGeometry.fit].
     */
    fitToSweep: Boolean = false,
    /**
     * Called with the chart angle a pointer is at, for a chart that reads a
     * value out of the dial rather than selecting a mark on it.
     *
     * `null` leaves the ordinary tap-to-select behaviour alone, which is what
     * every polar chart but an adjustable gauge wants.
     */
    onAngleAt: ((Float) -> Unit)? = null,
    /** Whether a drag continues to report angles, or only the initial press. */
    dragAngles: Boolean = false,
    /** Extra semantics for an adjustable gauge — range info and its actions. */
    semantics: (androidx.compose.ui.semantics.SemanticsPropertyReceiver.() -> Unit)? = null,
    plotModifier: Modifier = Modifier,
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
                        renderMode = renderMode,
                        staticOptions = staticOptions,
                        onDoubleTap = onDoubleTap,
                        ringThickness = ringThickness,
                        fitToSweep = fitToSweep,
                        onAngleAt = onAngleAt,
                        dragAngles = dragAngles,
                        semantics = semantics,
                        plotModifier = plotModifier,
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
    renderMode: io.devkit.chartkit.render.ChartRenderMode,
    staticOptions: io.devkit.chartkit.render.ChartStaticOptions,
    onDoubleTap: ((AnyChartSelection?) -> Unit)?,
    ringThickness: Float?,
    fitToSweep: Boolean,
    onAngleAt: ((Float) -> Unit)?,
    dragAngles: Boolean,
    semantics: (androidx.compose.ui.semantics.SemanticsPropertyReceiver.() -> Unit)?,
    plotModifier: Modifier,
    density: androidx.compose.ui.unit.Density,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    val theme = ChartKitTheme.current
    var size by remember { mutableStateOf(IntSize.Zero) }

    // Coordinates and layers are built once per layout, not per frame — the
    // same caching rule the Cartesian engine follows, for the same reason.
    val coordinates = remember(
        size, innerRadiusRatio, startAngle, sweepAngle, direction, theme, density, radiusInset,
        ringThickness, fitToSweep,
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
        val squareOuter = (available - radiusInset.coerceAtLeast(0f))
            .coerceAtLeast(available * MIN_RADIUS_FRACTION)

        // A chart whose arc is not the whole circle can use the space its
        // missing part would have taken. The arc's own box is fitted to the
        // plot, which both grows the radius and moves the centre — a
        // semicircular dial ends up wide and bottom-pivoted rather than small
        // and floating above a gap.
        val fit = if (fitToSweep) {
            io.devkit.chartkit.gauge.GaugeGeometry.fit(
                bounds = plot,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                reserve = radiusInset.coerceAtLeast(0f),
            )
        } else {
            null
        }
        val outer = fit?.radius?.takeIf { it > 0f } ?: squareOuter
        PolarCoordinates(
            plotArea = plot,
            center = fit?.center ?: ChartOffset(plot.centerX, plot.centerY),
            // An absolute ring width where a chart stated one — a gauge's arc
            // is a stroke of a given thickness, not a fraction of whatever
            // radius the layout produced — and otherwise the ratio, which is
            // what a donut's hole is naturally expressed as.
            innerRadius = ringThickness
                ?.let { (outer - it).coerceIn(0f, outer * MAX_INNER_RATIO) }
                ?: (outer * innerRadiusRatio.coerceIn(0f, MAX_INNER_RATIO)),
            outerRadius = outer,
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            direction = direction,
        )
    }

    val renderers = remember(coordinates, layers) { layers(coordinates) }
    // A gesture cannot have produced a selection in a static render, so the
    // only one that can exist is the caller's own — and whether it is drawn is
    // their decision, not the engine's.
    val selection = state.selection?.takeIf { !renderMode.isStatic || staticOptions.showSelection }

    val renderContext = ChartRenderContext(
        coordinates = coordinates,
        colors = theme.colors,
        typography = theme.typography,
        dimensions = theme.dimensions,
        density = density,
        textMeasurer = textMeasurer,
        // Settled, always, in a static render: nothing about an exported
        // picture may depend on a clock that is still running, or two captures
        // of the same chart would differ.
        reveal = if (renderMode.isStatic) 1f else reveal,
        selection = selection,
        renderMode = renderMode,
    )

    val summaries = remember(renderers) { renderers.flatMap { it.describe() } }
    val summary = remember(summaries, accessibilitySummary, accessibility) {
        accessibilitySummary?.invoke() ?: buildChartSummary(accessibility, summaries, valueFormatter)
    }
    val selectionText = selection?.let { selected ->
        renderers.firstNotNullOfOrNull { it.describeSelection(selected, valueFormatter) }
    } ?: selection?.let {
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
                // No pointer input at all in a static render. Not "gestures
                // that do nothing": a modifier that consumed events would still
                // stop a parent from scrolling.
                // An adjustable dial reads a *value* out of the pointer's
                // angle rather than selecting a mark, so it takes its own
                // gesture path: a press reports immediately and a drag keeps
                // reporting, which is what makes the needle follow a finger.
                .then(
                    if (renderMode.isStatic || onAngleAt == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(coordinates, onAngleAt, dragAngles) {
                            fun report(offset: androidx.compose.ui.geometry.Offset) {
                                val point = ChartOffset(offset.x, offset.y)
                                // A little past the arc still counts. A knob
                                // whose ring is fifteen pixels wide and which
                                // only responded inside it would be a control
                                // most fingers miss; the angle is the same
                                // whether the finger is on the track or just
                                // outside it.
                                if (coordinates.radiusOf(point) >
                                    coordinates.outerRadius * ANGLE_TOUCH_MARGIN
                                ) {
                                    return
                                }
                                onAngleAt(coordinates.angleOf(point))
                            }
                            if (dragAngles) {
                                detectDragGestures(
                                    onDragStart = { report(it) },
                                    // The change's own position, not the
                                    // accumulated drag: a knob follows where
                                    // the finger *is*, not how far it moved.
                                    onDrag = { change, _ -> report(change.position) },
                                )
                            } else {
                                detectTapGestures(onTap = { report(it) })
                            }
                        }
                    },
                )
                .then(
                    if (renderMode.isStatic || (!tapSelects && onDoubleTap == null)) {
                        Modifier
                    } else {
                        Modifier.pointerInput(coordinates, renderers, tapSelects) {
                            fun hitAt(offset: androidx.compose.ui.geometry.Offset) =
                                renderers.firstNotNullOfOrNull {
                                    it.hitTest(
                                        ChartOffset(offset.x, offset.y),
                                        renderContext,
                                        HitTestMode.Contains,
                                    )
                                }
                            detectTapGestures(
                                onTap = { offset ->
                                    if (!tapSelects) return@detectTapGestures
                                    val hit = hitAt(offset)
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
                                },
                                onDoubleTap = onDoubleTap?.let { callback ->
                                    { offset -> callback(hitAt(offset)) }
                                },
                            )
                        }
                    },
                )
                .clearAndSetSemantics {
                    contentDescription = listOfNotNull(summary, selectionText).joinToString(" ")
                    if (selectionText != null) liveRegion = LiveRegionMode.Polite
                    // An adjustable chart adds its range and its actions here,
                    // inside the same `clearAndSetSemantics` — set outside it,
                    // they would be cleared by it.
                    semantics?.invoke(this)
                }
                .then(plotModifier),
        ) {
            if (!coordinates.isDrawable) return@Canvas
            renderers.forEach { it.draw(this, renderContext) }
        }

        if (centerContent != null) {
            CenterContent(coordinates = coordinates, density = density, content = centerContent)
        }

        if (tooltipData != null && tooltip != null &&
            (!renderMode.isStatic || staticOptions.showTooltip)
        ) {
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

/** How far past the outer radius a pointer still reads as being on the dial. */
private const val ANGLE_TOUCH_MARGIN = 1.15f
