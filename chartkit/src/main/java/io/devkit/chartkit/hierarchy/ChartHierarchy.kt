package io.devkit.chartkit.hierarchy

/**
 * How a parent's value is decided when the data supplies one of its own.
 *
 * A treemap's rectangle areas have to sum: a department drawn larger than the
 * sum of its teams is claiming space that belongs to nothing. So the children
 * win by default, and a parent value that disagrees with them is reported
 * through [ChartHierarchy.valueConflicts] rather than silently honoured or
 * silently dropped.
 */
enum class HierarchyValuePolicy {

    /**
     * A parent's value is the sum of its children's. The default.
     *
     * The only policy under which the drawn areas are internally consistent at
     * every level, which is what a reader assumes when they compare two
     * rectangles.
     */
    AggregateChildren,

    /**
     * Use the parent's own value where it has one, and aggregate only where it
     * does not.
     *
     * Correct when the parent genuinely measures something the children do not
     * account for — a budget line with unallocated remainder — and misleading
     * otherwise. Opt-in for that reason.
     */
    PreferExplicit,
}

/**
 * What a hierarchy does with a value it cannot draw.
 *
 * Area encodes magnitude, and a negative area does not exist. Silently taking
 * the absolute value would draw a rectangle claiming a positive share of a
 * total the value reduces, so the behaviour is stated.
 */
enum class HierarchyValueGuard {

    /** Treat the value as absent, so the node contributes nothing. The default. */
    Ignore,

    /** Throw, for a caller who would rather find out at the call site. */
    Reject,
}

/**
 * One node of a normalised hierarchy.
 *
 * ### Identity
 *
 * [id] is what drill-down, animation, selection and state restoration are keyed
 * on. It comes from the caller's own `key` lambda where one was supplied, and
 * otherwise from the node's **position in the tree** — never from its label
 * alone. Two teams both called "Platform" under different departments are two
 * nodes, and a label-keyed hierarchy would merge them.
 *
 * ### The caller's object is carried, not copied
 *
 * [item] is the original object. A tooltip reads `node.item.headcount`
 * directly, and nothing here mutates or wraps the caller's own tree.
 *
 * @param label the node's name.
 * @param depth the root's depth is `0`.
 * @param value the node's magnitude after aggregation, never negative.
 * @param ownValue the value the caller supplied for this node, before
 *   aggregation. `null` where they supplied none.
 * @param path labels from the root to this node, inclusive. What a breadcrumb
 *   and an accessibility announcement are built from.
 */
class HierarchyNode internal constructor(
    val id: String,
    val label: String,
    val item: Any?,
    val depth: Int,
    val value: Double,
    val ownValue: Double?,
    val children: List<HierarchyNode>,
    val path: List<String>,
) {
    /**
     * The enclosing node, or `null` at the root.
     *
     * Assigned once, immediately after construction, because a tree is built
     * leaves-first and a child cannot be handed a parent that does not exist
     * yet. Nothing outside this package can write it, and it never changes
     * afterwards.
     */
    var parent: HierarchyNode? = null
        internal set

    val isLeaf: Boolean get() = children.isEmpty()

    /** Ids from the root to this node, inclusive. */
    val idPath: List<String>
        get() = generateSequence(this) { it.parent }.map { it.id }.toList().asReversed()

    /** This node's share of its parent, or of itself at the root. */
    val fractionOfParent: Double
        get() {
            val total = parent?.value ?: value
            return if (total <= 0.0) 0.0 else value / total
        }

    /** Every node beneath this one, this one first, in depth-first order. */
    fun selfAndDescendants(): List<HierarchyNode> = buildList {
        fun visit(node: HierarchyNode) {
            add(node)
            node.children.forEach(::visit)
        }
        visit(this@HierarchyNode)
    }

    /** This node's share of [root]. */
    fun fractionOf(root: HierarchyNode): Double =
        if (root.value <= 0.0) 0.0 else value / root.value

    override fun toString(): String = "HierarchyNode($id, \"$label\", value=$value, depth=$depth)"
}

/**
 * A hierarchy after normalisation: one root, stable ids, aggregated values.
 *
 * The single model behind both [io.devkit.chartkit.charts.Treemap] and
 * [io.devkit.chartkit.charts.SunburstChart]. The two differ in how they *lay
 * out* a tree — rectangles against arcs — and in nothing else: traversal,
 * identity, aggregation, drill-down, breadcrumbs, selection and accessibility
 * are this class and are shared.
 *
 * @param root the tree's root. Synthetic when the caller supplied a forest.
 * @param droppedValues how many values were rejected as negative or non-finite.
 * @param cyclesBroken how many child references pointed back at an ancestor and
 *   were cut. Non-zero means the caller's data was not a tree.
 * @param truncatedAtDepth how many subtrees were cut off at the depth limit.
 * @param valueConflicts nodes whose own value disagreed with their children's
 *   sum by more than a rounding error, under [HierarchyValuePolicy.AggregateChildren].
 *   Reported rather than corrected: it is usually a data problem worth seeing.
 */
class ChartHierarchy internal constructor(
    val root: HierarchyNode,
    val droppedValues: Int = 0,
    val cyclesBroken: Int = 0,
    val truncatedAtDepth: Int = 0,
    val valueConflicts: List<String> = emptyList(),
) {
    /** Every node, root first, in depth-first order. */
    val nodes: List<HierarchyNode> by lazy(LazyThreadSafetyMode.NONE) { root.selfAndDescendants() }

    private val byId: Map<String, HierarchyNode> by lazy(LazyThreadSafetyMode.NONE) {
        nodes.associateBy { it.id }
    }

    /** The deepest level present, with the root at `0`. */
    val maxDepth: Int get() = nodes.maxOf { it.depth }

    /** True when nothing in the tree has a magnitude to draw. */
    val isEmpty: Boolean get() = root.value <= 0.0 || root.children.isEmpty() && root.ownValue == null

    /** The node with [id], or `null`. What drill-down state resolves through. */
    fun node(id: String): HierarchyNode? = byId[id]

    /** The nodes from the root down to [id], inclusive — a breadcrumb trail. */
    fun trail(id: String): List<HierarchyNode> {
        val target = byId[id] ?: return listOf(root)
        return generateSequence(target) { it.parent }.toList().asReversed()
    }

    companion object {
        /** Deeper than this and a treemap's rectangles are smaller than a pixel. */
        const val DEFAULT_MAX_DEPTH: Int = 24
    }
}

/**
 * Normalises the caller's own tree into a [ChartHierarchy].
 *
 * ```kotlin
 * data class Department(val name: String, val revenue: Double, val teams: List<Department>)
 *
 * val hierarchy = buildHierarchy(
 *     root = company,
 *     children = { it.teams },
 *     value = { it.revenue },
 *     label = { it.name },
 * )
 * ```
 *
 * The caller's objects are read and never written. Nothing is required of them
 * beyond the four lambdas: no interface to implement, no node type to convert
 * into, and no reflection — the same contract the rest of ChartKit offers.
 *
 * ### Malformed input
 *
 * A `children` lambda that eventually returns an ancestor makes the input a
 * graph rather than a tree, and a naive traversal of it never terminates. The
 * repeated reference is **cut** and counted in [ChartHierarchy.cyclesBroken];
 * the rest of the tree still draws. Depth is capped at [maxDepth] for the same
 * reason — a self-referential structure that never repeats an *identity* would
 * otherwise recurse until the stack ran out.
 *
 * @param key stable identity for a node. Strongly preferred over relying on the
 *   generated positional id: drill-down, selection and animation are keyed on
 *   it, so a hierarchy whose ids move when a sibling is inserted will animate
 *   the wrong nodes into each other.
 * @param maxDepth levels below the root to normalise. Subtrees below it are cut
 *   and counted.
 */
@Suppress("LongParameterList")
fun <T> buildHierarchy(
    root: T,
    children: (T) -> List<T>,
    value: (T) -> Number?,
    label: (T) -> String,
    key: ((T) -> Any)? = null,
    valuePolicy: HierarchyValuePolicy = HierarchyValuePolicy.AggregateChildren,
    valueGuard: HierarchyValueGuard = HierarchyValueGuard.Ignore,
    maxDepth: Int = ChartHierarchy.DEFAULT_MAX_DEPTH,
): ChartHierarchy {
    require(maxDepth >= 1) { "A hierarchy needs at least one level, maxDepth was $maxDepth" }
    val builder = HierarchyBuilder(children, value, label, key, valuePolicy, valueGuard, maxDepth)
    val node = builder.visit(root, depth = 0, ancestors = ArrayList(), pathLabels = ArrayList(), idPrefix = "")
    builder.linkParents(node)
    return ChartHierarchy(
        root = node,
        droppedValues = builder.droppedValues,
        cyclesBroken = builder.cyclesBroken,
        truncatedAtDepth = builder.truncated,
        valueConflicts = builder.conflicts,
    )
}

/**
 * Normalises a forest — several top-level items — under one synthetic root.
 *
 * The common shape for a treemap of categories that have no single parent:
 * "spend by department" is a list of departments, not a company object.
 *
 * @param rootLabel the synthetic root's name, used by breadcrumbs and by the
 *   accessibility summary when the whole tree is in view.
 */
@Suppress("LongParameterList")
fun <T> buildHierarchy(
    roots: List<T>,
    children: (T) -> List<T>,
    value: (T) -> Number?,
    label: (T) -> String,
    key: ((T) -> Any)? = null,
    rootLabel: String = "All",
    valuePolicy: HierarchyValuePolicy = HierarchyValuePolicy.AggregateChildren,
    valueGuard: HierarchyValueGuard = HierarchyValueGuard.Ignore,
    maxDepth: Int = ChartHierarchy.DEFAULT_MAX_DEPTH,
): ChartHierarchy {
    require(maxDepth >= 1) { "A hierarchy needs at least one level, maxDepth was $maxDepth" }
    val builder = HierarchyBuilder(children, value, label, key, valuePolicy, valueGuard, maxDepth + 1)
    val topLevel = roots.mapIndexed { index, item ->
        builder.visit(
            item = item,
            depth = 1,
            ancestors = ArrayList(),
            pathLabels = arrayListOf(rootLabel),
            idPrefix = "$ROOT_ID/$index",
        )
    }
    val node = HierarchyNode(
        id = ROOT_ID,
        label = rootLabel,
        item = null,
        depth = 0,
        value = topLevel.sumOf { it.value },
        ownValue = null,
        children = topLevel,
        path = listOf(rootLabel),
    )
    builder.linkParents(node)
    return ChartHierarchy(
        root = node,
        droppedValues = builder.droppedValues,
        cyclesBroken = builder.cyclesBroken,
        truncatedAtDepth = builder.truncated,
        valueConflicts = builder.conflicts,
    )
}

/** The id of the synthetic root created for a forest. */
internal const val ROOT_ID: String = "root"

/**
 * The recursive half of [buildHierarchy], kept out of the public function so
 * the traversal's bookkeeping is not part of anyone's call site.
 *
 * `ancestors` holds the caller's own objects on the current path, compared by
 * **identity** rather than by `equals`. Two sibling nodes that compare equal —
 * two `Department("Platform", 0.0, emptyList())` values, say — are a legitimate
 * tree, and rejecting the second as a cycle would silently delete real data.
 */
private class HierarchyBuilder<T>(
    private val children: (T) -> List<T>,
    private val value: (T) -> Number?,
    private val label: (T) -> String,
    private val key: ((T) -> Any)?,
    private val valuePolicy: HierarchyValuePolicy,
    private val valueGuard: HierarchyValueGuard,
    private val maxDepth: Int,
) {
    var droppedValues: Int = 0
    var cyclesBroken: Int = 0
    var truncated: Int = 0
    val conflicts: MutableList<String> = ArrayList()

    /** Ids seen so far, so a caller's duplicate key does not merge two nodes. */
    private val usedIds = HashSet<String>()

    fun visit(
        item: T,
        depth: Int,
        ancestors: ArrayList<Any>,
        pathLabels: ArrayList<String>,
        idPrefix: String,
    ): HierarchyNode {
        val name = label(item)
        val ownValue = resolveValue(item)
        val id = uniqueId(key?.invoke(item)?.toString() ?: "$idPrefix/$name")

        pathLabels.add(name)
        val childNodes = if (depth >= maxDepth) {
            if (children(item).isNotEmpty()) truncated++
            emptyList()
        } else {
            ancestors.add(item as Any)
            val resolved = children(item).mapIndexedNotNull { index, child ->
                // Identity, not equality: two structurally identical siblings
                // are data, and only the *same object* reappearing on the path
                // is a cycle.
                if (ancestors.any { it === (child as Any) }) {
                    cyclesBroken++
                    null
                } else {
                    visit(child, depth + 1, ancestors, pathLabels, "$id/$index")
                }
            }
            ancestors.removeAt(ancestors.size - 1)
            resolved
        }
        pathLabels.removeAt(pathLabels.size - 1)

        val aggregate = childNodes.sumOf { it.value }
        val resolvedValue = when {
            childNodes.isEmpty() -> ownValue ?: 0.0
            valuePolicy == HierarchyValuePolicy.PreferExplicit && ownValue != null -> ownValue
            else -> {
                if (ownValue != null && !approximatelyEqual(ownValue, aggregate)) {
                    conflicts += name
                }
                aggregate
            }
        }

        return HierarchyNode(
            id = id,
            label = name,
            item = item,
            depth = depth,
            value = resolvedValue.coerceAtLeast(0.0),
            ownValue = ownValue,
            children = childNodes,
            path = pathLabels.toList() + name,
        )
    }

    /** Walks the finished tree once, giving every node its parent. */
    fun linkParents(node: HierarchyNode) {
        node.children.forEach {
            it.parent = node
            linkParents(it)
        }
    }

    private fun resolveValue(item: T): Double? {
        val raw = value(item)?.toDouble() ?: return null
        if (raw.isFinite() && raw >= 0.0) return raw
        if (valueGuard == HierarchyValueGuard.Reject) {
            throw IllegalArgumentException(
                "A hierarchy cannot draw the value $raw for \"${label(item)}\": area encodes " +
                    "magnitude, and a negative or non-finite area does not exist. Filter it " +
                    "out, or use HierarchyValueGuard.Ignore to drop it.",
            )
        }
        droppedValues++
        return null
    }

    /**
     * [candidate], or a suffixed variant when it has already been used.
     *
     * A caller's `key` lambda that returns the same value twice would otherwise
     * make two nodes indistinguishable, and drilling into one would select the
     * other.
     */
    private fun uniqueId(candidate: String): String {
        if (usedIds.add(candidate)) return candidate
        var suffix = 2
        while (!usedIds.add("$candidate#$suffix")) suffix++
        return "$candidate#$suffix"
    }

    private fun approximatelyEqual(a: Double, b: Double): Boolean {
        val scale = maxOf(1.0, kotlin.math.abs(a), kotlin.math.abs(b))
        return kotlin.math.abs(a - b) <= scale * CONFLICT_TOLERANCE
    }

    private companion object {
        /** Below this, a parent and its children's sum are the same number. */
        const val CONFLICT_TOLERANCE = 1e-6
    }
}
