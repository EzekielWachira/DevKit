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
import io.devkit.chartkit.hierarchy.ChartHierarchy
import io.devkit.chartkit.hierarchy.HierarchyNode
import io.devkit.chartkit.hierarchy.HierarchyValueGuard
import io.devkit.chartkit.hierarchy.HierarchyValuePolicy
import io.devkit.chartkit.hierarchy.TreemapLayout
import io.devkit.chartkit.hierarchy.TreemapSpacing
import io.devkit.chartkit.hierarchy.buildHierarchy
import io.devkit.chartkit.layer.hierarchy.TreemapLabels
import io.devkit.chartkit.layer.hierarchy.TreemapLayer
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.state.ChartHierarchyState
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.state.rememberHierarchyChartState

/**
 * How a tap and a double tap are divided between selecting and drilling in.
 *
 * Explicit because the two genuinely conflict. A chart where every tap drills
 * cannot be used to inspect a value; a chart where nothing drills cannot be
 * navigated. Neither is right for every application, so the choice is stated.
 */
enum class HierarchyInteraction {

    /** Tap selects. Nothing drills; navigation is the caller's, through the state. */
    SelectOnly,

    /** Tap selects, double tap enters the node. The default. */
    DrillOnDoubleTap,

    /** Tap enters a branch and selects a leaf. */
    DrillOnTap,
    ;

    internal val drillsOnTap: Boolean get() = this == DrillOnTap
    internal val drillsOnDoubleTap: Boolean get() = this == DrillOnDoubleTap
}

/**
 * A treemap over the caller's own hierarchy.
 *
 * ```kotlin
 * data class Department(val name: String, val revenue: Double, val teams: List<Department>)
 *
 * Treemap(
 *     data = company,
 *     children = { it.teams },
 *     value = { it.revenue },
 *     label = { it.name },
 *     modifier = Modifier.fillMaxWidth().height(300.dp),
 * )
 * ```
 *
 * No node type, no conversion, no interface to implement: `data` is a
 * `Department` and stays one, and a selection hands a `Department` back. The
 * same four lambdas drive [SunburstChart], which shares this chart's hierarchy
 * model, its drill-down state and its breadcrumbs.
 *
 * ### Areas that sum
 *
 * A parent's value is the sum of its children's by default, because that is the
 * only arrangement in which comparing two rectangles means anything. See
 * [HierarchyValuePolicy] for the alternative and what it costs.
 *
 * ### Drilling in
 *
 * Hoist a [ChartHierarchyState] to control it, read the current level, or keep
 * a treemap and a sunburst of the same data in step:
 *
 * ```kotlin
 * val hierarchy = rememberHierarchyChartState()
 * ChartBreadcrumbs(hierarchy)
 * Treemap(..., hierarchyState = hierarchy)
 * ```
 *
 * @param maxDepth how many levels below the current root to draw. Two shows a
 *   level and its children, which is what makes the nesting visible without
 *   producing tiles too small to see.
 * @param key stable identity per node. Strongly preferred: drill-down,
 *   selection and animation are keyed on it.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun <T> Treemap(
    data: T,
    children: (T) -> List<T>,
    value: (T) -> Number?,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    key: ((T) -> Any)? = null,
    maxDepth: Int = 2,
    labels: TreemapLabels = TreemapLabels.Label,
    valuePolicy: HierarchyValuePolicy = HierarchyValuePolicy.AggregateChildren,
    valueGuard: HierarchyValueGuard = HierarchyValueGuard.Ignore,
    color: ((T) -> Int?)? = null,
    interaction: HierarchyInteraction = HierarchyInteraction.DrillOnDoubleTap,
    legend: LegendPosition = LegendPosition.None,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    animation: ChartAnimation = ChartAnimation.Default,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    hierarchyState: ChartHierarchyState = rememberHierarchyChartState(),
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    onDrillDown: ((HierarchyNode) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter, showSeriesNames = false)
    },
    seriesId: String = ChartDefaults.SINGLE_SERIES_ID,
    seriesName: String = "",
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    val density = LocalDensity.current
    val theme = io.devkit.chartkit.theme.ChartKitTheme.current

    val hierarchy: ChartHierarchy = remember(data, children, value, label, key, valuePolicy) {
        buildHierarchy(
            root = data,
            children = children,
            value = value,
            label = label,
            key = key,
            valuePolicy = valuePolicy,
            valueGuard = valueGuard,
        )
    }
    // Published rather than passed in: a breadcrumb bar resolves ids to labels
    // through the state, and asking the caller to normalise the tree a second
    // time to get them would be two traversals that can disagree.
    LaunchedEffect(hierarchy) { hierarchyState.hierarchy = hierarchy }

    val visibleRoot = hierarchyState.currentRoot ?: hierarchy.root

    // Palette slots are assigned from the *visible* root's children, so
    // drilling in recolours the chart to distinguish what is now on screen
    // rather than keeping twelve hues for one branch.
    val paletteIndexOf = remember(visibleRoot) { branchPaletteOf(visibleRoot) }

    val spacing = remember(theme.dimensions, density) {
        with(density) {
            TreemapSpacing(
                padding = theme.dimensions.treemapNestingPadding.toPx(),
                headerHeight = 0f,
                tileGap = theme.dimensions.treemapTileGap.toPx(),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    val typedColor = color as ((Any?) -> Int?)?

    PlanarChartCore(
        layers = { coordinates ->
            val tiles = TreemapLayout.layout(
                root = visibleRoot,
                bounds = coordinates.contentBounds,
                maxDepth = maxDepth,
                spacing = spacing,
            )
            listOf(
                TreemapLayer(
                    id = "treemap",
                    tiles = tiles,
                    visibleRoot = visibleRoot,
                    seriesId = seriesId,
                    seriesName = seriesName.ifBlank { visibleRoot.label },
                    labels = labels,
                    valueFormatter = valueFormatter,
                    paletteIndexOf = paletteIndexOf,
                    colorOverrideOf = typedColor?.let { accessor ->
                        { node -> accessor(node.item) }
                    },
                ),
            )
        },
        modifier = modifier,
        legend = legend,
        legendItems = remember(visibleRoot) {
            visibleRoot.children.mapIndexed { index, child ->
                ChartKeyItem(
                    id = child.id,
                    label = child.label,
                    paletteIndex = index,
                    colorOverride = null,
                )
            }
        },
        animation = animation,
        tapSelects = true,
        clearOnTapOutside = true,
        state = state.asErased(),
        valueFormatter = valueFormatter,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        renderMode = renderMode,
        staticOptions = staticOptions,
        isEmpty = visibleRoot.children.isEmpty() || visibleRoot.value <= 0.0,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
        onSelectionChanged = { erased ->
            val node = erased?.let { hierarchy.node(it.xLabel) }
            if (interaction.drillsOnTap && node != null && node.children.isNotEmpty()) {
                hierarchyState.drillTo(node)
                onDrillDown?.invoke(node)
            }
            onSelectionChanged?.invoke(erased?.asTyped())
        },
        onDoubleTap = if (!interaction.drillsOnDoubleTap) {
            null
        } else {
            { erased ->
                val node = erased?.let { hierarchy.node(it.xLabel) }
                when {
                    node != null && node.children.isNotEmpty() -> {
                        hierarchyState.drillTo(node)
                        onDrillDown?.invoke(node)
                    }
                    // A double tap on empty space, or on a leaf, goes back up.
                    // Without it a treemap with no breadcrumb bar is a trap.
                    else -> hierarchyState.drillUp()
                }
            }
        },
        tooltip = tooltip?.let { slot -> { erased -> slot(erased.asTypedTooltip()) } },
    )
}
