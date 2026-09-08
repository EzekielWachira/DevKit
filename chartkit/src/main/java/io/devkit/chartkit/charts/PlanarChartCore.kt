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
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.accessibility.buildChartSummary
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.animation.rememberChartReveal
import io.devkit.chartkit.components.legend.ChartLegend
import io.devkit.chartkit.components.legend.ChartLegendEntry
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.components.overlay.ChartOverlay
import io.devkit.chartkit.coordinate.PlanarCoordinates
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartInsets
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.AnyChartTooltipData
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.theme.ChartKitTheme

/**
 * What a drag over a planar chart means.
 *
 * Only the graph charts use anything but [PlanarDragMode.None]: a treemap, a
 * funnel and a Sankey diagram have nothing to drag. Passing the callbacks
 * through the core rather than letting the graph chart install its own
 * `pointerInput` is what keeps tap-selection and dragging from fighting over
 * the same pointer stream.
 */
internal enum class PlanarDragMode {
    None,
    DragItems,
}

/**
 * The engine for charts laid out inside a plain rectangle.
 *
 * The third sibling of [CartesianChartCore] and [PolarChartCore], and — as with
 * the polar engine — deliberately not a copy of either. Treemap, sunburst's
 * flat cousin, Sankey, funnel and network graph differ from a line chart in
 * exactly one thing: they place their own geometry rather than mapping onto
 * axes. Everything else comes from the same place it does for a line chart —
 * the theme, the animation clock, the legend, the overlay positioning, the
 * selection state, the tooltip payload, the accessibility summary, the capture
 * modifier.
 *
 * @param layers built from the coordinates, once per layout.
 * @param overlayContent Compose content laid out over the plot — a breadcrumb
 *   bar, a centre readout, a custom node badge. Laid out by Compose rather than
 *   rasterised, and it takes no pointer input so it never steals a tap from the
 *   chart underneath.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun PlanarChartCore(
    layers: (PlanarCoordinates) -> List<ChartLayerRenderer>,
    modifier: Modifier,
    legend: LegendPosition,
    legendItems: List<ChartKeyItem>,
    animation: ChartAnimation,
    tapSelects: Boolean,
    clearOnTapOutside: Boolean,
    state: ChartState<Any?>,
    valueFormatter: ChartValueFormatter,
    accessibility: ChartAccessibility,
    accessibilitySummary: (() -> String)?,
    renderMode: ChartRenderMode,
    staticOptions: ChartStaticOptions,
    isEmpty: Boolean,
    isLoading: Boolean,
    error: Throwable?,
    loadingContent: @Composable () -> Unit,
    emptyContent: @Composable () -> Unit,
    errorContent: @Composable (Throwable) -> Unit,
    onSelectionChanged: ((AnyChartSelection?) -> Unit)? = null,
    onDoubleTap: ((AnyChartSelection?) -> Unit)? = null,
    tooltip: (@Composable (AnyChartTooltipData) -> Unit)? = null,
    overlayContent: (@Composable (PlanarCoordinates) -> Unit)? = null,
    contentInsets: ChartInsets? = null,
    dragMode: PlanarDragMode = PlanarDragMode.None,
    onDragStart: ((ChartOffset, PlanarCoordinates) -> Unit)? = null,
    onDrag: ((ChartOffset, PlanarCoordinates) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
) {
    val theme = ChartKitTheme.current

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
        // Display-only. Hiding one node of a flow or one branch of a treemap
        // would renormalise everything else, so the remaining shares would be
        // shares of a different whole — a different chart, not a filtered one.
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
                    else -> PlanarPlot(
                        layers = layers,
                        animation = animation,
                        tapSelects = tapSelects,
                        clearOnTapOutside = clearOnTapOutside,
                        state = state,
                        valueFormatter = valueFormatter,
                        accessibility = accessibility,
                        accessibilitySummary = accessibilitySummary,
                        renderMode = renderMode,
                        staticOptions = staticOptions,
                        onSelectionChanged = onSelectionChanged,
                        onDoubleTap = onDoubleTap,
                        tooltip = tooltip,
                        overlayContent = overlayContent,
                        contentInsets = contentInsets,
                        dragMode = dragMode,
                        onDragStart = onDragStart,
                        onDrag = onDrag,
                        onDragEnd = onDragEnd,
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

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
private fun PlanarPlot(
    layers: (PlanarCoordinates) -> List<ChartLayerRenderer>,
    animation: ChartAnimation,
    tapSelects: Boolean,
    clearOnTapOutside: Boolean,
    state: ChartState<Any?>,
    valueFormatter: ChartValueFormatter,
    accessibility: ChartAccessibility,
    accessibilitySummary: (() -> String)?,
    renderMode: ChartRenderMode,
    staticOptions: ChartStaticOptions,
    onSelectionChanged: ((AnyChartSelection?) -> Unit)?,
    onDoubleTap: ((AnyChartSelection?) -> Unit)?,
    tooltip: (@Composable (AnyChartTooltipData) -> Unit)?,
    overlayContent: (@Composable (PlanarCoordinates) -> Unit)?,
    contentInsets: ChartInsets?,
    dragMode: PlanarDragMode,
    onDragStart: ((ChartOffset, PlanarCoordinates) -> Unit)?,
    onDrag: ((ChartOffset, PlanarCoordinates) -> Unit)?,
    onDragEnd: (() -> Unit)?,
) {
    val theme = ChartKitTheme.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    // A static render is drawn at its settled state. Nothing about the picture
    // may depend on a clock that is still running, or two captures of the same
    // chart would differ.
    val reveal = if (renderMode.isStatic) 1f else rememberChartReveal(animation)
    var size by remember { mutableStateOf(IntSize.Zero) }

    val coordinates = remember(size, theme, density, contentInsets) {
        val bounds = ChartRect.fromSize(size.width.toFloat(), size.height.toFloat())
        // The chart's own reserve where it made one — a Sankey diagram's label
        // gutters, say — and otherwise the theme's uniform content padding.
        // Measured by the chart rather than guessed at here, the same rule the
        // Cartesian axes follow.
        val insets = contentInsets ?: with(density) {
            val padding = theme.dimensions.contentPadding.toPx()
            ChartInsets(padding, padding, padding, padding)
        }
        val inset = bounds.inset(insets)
        PlanarCoordinates(plotArea = ChartRect.fromSize(bounds.width, bounds.height), contentBounds = inset)
    }

    val renderers = remember(coordinates, layers) { layers(coordinates) }

    // A gesture cannot have produced a selection in a static render, so the
    // only one that can exist is a caller's own — and whether it is drawn is
    // the caller's decision, not the engine's.
    val selection = state.selection?.takeIf { !renderMode.isStatic || staticOptions.showSelection }

    val renderContext = ChartRenderContext(
        coordinates = coordinates,
        colors = theme.colors,
        typography = theme.typography,
        dimensions = theme.dimensions,
        density = density,
        textMeasurer = textMeasurer,
        reveal = reveal,
        selection = selection,
        renderMode = renderMode,
    )

    val summaries = remember(renderers, accessibility) { renderers.flatMap { it.describe() } }
    val summary = remember(summaries, accessibilitySummary, accessibility) {
        accessibilitySummary?.invoke() ?: buildChartSummary(accessibility, summaries, valueFormatter)
    }
    val selectionText = selection?.let { selected ->
        // A planar chart's selection is a node, a stage or a flow, and the
        // sentence for it is layer-specific — a Sankey link reads "Search to
        // Checkout: 1,240 users" and a treemap tile reads a path and a share.
        // The layer that produced the selection supplies it.
        renderers.firstNotNullOfOrNull { it.describeSelection(selected, valueFormatter) }
            ?: "${selected.seriesName}: ${valueFormatter.format(selected.y)}."
    }

    val showTooltip = tooltip != null && (!renderMode.isStatic || staticOptions.showTooltip)
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
                xLabel = selected.xLabel,
                valueFormatter = valueFormatter,
            )
        }
    }

    fun select(point: ChartOffset): AnyChartSelection? = renderers
        .firstNotNullOfOrNull { it.hitTest(point, renderContext, HitTestMode.Contains) }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // No pointer input at all in a static render. Not "gestures
                // that do nothing": a modifier that consumes events would still
                // stop a parent from scrolling.
                .then(
                    if (renderMode.isStatic || (!tapSelects && dragMode == PlanarDragMode.None)) {
                        Modifier
                    } else {
                        Modifier.pointerInput(coordinates, renderers, tapSelects) {
                            detectTapGestures(
                                onTap = { offset ->
                                    if (!tapSelects) return@detectTapGestures
                                    val hit = select(ChartOffset(offset.x, offset.y))
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
                                    { offset -> callback(select(ChartOffset(offset.x, offset.y))) }
                                },
                            )
                        }
                    },
                )
                .then(
                    if (renderMode.isStatic || dragMode == PlanarDragMode.None) {
                        Modifier
                    } else {
                        Modifier.pointerInput(coordinates, renderers, dragMode) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    onDragStart?.invoke(ChartOffset(offset.x, offset.y), coordinates)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    onDrag?.invoke(
                                        ChartOffset(change.position.x, change.position.y),
                                        coordinates,
                                    )
                                },
                                onDragEnd = { onDragEnd?.invoke() },
                                onDragCancel = { onDragEnd?.invoke() },
                            )
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

        overlayContent?.invoke(coordinates)

        if (tooltipData != null && tooltip != null && showTooltip) {
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
