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
import io.devkit.chartkit.geometry.PolarDirection
import io.devkit.chartkit.geometry.PolarGeometry
import io.devkit.chartkit.hierarchy.ChartHierarchy
import io.devkit.chartkit.hierarchy.HierarchyNode
import io.devkit.chartkit.hierarchy.HierarchyValueGuard
import io.devkit.chartkit.hierarchy.HierarchyValuePolicy
import io.devkit.chartkit.hierarchy.SunburstLayout
import io.devkit.chartkit.hierarchy.buildHierarchy
import io.devkit.chartkit.layer.hierarchy.SunburstLabels
import io.devkit.chartkit.layer.hierarchy.SunburstLayer
import io.devkit.chartkit.layer.custom.CustomPolarLayer
import io.devkit.chartkit.layer.custom.CustomPolarLayerRenderer
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.state.ChartHierarchyState
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.state.rememberHierarchyChartState
import io.devkit.chartkit.theme.ChartKitTheme

/**
 * A sunburst over the caller's own hierarchy.
 *
 * ```kotlin
 * SunburstChart(
 *     data = company,
 *     children = { it.teams },
 *     value = { it.revenue },
 *     label = { it.name },
 *     centerContent = { Text(hierarchy.currentRoot?.label.orEmpty()) },
 * )
 * ```
 *
 * ```text
 * angle  = the node's share of its parent
 * radius = how far below the visible root it sits
 * ```
 *
 * ### The same tree as the treemap
 *
 * The four lambdas, the hierarchy model, the drill-down state and the
 * breadcrumbs are [Treemap]'s. Pass the *same* [ChartHierarchyState] to both
 * and they stay in step — drilling into one drills the other — because there is
 * one navigation state and not two.
 *
 * ### Rings, and the polar engine
 *
 * Built on [PolarChartCore], the same engine as the pie, donut and radial bar
 * charts. The coordinate system, the tooltip overlay, the legend, the selection
 * state, the centre-content slot and the accessibility layer are all shared;
 * what a sunburst adds is one layout function.
 *
 * @param innerRadiusRatio the hole at the centre, as a fraction of the outer
 *   radius. Non-zero by default: the hole is where the current level's name and
 *   total belong, and it is what a reader taps to go back up.
 * @param maxDepth how many rings to draw below the current root.
 * @param centerContent Compose content laid out inside the hole. A total, the
 *   current level's name, a back button — anything.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun <T> SunburstChart(
    data: T,
    children: (T) -> List<T>,
    value: (T) -> Number?,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    key: ((T) -> Any)? = null,
    maxDepth: Int = 3,
    innerRadiusRatio: Float = 0.28f,
    startAngle: Float = 0f,
    sweepAngle: Float = PolarGeometry.FULL_CIRCLE,
    direction: PolarDirection = PolarDirection.Clockwise,
    labels: SunburstLabels = SunburstLabels.None,
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
    customLayers: List<CustomPolarLayer> = emptyList(),
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
    centerContent: (@Composable () -> Unit)? = null,
) {
    val density = LocalDensity.current
    val theme = ChartKitTheme.current

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
    LaunchedEffect(hierarchy) { hierarchyState.hierarchy = hierarchy }

    val visibleRoot = hierarchyState.currentRoot ?: hierarchy.root
    val paletteIndexOf = remember(visibleRoot) { branchPaletteOf(visibleRoot) }

    @Suppress("UNCHECKED_CAST")
    val typedColor = color as ((Any?) -> Int?)?

    PolarChartCore(
        layers = { polar ->
            val arcs = SunburstLayout.layout(
                root = visibleRoot,
                innerRadius = polar.innerRadius,
                outerRadius = polar.outerRadius,
                maxDepth = maxDepth,
                startAngle = polar.startAngle,
                sweepAngle = polar.sweepAngle,
                ringSpacing = with(density) { theme.dimensions.sunburstRingSpacing.toPx() },
                sliceGap = theme.dimensions.sunburstSliceGap,
            )
            listOf(
                SunburstLayer(
                    id = "sunburst",
                    arcs = arcs,
                    visibleRoot = visibleRoot,
                    seriesId = seriesId,
                    seriesName = seriesName.ifBlank { visibleRoot.label },
                    valueFormatter = valueFormatter,
                    paletteIndexOf = paletteIndexOf,
                    colorOverrideOf = typedColor?.let { accessor -> { node -> accessor(node.item) } },
                    labels = labels,
                ),
            ) + customLayers.map(::CustomPolarLayerRenderer)
        },
        modifier = modifier,
        innerRadiusRatio = innerRadiusRatio,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        direction = direction,
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
                if (node != null && node.children.isNotEmpty()) {
                    hierarchyState.drillTo(node)
                    onDrillDown?.invoke(node)
                } else {
                    // A double tap on the hole, or on a leaf, goes back up —
                    // which is what makes the centre of a sunburst the "up"
                    // affordance readers already expect it to be.
                    hierarchyState.drillUp()
                }
            }
        },
        tooltip = tooltip?.let { slot -> { erased -> slot(erased.asTypedTooltip()) } },
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        isEmpty = visibleRoot.children.isEmpty() || visibleRoot.value <= 0.0,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
        centerContent = centerContent,
        renderMode = renderMode,
        staticOptions = staticOptions,
    )
}

/** A node's palette slot: the index of its ancestor among the visible root's children. */
internal fun branchPaletteOf(visibleRoot: HierarchyNode): (HierarchyNode) -> Int {
    val slots = HashMap<String, Int>()
    visibleRoot.children.forEachIndexed { index, child ->
        child.selfAndDescendants().forEach { slots[it.id] = index }
    }
    return { node -> slots[node.id] ?: 0 }
}
