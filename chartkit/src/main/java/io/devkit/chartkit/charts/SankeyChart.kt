package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.flow.SankeyLayout
import io.devkit.chartkit.flow.SankeyLayoutSpec
import io.devkit.chartkit.flow.SankeyValidation
import io.devkit.chartkit.flow.buildSankeyGraph
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartInsets
import io.devkit.chartkit.layer.flow.SankeyLabels
import io.devkit.chartkit.layer.flow.SankeyLayer
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.theme.ChartKitTheme

/**
 * A Sankey diagram over the caller's own nodes and links.
 *
 * ```kotlin
 * SankeyChart(
 *     nodes = stages,
 *     links = transitions,
 *     nodeId = { it.id },
 *     nodeLabel = { it.name },
 *     source = { it.from },
 *     target = { it.to },
 *     value = { it.users },
 *     modifier = Modifier.fillMaxWidth().height(280.dp),
 * )
 * ```
 *
 * ```text
 * Search ▇▇▇▇▇▇▇▇▇▇▇ Product ▇▇▇▇▇▇ Checkout ▇▇▇ Purchase
 *        ▒▒▒▒▒▒▒            ▒▒▒▒            ▒
 * ```
 *
 * Two lists and six lambdas. No node type, no edge type, no graph to build: the
 * caller's `Stage` and `Transition` go in and come back on the selection.
 *
 * ### What it will not draw
 *
 * A **cycle**. Columns are assigned by distance from a source, and "A before B"
 * and "B before A" cannot both hold — so the links closing a cycle are cut
 * deterministically and the rest of the diagram is drawn. Unknown node
 * references, self-links and non-positive weights are dropped for related
 * reasons. Pass [SankeyValidation.Reject] to have any of them throw instead of
 * being quietly repaired; see
 * [io.devkit.chartkit.flow.buildSankeyGraph] for what each one means.
 *
 * ### Selecting
 *
 * Tapping a node emphasises everything it connects to and lists its flows in
 * the tooltip; tapping a band selects that one flow. Both hand back a typed
 * selection — [io.devkit.chartkit.layer.flow.SankeyNodeSelection] or
 * [io.devkit.chartkit.layer.flow.SankeyLinkSelection] — carrying the caller's
 * own object.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun <N, L> SankeyChart(
    nodes: List<N>,
    links: List<L>,
    nodeId: (N) -> Any,
    source: (L) -> Any,
    target: (L) -> Any,
    value: (L) -> Number?,
    modifier: Modifier = Modifier,
    nodeLabel: ((N) -> String)? = null,
    nodeColor: ((N) -> Int?)? = null,
    linkLabel: ((L) -> String?)? = null,
    labels: SankeyLabels = SankeyLabels.Outside,
    validation: SankeyValidation = SankeyValidation.Drop,
    orderingPasses: Int = 6,
    legend: LegendPosition = LegendPosition.None,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    animation: ChartAnimation = ChartAnimation.Default,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    state: ChartState<Any?> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<Any?>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<Any?>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter)
    },
    seriesId: String = ChartDefaults.SINGLE_SERIES_ID,
    seriesName: String = "Flow",
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    val density = LocalDensity.current
    val theme = ChartKitTheme.current

    val graph = remember(nodes, links, nodeId, source, target, value, validation) {
        buildSankeyGraph(
            nodes = nodes,
            links = links,
            nodeId = nodeId,
            nodeLabel = { nodeLabel?.invoke(it) ?: nodeId(it).toString() },
            source = source,
            target = target,
            value = value,
            linkLabel = linkLabel,
            validation = validation,
        )
    }

    val spec = remember(theme.dimensions, density, orderingPasses) {
        with(density) {
            SankeyLayoutSpec(
                nodeWidth = theme.dimensions.sankeyNodeWidth.toPx(),
                nodePadding = theme.dimensions.sankeyNodePadding.toPx(),
                iterations = orderingPasses,
            )
        }
    }

    // Labels sit outside the node boxes, so the columns need a gutter at each
    // end. Measured from the widest label rather than guessed at, which is the
    // same rule the Cartesian axes and the radar's spokes follow — an estimate
    // either wastes half the plot or clips the longest name.
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val insets = remember(graph, labels, theme, density) {
        with(density) {
            val padding = theme.dimensions.contentPadding.toPx()
            if (labels == SankeyLabels.None) {
                ChartInsets(padding, padding, padding, padding)
            } else {
                val style = theme.typography.nodeLabel
                val widest = graph.nodes.maxOfOrNull { node ->
                    val text = if (labels == SankeyLabels.OutsideWithValue) {
                        "${node.label}  ${valueFormatter.format(node.throughput)}"
                    } else {
                        node.label
                    }
                    textMeasurer.measure(text, style, maxLines = 1).size.width.toFloat()
                } ?: 0f
                // Capped, so one pathologically long node name costs its own
                // label rather than the whole diagram.
                val gutter = (widest + theme.dimensions.labelPadding.toPx())
                    .coerceAtMost(MAX_LABEL_GUTTER.toPx())
                ChartInsets(
                    left = padding + gutter,
                    top = padding,
                    right = padding + gutter,
                    bottom = padding,
                )
            }
        }
    }

    PlanarChartCore(
        layers = { coordinates ->
            val bounds = coordinates.contentBounds
            val geometry = SankeyLayout.layout(graph, bounds, spec)
            listOf(
                SankeyLayer(
                    id = "sankey",
                    graph = graph,
                    geometry = geometry,
                    seriesId = seriesId,
                    seriesName = seriesName,
                    valueFormatter = valueFormatter,
                    labels = labels,
                    nodeColorOf = nodeColor?.let { accessor ->
                        { index -> nodes.getOrNull(index)?.let(accessor) }
                    },
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
        // Room at both ends for the labels the layer writes outside the boxes.
        contentInsets = insets,
    )
}

/** However long a node's name, a Sankey diagram keeps most of its width. */
private val MAX_LABEL_GUTTER = 96.dp
