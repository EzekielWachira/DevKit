package io.devkit.chartkit.accessibility

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.devkit.chartkit.flow.SankeyGraph
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.graph.ChartGraph
import io.devkit.chartkit.hierarchy.ChartHierarchy
import io.devkit.chartkit.hierarchy.HierarchyNode
import io.devkit.chartkit.timeline.TimelineModel
import io.devkit.chartkit.transform.FunnelStage
import io.devkit.chartkit.transform.WaterfallStep

/**
 * Typed table adapters for the visualisations whose rows are not `x, series,
 * value`.
 *
 * ### One adapter per shape, never reflection
 *
 * A hierarchy's row is a path, a value and two percentages; a flow's is a
 * source, a target and a weight; a timeline's is a start, an end and a lane.
 * Each is written out here, by hand, against the model that produced it.
 *
 * The alternative — walking the caller's objects and guessing at their fields —
 * would need reflection, would break under R8, and would produce column names
 * from property names rather than from what the chart actually plotted.
 */

/**
 * A hierarchy as rows: path, value, share of parent, share of root.
 *
 * The two percentages are the point. A treemap's numbers mean nothing in
 * isolation — "42" is not a reading, "42, which is 18% of Engineering" is — and
 * a table that listed only the raw value would lose exactly what the picture
 * conveys.
 */
fun hierarchyDataTable(
    hierarchy: ChartHierarchy,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    caption: String? = null,
    root: HierarchyNode = hierarchy.root,
    maxDepth: Int = Int.MAX_VALUE,
): ChartDataTable {
    val rows = root.selfAndDescendants()
        .filter { it !== root && it.depth - root.depth <= maxDepth }
        .map { node ->
            listOf(
                node.path.joinToString(" › "),
                valueFormatter.format(node.value),
                percentageOf(node.fractionOfParent),
                percentageOf(node.fractionOf(root)),
            )
        }
    return ChartDataTable(
        columns = listOf("Path", "Value", "Of parent", "Of ${root.label}"),
        rows = rows,
        caption = caption,
    )
}

/**
 * A flow graph as rows: source, target, weight.
 *
 * The **links**, not the nodes. A Sankey diagram's content is what moved from
 * where to where; a table of node totals would answer a question the chart does
 * not ask.
 */
fun sankeyDataTable(
    graph: SankeyGraph,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    caption: String? = null,
): ChartDataTable = ChartDataTable(
    columns = listOf("From", "To", "Value"),
    rows = graph.links.map { link ->
        listOf(
            graph.nodes.getOrNull(link.sourceIndex)?.label.orEmpty(),
            graph.nodes.getOrNull(link.targetIndex)?.label.orEmpty(),
            valueFormatter.format(link.value),
        )
    },
    caption = caption,
)

/** A funnel as rows: stage, value, share of the first stage, conversion, lost. */
fun funnelDataTable(
    stages: List<FunnelStage>,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    caption: String? = null,
): ChartDataTable = ChartDataTable(
    columns = listOf("Stage", "Value", "Of first", "Conversion", "Lost"),
    rows = stages.map { stage ->
        listOf(
            stage.label,
            valueFormatter.format(stage.value),
            percentageOf(stage.fractionOfFirst),
            stage.conversionFromPrevious?.let(::percentageOf).orEmpty(),
            stage.dropOffCount?.let(valueFormatter::format).orEmpty(),
        )
    },
    caption = caption,
)

/** A waterfall as rows: step, change, running total. */
fun waterfallDataTable(
    steps: List<WaterfallStep>,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    caption: String? = null,
): ChartDataTable = ChartDataTable(
    columns = listOf("Step", "Change", "Running total"),
    rows = steps.map { step ->
        listOf(
            step.label,
            if (step.kind.isAnchored) "" else valueFormatter.format(step.delta),
            valueFormatter.format(step.runningTotal),
        )
    },
    caption = caption,
)

/**
 * A timeline as rows: event, lane, start, end.
 *
 * Times are formatted by the caller's own formatter, because the useful
 * precision depends entirely on the span — "09:15" for a day of bookings,
 * "Mar 2025" for a two-year roadmap.
 */
fun timelineDataTable(
    model: TimelineModel,
    formatTime: (Long) -> String,
    caption: String? = null,
): ChartDataTable = ChartDataTable(
    columns = listOf("Event", "Lane", "Start", "End"),
    rows = model.entries.map { entry ->
        listOf(
            entry.label,
            entry.lane,
            formatTime(entry.start),
            entry.end?.let(formatTime).orEmpty(),
        )
    },
    caption = caption,
)

/**
 * A graph as rows: node, connections, neighbours.
 *
 * The nodes rather than the edges, and the neighbours as a list rather than one
 * row per edge: a graph of two hundred edges is four hundred table rows nobody
 * can navigate, while "Auth, 5 connections, Payments, Orders…" is the shape a
 * reader is actually after.
 */
fun graphDataTable(
    graph: ChartGraph,
    caption: String? = null,
    maxNeighbours: Int = 6,
): ChartDataTable = ChartDataTable(
    columns = listOf("Node", "Connections", "Connected to"),
    rows = graph.nodes.map { node ->
        val neighbours = graph.adjacency.getOrNull(node.index).orEmpty()
            .mapNotNull { graph.nodes.getOrNull(it)?.label }
        listOf(
            node.label,
            node.degree.toString(),
            buildString {
                append(neighbours.take(maxNeighbours).joinToString(", "))
                if (neighbours.size > maxNeighbours) {
                    append(" and ${neighbours.size - maxNeighbours} more")
                }
            },
        )
    },
    caption = caption,
)

/**
 * A chart and its table, with a control to switch between them.
 *
 * ```kotlin
 * ChartWithDataTable(
 *     table = chartDataTable(revenue, category = { it.month }, value = { it.amount }),
 * ) {
 *     LineChart(data = revenue, x = { it.month }, y = { it.amount })
 * }
 * ```
 *
 * ### A helper, not the default
 *
 * A tab strip above every chart would be an opinion about layout that no
 * library should impose — it belongs inside a card for one app and in a bottom
 * sheet for another. This exists because the pairing is common and because
 * getting the *semantics* right is fiddly: only one of the two is in the
 * semantics tree at a time, so a screen reader is never handed both the summary
 * and the whole table.
 *
 * The selected view is remembered across configuration changes, because a
 * reader who chose the table did so deliberately.
 */
@Composable
fun ChartWithDataTable(
    table: ChartDataTable,
    modifier: Modifier = Modifier,
    chartLabel: String = "Chart",
    tableLabel: String = "Table",
    chart: @Composable () -> Unit,
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier) {
        TabRow(selectedTabIndex = selected, modifier = Modifier.fillMaxWidth()) {
            Tab(
                selected = selected == 0,
                onClick = { selected = 0 },
                text = { Text(chartLabel) },
            )
            Tab(
                selected = selected == 1,
                onClick = { selected = 1 },
                text = { Text(tableLabel) },
            )
        }
        // One or the other is composed, never both. Keeping the hidden one in
        // the tree — even at zero size — would leave a screen reader reading a
        // table the user is not looking at.
        if (selected == 0) chart() else ChartDataTableView(table)
    }
}

/** A share as a whole-number percentage, or an empty string for a non-value. */
private fun percentageOf(fraction: Double): String =
    if (!fraction.isFinite()) "" else "${Math.round(fraction * 100.0)}%"
