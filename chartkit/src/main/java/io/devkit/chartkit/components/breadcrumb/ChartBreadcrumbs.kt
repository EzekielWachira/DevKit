package io.devkit.chartkit.components.breadcrumb

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.hierarchy.HierarchyNode
import io.devkit.chartkit.state.ChartHierarchyState
import io.devkit.chartkit.theme.ChartKitTheme

/**
 * The trail from the whole tree down to what a hierarchical chart is showing.
 *
 * ```kotlin
 * val hierarchy = rememberHierarchyChartState()
 *
 * ChartBreadcrumbs(hierarchy)
 * Treemap(data = company, children = { it.teams }, value = { it.revenue },
 *         label = { it.name }, hierarchyState = hierarchy)
 * ```
 *
 * ```text
 * Company  ›  Engineering  ›  Android
 * ```
 *
 * ### Real composables, not canvas text
 *
 * Every ancestor is a `Text` with its own click target and its own semantics
 * node, so a screen reader announces "Company, button" and a keyboard or D-pad
 * can reach it. Drawn onto the chart's canvas it would be an unreachable
 * picture of navigation.
 *
 * ### Fully replaceable
 *
 * [entry] is a slot. A caller whose design system has its own breadcrumb
 * component passes it here and keeps the navigation behaviour without adopting
 * ChartKit's appearance:
 *
 * ```kotlin
 * ChartBreadcrumbs(hierarchy) { node, isCurrent, onClick ->
 *     MyChip(node.label, selected = isCurrent, onClick = onClick)
 * }
 * ```
 *
 * @param separator drawn between entries.
 * @param entry renders one crumb. Receives the node, whether it is the current
 *   level, and the action that navigates to it.
 */
@Composable
fun ChartBreadcrumbs(
    state: ChartHierarchyState,
    modifier: Modifier = Modifier,
    separator: String = "›",
    entry: (@Composable (node: HierarchyNode, isCurrent: Boolean, onClick: () -> Unit) -> Unit)? = null,
) {
    val trail = state.breadcrumbs
    if (trail.isEmpty()) return
    val theme = ChartKitTheme.current

    // A deep hierarchy produces a trail longer than a phone is wide. Scrolling
    // keeps the end of it — where the reader currently is — reachable, which
    // wrapping onto three lines would not.
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        trail.forEachIndexed { index, node ->
            val isCurrent = index == trail.lastIndex
            val navigate = { state.navigateTo(if (index == 0) null else node.id) }
            if (entry != null) {
                entry(node, isCurrent, navigate)
            } else {
                Text(
                    text = node.label,
                    style = theme.typography.breadcrumbLabel,
                    color = if (isCurrent) {
                        theme.colors.hierarchy.breadcrumbCurrent
                    } else {
                        theme.colors.hierarchy.breadcrumbAncestor
                    },
                    modifier = Modifier
                        // The current level is where the reader already is, so
                        // it is not a target: a button that does nothing is
                        // worse than plain text for anyone navigating by
                        // keyboard or screen reader.
                        .then(
                            if (isCurrent) {
                                Modifier
                            } else {
                                Modifier.clickable(role = Role.Button, onClick = navigate)
                            },
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            if (!isCurrent) {
                Text(
                    text = separator,
                    style = theme.typography.breadcrumbLabel,
                    color = theme.colors.hierarchy.breadcrumbAncestor,
                )
            }
        }
    }
}
