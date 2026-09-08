package io.devkit.chartkit.graph

/**
 * A graph node after normalisation.
 *
 * @param id the caller's identifier, as a string.
 * @param label its name.
 * @param item the caller's own object, carried through untouched.
 * @param index its position in the caller's list, which is what every position
 *   array below is indexed by.
 * @param weight an optional magnitude driving the node's drawn size. `null`
 *   draws every node the same size.
 * @param degree how many edges touch it.
 */
class GraphNode internal constructor(
    val id: String,
    val label: String,
    val item: Any?,
    val index: Int,
    val weight: Double?,
    val degree: Int,
)

/** A graph edge after normalisation, with its endpoints resolved to indices. */
class GraphEdge internal constructor(
    val sourceIndex: Int,
    val targetIndex: Int,
    val weight: Double,
    val item: Any?,
    val index: Int,
    val label: String?,
)

/**
 * A relationship graph, validated.
 *
 * Undirected for layout purposes — a force simulation pulls both ends of an
 * edge together whichever way it points — while [GraphEdge] keeps the caller's
 * direction so a renderer can draw an arrowhead and a tooltip can say "A
 * depends on B" rather than "A and B are related".
 *
 * @param unknownReferences edges naming a node that does not exist.
 * @param selfEdges edges from a node to itself, which have no length to lay out.
 */
class ChartGraph internal constructor(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val unknownReferences: Int = 0,
    val selfEdges: Int = 0,
) {
    val isEmpty: Boolean get() = nodes.isEmpty()

    /**
     * Neighbours of each node, by index.
     *
     * Built once because it is read on every hover: highlighting a node's
     * connections must not walk the whole edge list per frame.
     */
    val adjacency: List<Set<Int>> by lazy(LazyThreadSafetyMode.NONE) {
        val sets = List(nodes.size) { HashSet<Int>() }
        edges.forEach {
            sets[it.sourceIndex] += it.targetIndex
            sets[it.targetIndex] += it.sourceIndex
        }
        sets.map { it.toSet() }
    }

    /** Edge indices touching [nodeIndex]. */
    fun edgesAt(nodeIndex: Int): List<GraphEdge> =
        edges.filter { it.sourceIndex == nodeIndex || it.targetIndex == nodeIndex }
}

/** What a graph does with an edge it cannot use. */
enum class GraphValidation {

    /** Drop the offending edges and lay out the rest. The default. */
    Drop,

    /** Throw, naming the first problem found. */
    Reject,
}

/**
 * Normalises the caller's own nodes and edges into a [ChartGraph].
 *
 * ```kotlin
 * val graph = buildChartGraph(
 *     nodes = services,
 *     edges = dependencies,
 *     nodeId = { it.name },
 *     source = { it.from },
 *     target = { it.to },
 * )
 * ```
 *
 * Nothing is required of the caller's types: no node interface, no edge
 * interface, no conversion, no reflection. The consumer's objects are carried
 * through on [GraphNode.item] and [GraphEdge.item], so a selection hands back
 * the `Service` that was put in.
 */
@Suppress("LongParameterList")
fun <N, E> buildChartGraph(
    nodes: List<N>,
    edges: List<E>,
    nodeId: (N) -> Any,
    source: (E) -> Any,
    target: (E) -> Any,
    nodeLabel: ((N) -> String)? = null,
    nodeWeight: ((N) -> Number?)? = null,
    edgeWeight: ((E) -> Number?)? = null,
    edgeLabel: ((E) -> String?)? = null,
    validation: GraphValidation = GraphValidation.Drop,
): ChartGraph {
    if (nodes.isEmpty()) return ChartGraph(emptyList(), emptyList())

    val indexById = HashMap<String, Int>(nodes.size)
    nodes.forEachIndexed { index, node -> indexById.putIfAbsent(nodeId(node).toString(), index) }

    var unknown = 0
    var self = 0
    val resolved = ArrayList<GraphEdge>(edges.size)
    val degrees = IntArray(nodes.size)

    edges.forEachIndexed { index, edge ->
        val fromId = source(edge).toString()
        val toId = target(edge).toString()
        val from = indexById[fromId]
        val to = indexById[toId]
        if (from == null || to == null) {
            unknown++
            if (validation == GraphValidation.Reject) {
                throw IllegalArgumentException(
                    "A graph edge refers to the unknown node \"${if (from == null) fromId else toId}\".",
                )
            }
            return@forEachIndexed
        }
        if (from == to) {
            self++
            if (validation == GraphValidation.Reject) {
                throw IllegalArgumentException(
                    "The node \"$fromId\" links to itself. A self-edge has no length to lay out.",
                )
            }
            return@forEachIndexed
        }
        val weight = edgeWeight?.invoke(edge)?.toDouble()?.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        degrees[from]++
        degrees[to]++
        resolved += GraphEdge(from, to, weight, edge, index, edgeLabel?.invoke(edge))
    }

    return ChartGraph(
        nodes = nodes.mapIndexed { index, item ->
            GraphNode(
                id = nodeId(item).toString(),
                label = nodeLabel?.invoke(item) ?: nodeId(item).toString(),
                item = item,
                index = index,
                weight = nodeWeight?.invoke(item)?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 },
                degree = degrees[index],
            )
        },
        edges = resolved,
        unknownReferences = unknown,
        selfEdges = self,
    )
}
