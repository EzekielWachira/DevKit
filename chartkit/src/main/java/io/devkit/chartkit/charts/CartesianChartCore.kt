package io.devkit.chartkit.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
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
import androidx.compose.runtime.DisposableEffect
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
import io.devkit.chartkit.layer.crosshair.drawAxisReadouts
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
import io.devkit.chartkit.layer.annotation.ResolvedAnnotation
import io.devkit.chartkit.model.ChartX
import io.devkit.chartkit.model.resolveOrDefault
import io.devkit.chartkit.model.AnyChartRangeSelection
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.AnyChartTooltipData
import io.devkit.chartkit.model.ChartRangeSelection
import io.devkit.chartkit.model.ChartRangeSelectionPhase
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.ChartSharedCrosshairState
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
    sharedCrosshair: ChartSharedCrosshairState?,
    annotations: List<ResolvedAnnotation>,
    secondaryValueAxis: ChartAxis? = null,
    /** Every axis this chart has, or `null` for the implicit one-per-dimension pair. */
    axisRegistry: io.devkit.chartkit.axis.AxisRegistry? = null,
    tickAlignment: io.devkit.chartkit.axis.AxisTickAlignment =
        io.devkit.chartkit.axis.AxisTickAlignment.Independent,
    axisDensity: io.devkit.chartkit.axis.AxisDensity = io.devkit.chartkit.axis.AxisDensity.Auto,
    tooltipOrder: io.devkit.chartkit.model.ChartTooltipOrder =
        io.devkit.chartkit.model.ChartTooltipOrder.Declaration,
    onAxisDiagnostics: ((List<io.devkit.chartkit.axis.AxisDiagnostic>) -> Unit)? = null,
    customLayers: List<io.devkit.chartkit.layer.custom.CustomCartesianLayer> = emptyList(),
    xResolver: io.devkit.chartkit.model.ChartXResolver =
        io.devkit.chartkit.model.ChartXResolver.Default,
    renderMode: io.devkit.chartkit.render.ChartRenderMode =
        io.devkit.chartkit.render.ChartRenderMode.Interactive,
    staticOptions: io.devkit.chartkit.render.ChartStaticOptions =
        io.devkit.chartkit.render.ChartStaticOptions.Default,
    plotAlignment: io.devkit.chartkit.state.ChartPlotAlignment? = null,
    @Suppress("ComposableLambdaParameterNaming")
    overlay: (@Composable ChartOverlayScope.() -> Unit)? = null,
    sceneState: io.devkit.chartkit.scene.ChartSceneState? = null,
    keyboardNavigation: Boolean = true,
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
                        sharedCrosshair = sharedCrosshair,
                        annotations = annotations,
                        secondaryValueAxisConfig = secondaryValueAxis,
                        axisRegistry = axisRegistry,
                        tickAlignment = tickAlignment,
                        axisDensity = axisDensity,
                        tooltipOrder = tooltipOrder,
                        onAxisDiagnostics = onAxisDiagnostics,
                        customLayers = customLayers,
                        xResolver = xResolver,
                        renderMode = renderMode,
                        staticOptions = staticOptions,
                        plotAlignment = plotAlignment,
                        overlay = overlay,
                        sceneState = sceneState,
                        keyboardNavigation = keyboardNavigation,
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
            // Each layer decides what it contributes: a line or bar layer one
            // row per series, a price layer a single row, a box plot none —
            // its categories are already on the axis.
            layer.legendRows().map { series ->
                ChartLegendEntry(
                    seriesId = series.seriesId,
                    name = series.name,
                    color = series.colorOverride?.let { Color(it) }
                        ?: colors.seriesColor(series.paletteIndex),
                    visible = series.visible && state.isSeriesVisible(series.seriesId),
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
    sharedCrosshair: ChartSharedCrosshairState?,
    annotations: List<ResolvedAnnotation>,
    secondaryValueAxisConfig: ChartAxis?,
    axisRegistry: io.devkit.chartkit.axis.AxisRegistry?,
    tickAlignment: io.devkit.chartkit.axis.AxisTickAlignment,
    axisDensity: io.devkit.chartkit.axis.AxisDensity,
    tooltipOrder: io.devkit.chartkit.model.ChartTooltipOrder,
    onAxisDiagnostics: ((List<io.devkit.chartkit.axis.AxisDiagnostic>) -> Unit)?,
    customLayers: List<io.devkit.chartkit.layer.custom.CustomCartesianLayer>,
    xResolver: io.devkit.chartkit.model.ChartXResolver,
    renderMode: io.devkit.chartkit.render.ChartRenderMode,
    staticOptions: io.devkit.chartkit.render.ChartStaticOptions,
    plotAlignment: io.devkit.chartkit.state.ChartPlotAlignment?,
    overlay: (@Composable ChartOverlayScope.() -> Unit)?,
    sceneState: io.devkit.chartkit.scene.ChartSceneState?,
    keyboardNavigation: Boolean,
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
    // A static render is drawn settled. Nothing about an exported picture may
    // depend on a clock that is still running, or two captures of the same
    // chart at the same size would differ.
    val reveal = if (renderMode.isStatic) 1f else rememberChartReveal(animation)

    // A crosshair follows a pointer, and a static render has none. Suppressed
    // by handing the geometry a disabled config rather than by skipping the
    // layer at draw time, so the layout does not reserve room for axis chips
    // that will never be drawn.
    val effectiveCrosshair = if (renderMode.isStatic && !staticOptions.showCrosshair) {
        CrosshairConfig(enabled = false)
    } else {
        crosshair
    }

    // Keyed on everything the geometry actually depends on. Scales, ticks,
    // interpolated paths and bar rectangles are therefore computed on a data,
    // size or theme change — and not when a tooltip appears, a selection moves
    // or an animation frame ticks.
    val viewport = viewportState.viewport
    val rangeSelectable = interaction.dragMode == ChartDragMode.Range

    // The padding this chart adds to reach the gutters its group agreed on.
    //
    // Derived from the chart's *own* last-measured natural gutters rather than
    // from the current geometry, which would be circular — the geometry is what
    // the padding is an input to. Zero on the first frame; measured on the
    // second; stable from then on, because a natural gutter does not depend on
    // the padding added outside it.
    var naturalInsets by remember {
        mutableStateOf(io.devkit.chartkit.geometry.ChartInsets.Zero)
    }
    val alignmentPadding = plotAlignment?.extraFor(naturalInsets)
        ?: io.devkit.chartkit.geometry.ChartInsets.Zero
    val geometry = remember(
        layers, size, orientation, domainAxisConfig, valueAxisConfig, grid,
        valueDomainPolicy, effectiveCrosshair, viewport, rangeSelectable, theme, density,
        locale, accessibility, annotations, secondaryValueAxisConfig, customLayers,
        xResolver, alignmentPadding, axisRegistry, tickAlignment, axisDensity,
    ) {
        buildCartesianGeometry(
            bounds = ChartRect.fromSize(size.width.toFloat(), size.height.toFloat()),
            layers = layers,
            orientation = orientation,
            domainAxisConfig = domainAxisConfig,
            valueAxisConfig = valueAxisConfig,
            grid = grid,
            valueDomainPolicy = valueDomainPolicy,
            crosshair = effectiveCrosshair,
            viewport = viewport,
            rangeSelectable = rangeSelectable,
            density = density,
            textMeasurer = textMeasurer,
            typography = theme.typography,
            dimensions = theme.dimensions,
            locale = locale,
            accessibility = accessibility,
            annotations = annotations,
            secondaryValueAxisConfig = secondaryValueAxisConfig,
            axisRegistry = axisRegistry,
            tickAlignment = tickAlignment,
            axisDensity = axisDensity,
            customLayers = customLayers,
            xResolver = xResolver,
            alignmentInsets = alignmentPadding,
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

    // Reported once per layout, and only when there is something to report.
    LaunchedEffect(geometry.axisDiagnostics, onAxisDiagnostics) {
        if (geometry.axisDiagnostics.isNotEmpty()) onAxisDiagnostics?.invoke(geometry.axisDiagnostics)
    }

    // A gesture cannot have produced a selection in a static render, so the only
    // one that can exist is the caller's own — and whether it is drawn is their
    // decision, not the engine's.
    val selection = state.selection?.takeIf { !renderMode.isStatic || staticOptions.showSelection }
    val range = state.rangeSelection?.takeIf { !renderMode.isStatic }

    // An opaque identity for this chart within a linked group, so it can tell
    // its own published position from another chart's, and so a plot-alignment
    // group can keep one entry per chart.
    val chartId = remember { Any() }

    // Published so charts stacked above one another can share a plot left and
    // right edge even when their value labels differ in width. See
    // [ChartPlotAlignment] for why this is a measurement exchange rather than a
    // layout engine.
    if (plotAlignment != null) {
        LaunchedEffect(geometry, plotAlignment, chartId) {
            naturalInsets = geometry.naturalPlotInsets
            plotAlignment.report(chartId, geometry.naturalPlotInsets)
        }
        DisposableEffect(plotAlignment, chartId) {
            onDispose { plotAlignment.forget(chartId) }
        }
    }
    val externalDomain: ChartX? = sharedCrosshair
        ?.takeIf { it.source !== chartId }
        ?.domain

    // A position published by another chart moves this one's selection too, so
    // a linked dashboard reports a value per chart rather than a bare guide.
    // Driven from the shared state and never writing back to it: a chart
    // publishes only from its own gestures, which is what makes a loop
    // structurally impossible.
    LaunchedEffect(externalDomain, geometry) {
        if (externalDomain == null) {
            if (sharedCrosshair != null && state.selection != null && sharedCrosshair.source !== chartId) {
                state.clearSelection()
            }
            return@LaunchedEffect
        }
        val position = geometry.domainPositionOf(externalDomain) ?: return@LaunchedEffect
        if (!position.isFinite()) return@LaunchedEffect
        val plot = geometry.coordinates.plotArea
        val probe = geometry.coordinates.pointAt(
            position,
            geometry.coordinates.valueOf(ChartOffset(plot.centerX, plot.centerY)),
        )
        val probeContext = ChartRenderContext(
            coordinates = geometry.coordinates,
            colors = theme.colors,
            typography = theme.typography,
            dimensions = theme.dimensions,
            density = density,
            textMeasurer = textMeasurer,
            reveal = 1f,
            selection = null,
            viewport = viewport,
        )
        val best = geometry.hitTestable
            .mapNotNull { it.hitTest(probe, probeContext, HitTestMode.NearestDomain) }
            .minByOrNull { kotlin.math.abs(geometry.coordinates.domainOf(it.position) - position) }
        if (best != null && best != state.selection) state.selection = best
    }

    // Built in two steps. The first has everything a layer needs to draw except
    // the per-axis crosshair readouts, which are derived from the tooltip's own
    // entries — and the tooltip is resolved through the axis contexts that come
    // from this one. Deriving them rather than hit testing twice is what keeps a
    // crosshair chip and the tooltip row beside it the same number by
    // construction.
    val baseContext = ChartRenderContext(
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
        externalDomain = externalDomain,
        renderMode = renderMode,
    )

    // One context per value axis, derived from the chart's and differing in
    // exactly one thing: the coordinate system. A derived copy rather than a
    // flag inside the context, so a layer never has to ask which axis it is on
    // — it draws against the coordinates it is given.
    //
    // Built once per geometry and looked up by name, so a draw loop holds a
    // direct reference rather than resolving an axis id per point.
    val baseAxisContexts: Map<io.devkit.chartkit.axis.ChartAxisId, ChartRenderContext> =
        remember(geometry, baseContext) {
            geometry.axisCoordinates.mapValues { (_, coords) -> baseContext.withCoordinates(coords) }
        }
    fun baseContextFor(renderer: io.devkit.chartkit.layer.ChartLayerRenderer): ChartRenderContext =
        baseAxisContexts[geometry.axisOf(renderer)] ?: baseContext

    // One tooltip payload, built the same way whatever produced the selection.
    // Chart-specific code supplies the data; the overlay does the layout.
    val tooltipData: AnyChartTooltipData? = remember(selection, geometry, sharedTooltip, tooltipOrder) {
        selection?.let { selected ->
            val entries = if (sharedTooltip) {
                // Each layer reports its own value at the selected domain
                // position; the axis it belongs to is then attached here, along
                // with the text that axis would have written and the point the
                // series occupies on screen. Doing it centrally rather than in
                // every layer is what keeps a layer ignorant of how many axes
                // the chart has.
                val enriched = geometry.hitTestable.flatMap { renderer ->
                    val axisId = geometry.axisOf(renderer)
                    val coords = geometry.coordinatesFor(axisId)
                    val domainPosition = geometry.coordinates.domainOf(selected.position)
                    renderer.tooltipEntriesAt(selected, baseContextFor(renderer)).map { entry ->
                        entry.copy(
                            axisId = axisId,
                            axisTitle = geometry.axisSpecs[axisId]?.displayName,
                            // The unit the axis *writes*, not the one it
                            // measures: a currency formatter already carries
                            // its symbol.
                            unit = geometry.axisLabelUnits[axisId]
                                ?: io.devkit.chartkit.axis.ChartUnit.None,
                            formattedValue = geometry.labelFor(axisId, entry.value),
                            position = coords.pointAt(
                                domainPosition,
                                coords.positionOfValue(entry.value),
                            ),
                        )
                    }
                }
                tooltipOrder.sort(enriched)
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
                valueFormatter = geometry.valueFormatter,
            )
        }
    }

    // Step two: the readouts, and the contexts that carry them. One extra
    // `mapValues` per axis, not per point.
    val axisReadouts = remember(tooltipData, geometry, effectiveCrosshair.axisValueLabels) {
        if (!effectiveCrosshair.axisValueLabels || tooltipData == null) {
            emptyList()
        } else {
            val axisById = geometry.valueAxes.associateBy { it.id }
            tooltipData.entries
                .mapNotNull { entry -> entry.axisId?.let { it to entry } }
                .distinctBy { it.first }
                .mapNotNull { (axisId, entry) ->
                    val axis = axisById[axisId] ?: return@mapNotNull null
                    val coords = geometry.coordinatesFor(axisId)
                    io.devkit.chartkit.layer.AxisValueReadout(
                        axisId = axisId,
                        position = axis.position,
                        offset = axis.offset,
                        at = coords.positionOfValue(entry.value),
                        text = entry.formattedValue ?: geometry.labelFor(axisId, entry.value),
                    )
                }
        }
    }
    val renderContext = if (axisReadouts.isEmpty()) baseContext else baseContext.withReadouts(axisReadouts)
    val axisContexts = if (axisReadouts.isEmpty()) {
        baseAxisContexts
    } else {
        baseAxisContexts.mapValues { (_, context) -> context.withReadouts(axisReadouts) }
    }

    // The context a layer draws with: the one built over its own axis' scale.
    fun contextFor(renderer: io.devkit.chartkit.layer.ChartLayerRenderer): ChartRenderContext =
        axisContexts[geometry.axisOf(renderer)] ?: renderContext

    val summary = remember(geometry, accessibilitySummary, accessibility) {
        accessibilitySummary?.invoke()
            ?: buildChartSummary(
                accessibility = accessibility,
                summaries = geometry.summaries,
                formatter = geometry.valueFormatter,
                axes = geometry.valueAxes.map { axis ->
                    val spec = geometry.axisSpecs[axis.id]
                    io.devkit.chartkit.accessibility.AxisDescription(
                        title = spec?.title ?: axis.config.title,
                        unit = spec?.unit?.spokenName,
                    )
                },
            )
    }
    // A selection on a multi-axis chart is announced across every axis, each
    // value in its own unit — "March. Rainfall: 82 millimetres. Temperature:
    // 14.2 degrees Celsius." A single-axis chart keeps the shorter sentence.
    val selectionText = selection?.let { selected ->
        val entries = tooltipData?.entries.orEmpty()
        val axisCount = entries.mapNotNull { it.axisId }.distinct().size
        if (axisCount > 1) {
            io.devkit.chartkit.accessibility.describeMultiAxisSelection(
                xLabel = geometry.formatDomainValue(selected.x),
                entries = entries.map { entry ->
                    Triple(entry.seriesName.ifBlank { entry.seriesId }, entry.value, entry.unit)
                },
                formatters = entries.map { entry ->
                    entry.axisId?.let(geometry::formatterFor) ?: geometry.valueFormatter
                },
            )
        } else {
            describeSelection(
                seriesName = selected.seriesName,
                xLabel = geometry.formatDomainValue(selected.x),
                value = selected.y,
                formatter = selection.let { geometry.valueFormatter },
                multiSeries = geometry.summaries.size > 1,
            )
        }
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

    // Rebuilt when the geometry changes, and never during a draw pass. A scene
    // is a description of the finished picture; deriving it while drawing would
    // both cost a frame and capture whatever the animation happened to be doing.
    if (sceneState != null) {
        LaunchedEffect(geometry, theme, size) {
            if (geometry.isEmpty || size == IntSize.Zero) return@LaunchedEffect
            sceneState.scene = buildCartesianScene(
                geometry = geometry,
                // Settled and unselected: an exported picture should not
                // depend on a running clock or on what happened to be under
                // the pointer when the button was pressed.
                context = renderContext.settledForExport(),
                size = ChartRect.fromSize(size.width.toFloat(), size.height.toFloat()),
                background = null,
                density = density,
            )
        }
    }

    val gestures = rememberChartGestureCallbacks(
        geometry = geometry,
        renderContext = renderContext,
        axisContexts = axisContexts,
        hitTestMode = hitTestMode,
        interaction = interaction,
        state = state,
        viewportState = viewportState,
        sharedCrosshair = sharedCrosshair,
        chartId = chartId,
        onSelectionChanged = onSelectionChanged,
        onRangeSelectionChanged = onRangeSelectionChanged,
    )

    // Stepping the selection without a pointer: arrow keys and a D-pad through
    // `onKeyEvent`, and TalkBack's own gesture through the custom actions
    // installed in the semantics below. Both resolve through the same hit test
    // a scrub uses, so there is one notion of what is selected.
    fun step(delta: Int, series: Int) {
        if (geometry.isEmpty) return
        val ids = geometry.navigableSeriesIds()
        val currentSeries = state.selection?.seriesId
        val seriesIndex = ids.indexOf(currentSeries).takeIf { it >= 0 } ?: 0
        val targetSeries = when {
            series == 0 || ids.isEmpty() -> currentSeries
            else -> ids[((seriesIndex + series) % ids.size + ids.size) % ids.size]
        }
        val nextStep = (geometry.stepOf(state.selection) + delta)
            .coerceIn(0, (geometry.stepCount() - 1).coerceAtLeast(0))
        val next = geometry.selectionAtStep(nextStep, targetSeries, theme, density, textMeasurer)
            ?: return
        if (next != state.selection) {
            state.selection = next
            onSelectionChanged?.invoke(next)
            sharedCrosshair?.publish(next.x, chartId)
        }
    }

    val stepActions = remember(geometry, keyboardNavigation) {
        if (!keyboardNavigation || geometry.isEmpty) {
            emptyList()
        } else {
            buildList {
                add(CustomAccessibilityAction("Next data point") { step(1, 0); true })
                add(CustomAccessibilityAction("Previous data point") { step(-1, 0); true })
                if (geometry.navigableSeriesIds().size > 1) {
                    add(CustomAccessibilityAction("Next series") { step(0, 1); true })
                    add(CustomAccessibilityAction("Previous series") { step(0, -1); true })
                }
            }
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
                .then(
                    if (renderMode.isStatic) {
                        Modifier
                    } else {
                        Modifier.chartGestures(
                            key = geometry,
                            interaction = interaction,
                            orientation = orientation,
                            plotArea = geometry.coordinates.plotArea,
                            isZoomedIn = { !viewportState.isFullyZoomedOut },
                            callbacks = gestures,
                        )
                    },
                )
                // One description for the whole chart, replacing the child
                // semantics rather than adding to them: a Canvas has none worth
                // merging, and a screen reader given both a summary and a stray
                // node reads the chart twice.
                // Focusable and key-driven, so the chart can be read on a
                // desktop, a TV or a device with a physical keyboard — none of
                // which have a finger to scrub with.
                .then(
                    if (!keyboardNavigation || renderMode.isStatic) {
                        Modifier
                    } else {
                        Modifier
                            .focusable()
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) {
                                    false
                                } else {
                                    // Along the domain moves between points and
                                    // across it moves between series, whichever
                                    // way round the chart is drawn — so the keys
                                    // mean the same thing on a horizontal bar
                                    // chart as on a vertical line chart.
                                    val along = orientation.isVertical
                                    when (event.key) {
                                        Key.DirectionRight ->
                                            if (along) step(1, 0) else step(0, 1)
                                        Key.DirectionLeft ->
                                            if (along) step(-1, 0) else step(0, -1)
                                        Key.DirectionDown ->
                                            if (along) step(0, 1) else step(1, 0)
                                        Key.DirectionUp ->
                                            if (along) step(0, -1) else step(-1, 0)
                                        Key.Escape -> {
                                            state.clearSelection()
                                            sharedCrosshair?.clear()
                                            onSelectionChanged?.invoke(null)
                                        }
                                        else -> return@onKeyEvent false
                                    }
                                    true
                                }
                            }
                    },
                )
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
                    // TalkBack's actions menu, so a screen-reader user can step
                    // through the values without a keyboard and without having
                    // to place a finger accurately on a 3-pixel line.
                    if (stepActions.isNotEmpty()) customActions = stepActions
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
                    .forEach { it.draw(this, contextFor(it)) }
            }
            geometry.renderers.filterNot { it.clipToPlot }
                .forEach { it.draw(this, contextFor(it)) }

            geometry.domainAxis?.let { drawAxis(it, plot, renderContext) }
            // Every value axis, each at the offset the layout engine measured.
            geometry.valueAxes.forEach { drawAxis(it, plot, renderContext) }
            // Last: a readout chip sits at the same row as one of its axis' own
            // tick labels, and an axis drawn afterwards would print straight
            // through it.
            drawAxisReadouts(axisReadouts, plot, renderContext)
        }

        if (geometry.isEmpty) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { emptyContent() }
        }

        // Above the canvas and below the tooltip: overlay content is part of the
        // chart's picture, and a tooltip is a transient thing that goes over
        // all of it.
        if (overlay != null && !geometry.isEmpty) {
            val scope = remember(geometry) {
                ChartOverlayScopeImpl(geometry) { value ->
                    geometry.domainPositionOf(xResolver.resolveOrDefault(value))
                }
            }
            Box(Modifier.fillMaxSize()) { scope.overlay() }
        }

        if (tooltipData != null && tooltip != null && !geometry.isEmpty &&
            (!renderMode.isStatic || staticOptions.showTooltip)
        ) {
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
    axisContexts: Map<io.devkit.chartkit.axis.ChartAxisId, ChartRenderContext>,
    hitTestMode: HitTestMode,
    interaction: ChartInteraction,
    state: ChartState<Any?>,
    viewportState: ChartViewportState,
    sharedCrosshair: ChartSharedCrosshairState?,
    chartId: Any,
    onSelectionChanged: ((AnyChartSelection?) -> Unit)?,
    onRangeSelectionChanged: ((AnyChartRangeSelection?) -> Unit)?,
): ChartGestureCallbacks {
    val rangeAnchor = remember(geometry) { mutableStateOf<Double?>(null) }

    return remember(geometry, interaction, hitTestMode, state, viewportState, sharedCrosshair) {
        // A layer is hit-tested against its own axis' coordinates, so a tap on
        // a conversion-rate line resolves against percentages rather than
        // against pounds.
        fun contextOf(renderer: io.devkit.chartkit.layer.ChartLayerRenderer): ChartRenderContext =
            axisContexts[geometry.axisOf(renderer)] ?: renderContext

        fun select(point: ChartOffset, mode: HitTestMode) {
            val best = geometry.hitTestable
                .mapNotNull { it.hitTest(point, contextOf(it), mode) }
                .minByOrNull { candidate ->
                    val dx = candidate.position.x - point.x
                    val dy = candidate.position.y - point.y
                    dx * dx + dy * dy
                }
            if (best != null && best != state.selection) {
                state.selection = best
                onSelectionChanged?.invoke(best)
            }
            // Published from a gesture, and only from a gesture. Every linked
            // chart reads it; none of them writes back.
            if (best != null) sharedCrosshair?.publish(best.x, chartId)
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
                        sharedCrosshair?.clear()
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
                    sharedCrosshair?.clear()
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
