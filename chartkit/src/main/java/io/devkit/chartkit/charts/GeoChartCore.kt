package io.devkit.chartkit.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
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
import io.devkit.chartkit.components.legend.ChartColorLegend
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.components.overlay.ChartOverlay
import io.devkit.chartkit.coordinate.GeoCoordinates
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geo.GeoProjection
import io.devkit.chartkit.geo.ProjectedBounds
import io.devkit.chartkit.geo.projectedBounds
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.interaction.GeoInteraction
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.AnyChartTooltipData
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.scale.ColorScale
import io.devkit.chartkit.state.ChartGeoViewportState
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.theme.ChartKitTheme

/**
 * The engine for maps.
 *
 * The fourth sibling of [CartesianChartCore], [PolarChartCore] and
 * [PlanarChartCore], and — like the polar engine before it — deliberately not a
 * second rendering stack. A choropleth differs from a bar chart in exactly
 * three places: it builds [GeoCoordinates] instead of Cartesian ones, its
 * legend explains a colour scale rather than a set of series, and its camera is
 * two-dimensional. Everything else arrives from where it always has — the
 * theme, the animation clock, the layer list, the selection state, the tooltip
 * overlay, the accessibility summary, the static render mode.
 *
 * That is the whole argument for adding maps to ChartKit rather than shipping a
 * mapping SDK: the cost is a coordinate system, a layer and this file.
 *
 * ```text
 * projected extent  ─┐
 * plot size          ├─→ GeoCoordinates ─→ layers ─→ Canvas
 * camera (zoom/pan) ─┘                        ↑
 *                                    hit test ┘─→ selection ─→ tooltip
 * ```
 *
 * @param extent the projected bounding box of the geography, computed once by
 *   the caller from the geometry it already projected.
 * @param onFocusRequested asked to translate a pending region focus into a
 *   camera, once the plot size is known.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun GeoChartCore(
    layers: (GeoCoordinates) -> List<ChartLayerRenderer>,
    projection: GeoProjection,
    extent: ProjectedBounds?,
    viewportState: ChartGeoViewportState,
    modifier: Modifier,
    legend: LegendPosition,
    colorScale: ColorScale?,
    legendTitle: String?,
    missingLabel: String?,
    animation: ChartAnimation,
    interaction: GeoInteraction,
    clearOnTapOutside: Boolean,
    background: Color,
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
    tooltip: (@Composable (AnyChartTooltipData) -> Unit)? = null,
    overlayContent: (@Composable (GeoCoordinates) -> Unit)? = null,
) {
    val theme = ChartKitTheme.current

    val legendSlot: @Composable (Modifier) -> Unit = { legendModifier ->
        if (colorScale != null) {
            // A map's legend explains the *scale*, not a list of series: every
            // region belongs to one series, and naming two hundred counties in
            // a legend would be a table, not a key.
            ChartColorLegend(
                scale = colorScale,
                modifier = legendModifier,
                formatter = valueFormatter,
                title = legendTitle,
                missingLabel = missingLabel,
            )
        }
    }

    Column(modifier.defaultMinSize(minHeight = theme.dimensions.defaultChartHeight)) {
        if (legend == LegendPosition.Top) {
            legendSlot(Modifier.fillMaxWidth().padding(bottom = theme.dimensions.labelPadding))
        }
        Row(Modifier.weight(1f, fill = true)) {
            if (legend == LegendPosition.Start) {
                legendSlot(Modifier.padding(end = theme.dimensions.legendItemSpacing))
            }
            Box(Modifier.weight(1f, fill = true)) {
                when {
                    error != null -> errorContent(error)
                    isLoading -> loadingContent()
                    isEmpty || extent == null -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { emptyContent() }
                    else -> GeoPlot(
                        layers = layers,
                        projection = projection,
                        extent = extent,
                        viewportState = viewportState,
                        animation = animation,
                        interaction = interaction,
                        clearOnTapOutside = clearOnTapOutside,
                        background = background,
                        state = state,
                        valueFormatter = valueFormatter,
                        accessibility = accessibility,
                        accessibilitySummary = accessibilitySummary,
                        renderMode = renderMode,
                        staticOptions = staticOptions,
                        onSelectionChanged = onSelectionChanged,
                        tooltip = tooltip,
                        overlayContent = overlayContent,
                    )
                }
            }
            if (legend == LegendPosition.End) {
                legendSlot(Modifier.padding(start = theme.dimensions.legendItemSpacing))
            }
        }
        if (legend == LegendPosition.Bottom) {
            legendSlot(Modifier.fillMaxWidth().padding(top = theme.dimensions.labelPadding))
        }
    }
}

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
private fun GeoPlot(
    layers: (GeoCoordinates) -> List<ChartLayerRenderer>,
    projection: GeoProjection,
    extent: ProjectedBounds,
    viewportState: ChartGeoViewportState,
    animation: ChartAnimation,
    interaction: GeoInteraction,
    clearOnTapOutside: Boolean,
    background: Color,
    state: ChartState<Any?>,
    valueFormatter: ChartValueFormatter,
    accessibility: ChartAccessibility,
    accessibilitySummary: (() -> String)?,
    renderMode: ChartRenderMode,
    staticOptions: ChartStaticOptions,
    onSelectionChanged: ((AnyChartSelection?) -> Unit)?,
    tooltip: (@Composable (AnyChartTooltipData) -> Unit)?,
    overlayContent: (@Composable (GeoCoordinates) -> Unit)?,
) {
    val theme = ChartKitTheme.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val reveal = if (renderMode.isStatic) 1f else rememberChartReveal(animation)
    var size by remember { mutableStateOf(IntSize.Zero) }

    val padding = with(density) { theme.dimensions.geoMapPadding.toPx() }

    val coordinates = remember(
        size,
        projection,
        extent,
        padding,
        viewportState.zoom,
        viewportState.panX,
        viewportState.panY,
    ) {
        GeoCoordinates(
            plotArea = ChartRect.fromSize(size.width.toFloat(), size.height.toFloat()),
            projection = projection,
            extent = extent,
            zoom = viewportState.zoom,
            panX = viewportState.panX,
            panY = viewportState.panY,
            padding = padding,
        )
    }

    // A focus request arrives without a plot size — the caller has a region, not
    // a camera — so it is resolved here, on the first frame that has one, and
    // consumed so it does not re-fire on every recomposition.
    LaunchedEffect(coordinates, viewportState.pendingFocus) {
        if (!coordinates.isDrawable) return@LaunchedEffect
        val target = viewportState.pendingFocus ?: return@LaunchedEffect
        val projected = projection.projectedBounds(target)
        viewportState.consumeFocus()
        if (projected == null) return@LaunchedEffect
        val zoom = coordinates.zoomToFit(projected)
        val (panX, panY) = coordinates.panToCentre(projected, zoom)
        viewportState.animateTo(zoom, panX, panY, animation)
    }

    val renderers = remember(coordinates, layers) { layers(coordinates) }

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
        renderers.firstNotNullOfOrNull { it.describeSelection(selected, valueFormatter) }
            ?: "${selected.seriesName}: ${valueFormatter.format(selected.y)}."
    }

    val showTooltip = tooltip != null && (!renderMode.isStatic || staticOptions.showTooltip)
    val tooltipData: AnyChartTooltipData? = remember(selection, renderers) {
        selection?.let { selected ->
            ChartTooltipData(
                selection = selected,
                entries = listOf(
                    ChartTooltipEntry(
                        seriesId = selected.seriesId,
                        seriesName = selected.seriesName,
                        // Absent rather than zero: `Geo.hasValue` is what says
                        // whether a region was measured, and a tooltip reading
                        // "0" over an unmeasured county states a fact nobody
                        // collected.
                        value = selected.geo?.let { if (it.hasValue) selected.y else null }
                            ?: selected.y,
                        item = selected.item,
                        paletteIndex = selected.pointIndex,
                    ),
                ),
                anchor = selected.position,
                xLabel = selected.geo?.featureLabel?.takeIf { it.isNotBlank() } ?: selected.xLabel,
                valueFormatter = valueFormatter,
            )
        }
    }

    // **Backwards**, so the layer drawn last is tested first. Layers are drawn
    // bottom-up — base map, then choropleth, then routes, then marks — and a
    // tap must select what the reader can actually see at that point. Testing
    // forwards would let the base map under a bubble swallow every tap meant
    // for the bubble.
    fun select(point: ChartOffset): AnyChartSelection? = renderers
        .asReversed()
        .firstNotNullOfOrNull { it.hitTest(point, renderContext, HitTestMode.Contains) }

    val interactive = !renderMode.isStatic
    val tapSelects = interaction.select
    val panEnabled = interaction.pan
    val zoomEnabled = interaction.zoom

    fun applySelection(hit: AnyChartSelection?) {
        when {
            hit != null && hit != state.selection -> {
                state.selection = hit
                onSelectionChanged?.invoke(hit)
            }
            hit == null && clearOnTapOutside && state.selection != null -> {
                state.clearSelection()
                onSelectionChanged?.invoke(null)
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
                .then(
                    if (!interactive || !tapSelects) {
                        Modifier
                    } else {
                        Modifier.pointerInput(coordinates, renderers, interaction.doubleTapZoom) {
                            detectTapGestures(
                                onTap = { offset ->
                                    applySelection(select(ChartOffset(offset.x, offset.y)))
                                },
                                // Installed only when the caller asked for it.
                                // `detectTapGestures` withholds `onTap` for the
                                // whole double-tap window while a double-tap
                                // handler exists, so this shortcut costs every
                                // *single* tap roughly a third of a second —
                                // which is why `GeoInteraction.doubleTapZoom`
                                // defaults to off and this stays `null`.
                                onDoubleTap = if (!interaction.doubleTapZoom || !zoomEnabled) {
                                    null
                                } else {
                                    { offset ->
                                        val width = size.width.toFloat()
                                        val height = size.height.toFloat()
                                        if (width > 0f && height > 0f) {
                                            // About the tapped point, so the
                                            // place the reader aimed at stays
                                            // under their finger.
                                            viewportState.zoomBy(
                                                factor = DOUBLE_TAP_ZOOM,
                                                focusX = offset.x / width,
                                                focusY = offset.y / height,
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    },
                )
                .then(
                    // Pinch and two-finger pan together, from one gesture
                    // detector. Two competing `pointerInput` modifiers would
                    // each see part of the stream and fight over it.
                    if (!interactive || !zoomEnabled) {
                        Modifier
                    } else {
                        Modifier.pointerInput(coordinates) {
                            detectTransformGestures { centroid, pan, gestureZoom, _ ->
                                val width = size.width.toFloat().takeIf { it > 0f } ?: return@detectTransformGestures
                                val height = size.height.toFloat().takeIf { it > 0f } ?: return@detectTransformGestures
                                if (gestureZoom != 1f) {
                                    viewportState.zoomBy(
                                        factor = gestureZoom,
                                        focusX = centroid.x / width,
                                        focusY = centroid.y / height,
                                    )
                                }
                                if (panEnabled) {
                                    viewportState.panBy(pan.x / width, pan.y / height)
                                }
                            }
                        }
                    },
                )
                .then(
                    // A single-finger drag pans, but only once zoomed: at full
                    // extent the whole map is already showing, so a drag that
                    // moved it would only take geography off screen.
                    if (!interactive || !panEnabled || zoomEnabled && viewportState.isReset) {
                        Modifier
                    } else {
                        Modifier.pointerInput(coordinates, viewportState.isReset) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    if (viewportState.isReset) return@detectDragGestures
                                    change.consume()
                                    val width = size.width.toFloat()
                                    val height = size.height.toFloat()
                                    if (width > 0f && height > 0f) {
                                        viewportState.panBy(
                                            dragAmount.x / width,
                                            dragAmount.y / height,
                                        )
                                    }
                                },
                            )
                        }
                    },
                )
                .then(
                    // Hover, where there is a pointer that can hover. A mouse
                    // or a stylus moving over the map selects what is under it
                    // through the *same* hit test a tap uses, so a desktop
                    // reader and a touch reader are looking at the same
                    // selection model rather than two that drifted apart.
                    if (!interactive || !interaction.hover || !tapSelects) {
                        Modifier
                    } else {
                        Modifier.pointerInput(coordinates, renderers) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    when (event.type) {
                                        PointerEventType.Move -> {
                                            val position = event.changes.lastOrNull()?.position
                                            if (position != null) {
                                                applySelection(
                                                    select(ChartOffset(position.x, position.y)),
                                                )
                                            }
                                        }
                                        PointerEventType.Exit ->
                                            if (clearOnTapOutside) applySelection(null)
                                        else -> Unit
                                    }
                                }
                            }
                        }
                    },
                )
                .then(
                    if (!interactive || !(zoomEnabled || panEnabled)) {
                        Modifier
                    } else {
                        // Keyboard equivalents for every gesture. A map that can
                        // only be explored by pinching excludes anyone using a
                        // keyboard, a switch or a desktop window.
                        Modifier
                            .focusable()
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (event.key) {
                                    Key.DirectionLeft -> viewportState.panBy(KEY_PAN, 0f)
                                    Key.DirectionRight -> viewportState.panBy(-KEY_PAN, 0f)
                                    Key.DirectionUp -> viewportState.panBy(0f, KEY_PAN)
                                    Key.DirectionDown -> viewportState.panBy(0f, -KEY_PAN)
                                    Key.Equals, Key.Plus -> viewportState.zoomBy(KEY_ZOOM)
                                    Key.Minus -> viewportState.zoomBy(1f / KEY_ZOOM)
                                    Key.Zero, Key.Escape -> viewportState.reset()
                                    else -> return@onKeyEvent false
                                }
                                true
                            }
                    },
                )
                .clearAndSetSemantics {
                    contentDescription = listOfNotNull(summary, selectionText).joinToString(" ")
                    if (selectionText != null) liveRegion = LiveRegionMode.Polite
                },
        ) {
            if (!coordinates.isDrawable) return@Canvas
            // Behind the geography and inside the plot: what a reader reads as
            // the ocean, though ChartKit does not know that — it is simply
            // whatever is not a feature. Transparent by default, because a
            // chart sitting in a card should not paint over the card.
            if (background.alpha > 0f) drawRect(background)
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

/** How far one arrow-key press pans, as a fraction of the plot. */
private const val KEY_PAN: Float = 0.1f

/** How much one `+` or `-` press zooms. */
private const val KEY_ZOOM: Float = 1.25f

/** How much one double tap zooms, when the shortcut is turned on. */
private const val DOUBLE_TAP_ZOOM: Float = 2f
