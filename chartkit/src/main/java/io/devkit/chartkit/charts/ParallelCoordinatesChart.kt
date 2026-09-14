package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartInsets
import io.devkit.chartkit.geometry.ParallelAxisSpec
import io.devkit.chartkit.geometry.ParallelLayout
import io.devkit.chartkit.layer.comparison.ParallelAxisLabels
import io.devkit.chartkit.layer.comparison.ParallelLayer
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.state.ChartParallelBrushState
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.state.rememberParallelBrushState
import io.devkit.chartkit.theme.ChartKitTheme
import kotlin.math.abs

/**
 * One dimension of a parallel-coordinates chart.
 *
 * @param label the axis' name.
 * @param value the number this dimension reads off a row, or `null`.
 * @param domain the interval to scale against, or `null` to take the data's own
 *   minimum and maximum. Fix it when two charts must be comparable, or when the
 *   axis means something the data does not reach — a score out of ten where
 *   nobody scored ten.
 */
class ParallelDimension<T>(
    val label: String,
    val domain: NumericDomain? = null,
    // Last, so it can be passed as a trailing lambda:
    // `ParallelDimension("Price") { it.price }`.
    val value: (T) -> Number?,
)

/**
 * Many rows compared across many measures.
 *
 * ```kotlin
 * ParallelCoordinatesChart(
 *     data = cars,
 *     dimensions = listOf(
 *         ParallelDimension("Price") { it.price },
 *         ParallelDimension("MPG") { it.mpg },
 *         ParallelDimension("Power") { it.horsepower },
 *         ParallelDimension("Weight") { it.weightKg },
 *     ),
 *     group = { it.maker },
 *     modifier = Modifier.fillMaxWidth().height(320.dp),
 * )
 * ```
 *
 * ```text
 *  price   mpg    hp    weight
 *    │      │      │      │
 *    ├──────┼──╲   │   ╱──┤      one polyline = one row
 *    │   ╲  │   ╲──┼──╱   │
 *    ├────╲─┼──────┼──────┤
 * ```
 *
 * ### Every axis has its own domain
 *
 * That is what makes the chart work at all: the dimensions are in different
 * units, and forcing them onto one scale would flatten every axis but the
 * largest into a line along the bottom. The consequence is worth stating
 * plainly, because the picture does not — **vertical position is comparable
 * only within an axis.** A line high on two axes is high on each of them
 * separately; it is not "higher overall", because there is no overall.
 *
 * ### What this does that a radar cannot
 *
 * [RadarChart] is the other multivariate view and it degrades past six or eight
 * metrics: the spokes crowd, and the polygon becomes a shape rather than a
 * reading. This takes ten or twenty dimensions and hundreds of rows, and trades
 * the radar's single readable silhouette for the ability to see *groups* of rows
 * behaving alike.
 *
 * ### Brushing is the point
 *
 * A plot of any size is a thicket, and its value is not in reading one line but
 * in asking "which rows are high here **and** low there". Drag down an axis to
 * brush a range; the rows outside it are muted rather than removed, because a
 * reader brushing is comparing a subset against the whole — and removing rows
 * would rescale the axes under the finger doing the dragging.
 *
 * Tap a brushed axis to clear it. That gesture has to exist separately, because
 * a drag both clears and rebrushes — it clears on touch-down and rebrushes as
 * the finger moves — so no drag can ever leave an axis unfiltered. A tap that
 * lands on an unbrushed axis selects a row as usual.
 *
 * Hoist [brushState] to read the filters, set them from code, or drive a reset
 * control with [ChartParallelBrushState.clearAll].
 *
 * @param group an optional category per row, which colours the lines and fills
 *   the legend. Without it every line takes one colour, which is the right
 *   picture when the rows are not of distinguishable kinds.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun <T> ParallelCoordinatesChart(
    data: List<T>,
    dimensions: List<ParallelDimension<T>>,
    modifier: Modifier = Modifier,
    group: ((T) -> String)? = null,
    groupColor: ((Int) -> Int?)? = null,
    labels: ParallelAxisLabels = ParallelAxisLabels.Names,
    lineWidth: Dp? = null,
    brushingEnabled: Boolean = true,
    legend: LegendPosition = if (group != null) LegendPosition.Bottom else LegendPosition.None,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    animation: ChartAnimation = ChartAnimation.Default,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    brushState: ChartParallelBrushState = rememberParallelBrushState(),
    state: ChartState<Any?> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<Any?>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<Any?>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter)
    },
    seriesId: String = ChartDefaults.SINGLE_SERIES_ID,
    seriesName: String = "Rows",
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    val density = LocalDensity.current
    val theme = ChartKitTheme.current

    val groups = remember(data, group) {
        if (group == null) emptyList() else LinkedHashSet(data.map(group)).toList()
    }
    // Resolved once into an array rather than per frame: `indexOf` over the
    // group list is linear, and a chart of a thousand rows would otherwise walk
    // it a thousand times on every layout.
    val groupByRow: IntArray = remember(data, group, groups) {
        val accessor = group
        if (accessor == null) {
            IntArray(data.size)
        } else {
            IntArray(data.size) { row -> groups.indexOf(accessor(data[row])).coerceAtLeast(0) }
        }
    }
    val groupIndexOf: (Int) -> Int = remember(groupByRow) {
        { row -> groupByRow.getOrElse(row) { 0 } }
    }

    val specs = remember(data, dimensions) {
        dimensions.map { dimension ->
            ParallelAxisSpec(
                label = dimension.label,
                values = data.map { dimension.value(it)?.toDouble() },
                domain = dimension.domain,
            )
        }
    }

    val stroke = with(density) { (lineWidth ?: theme.dimensions.parallelLineWidth).toPx() }

    // Room above and below the axes for the names and the range readouts,
    // measured from the font rather than guessed at.
    val textMeasurer = rememberTextMeasurer()
    val inset = remember(labels, theme, density) {
        if (labels == ParallelAxisLabels.None) {
            0f
        } else {
            with(density) {
                textMeasurer.measure("Ag", theme.typography.axisLabel, maxLines = 1)
                    .size.height.toFloat() + theme.dimensions.labelPadding.toPx() * 2f
            }
        }
    }
    val padding = with(density) { theme.dimensions.contentPadding.toPx() }

    // The end axes stand on the edges of the content box and their names are
    // centred on them, so half of each name falls outside it. Measured from the
    // widest name rather than guessed at — and capped, so one very long
    // dimension name costs its own label rather than squeezing every axis
    // towards the middle.
    val sideInset = remember(dimensions, labels, theme, density) {
        if (labels == ParallelAxisLabels.None) {
            0f
        } else {
            with(density) {
                val widest = dimensions.maxOfOrNull { dimension ->
                    textMeasurer.measure(dimension.label, theme.typography.axisLabel, maxLines = 1)
                        .size.width.toFloat()
                } ?: 0f
                (widest / 2f).coerceAtMost(MAX_SIDE_INSET.toPx())
            }
        }
    }

    // Where the drag started, in pixels. Kept here rather than in the brush
    // state because it is gesture scratch, not a filter — hoisting it would
    // put a half-finished drag into state a caller is meant to read.
    var dragOrigin by remember { mutableStateOf<Float?>(null) }

    PlanarChartCore(
        layers = { coordinates ->
            val geometry = ParallelLayout.layout(
                specs = specs,
                bounds = coordinates.contentBounds,
                groupOf = groupIndexOf,
                inset = inset,
            )
            listOf(
                ParallelLayer(
                    id = "parallel",
                    geometry = geometry,
                    groupNames = groups,
                    items = { row -> data.getOrNull(row) },
                    rowValues = { row, axis -> specs.getOrNull(axis)?.values?.getOrNull(row) },
                    seriesId = seriesId,
                    seriesName = seriesName,
                    valueFormatter = valueFormatter,
                    labels = labels,
                    brushState = brushState.takeIf { brushingEnabled },
                    lineWidth = stroke,
                    groupColorOf = groupColor,
                ),
            )
        },
        modifier = modifier,
        legend = legend,
        legendItems = remember(groups, groupColor) {
            groups.mapIndexed { index, name ->
                ChartKeyItem(
                    id = name,
                    label = name,
                    paletteIndex = index,
                    colorOverride = groupColor?.invoke(index),
                )
            }
        },
        animation = animation,
        tapSelects = true,
        clearOnTapOutside = true,
        state = state,
        valueFormatter = valueFormatter,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        renderMode = renderMode,
        staticOptions = staticOptions,
        isEmpty = data.isEmpty() || dimensions.size < 2,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
        onSelectionChanged = onSelectionChanged,
        tooltip = tooltip,
        contentInsets = ChartInsets(padding + sideInset, padding, padding + sideInset, padding),
        dragMode = if (brushingEnabled && renderMode.isInteractive) {
            PlanarDragMode.DragItems
        } else {
            PlanarDragMode.None
        },
        onDragStart = { point, coordinates ->
            // The axis the finger came down nearest, resolved once. Re-resolving
            // it per frame would hand the brush to whichever axis the drag
            // happened to cross on its way down.
            val axes = ParallelLayout.axes(specs, coordinates.contentBounds, inset)
            val nearest = axes.minByOrNull { abs(it.x - point.x) }
            val reach = with(density) { theme.dimensions.parallelBrushReach.toPx() }
            if (nearest != null && abs(nearest.x - point.x) <= reach) {
                brushState.activeAxis = nearest.index
                dragOrigin = point.y
                // Cleared on touch-down so a new drag replaces the old brush
                // rather than appearing to extend it.
                brushState.clear(nearest.index)
            }
        },
        onDrag = { point, coordinates ->
            val axisIndex = brushState.activeAxis ?: return@PlanarChartCore
            val origin = dragOrigin ?: return@PlanarChartCore
            val axes = ParallelLayout.axes(specs, coordinates.contentBounds, inset)
            val axis = axes.getOrNull(axisIndex) ?: return@PlanarChartCore
            val a = axis.valueAt(origin)
            val b = axis.valueAt(point.y)
            brushState.brush(axisIndex, minOf(a, b)..maxOf(a, b))
        },
        onDragEnd = {
            brushState.activeAxis = null
            dragOrigin = null
        },
        onTap = onTap@{ point, coordinates ->
            // A tap on a brushed axis clears it. Every drag that starts on an
            // axis also ends in a brush — it clears on touch-down and rebrushes
            // as the finger moves — so without this there would be no gesture
            // that removes one, and a reader could only widen a filter they had
            // narrowed by mistake.
            if (!brushingEnabled || brushState.isEmpty) return@onTap false
            val axes = ParallelLayout.axes(specs, coordinates.contentBounds, inset)
            val nearest = axes.minByOrNull { abs(it.x - point.x) } ?: return@onTap false
            val reach = with(density) { theme.dimensions.parallelBrushReach.toPx() }
            if (abs(nearest.x - point.x) > reach) return@onTap false
            if (nearest.index !in brushState.ranges) return@onTap false
            brushState.clear(nearest.index)
            // Handled, so the tap does not also select whichever line happened
            // to pass near the axis.
            true
        },
    )
}

/** However long a dimension's name, the axes keep most of the width. */
private val MAX_SIDE_INSET = 48.dp
