package io.devkit.chartkit.flow

/**
 * One group on the circle: an endpoint of every flow that touches it.
 *
 * @param id the caller's own identifier, as a string.
 * @param label its name.
 * @param item the caller's object, carried through untouched.
 * @param index its position in the caller's group list.
 * @param outgoing total weight leaving.
 * @param incoming total weight arriving.
 */
class ChordGroup internal constructor(
    val id: String,
    val label: String,
    val item: Any?,
    val index: Int,
    val outgoing: Double,
    val incoming: Double,
) {
    /**
     * The weight the group's arc is sized by: everything touching it.
     *
     * Both directions, not one. A Sankey node is sized by the larger of its two
     * sides because it sits between two columns and carries flow *through*
     * itself. A chord group is an endpoint, and the question a reader asks of it
     * is "how much moves in and out of here in total" — so a region that only
     * receives migrants still gets an arc proportional to what it receives,
     * rather than the zero-width arc an outflow-only measure would give it.
     */
    val total: Double get() = outgoing + incoming

    override fun toString(): String = "ChordGroup($id, out=$outgoing, in=$incoming)"
}

/** One directed flow after normalisation, with its endpoints resolved to indices. */
class ChordFlow internal constructor(
    val sourceIndex: Int,
    val targetIndex: Int,
    val value: Double,
    val item: Any?,
    val index: Int,
    val label: String?,
) {
    /** True when the flow returns to the group it left. */
    val isSelfFlow: Boolean get() = sourceIndex == targetIndex

    override fun toString(): String = "ChordFlow($sourceIndex -> $targetIndex, $value)"
}

/**
 * What a chord matrix does with input it cannot lay out.
 *
 * The same reasoning as [SankeyValidation]: every one of these is a data problem
 * the caller would want to know about, and every one has a silent reading that
 * produces a plausible-looking and wrong diagram.
 */
enum class ChordValidation {

    /** Drop the offending flows and carry on. The default. */
    Drop,

    /** Throw, naming the first problem found. */
    Reject,
}

/**
 * A set of groups and the directed flows between them.
 *
 * ### One ribbon per flow, both ends the same width
 *
 * The classic Circos and D3 chord diagram merges the two directions between a
 * pair into a **single** ribbon with ends of different widths — the end in group
 * `i` sized by `M[i][j]` and the end in group `j` by `M[j][i]`. It is a dense
 * encoding, and it costs the reader the ability to say what any one ribbon
 * means: a ribbon that is wide at one end and narrow at the other is two numbers
 * wearing one shape, and nothing in the picture says which is which.
 *
 * Here a ribbon is exactly one [ChordFlow], and both of its ends are that flow's
 * value. Two directions between the same pair are two ribbons. The cost is one
 * more shape on a dense diagram; what it buys is that every ribbon carries one
 * number, a tap on it selects the caller's own flow object, and the arithmetic
 * closes — each group's arc is exactly the sum of the ribbon ends attached to
 * it, which is what makes the angular widths readable as quantities at all.
 *
 * @param groups in the caller's own order.
 * @param flows with endpoints resolved to indices into [groups].
 * @param unknownReferences flows naming a group that does not exist.
 * @param invalidValues flows whose weight was negative, zero or non-finite.
 */
class ChordMatrix internal constructor(
    val groups: List<ChordGroup>,
    val flows: List<ChordFlow>,
    val unknownReferences: Int = 0,
    val invalidValues: Int = 0,
) {
    /** True when there is nothing to draw. */
    val isEmpty: Boolean get() = groups.isEmpty() || flows.isEmpty()

    /** The sum of every flow's value. */
    val total: Double get() = flows.sumOf { it.value }

    /** The flows touching [groupIndex], in either direction. */
    fun flowsTouching(groupIndex: Int): List<ChordFlow> =
        flows.filter { it.sourceIndex == groupIndex || it.targetIndex == groupIndex }

    /** Every group reachable from [groupIndex], plus itself. */
    fun neighbours(groupIndex: Int): Set<Int> = buildSet {
        add(groupIndex)
        flows.forEach { flow ->
            when (groupIndex) {
                flow.sourceIndex -> add(flow.targetIndex)
                flow.targetIndex -> add(flow.sourceIndex)
            }
        }
    }

    override fun toString(): String =
        "ChordMatrix(${groups.size} groups, ${flows.size} flows, total=$total)"
}

/**
 * Builds a [ChordMatrix] from the caller's own groups and flows.
 *
 * Nothing is required of the caller's types: no interface, no conversion, no
 * reflection. The objects go in and come back on the selection.
 *
 * ### Self-flows are kept
 *
 * A flow from a group to itself is dropped by [buildSankeyGraph], where it would
 * be a cycle in something that has to be layered into columns. A chord diagram
 * has no columns and no ordering, so a self-flow is simply a ribbon that leaves
 * an arc and returns to it — internal migration within a region, traffic from a
 * page back to itself. It takes two segments of its group's arc, like any other
 * flow, and the arithmetic still closes.
 */
@Suppress("LongParameterList")
fun <G, F> buildChordMatrix(
    groups: List<G>,
    flows: List<F>,
    groupId: (G) -> Any,
    groupLabel: (G) -> String,
    source: (F) -> Any,
    target: (F) -> Any,
    value: (F) -> Number?,
    flowLabel: ((F) -> String?)? = null,
    validation: ChordValidation = ChordValidation.Drop,
): ChordMatrix {
    // One pass to index the groups, so resolving a flow's endpoints is a hash
    // lookup rather than a scan of the group list per flow.
    val indexById = HashMap<String, Int>(groups.size)
    groups.forEachIndexed { index, group ->
        val id = groupId(group).toString()
        // First declaration wins, deterministically. A duplicate id is the
        // caller's bug, and silently letting the later one shadow the earlier
        // would move every flow that named it.
        indexById.putIfAbsent(id, index)
    }

    val outgoing = DoubleArray(groups.size)
    val incoming = DoubleArray(groups.size)
    val resolved = ArrayList<ChordFlow>(flows.size)
    var unknownReferences = 0
    var invalidValues = 0

    flows.forEachIndexed { index, flow ->
        val sourceId = source(flow).toString()
        val targetId = target(flow).toString()
        val sourceIndex = indexById[sourceId]
        val targetIndex = indexById[targetId]
        if (sourceIndex == null || targetIndex == null) {
            val missing = if (sourceIndex == null) sourceId else targetId
            if (validation == ChordValidation.Reject) {
                error("Flow $index names the group \"$missing\", which is not in the group list")
            }
            unknownReferences++
            return@forEachIndexed
        }

        val weight = value(flow)?.toDouble()
        if (weight == null || !weight.isFinite() || weight <= 0.0) {
            if (validation == ChordValidation.Reject) {
                error("Flow $index ($sourceId to $targetId) has a weight of $weight, which cannot be drawn")
            }
            invalidValues++
            return@forEachIndexed
        }

        outgoing[sourceIndex] += weight
        incoming[targetIndex] += weight
        resolved += ChordFlow(
            sourceIndex = sourceIndex,
            targetIndex = targetIndex,
            value = weight,
            item = flow,
            index = resolved.size,
            label = flowLabel?.invoke(flow),
        )
    }

    return ChordMatrix(
        groups = groups.mapIndexed { index, group ->
            ChordGroup(
                id = groupId(group).toString(),
                label = groupLabel(group),
                item = group,
                index = index,
                outgoing = outgoing[index],
                incoming = incoming[index],
            )
        },
        flows = resolved,
        unknownReferences = unknownReferences,
        invalidValues = invalidValues,
    )
}
