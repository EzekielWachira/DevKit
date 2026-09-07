package io.devkit.chartkit.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import io.devkit.chartkit.components.tooltip.tooltipOffset
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.interaction.ChartSelectionBehaviour
import io.devkit.chartkit.interaction.ChartSelectionMode
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.state.ChartState
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
    selectionMode: ChartSelectionMode,
    selectionBehaviour: ChartSelectionBehaviour,
    hitTestMode: HitTestMode,
    state: ChartState<Any?>,
    onSelectionChanged: ((AnyChartSelection?) -> Unit)?,
    tooltip: (@Composable (AnyChartSelection) -> Unit)?,
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
                        selectionMode = selectionMode,
                        selectionBehaviour = selectionBehaviour,
                        hitTestMode = hitTestMode,
                        state = state,
                        onSelectionChanged = onSelectionChanged,
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
    selectionMode: ChartSelectionMode,
    selectionBehaviour: ChartSelectionBehaviour,
    hitTestMode: HitTestMode,
    state: ChartState<Any?>,
    onSelectionChanged: ((AnyChartSelection?) -> Unit)?,
    tooltip: (@Composable (AnyChartSelection) -> Unit)?,
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
    val geometry = remember(
        layers, size, orientation, domainAxisConfig, valueAxisConfig, grid,
        valueDomainPolicy, theme, density, locale, accessibility,
    ) {
        buildCartesianGeometry(
            bounds = ChartRect.fromSize(size.width.toFloat(), size.height.toFloat()),
            layers = layers,
            orientation = orientation,
            domainAxisConfig = domainAxisConfig,
            valueAxisConfig = valueAxisConfig,
            grid = grid,
            valueDomainPolicy = valueDomainPolicy,
            density = density,
            textMeasurer = textMeasurer,
            typography = theme.typography,
            dimensions = theme.dimensions,
            locale = locale,
            accessibility = accessibility,
        )
    }

    val selection = state.selection
    val summary = remember(geometry, accessibilitySummary, accessibility) {
        accessibilitySummary?.invoke()
            ?: buildChartSummary(accessibility, geometry.summaries, geometry.valueFormatter)
    }
    val selectionText = selection?.let {
        describeSelection(
            seriesName = it.seriesName,
            xLabel = it.xLabel,
            value = it.y,
            formatter = geometry.valueFormatter,
            multiSeries = geometry.summaries.size > 1,
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .chartGestures(
                    geometry = geometry,
                    orientation = orientation,
                    selectionMode = selectionMode,
                    selectionBehaviour = selectionBehaviour,
                    hitTestMode = hitTestMode,
                    theme = theme,
                    density = density,
                    textMeasurer = textMeasurer,
                    reveal = reveal,
                    state = state,
                    onSelectionChanged = onSelectionChanged,
                )
                // One description for the whole chart, replacing the child
                // semantics rather than adding to them: a Canvas has none worth
                // merging, and a screen reader given both a summary and a stray
                // node reads the chart twice.
                .clearAndSetSemantics {
                    contentDescription = listOfNotNull(summary, selectionText).joinToString(" ")
                    if (selectionText != null) liveRegion = LiveRegionMode.Polite
                },
        ) {
            if (geometry.isEmpty || geometry.coordinates.plotArea.isEmpty) return@Canvas
            val context = ChartRenderContext(
                coordinates = geometry.coordinates,
                colors = theme.colors,
                typography = theme.typography,
                dimensions = theme.dimensions,
                density = density,
                textMeasurer = textMeasurer,
                reveal = reveal,
                selection = selection,
            )
            geometry.renderers.forEach { it.draw(this, context) }
            geometry.domainAxis?.let { drawAxis(it, geometry.coordinates.plotArea, context) }
            geometry.valueAxis?.let { drawAxis(it, geometry.coordinates.plotArea, context) }
        }

        if (geometry.isEmpty) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { emptyContent() }
        }

        if (selection != null && tooltip != null && !geometry.isEmpty) {
            TooltipOverlay(
                selection = selection,
                plot = geometry.coordinates.plotArea,
                gap = with(density) { theme.dimensions.tooltipPadding.toPx() },
                content = tooltip,
            )
        }
    }
}

/**
 * Places the tooltip so it points at the selection and stays inside the chart.
 *
 * Measured rather than offset by a constant: a tooltip nudged a fixed distance
 * up and to the right leaves the chart for any selection near the top-right
 * corner, which on a rising series is the most interesting point on it.
 */
@Composable
private fun TooltipOverlay(
    selection: AnyChartSelection,
    plot: ChartRect,
    gap: Float,
    content: @Composable (AnyChartSelection) -> Unit,
) {
    var tooltipSize by remember { mutableStateOf(IntSize.Zero) }
    val offset = remember(selection, plot, tooltipSize, gap) {
        tooltipOffset(
            anchorX = selection.position.x,
            anchorY = selection.position.y,
            tooltipWidth = tooltipSize.width,
            tooltipHeight = tooltipSize.height,
            bounds = plot,
            gap = gap,
        )
    }
    Box(
        Modifier
            .wrapContentSize(align = Alignment.TopStart, unbounded = true)
            .offsetPx(offset.x, offset.y)
            .onSizeChanged { tooltipSize = it },
    ) {
        content(selection)
    }
}

private fun Modifier.offsetPx(x: Int, y: Int): Modifier = this.offset { IntOffset(x, y) }

/**
 * Tap and scrub selection.
 *
 * Scrubbing is detected on the **domain axis only** — horizontally for a
 * vertical chart, vertically for a horizontal one — which is what lets a chart
 * live inside a vertically scrolling screen without stealing the scroll. A
 * general drag detector here would make the page unscrollable wherever a chart
 * covered it.
 */
@Suppress("LongParameterList")
private fun Modifier.chartGestures(
    geometry: CartesianGeometry,
    orientation: ChartOrientation,
    selectionMode: ChartSelectionMode,
    selectionBehaviour: ChartSelectionBehaviour,
    hitTestMode: HitTestMode,
    theme: io.devkit.chartkit.theme.ChartTheme,
    density: androidx.compose.ui.unit.Density,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    reveal: Float,
    state: ChartState<Any?>,
    onSelectionChanged: ((AnyChartSelection?) -> Unit)?,
): Modifier {
    if (selectionMode == ChartSelectionMode.None) return this

    fun context(): ChartRenderContext = ChartRenderContext(
        coordinates = geometry.coordinates,
        colors = theme.colors,
        typography = theme.typography,
        dimensions = theme.dimensions,
        density = density,
        textMeasurer = textMeasurer,
        reveal = reveal,
        selection = state.selection,
    )

    fun select(point: ChartOffset, mode: HitTestMode) {
        val ctx = context()
        val best = geometry.hitTestable
            .mapNotNull { it.hitTest(point, ctx, mode) }
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

    var result = this
    if (selectionMode.allowsTap) {
        result = result.pointerInput(geometry, hitTestMode) {
            detectTapGestures { offset ->
                val point = ChartOffset(offset.x, offset.y)
                if (!geometry.coordinates.plotArea.contains(point)) {
                    if (selectionBehaviour.clearOnTapOutside) {
                        state.clearSelection()
                        onSelectionChanged?.invoke(null)
                    }
                    return@detectTapGestures
                }
                select(point, hitTestMode)
            }
        }
    }
    if (selectionMode.allowsScrub) {
        result = result.pointerInput(geometry) {
            val onScrub: (androidx.compose.ui.geometry.Offset) -> Unit = { position ->
                val point = ChartOffset(position.x, position.y)
                state.pointerPosition = point
                select(point, HitTestMode.NearestDomain)
            }
            val onEnd: () -> Unit = {
                state.pointerPosition = null
                if (selectionBehaviour.clearOnScrubEnd) {
                    state.clearSelection()
                    onSelectionChanged?.invoke(null)
                }
            }
            if (orientation.isVertical) {
                detectHorizontalDragGestures(
                    onDragStart = onScrub,
                    onDragEnd = onEnd,
                    onDragCancel = onEnd,
                ) { change, _ -> onScrub(change.position) }
            } else {
                detectVerticalDragGestures(
                    onDragStart = onScrub,
                    onDragEnd = onEnd,
                    onDragCancel = onEnd,
                ) { change, _ -> onScrub(change.position) }
            }
        }
    }
    return result
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
