package io.devkit.chartkit.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.devkit.chartkit.hierarchy.ChartHierarchy
import io.devkit.chartkit.hierarchy.HierarchyNode

/**
 * Where a hierarchical chart is currently looking, hoisted.
 *
 * ```kotlin
 * val hierarchy = rememberHierarchyChartState()
 *
 * ChartBreadcrumbs(hierarchy)
 * Treemap(
 *     data = company,
 *     children = { it.teams }, value = { it.revenue }, label = { it.name },
 *     hierarchyState = hierarchy,
 * )
 * ```
 *
 * Shared by [io.devkit.chartkit.charts.Treemap] and
 * [io.devkit.chartkit.charts.SunburstChart] unchanged: drilling into a node
 * means the same thing in both, so it is one state object and not two. A
 * treemap and a sunburst given the *same* state stay in step, which is exactly
 * what a dashboard showing both wants.
 *
 * ### Ids, not nodes
 *
 * The state stores the current root's **id**, not the node itself. Data
 * arriving from a refresh produces new [HierarchyNode] objects for the same
 * logical tree; a state holding a node would be pointing at the old tree and
 * would either show stale numbers or fall back to the root on every refresh.
 * Ids survive that, which is why [io.devkit.chartkit.hierarchy.buildHierarchy]
 * takes a `key` lambda and why supplying one matters.
 */
@Stable
class ChartHierarchyState internal constructor(
    initialRootId: String? = null,
) {
    /**
     * The tree currently being shown, published by the chart on each data
     * change.
     *
     * Kept here so a breadcrumb composable can resolve ids to labels without
     * the caller having to normalise the hierarchy a second time.
     */
    var hierarchy: ChartHierarchy? by mutableStateOf(null)
        internal set

    /** Ids visited on the way here, oldest first. Empty at the root. */
    private val visited = mutableStateListOf<String>().also { list ->
        initialRootId?.let(list::add)
    }

    /** The id of the node currently filling the chart, or `null` for the root. */
    val currentRootId: String? get() = visited.lastOrNull()

    /** The node currently filling the chart. */
    val currentRoot: HierarchyNode?
        get() {
            val tree = hierarchy ?: return null
            return currentRootId?.let(tree::node) ?: tree.root
        }

    /**
     * The path from the whole tree's root to [currentRoot], inclusive.
     *
     * What a breadcrumb bar renders. Derived from the tree rather than from the
     * navigation history, so a caller who jumped straight to a deep node —
     * restoring a saved view, following a link — still gets the full trail.
     */
    val breadcrumbs: List<HierarchyNode>
        get() {
            val tree = hierarchy ?: return emptyList()
            return currentRootId?.let(tree::trail) ?: listOf(tree.root)
        }

    /** True when there is somewhere to go back to. */
    val canDrillUp: Boolean get() = visited.isNotEmpty()

    /** Enters [nodeId], if it exists and has children worth entering. */
    fun drillTo(nodeId: String) {
        val node = hierarchy?.node(nodeId) ?: return
        // A leaf has nothing inside it. Entering one would produce a chart of a
        // single full-bleed rectangle, which reads as a rendering fault.
        if (node.children.isEmpty()) return
        if (node.id == currentRootId) return
        visited += node.id
    }

    /** Enters [node]. */
    fun drillTo(node: HierarchyNode) {
        drillTo(node.id)
    }

    /** Goes back one level. */
    fun drillUp() {
        if (visited.isNotEmpty()) visited.removeAt(visited.size - 1)
    }

    /**
     * Jumps to [nodeId], truncating the history to the path that leads there.
     *
     * What a breadcrumb tap calls: tapping "Engineering" three levels down
     * should leave the reader at Engineering with Company behind them, not with
     * the two levels they came through still on the stack.
     */
    fun navigateTo(nodeId: String?) {
        if (nodeId == null) {
            reset()
            return
        }
        val tree = hierarchy ?: return
        val trail = tree.trail(nodeId)
        visited.clear()
        // The whole tree's root is the implicit start and is not in the stack.
        visited += trail.drop(1).map { it.id }
    }

    /** Returns to the whole tree. */
    fun reset() {
        visited.clear()
    }
}

/** Remembers a [ChartHierarchyState]. */
@Composable
fun rememberHierarchyChartState(initialRootId: String? = null): ChartHierarchyState =
    remember { ChartHierarchyState(initialRootId) }

/**
 * A [ChartHierarchyState] whose position survives configuration changes and
 * process death.
 *
 * The current node's id is a string and genuinely is screen state — a reader
 * who rotated the device three levels into a treemap would be surprised to find
 * themselves back at the top. Only the current id is saved and not the whole
 * history: restoring a back stack that no longer matches a refreshed tree is
 * worse than restoring a position that does.
 */
@Composable
fun rememberSaveableHierarchyChartState(initialRootId: String? = null): ChartHierarchyState =
    rememberSaveable(
        saver = listSaver(
            save = { listOfNotNull(it.currentRootId) },
            restore = { saved -> ChartHierarchyState(saved.firstOrNull()) },
        ),
    ) {
        ChartHierarchyState(initialRootId)
    }
