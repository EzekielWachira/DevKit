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
import io.devkit.chartkit.axis.ChartUnit
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.geo.GeoFeature
import io.devkit.chartkit.geo.GeoFeatureCollection
import io.devkit.chartkit.graph.ChartGraph
import io.devkit.chartkit.hierarchy.ChartHierarchy
import io.devkit.chartkit.charts.SetRegionNaming
import io.devkit.chartkit.hierarchy.HierarchyNode
import io.devkit.chartkit.layer.set.defaultRegionName
import io.devkit.chartkit.set.SetDiagramData
import io.devkit.chartkit.set.SetRelationship
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
 * One measure of a combo chart: its series, its axis and how it is written.
 *
 * @param axisTitle what the quantity is called — "Rainfall", "Temperature".
 * @param unit what it is measured in. Written in the table's unit column and
 *   spelled out nowhere else, because a table is read and not spoken.
 * @param value the accessor the chart itself was given, so the table cannot
 *   disagree with what was drawn.
 */
class ComboMeasure<T>(
    val axisTitle: String,
    val series: List<ChartSeries<T>>,
    val category: (T) -> Any?,
    val value: (T) -> Number?,
    val unit: ChartUnit = ChartUnit.None,
    val valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
)

/**
 * A multi-axis chart as rows: x, series, value, unit.
 *
 * ### Why the unit column exists
 *
 * A combo chart's table without one is a column of numbers that cannot be read:
 * `82`, `14.2` and `1018` under a single "Value" heading are three quantities
 * presented as if they were comparable, which is precisely the misreading a
 * second axis invites in the picture and which a table has no excuse for.
 *
 * ```kotlin
 * ChartDataTableView(
 *     table = comboDataTable(
 *         ComboMeasure("Rainfall", rainSeries, { it.month }, { it.mm },
 *             unit = ChartUnit.Custom("mm", "millimetres")),
 *         ComboMeasure("Temperature", tempSeries, { it.month }, { it.celsius },
 *             unit = ChartUnit.Custom("°C", "degrees Celsius")),
 *     ),
 * )
 * ```
 *
 * Rows are grouped by measure, in the order the measures were given — the same
 * order the axes were declared in, so the table reads the way the chart does.
 */
fun comboDataTable(
    vararg measures: ComboMeasure<*>,
    caption: String? = null,
    xColumn: String = "X",
): ChartDataTable {
    val anyUnit = measures.any { it.unit != ChartUnit.None }
    val columns = buildList {
        add(xColumn)
        add("Measure")
        add("Series")
        add("Value")
        if (anyUnit) add("Unit")
    }
    val rows = measures.flatMap { measure -> comboRows(measure, anyUnit) }
    return ChartDataTable(columns = columns, rows = rows, caption = caption)
}

private fun <T> comboRows(measure: ComboMeasure<T>, includeUnit: Boolean): List<List<String>> =
    measure.series.flatMap { series ->
        series.data.map { item ->
            buildList {
                add(measure.category(item).toString())
                add(measure.axisTitle)
                add(series.name.ifBlank { series.id })
                add(measure.value(item)?.toDouble()?.let(measure.valueFormatter::format) ?: "no value")
                if (includeUnit) add(measure.unit.symbol ?: "")
            }
        }
    }

/**
 * A 3D column chart as rows: category, series, stack, value.
 *
 * ### Why a 3D chart still has an ordinary table
 *
 * Nothing in the table mentions a camera, a projection, a face or a depth,
 * because none of those is data. A 3D column chart plots exactly what its 2D
 * counterpart plots — a value, for a series, in a category, in a stack — and
 * the three-dimensional presentation is a rendering choice made on top of that.
 * A table that reported projected coordinates would be describing ChartKit's
 * drawing rather than the caller's numbers.
 *
 * The stack column is the one addition, and it earns its place: on a chart with
 * two stacks per category the picture makes the grouping obvious and a flat
 * list of series does not.
 *
 * ```kotlin
 * ChartDataTableView(
 *     table = columns3DDataTable(
 *         series = listOf(john, jane, joe, janet),
 *         category = { it.fruit },
 *         value = { it.count },
 *         stack = { if (it.id in setOf("john", "joe")) "male" else "female" },
 *     ),
 * )
 * ```
 */
fun <T> columns3DDataTable(
    series: List<ChartSeries<T>>,
    category: (T) -> Any?,
    value: (T) -> Number?,
    stack: ((ChartSeries<T>) -> String?)? = null,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    unit: ChartUnit = ChartUnit.None,
    caption: String? = null,
    categoryColumn: String = "Category",
): ChartDataTable {
    val stacked = series.any { stack?.invoke(it) != null }
    val columns = buildList {
        add(categoryColumn)
        add("Series")
        if (stacked) add("Stack")
        add("Value")
        if (unit != ChartUnit.None) add("Unit")
    }
    val rows = series.flatMap { s ->
        val stackId = stack?.invoke(s) ?: s.id
        s.data.map { item ->
            buildList {
                add(category(item).toString())
                add(s.name.ifBlank { s.id })
                if (stacked) add(stackId)
                add(value(item)?.toDouble()?.let(valueFormatter::format) ?: "no value")
                if (unit != ChartUnit.None) add(unit.symbol ?: "")
            }
        }
    }
    return ChartDataTable(columns = columns, rows = rows, caption = caption)
}

/**
 * A pie or donut as rows: category, value, share.
 *
 * ### Why the share is a column and not a footnote
 *
 * A part-to-whole chart communicates proportions. "4,823" is not what a reader
 * takes from a pie; "4,823, which is 42.1%" is, and a table that listed only the
 * raw numbers would drop exactly the thing the picture is for. The shares come
 * from [io.devkit.chartkit.geometry.computePolarSlices] — the same arithmetic
 * the chart drew — so the table cannot disagree with the picture.
 *
 * ### Why a 3D pie has the same table as a flat one
 *
 * Because it plots the same data. A camera, a projection, an extrusion and an
 * exploded slice are rendering choices; none of them is a number the caller
 * supplied. A table reporting projected areas would be describing ChartKit's
 * drawing rather than the values, and would report a different answer at every
 * camera angle for data that had not changed.
 *
 * ```kotlin
 * ChartDataTableView(
 *     table = pieDataTable(
 *         data = shares,
 *         value = { it.users },
 *         label = { it.browser },
 *     ),
 * )
 * ```
 *
 * @param categoryColumn what the labels are called — "Browser", "Region".
 * @param valuePolicy how values a pie cannot plot are handled, so the table
 *   shows the same slices the chart did.
 */
@Suppress("LongParameterList")
fun <T> pieDataTable(
    data: List<T>,
    value: (T) -> Number?,
    label: (T) -> String,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    valuePolicy: io.devkit.chartkit.geometry.PolarValuePolicy =
        io.devkit.chartkit.geometry.PolarValuePolicy.Ignore,
    unit: ChartUnit = ChartUnit.None,
    caption: String? = null,
    categoryColumn: String = "Category",
): ChartDataTable {
    val slices = io.devkit.chartkit.geometry.computePolarSlices(
        values = data.map { value(it)?.toDouble() },
        policy = valuePolicy,
    )
    val columns = buildList {
        add(categoryColumn)
        add("Value")
        if (unit != ChartUnit.None) add("Unit")
        add("Share")
    }
    val rows = data.mapIndexed { index, item ->
        val slice = slices.getOrNull(index)
        buildList {
            add(label(item))
            add(value(item)?.toDouble()?.let(valueFormatter::format) ?: "no value")
            if (unit != ChartUnit.None) add(unit.symbol ?: "")
            add(
                slice?.fraction
                    ?.let { io.devkit.chartkit.layer.polar.percentage(it) }
                    ?.ifEmpty { "\u2014" }
                    ?: "\u2014",
            )
        }
    }
    return ChartDataTable(columns = columns, rows = rows, caption = caption)
}

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
 * A thematic map as rows: region, value, and whether it was measured at all.
 *
 * ### Why a map needs this more than any other chart
 *
 * A choropleth encodes its data in *position and colour*, and a reader who
 * cannot see the picture gets neither. Shape, adjacency and area are not
 * describable in a sentence, and no summary of two hundred counties is
 * navigable by ear. The table is not a fallback for this chart; for a
 * non-sighted reader it is the chart.
 *
 * ### The third column
 *
 * "No data" is a value a reader must be able to distinguish from a low one — on
 * the map it is a different colour, and in the table it is a different word.
 * Rendering an absent measurement as an empty cell would be the same mistake as
 * painting it with the colour of zero.
 *
 * ```kotlin
 * ChartWithDataTable(
 *     table = geoDataTable(counties, rates, { it.properties.string("fips") }, { it.fips }, { it.rate }),
 * ) {
 *     ChoroplethMap(geometry = counties, data = rates, ...)
 * }
 * ```
 *
 * @param order how rows are sorted. Alphabetical by default — a reader looking
 *   for one region should not have to hear the other 199 first.
 */
@Suppress("LongParameterList")
fun <T> geoDataTable(
    geometry: GeoFeatureCollection,
    data: List<T>,
    featureKey: (GeoFeature) -> String?,
    dataKey: (T) -> String?,
    value: (T) -> Number?,
    featureLabel: (GeoFeature) -> String = { feature ->
        feature.properties.string("name") ?: feature.id.orEmpty()
    },
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    order: GeoTableOrder = GeoTableOrder.ByRegion,
    missingText: String = "No data",
    caption: String? = null,
): ChartDataTable {
    val byKey = HashMap<String, Double?>(data.size)
    data.forEach { item ->
        val key = dataKey(item) ?: return@forEach
        if (!byKey.containsKey(key)) {
            byKey[key] = value(item)?.toDouble()?.takeIf { it.isFinite() }
        }
    }

    val rows = geometry.features.map { feature ->
        val measured = featureKey(feature)?.let { byKey[it] }
        featureLabel(feature) to measured
    }
    val ordered = when (order) {
        GeoTableOrder.ByRegion -> rows.sortedBy { it.first }
        // Unmeasured regions sort last: a reader asking for "the highest" wants
        // the highest measurement, not the regions with no measurement at all.
        GeoTableOrder.ByValueDescending ->
            rows.sortedWith(compareByDescending(nullsLast()) { it.second })
        GeoTableOrder.AsGiven -> rows
    }

    return ChartDataTable(
        columns = listOf("Region", "Value"),
        rows = ordered.map { (label, measured) ->
            listOf(label, measured?.let(valueFormatter::format) ?: missingText)
        },
        caption = caption,
    )
}

/** How [geoDataTable] orders its rows. */
enum class GeoTableOrder {

    /** Alphabetically by region name. Findable. */
    ByRegion,

    /** Largest value first, unmeasured regions last. */
    ByValueDescending,

    /** The order the geometry file lists them in. */
    AsGiven,
}

/**
 * A set diagram as rows: region, value, share of the union.
 *
 * ```text
 * Region          Value   Share
 * Android only    130     33%
 * iOS only         90     23%
 * Android & iOS    70     18%
 * ```
 *
 * ### The regions, never the sets
 *
 * A table of set totals would overlap: Android's 200 and iOS's 160 both count
 * the seventy people who have both, so the column would sum to more than there
 * are people and no reader could tell why. The regions partition the union, so
 * these rows add up — which is the only version of this table that can be read
 * without the picture.
 *
 * ### Why a set diagram needs one more than most charts
 *
 * The data is encoded in *overlap*, and overlap is not describable in a
 * sentence. A summary can say how many sets there are and how big each is; it
 * cannot let a reader compare the triple intersection against the pair, or find
 * the region they care about among fifteen. For a reader who cannot see the
 * diagram, this table is the diagram.
 *
 * @param naming how region names are written — see [SetRegionNaming]. Passing
 *   the same value the chart uses keeps the table and the tooltip in step.
 * @param includeEmpty whether combinations with nothing in them get a row. Off
 *   by default: a four-set Venn has fifteen regions and usually a handful with
 *   anything in them.
 */
@Suppress("LongParameterList")
fun setDataTable(
    data: SetDiagramData,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    naming: SetRegionNaming = SetRegionNaming(),
    includeEmpty: Boolean = false,
    showShare: Boolean = true,
    caption: String? = null,
): ChartDataTable {
    val rows = data.regions
        .filter { includeEmpty || it.value > 0.0 }
        .sortedWith(compareBy({ it.size }, { it.id }))
        .map { region ->
            val name = defaultRegionName(region, data, naming.exclusiveSuffix, naming.separator)
            buildList {
                add(name)
                add(valueFormatter.format(region.value))
                if (showShare) {
                    add(
                        if (data.union <= 0.0) {
                            ""
                        } else {
                            // Of the union, always. A share of a set would mean
                            // something different in every row, because each row
                            // belongs to a different combination of sets.
                            percentageOf(region.value / data.union)
                        },
                    )
                }
            }
        }
    return ChartDataTable(
        columns = if (showShare) listOf("Region", "Value", "Share of union") else listOf("Region", "Value"),
        rows = rows,
        caption = caption,
    )
}

/**
 * The relationships between the sets, in words.
 *
 * ```text
 * Mammals is contained within Animals.
 * Plants shares nothing with Animals.
 * ```
 *
 * What an Euler diagram's *structure* means, which the value table does not
 * carry: nesting and disjointness are the picture's content, and a reader who
 * cannot see it has no other way to learn them.
 *
 * Every sentence comes from the modelled cardinalities. Nothing is inferred from
 * a label — "Scotland is inside Great Britain" is said only because the numbers
 * say so, never because the words look geographic.
 */
fun setRelationshipTable(
    data: SetDiagramData,
    containsText: String = "contains",
    containedText: String = "is contained within",
    overlapsText: String = "overlaps",
    disjointText: String = "shares nothing with",
    equalText: String = "is identical to",
    caption: String? = null,
): ChartDataTable {
    val labelOf = { id: String -> data.set(id)?.label ?: id }
    val rows = ArrayList<List<String>>()
    data.sets.forEach { first ->
        data.sets.forEach { second ->
            if (first.id >= second.id) return@forEach
            val relation = when (data.relationships.between(first.id, second.id)) {
                SetRelationship.Contains -> containsText
                SetRelationship.ContainedBy -> containedText
                SetRelationship.Overlaps -> overlapsText
                SetRelationship.Disjoint -> disjointText
                SetRelationship.Equal -> equalText
            }
            rows += listOf(labelOf(first.id), relation, labelOf(second.id))
        }
    }
    return ChartDataTable(
        columns = listOf("Set", "Relationship", "Set"),
        rows = rows,
        caption = caption,
    )
}

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
