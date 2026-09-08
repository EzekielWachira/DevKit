package io.devkit.chartkit.flow

/**
 * A flow node after normalisation.
 *
 * @param id the caller's own identifier, as a string.
 * @param label its name.
 * @param item the caller's object, carried through untouched.
 * @param index its position in the caller's node list.
 * @param incoming total weight arriving.
 * @param outgoing total weight leaving.
 */
class SankeyNode internal constructor(
    val id: String,
    val label: String,
    val item: Any?,
    val index: Int,
    val incoming: Double,
    val outgoing: Double,
) {
    /**
     * The weight the node's box is sized by.
     *
     * The larger of the two sides, so a source (no inflow) and a sink (no
     * outflow) are both drawn at the size of the flow they actually carry. Sizing
     * by inflow alone would draw every source as a point.
     */
    val throughput: Double get() = if (incoming > outgoing) incoming else outgoing

    override fun toString(): String = "SankeyNode($id, in=$incoming, out=$outgoing)"
}

/** A flow link after normalisation, with its endpoints resolved to indices. */
class SankeyLink internal constructor(
    val sourceIndex: Int,
    val targetIndex: Int,
    val value: Double,
    val item: Any?,
    val index: Int,
    val label: String?,
)

/**
 * What a flow graph does with input it cannot lay out.
 *
 * Every one of these is a data problem the caller would want to know about, and
 * every one of them has a silent reading that produces a plausible-looking and
 * wrong diagram. So they are counted and reported rather than hidden, and a
 * caller who would rather be told loudly asks for [Reject].
 */
enum class SankeyValidation {

    /** Drop the offending links and carry on. The default. */
    Drop,

    /** Throw, naming the first problem found. */
    Reject,
}

/**
 * A directed flow graph, validated and depth-assigned.
 *
 * @param nodes in the caller's own order.
 * @param links with endpoints resolved to indices into [nodes].
 * @param depths one column index per node. Sources are at `0`.
 * @param unknownReferences links naming a node that does not exist.
 * @param selfLinks links whose source and target are the same node.
 * @param invalidValues links whose weight was negative, zero or non-finite.
 * @param cyclicLinks links cut to make the graph layerable.
 */
class SankeyGraph internal constructor(
    val nodes: List<SankeyNode>,
    val links: List<SankeyLink>,
    val depths: IntArray,
    val unknownReferences: Int = 0,
    val selfLinks: Int = 0,
    val invalidValues: Int = 0,
    val cyclicLinks: Int = 0,
) {
    /** The number of columns the diagram needs. */
    val columnCount: Int get() = if (depths.isEmpty()) 0 else depths.max() + 1

    val isEmpty: Boolean get() = links.isEmpty() || nodes.isEmpty()

    /** Node indices in column [depth], in the caller's order. */
    fun column(depth: Int): List<Int> = depths.indices.filter { depths[it] == depth }

    /** Links touching [nodeIndex], in either direction. */
    fun linksAt(nodeIndex: Int): List<SankeyLink> =
        links.filter { it.sourceIndex == nodeIndex || it.targetIndex == nodeIndex }

    /** Nodes directly connected to [nodeIndex]. */
    fun neighbours(nodeIndex: Int): Set<Int> = buildSet {
        links.forEach { link ->
            when (nodeIndex) {
                link.sourceIndex -> add(link.targetIndex)
                link.targetIndex -> add(link.sourceIndex)
            }
        }
    }
}

/**
 * Normalises the caller's own nodes and links into a [SankeyGraph].
 *
 * ```kotlin
 * val graph = buildSankeyGraph(
 *     nodes = stages,
 *     links = transitions,
 *     nodeId = { it.id },
 *     nodeLabel = { it.name },
 *     source = { it.from },
 *     target = { it.to },
 *     value = { it.users },
 * )
 * ```
 *
 * ### Cycles
 *
 * A Sankey diagram places nodes in columns by how far they are from a source,
 * which requires the flow to have a direction that does not come back. A cycle
 * has no such column assignment: "A before B" and "B before A" cannot both
 * hold. Rather than laying out something arbitrary, the links that close a
 * cycle are **cut** — counted in [SankeyGraph.cyclicLinks] — and the rest of
 * the diagram is drawn. Pass [SankeyValidation.Reject] to have a cyclic graph
 * throw instead.
 *
 * The cut is the *back edge* found by a depth-first traversal in the caller's
 * own link order, so it is deterministic: the same input always produces the
 * same diagram.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod", "LongMethod")
fun <N, L> buildSankeyGraph(
    nodes: List<N>,
    links: List<L>,
    nodeId: (N) -> Any,
    nodeLabel: (N) -> String,
    source: (L) -> Any,
    target: (L) -> Any,
    value: (L) -> Number?,
    linkLabel: ((L) -> String?)? = null,
    validation: SankeyValidation = SankeyValidation.Drop,
): SankeyGraph {
    if (nodes.isEmpty()) return SankeyGraph(emptyList(), emptyList(), IntArray(0))

    val indexById = HashMap<String, Int>(nodes.size)
    nodes.forEachIndexed { index, node ->
        val id = nodeId(node).toString()
        // First occurrence wins, and the duplicate is not silently merged into
        // it: merging would make two distinct rows in the caller's list draw as
        // one box carrying both their flows.
        if (indexById.putIfAbsent(id, index) != null && validation == SankeyValidation.Reject) {
            throw IllegalArgumentException(
                "Two nodes share the id \"$id\". Flow nodes are matched to links by id, so " +
                    "duplicates make a link ambiguous.",
            )
        }
    }

    var unknown = 0
    var self = 0
    var invalid = 0

    val resolved = ArrayList<SankeyLink>(links.size)
    links.forEachIndexed { index, link ->
        val sourceId = source(link).toString()
        val targetId = target(link).toString()
        val from = indexById[sourceId]
        val to = indexById[targetId]
        if (from == null || to == null) {
            unknown++
            if (validation == SankeyValidation.Reject) {
                val missing = if (from == null) sourceId else targetId
                throw IllegalArgumentException(
                    "A flow link refers to the unknown node \"$missing\". Every link's source " +
                        "and target must appear in the node list.",
                )
            }
            return@forEachIndexed
        }
        if (from == to) {
            self++
            if (validation == SankeyValidation.Reject) {
                throw IllegalArgumentException(
                    "The node \"$sourceId\" links to itself. A self-link has no width to draw " +
                        "and no column to span.",
                )
            }
            return@forEachIndexed
        }
        val weight = value(link)?.toDouble()
        if (weight == null || !weight.isFinite() || weight <= 0.0) {
            invalid++
            if (validation == SankeyValidation.Reject) {
                throw IllegalArgumentException(
                    "The link \"$sourceId\" to \"$targetId\" has the weight $weight. A flow's " +
                        "width encodes its magnitude, so it must be a finite positive number.",
                )
            }
            return@forEachIndexed
        }
        resolved += SankeyLink(
            sourceIndex = from,
            targetIndex = to,
            value = weight,
            item = link,
            index = index,
            label = linkLabel?.invoke(link),
        )
    }

    val acyclic = removeCycles(nodes.size, resolved)
    if (acyclic.size != resolved.size && validation == SankeyValidation.Reject) {
        throw IllegalArgumentException(
            "The flow graph contains a cycle. A Sankey diagram assigns nodes to columns by " +
                "distance from a source, which a cycle has no answer for. Break the cycle, or " +
                "use SankeyValidation.Drop to have the back edges cut.",
        )
    }

    val depths = assignDepths(nodes.size, acyclic)
    val incoming = DoubleArray(nodes.size)
    val outgoing = DoubleArray(nodes.size)
    acyclic.forEach {
        outgoing[it.sourceIndex] += it.value
        incoming[it.targetIndex] += it.value
    }

    return SankeyGraph(
        nodes = nodes.mapIndexed { index, item ->
            SankeyNode(
                id = nodeId(item).toString(),
                label = nodeLabel(item),
                item = item,
                index = index,
                incoming = incoming[index],
                outgoing = outgoing[index],
            )
        },
        links = acyclic,
        depths = depths,
        unknownReferences = unknown,
        selfLinks = self,
        invalidValues = invalid,
        cyclicLinks = resolved.size - acyclic.size,
    )
}

/**
 * Drops the links that close a cycle, keeping the rest.
 *
 * A depth-first traversal in the caller's own node and link order: an edge
 * reaching a node currently on the stack is a back edge and is cut. Iterative
 * rather than recursive, because a chain of ten thousand nodes is a legitimate
 * flow graph and would overflow the stack.
 */
private fun removeCycles(nodeCount: Int, links: List<SankeyLink>): List<SankeyLink> {
    if (links.isEmpty()) return links

    val outgoing = Array(nodeCount) { ArrayList<SankeyLink>() }
    links.forEach { outgoing[it.sourceIndex] += it }

    val UNVISITED = 0
    val ON_STACK = 1
    val DONE = 2
    val state = IntArray(nodeCount)
    val kept = HashSet<SankeyLink>(links.size)
    var cut = false

    for (start in 0 until nodeCount) {
        if (state[start] != UNVISITED) continue
        // Each frame is a node plus how far through its outgoing list we are.
        val nodeStack = ArrayList<Int>()
        val edgeStack = ArrayList<Int>()
        nodeStack += start
        edgeStack += 0
        state[start] = ON_STACK

        while (nodeStack.isNotEmpty()) {
            val node = nodeStack.last()
            val cursor = edgeStack.last()
            val edges = outgoing[node]
            if (cursor >= edges.size) {
                state[node] = DONE
                nodeStack.removeAt(nodeStack.size - 1)
                edgeStack.removeAt(edgeStack.size - 1)
                continue
            }
            edgeStack[edgeStack.size - 1] = cursor + 1
            val edge = edges[cursor]
            when (state[edge.targetIndex]) {
                ON_STACK -> cut = true // a back edge: dropped by not keeping it
                UNVISITED -> {
                    kept += edge
                    state[edge.targetIndex] = ON_STACK
                    nodeStack += edge.targetIndex
                    edgeStack += 0
                }
                else -> kept += edge // a forward or cross edge: harmless
            }
        }
    }
    return if (!cut) links else links.filter { it in kept }
}

/**
 * The column each node belongs in: its longest distance from any source.
 *
 * Longest rather than shortest, so a node is never drawn to the left of
 * something that flows into it. With the shortest path, a link that skips a
 * stage would drag its target back a column and the diagram would show flow
 * running right to left.
 *
 * Nodes with no inflow start at column zero, which is also what an isolated
 * node gets.
 */
private fun assignDepths(nodeCount: Int, links: List<SankeyLink>): IntArray {
    val depths = IntArray(nodeCount)
    if (links.isEmpty()) return depths

    val indegree = IntArray(nodeCount)
    val outgoing = Array(nodeCount) { ArrayList<SankeyLink>() }
    links.forEach {
        indegree[it.targetIndex]++
        outgoing[it.sourceIndex] += it
    }

    // Kahn's algorithm. The graph is acyclic by construction here, so every
    // node is emitted exactly once and the relaxation below is a single pass.
    val queue = ArrayDeque<Int>()
    for (index in 0 until nodeCount) if (indegree[index] == 0) queue += index

    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        outgoing[node].forEach { link ->
            val candidate = depths[node] + 1
            if (candidate > depths[link.targetIndex]) depths[link.targetIndex] = candidate
            if (--indegree[link.targetIndex] == 0) queue += link.targetIndex
        }
    }
    return depths
}
