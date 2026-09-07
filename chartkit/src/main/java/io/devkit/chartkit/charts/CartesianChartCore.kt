package io.devkit.chartkit.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.intl.Locale as ComposeLocale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.accessibility.buildChartSummary
import io.devkit.chartkit.accessibility.describeSelection
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.animation.rememberChartReveal
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartGrid
import io.devkit.chartkit.axis.drawAxis
import io.devkit.chartkit.components.legend.ChartLegend
import io.devkit.chartkit.components.legend.ChartLegendEntry
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.components.overlay.ChartOverlay
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.interaction.ChartDragMode
import io.devkit.chartkit.interaction.ChartGestureCallbacks
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.interaction.chartGestures
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartRangeSelection
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.AnyChartTooltipData
import io.devkit.chartkit.model.ChartRangeSelection
import io.devkit.chartkit.model.ChartRangeSelectionPhase
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.ChartViewportState
import io.devkit.chartkit.state.rememberChartViewportState
import io.devkit.chartkit.theme.ChartKitTheme
import java.util.Locale

/**
 * The chart engine, shared by every ChartKit chart.
 *
 * `LineChart`, `AreaChart`, `BarChart` and the experimental [CartesianChart]
 * all reduce to a call to this. There is one plot area, one pair of scales, one
 * hit-testing pass and one animation clock in the library — which is what makes
 * a combined chart possible and what stops four chart types drifting apart.
 *
 * Everything here is orientation-agnostic and layer-agnostic; the specifics
 * live in the [ResolvedLayer]s it is given.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun CartesianChartCore(
    layers: List<ResolvedLayer>,
    modifier: Modifier,
    orientation: ChartOrientation,
    domainAxis: ChartAxis,
    valueAxis: ChartAxis,
    grid: ChartGrid,
    valueDomainPolicy: DomainPolicy,
    legend: LegendPosition,
    legendTogglesSeries: Boolean,
    animation: ChartAnimation,
    interaction: ChartInteraction,
    crosshair: CrosshairConfig,
    hitTestMode: HitTestMode,
    sharedTooltip: Boolean,
    state: ChartState<Any?>,
    viewportState: ChartViewportState,
    onSelectionChanged: ((AnyChartSelection?) -> Unit)?,
    onRangeSelectionChanged: ((AnyChartRangeSelection?) -> Unit)?,
    tooltip: (@Composable (AnyChartTooltipData) -> Unit)?,
    accessibility: ChartAccessibility,
    accessibilitySummary: (() -> String)?,
    isLoading: Boolean,
    error: Throwable?,
    loadingContent: @Composable () -> Unit,
    emptyContent: @Composable () -> Unit,
    errorContent: @Composable (Throwable) -> Unit,
) {
    val theme = ChartKitTheme.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    // Compose's own locale rather than the Configuration's: it is what the
    // text in this composition is being laid out for, and it updates with the
    // composition instead of needing a Configuration read.
    val composeLocale = ComposeLocale.current
    val locale = remember(composeLocale) { Locale.forLanguageTag(composeLocale.toLanguageTag()) }

    val legendSlot: @Composable (Modifier, Boolean) -> Unit = { legendModifier, vertical ->
        LegendSlot(
            layers = layers,
            state = state,
            togglesSeries = legendTogglesSeries,
            modifier = legendModifier,
            vertical = vertical,
        )
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
                    else -> ChartPlot(
                        layers = layers,
                        orientation = orientation,
                        domainAxisConfig = domainAxis,
                        valueAxisConfig = valueAxis,
                        grid = grid,
                        valueDomainPolicy = valueDomainPolicy,
                        animation = animation,
                        interaction = interaction,
                        crosshair = crosshair,
                        hitTestMode = hitTestMode,
                        sharedTooltip = sharedTooltip,
                        state = state,
                        viewportState = viewportState,
                        onSelectionChanged = onSelectionChanged,
                        onRangeSelectionChanged = onRangeSelectionChanged,
                        tooltip = tooltip,
                        accessibility = accessibility,
                        accessibilitySummary = accessibilitySummary,
                        emptyContent = emptyContent,
                        density = density,
                        textMeasurer = textMeasurer,
                        locale = locale,
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

@Composable
private fun LegendSlot(
    layers: List<ResolvedLayer>,
    state: ChartState<Any?>,
    togglesSeries: Boolean,
    modifier: Modifier,
    vertical: Boolean,
) {
    val colors = ChartKitTheme.colors
    val entries = remember(layers, colors, state.hiddenSeriesIds) {
        layers.flatMap { layer ->
            layer.data.series.map { series ->
                ChartLegendEntry(
                    seriesId = series.id,
                    name = series.name,
                    color = series.color?.let { Color(it) } ?: colors.seriesColor(series.paletteIndex),
                    visible = series.visible && state.isSeriesVisible(series.id),
                )
            }
        }.distinctBy { it.seriesId }
    }
    ChartLegend(
        entries = entries,
        modifier = modifier,
        vertical = vertical,
        onToggle = if (togglesSeries) state::toggleSeries else null,
    )
}

/** The measured plot: canvas, gestures, tooltip overlay and semantics. */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun ChartPlot(
    layers: List<ResolvedLayer>,
    orientation: ChartOrientation,
    domainAxisConfig: ChartAxis,
    valueAxisConfig: ChartAxis,
    grid: ChartGrid,
    valueDomainPolicy: DomainPolicy,
    animation: ChartAnimation,
    interaction: ChartInteraction,
    crosshair: CrosshairConfig,
    hitTestMode: HitTestMode,
    sharedTooltip: Boolean,
    state: ChartState<Any?>,
    viewportState: ChartViewportState,
    onSelectionChanged: ((AnyChartSelection?) -> Unit)?,
    onRangeSelectionChanged: ((AnyChartRangeSelection?) -> Unit)?,
    tooltip: (@Composable (AnyChartTooltipData) -> Unit)?,
    accessibility: ChartAccessibility,
    accessibilitySummary: (() -> String)?,
    emptyContent: @Composable () -> Unit,
    density: androidx.compose.ui.unit.Density,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    locale: Locale,
) {
    val theme = ChartKitTheme.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val reveal = rememberChartReveal(animation)

    // Keyed on everything the geometry actually depends on. Scales, ticks,
    // interpolated paths and bar rectangles are therefore computed on a data,
    // size or theme change — and not when a tooltip appears, a selection moves
    // or an animation frame ticks.
    val viewport = viewportState.viewport
    val rangeSelectable = interaction.dragMode == ChartDragMode.Range
    val geometry = remember(
        layers, size, orientation, domainAxisConfig, valueAxisConfig, grid,
        valueDomainPolicy, crosshair, viewport, rangeSelectable, theme, density,
        locale, accessibility,
    ) {
        buildCartesianGeometry(
            bounds = ChartRect.fromSize(size.width.toFloat(), size.height.toFloat()),
            layers = layers,
            orientation = orientation,
            domainAxisConfig = domainAxisConfig,
            valueAxisConfig = valueAxisConfig,
            grid = grid,
            valueDomainPolicy = valueDomainPolicy,
            crosshair = crosshair,
            viewport = viewport,
            rangeSelectable = rangeSelectable,
            density = density,
            textMeasurer = textMeasurer,
            typography = theme.typography,
            dimensions = theme.dimensions,
            locale = locale,
            accessibility = accessibility,
        )
    }

    // The viewport state is the caller's window onto the data, so it needs to
    // know what the data's full extent is. Published on every layout rather
    // than held by the caller, who would otherwise have to compute the domain
    // themselves to interpret their own zoom level.
    LaunchedEffect(geometry) {
        viewportState.fullDomain = geometry.fullDomain
        viewportState.categoryCount = geometry.categoryCount
    }

    val selection = state.selection
    val range = state.rangeSelection

    val renderContext = ChartRenderContext(
        coordinates = geometry.coordinates,
        colors = theme.colors,
        typography = theme.typography,
        dimensions = theme.dimensions,
        density = density,
        textMeasurer = textMeasurer,
        reveal = reveal,
        selection = selection,
        range = range,
        viewport = viewport,
    )

    // One tooltip payload, built the same way whatever produced the selection.
    // Chart-specific code supplies the data; the overlay does the layout.
    val tooltipData: AnyChartTooltipData? = remember(selection, geometry, sharedTooltip) {
        selection?.let { selected ->
            val entries = if (sharedTooltip) {
                geometry.hitTestable.flatMap { it.tooltipEntriesAt(selected, renderContext) }
            } else {
                emptyList()
            }
            ChartTooltipData(
                selection = selected,
                entries = entries.ifEmpty {
                    listOf(
                        ChartTooltipEntry(
                            seriesId = selected.seriesId,
                            seriesName = selected.seriesName,
                            value = selected.y,
                            item = selected.item,
                            paletteIndex = selected.seriesIndex,
                        ),
                    )
                },
                anchor = selected.position,
                xLabel = geometry.formatDomainValue(selected.x),
            )
        }
    }

    val summary = remember(geometry, accessibilitySummary, accessibility) {
        accessibilitySummary?.invoke()
            ?: buildChartSummary(accessibility, geometry.summaries, geometry.valueFormatter)
    }
    val selectionText = selection?.let {
        describeSelection(
            seriesName = it.seriesName,
            xLabel = geometry.formatDomainValue(it.x),
            value = it.y,
            formatter = geometry.valueFormatter,
            multiSeries = geometry.summaries.size > 1,
        )
    }
    // Announced only when the gesture settles. A live region updated on every
    // pointer frame turns a screen reader into a stream of half-sentences.
    val viewportText = remember(viewport, geometry) {
        if (viewport.isFullyZoomedOut) {
            null
        } else {
            "Showing ${geometry.domainLabeller(viewport.start)} to " +
                "${geometry.domainLabeller(viewport.end)}."
        }
    }
    val rangeText = range
        ?.takeIf { it.phase == ChartRangeSelectionPhase.Completed && !it.isEmpty }
        ?.let { "Selected ${geometry.formatDomainValue(it.start)} to ${geometry.formatDomainValue(it.end)}." }

    val gestures = rememberChartGestureCallbacks(
        geometry = geometry,
        renderContext = renderContext,
        hitTestMode = hitTestMode,
        interaction = interaction,
        state = state,
        viewportState = viewportState,
        onSelectionChanged = onSelectionChanged,
        onRangeSelectionChanged = onRangeSelectionChanged,
    )

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .chartGestures(
                    key = geometry,
                    interaction = interaction,
                    orientation = orientation,
                    plotArea = geometry.coordinates.plotArea,
                    isZoomedIn = { !viewportState.isFullyZoomedOut },
                    callbacks = gestures,
                )
                // One description for the whole chart, replacing the child
                // semantics rather than adding to them: a Canvas has none worth
                // merging, and a screen reader given both a summary and a stray
                // node reads the chart twice.
                .clearAndSetSemantics {
                    // Selection, viewport and range all land in the one
                    // description. A screen reader reading the chart hears what
                    // is on screen *and* what has been selected, and neither is
                    // announced twice by a separate node.
                    contentDescription = listOfNotNull(
                        summary,
                        viewportText,
                        selectionText,
                        rangeText,
                    ).joinToString(" ")
                    if (selectionText != null || rangeText != null) {
                        liveRegion = LiveRegionMode.Polite
                    }
                },
        ) {
            val plot = geometry.coordinates.plotArea
            if (geometry.isEmpty || plot.isEmpty) return@Canvas

            // Data layers are clipped to the plot; the crosshair is not,
            // because its axis chips sit in the gutter by design. Clipping
            // matters once a viewport exists: a zoomed chart's off-screen
            // geometry would otherwise be drawn across the axes.
            clipRect(plot.left, plot.top, plot.right, plot.bottom) {
                geometry.renderers.filter { it.clipToPlot }
                    .forEach { it.draw(this, renderContext) }
            }
            geometry.renderers.filterNot { it.clipToPlot }
                .forEach { it.draw(this, renderContext) }

            geometry.domainAxis?.let { drawAxis(it, plot, renderContext) }
            geometry.valueAxis?.let { drawAxis(it, plot, renderContext) }
        }

        if (geometry.isEmpty) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { emptyContent() }
        }

        if (tooltipData != null && tooltip != null && !geometry.isEmpty) {
            ChartOverlay(
                anchor = tooltipData.anchor,
                bounds = geometry.coordinates.plotArea,
                gap = with(density) { theme.dimensions.tooltipPadding.toPx() },
            ) {
                tooltip(tooltipData)
            }
        }
    }
}

/**
 * Turns pointer intent into chart behaviour.
 *
 * The coordinator recognises gestures; this decides what they *mean* for a
 * Cartesian chart — which item a tap selects, how far a drag moves the
 * viewport, what fraction of the domain a range covers. Keeping the two apart
 * is what lets the polar charts reuse the coordinator without inheriting any
 * of this.
 */
@Suppress("LongParameterList")
@Composable
private fun rememberChartGestureCallbacks(
    geometry: CartesianGeometry,
    renderContext: ChartRenderContext,
    hitTestMode: HitTestMode,
    interaction: ChartInteraction,
    state: ChartState<Any?>,
    viewportState: ChartViewportState,
    onSelectionChanged: ((AnyChartSelection?) -> Unit)?,
    onRangeSelectionChanged: ((AnyChartRangeSelection?) -> Unit)?,
): ChartGestureCallbacks {
    val rangeAnchor = remember(geometry) { mutableStateOf<Double?>(null) }

    return remember(geometry, interaction, hitTestMode, state, viewportState) {
        fun select(point: ChartOffset, mode: HitTestMode) {
            val best = geometry.hitTestable
                .mapNotNull { it.hitTest(point, renderContext, mode) }
                .minByOrNull { candidate ->
                    val dx = candidate.position.x - point.x
                    val dy = candidate.position.y - point.y
                    dx * dx + dy * dy
                }
            if (best != null && best != state.selection) {
                state.selection = best
                onSelectionChanged?.invoke(best)
            }
        }

        fun publishRange(to: Double, phase: ChartRangeSelectionPhase) {
            val from = rangeAnchor.value ?: return
            val low = minOf(from, to)
            val high = maxOf(from, to)
            val range = ChartRangeSelection(
                start = geometry.domainValueAtFraction(low),
                end = geometry.domainValueAtFraction(high),
                startFraction = low,
                endFraction = high,
                items = geometry.itemsInRange(low, high),
                phase = phase,
            )
            state.rangeSelection = range
            onRangeSelectionChanged?.invoke(range)
        }

        ChartGestureCallbacks(
            onTap = { point ->
                if (!geometry.coordinates.plotArea.contains(point)) {
                    if (interaction.behaviour.clearOnTapOutside) {
                        state.clearSelection()
                        onSelectionChanged?.invoke(null)
                        if (state.rangeSelection != null) {
                            state.clearRangeSelection()
                            onRangeSelectionChanged?.invoke(null)
                        }
                    }
                } else {
                    select(point, hitTestMode)
                }
            },
            onScrubStart = { point ->
                state.pointerPosition = point
                select(point, HitTestMode.NearestDomain)
            },
            onScrub = { point ->
                state.pointerPosition = point
                select(point, HitTestMode.NearestDomain)
            },
            onScrubEnd = {
                state.pointerPosition = null
                if (interaction.behaviour.clearOnScrubEnd) {
                    state.clearSelection()
                    onSelectionChanged?.invoke(null)
                }
            },
            // The drag is reported as a fraction of the plot; converting it
            // into a domain move means scaling by the *visible* width — a pan
            // of half the plot moves half of what is on screen, not half the
            // dataset — and inverting, because content follows the finger.
            onPan = { deltaFraction ->
                viewportState.panBy(-deltaFraction * viewportState.viewport.width)
            },
            onZoom = { factor, focus -> viewportState.zoomBy(factor, focus) },
            onRangeStart = { point ->
                rangeAnchor.value = geometry.domainFractionAt(point)
                publishRange(rangeAnchor.value ?: 0.0, ChartRangeSelectionPhase.InProgress)
            },
            onRangeChange = { point ->
                publishRange(geometry.domainFractionAt(point), ChartRangeSelectionPhase.InProgress)
            },
            onRangeEnd = {
                val current = state.rangeSelection ?: return@ChartGestureCallbacks
                // A drag that never left its starting point is a tap that
                // happened to pass slop, not an interval worth acting on.
                if (current.isEmpty) {
                    state.clearRangeSelection()
                    onRangeSelectionChanged?.invoke(null)
                } else {
                    val completed = current.copy(phase = ChartRangeSelectionPhase.Completed)
                    state.rangeSelection = completed
                    onRangeSelectionChanged?.invoke(completed)
                }
                rangeAnchor.value = null
            },
        )
    }
}

/** The built-in placeholder for a chart with nothing to show. */
@Composable
internal fun DefaultEmptyContent(text: String = "No data") {
    Text(
        text = text,
        style = ChartKitTheme.typography.axisLabel,
        color = ChartKitTheme.colors.emptyContent,
        modifier = Modifier.padding(8.dp),
    )
}

/** The built-in placeholder while data is loading. */
@Composable
internal fun DefaultLoadingContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Loading",
            style = ChartKitTheme.typography.axisLabel,
            color = ChartKitTheme.colors.emptyContent,
        )
    }
}

/**
 * The built-in error placeholder.
 *
 * States that something failed and stops there. ChartKit does not own retry:
 * retrying is an application concern with an application's own backoff,
 * authentication and navigation attached, and a library that grew a Retry
 * button would be guessing at all three.
 */
@Composable
internal fun DefaultErrorContent(error: Throwable) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = error.message?.takeIf { it.isNotBlank() } ?: "Could not load chart data",
            style = ChartKitTheme.typography.axisLabel,
            color = ChartKitTheme.colors.emptyContent,
            modifier = Modifier.padding(8.dp),
        )
    }
}
