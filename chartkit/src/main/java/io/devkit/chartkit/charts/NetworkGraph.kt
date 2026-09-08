package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.graph.ChartGraph
import io.devkit.chartkit.graph.GraphLayout
import io.devkit.chartkit.graph.GraphLayoutStrategy
import io.devkit.chartkit.graph.GraphValidation
import io.devkit.chartkit.graph.buildChartGraph
import io.devkit.chartkit.layer.graph.GraphLabels
import io.devkit.chartkit.layer.graph.GraphLayer
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.state.ChartGraphLayoutState
import io.devkit.chartkit.state.ChartPlanarViewportState
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.state.rememberGraphLayoutState
import io.devkit.chartkit.state.rememberPlanarViewportState
import io.devkit.chartkit.theme.ChartKitTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.compose.runtime.snapshotFlow
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * A relationship graph over the caller's own nodes and edges.
 *
 * ```kotlin
 * NetworkGraph(
 *     nodes = services,
 *     edges = dependencies,
 *     nodeId = { it.name },
 *     source = { it.from },
 *     target = { it.to },
 *     modifier = Modifier.fillMaxWidth().height(320.dp),
 * )
 * ```
 *
 * Two lists and three lambdas. Nothing is required of `Service` or `Dependency`:
 * no interface, no conversion, no reflection, and a selection hands the
 * caller's own object back.
 *
 * ### Layout runs off the composition
 *
 * A force simulation is a loop over every pair of nodes, stepped in batches on
 * [Dispatchers.Default] and published as immutable snapshots — never one
 * recomposition per iteration, and never inside a draw pass. It stops as soon
 * as it settles and restarts when a node is dragged. Changing the data or the
 * strategy cancels the outstanding work.
 *
 * A graph above
 * [io.devkit.chartkit.graph.ForceSimulation.MAX_SIMULATED] nodes falls back to
 * the circular layout: the pairwise repulsion beyond that costs more than the
 * picture is worth, and a graph of ten thousand nodes is not a picture.
 *
 * ### Deterministic
 *
 * [GraphLayoutStrategy.ForceDirected] is seeded, so the same graph lays out the
 * same way every time. A layout that reshuffled itself on every launch would be
 * unusable and untestable.
 *
 * @param nodeWeight an optional magnitude driving each node's drawn radius.
 * @param layoutState hoist it to switch strategies, pin nodes or restart.
 * @param viewportState hoist it to control zoom and pan from elsewhere.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun <N, E> NetworkGraph(
    nodes: List<N>,
    edges: List<E>,
    nodeId: (N) -> Any,
    source: (E) -> Any,
    target: (E) -> Any,
    modifier: Modifier = Modifier,
    nodeLabel: ((N) -> String)? = null,
    nodeWeight: ((N) -> Number?)? = null,
    nodeColor: ((N) -> Int?)? = null,
    edgeWeight: ((E) -> Number?)? = null,
    edgeLabel: ((E) -> String?)? = null,
    labels: GraphLabels = GraphLabels.Selected,
    validation: GraphValidation = GraphValidation.Drop,
    draggableNodes: Boolean = true,
    legend: LegendPosition = LegendPosition.None,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    animation: ChartAnimation = ChartAnimation.Default,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    layoutState: ChartGraphLayoutState = rememberGraphLayoutState(),
    viewportState: ChartPlanarViewportState = rememberPlanarViewportState(),
    state: ChartState<Any?> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<Any?>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<Any?>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter)
    },
    seriesId: String = ChartDefaults.SINGLE_SERIES_ID,
    seriesName: String = "Graph",
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    val density = LocalDensity.current
    val theme = ChartKitTheme.current

    val graph = remember(nodes, edges, nodeId, source, target, validation) {
        buildChartGraph(
            nodes = nodes,
            edges = edges,
            nodeId = nodeId,
            source = source,
            target = target,
            nodeLabel = nodeLabel,
            nodeWeight = nodeWeight,
            edgeWeight = edgeWeight,
            edgeLabel = edgeLabel,
            validation = validation,
        )
    }

    // A static render must not depend on where a simulation happened to be when
    // the capture was taken. The circular layout is instant and reproducible,
    // which is the whole requirement for an exported picture.
    val effectiveStrategy = if (renderMode.isStatic) {
        GraphLayoutStrategy.Circular(GraphLayout.ByDegree)
    } else {
        layoutState.strategy
    }

    RunGraphLayout(graph, effectiveStrategy, layoutState)

    val radii = remember(graph, theme.dimensions, density) {
        nodeRadii(graph, theme.dimensions, density)
    }

    PlanarChartCore(
        layers = { coordinates ->
            listOf(
                GraphLayer(
                    id = "graph",
                    graph = graph,
                    positions = layoutState.positions,
                    viewport = viewportState,
                    seriesId = seriesId,
                    seriesName = seriesName,
                    labels = labels,
                    nodeRadiusOf = { index -> radii.getOrElse(index) { 0f } },
                    nodeColorOf = nodeColor?.let { accessor ->
                        { index -> nodes.getOrNull(index)?.let(accessor) }
                    },
                    draggedIndex = layoutState.draggedIndex,
                ),
            )
        },
        modifier = modifier,
        legend = legend,
        legendItems = remember(graph) {
            graph.nodes.map { node ->
                ChartKeyItem(
                    id = node.id,
                    label = node.label,
                    paletteIndex = node.index,
                    colorOverride = nodeColor?.let { accessor ->
                        nodes.getOrNull(node.index)?.let(accessor)
                    },
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
        isEmpty = graph.isEmpty,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
        onSelectionChanged = onSelectionChanged,
        tooltip = tooltip,
        dragMode = if (draggableNodes) PlanarDragMode.DragItems else PlanarDragMode.None,
        onDragStart = { point, coordinates ->
            // The node under the finger, resolved once at the start of the
            // gesture. Re-resolving it on every frame would hand the drag to
            // whichever node the finger happened to pass over.
            val index = nodeUnderPointer(graph, layoutState, viewportState, coordinates, point, radii)
            if (index != null) layoutState.beginDrag(index)
        },
        onDrag = { point, coordinates ->
            val fraction = coordinates.fractionOf(point)
            layoutState.dragTo(
                viewportState.untransformX(fraction.x),
                viewportState.untransformY(fraction.y),
            )
        },
        onDragEnd = { layoutState.endDrag() },
    )
}

/**
 * Steps the force simulation, off the composition, until it settles.
 *
 * `collectLatest` on the wake counter is what makes a drag restart the loop
 * without recreating the simulation: the previous stepping block is cancelled
 * and a new one picks up the same object, so the layout carries on from where
 * the reader left it rather than throwing every node somewhere else.
 */
@Composable
private fun RunGraphLayout(
    graph: ChartGraph,
    strategy: GraphLayoutStrategy,
    layoutState: ChartGraphLayoutState,
) {
    LaunchedEffect(graph, strategy) {
        when (strategy) {
            is GraphLayoutStrategy.Circular -> layoutState.placeCircular(graph, strategy)
            is GraphLayoutStrategy.ForceDirected -> {
                snapshotFlow { layoutState.wakeCount }.collectLatest {
                    val simulation = layoutState.simulationFor(graph, strategy)
                    if (!simulation.isSimulatable) {
                        // Too large to simulate: the circular layout is instant,
                        // never overlaps and is reproducible, which beats a
                        // second-per-frame approximation of a force layout.
                        layoutState.placeCircular(
                            graph,
                            GraphLayoutStrategy.Circular(GraphLayout.ByDegree),
                        )
                        return@collectLatest
                    }
                    while (coroutineContext.isActive && !simulation.settled) {
                        withContext(Dispatchers.Default) { simulation.run(STEPS_PER_FRAME) }
                        layoutState.publish(simulation.snapshot())
                        // Published at a frame's cadence rather than per
                        // iteration: four hundred recompositions to show a
                        // picture nobody can read until the end is four hundred
                        // wasted frames.
                        delay(PUBLISH_INTERVAL_MILLIS)
                    }
                    layoutState.publish(simulation.snapshot())
                }
            }
        }
    }
}

/**
 * Each node's drawn radius.
 *
 * A weight encodes as **area**, not radius — a node twice the radius is four
 * times the ink, and reading it as twice the value is the single most common
 * misreading of a bubble.
 */
private fun nodeRadii(
    graph: ChartGraph,
    dimensions: io.devkit.chartkit.theme.ChartDimensions,
    density: androidx.compose.ui.unit.Density,
): FloatArray {
    val plain = with(density) { dimensions.graphNodeRadius.toPx() }
    val minimum = with(density) { dimensions.graphNodeMinRadius.toPx() }
    val maximum = with(density) { dimensions.graphNodeMaxRadius.toPx() }
    val weights = graph.nodes.mapNotNull { it.weight }
    if (weights.isEmpty()) return FloatArray(graph.nodes.size) { plain }

    val low = weights.min()
    val high = weights.max()
    val span = high - low
    return FloatArray(graph.nodes.size) { index ->
        val weight = graph.nodes[index].weight ?: return@FloatArray plain
        val fraction = if (span <= 0.0) 0.5 else (weight - low) / span
        val minArea = minimum * minimum
        val maxArea = maximum * maximum
        sqrt(minArea + (maxArea - minArea) * fraction).toFloat()
    }
}

/**
 * The node under a pointer position, or `null` when the finger missed.
 *
 * Compared in **pixels**, not in unit space: a unit-space threshold would be a
 * different physical size on every chart, and a node's touch target has to be a
 * finger's width whatever the plot's aspect ratio.
 */
private fun nodeUnderPointer(
    graph: ChartGraph,
    layoutState: ChartGraphLayoutState,
    viewport: ChartPlanarViewportState,
    coordinates: io.devkit.chartkit.coordinate.PlanarCoordinates,
    point: ChartOffset,
    radii: FloatArray,
): Int? {
    val positions = layoutState.positions
    if (positions.size < graph.nodes.size) return null
    val bounds = coordinates.contentBounds
    if (bounds.isEmpty) return null

    var best = -1
    var bestDistance = Float.MAX_VALUE
    for (index in 0 until positions.size) {
        val x = bounds.left + viewport.transformX(positions.x[index]) * bounds.width
        val y = bounds.top + viewport.transformY(positions.y[index]) * bounds.height
        val dx = x - point.x
        val dy = y - point.y
        val distance = sqrt(dx * dx + dy * dy)
        val reach = maxOf(radii.getOrElse(index) { 0f }, MIN_DRAG_TARGET)
        if (distance <= reach && distance < bestDistance) {
            bestDistance = distance
            best = index
        }
    }
    return best.takeIf { it >= 0 }
}

/** Nodes are small; fingers are not. In pixels, at the chart's own density. */
private const val MIN_DRAG_TARGET = 24f

/** Enough iterations per publish to make visible progress, few enough to stay responsive. */
private const val STEPS_PER_FRAME = 8

/** Roughly a frame at 30 Hz. */
private const val PUBLISH_INTERVAL_MILLIS = 32L
