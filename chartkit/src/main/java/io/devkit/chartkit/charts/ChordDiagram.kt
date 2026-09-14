package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.flow.ChordLayout
import io.devkit.chartkit.flow.ChordValidation
import io.devkit.chartkit.flow.buildChordMatrix
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.PolarDirection
import io.devkit.chartkit.geometry.PolarGeometry
import io.devkit.chartkit.layer.flow.ChordLabels
import io.devkit.chartkit.layer.flow.ChordLayer
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.theme.ChartKitTheme

/**
 * Flows between groups, arranged on a circle.
 *
 * ```kotlin
 * ChordDiagram(
 *     groups = regions,
 *     flows = migrations,
 *     groupId = { it.code },
 *     groupLabel = { it.name },
 *     source = { it.from },
 *     target = { it.to },
 *     value = { it.people },
 *     modifier = Modifier.fillMaxWidth().height(320.dp),
 * )
 * ```
 *
 * Two lists and five lambdas. Nothing is required of `Region` or `Migration`:
 * no interface, no conversion, no matrix to assemble — and a selection hands the
 * caller's own object back.
 *
 * ### When this rather than a Sankey
 *
 * [SankeyChart] lays flow out left to right, which encodes a direction of travel
 * through stages: it is the right picture for a funnel, a pipeline or a budget,
 * and it cannot express a flow that goes back. A circle has no upstream, so two
 * groups can exchange in **both** directions and a group can flow into itself —
 * migration between regions, trade between countries, traffic between pages.
 * Reach for a Sankey when the stages are ordered and this when they are peers.
 *
 * ### One ribbon per flow
 *
 * Unlike the classic Circos diagram, a ribbon here is exactly one of the
 * caller's flows and both of its ends are that flow's value; two directions
 * between the same pair are two ribbons. See
 * [io.devkit.chartkit.flow.ChordMatrix] for why — briefly, a ribbon with ends of
 * two different widths is two numbers wearing one shape.
 *
 * ### Built on the polar engine
 *
 * The same [io.devkit.chartkit.coordinate.PolarCoordinates] as pie, donut and
 * radial bar, and the same angle convention: the first group starts at twelve
 * o'clock and the diagram runs clockwise. It cost one layer, not a second
 * engine.
 *
 * @param padAngle the gap between two groups, in degrees. Some gap is load
 *   bearing — without it adjacent groups read as one arc — and it is capped
 *   internally so a diagram of many groups cannot become all gaps.
 * @param validation what to do with a flow naming a group that does not exist,
 *   or carrying a weight that cannot be drawn.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun <G, F> ChordDiagram(
    groups: List<G>,
    flows: List<F>,
    groupId: (G) -> Any,
    source: (F) -> Any,
    target: (F) -> Any,
    value: (F) -> Number?,
    modifier: Modifier = Modifier,
    groupLabel: ((G) -> String)? = null,
    groupColor: ((G) -> Int?)? = null,
    flowLabel: ((F) -> String?)? = null,
    labels: ChordLabels = ChordLabels.Outside,
    validation: ChordValidation = ChordValidation.Drop,
    startAngle: Float = 0f,
    sweepAngle: Float = PolarGeometry.FULL_CIRCLE,
    direction: PolarDirection = PolarDirection.Clockwise,
    padAngle: Float? = null,
    bandThickness: Dp? = null,
    legend: LegendPosition = LegendPosition.None,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    animation: ChartAnimation = ChartAnimation.Default,
    tapSelects: Boolean = true,
    clearOnTapOutside: Boolean = true,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    state: ChartState<Any?> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<Any?>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<Any?>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter)
    },
    seriesId: String = ChartDefaults.SINGLE_SERIES_ID,
    seriesName: String = "Flow",
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
    centerContent: (@Composable () -> Unit)? = null,
) {
    val density = LocalDensity.current
    val theme = ChartKitTheme.current

    val matrix = remember(groups, flows, groupId, source, target, value, validation) {
        buildChordMatrix(
            groups = groups,
            flows = flows,
            groupId = groupId,
            groupLabel = { groupLabel?.invoke(it) ?: groupId(it).toString() },
            source = source,
            target = target,
            value = value,
            flowLabel = flowLabel,
            validation = validation,
        )
    }

    val thickness = with(density) { (bandThickness ?: theme.dimensions.chordBandThickness).toPx() }
    val pad = padAngle ?: theme.dimensions.chordGroupPadding

    // The names are written outside the circle, so the circle has to be smaller
    // than the square it is inscribed in. Measured from the widest label rather
    // than guessed at — the same rule the Sankey gutters and the radar's spokes
    // follow, because an estimate either wastes half the plot or clips the
    // longest name.
    val textMeasurer = rememberTextMeasurer()
    val labelInset = remember(matrix, labels, theme, density, valueFormatter) {
        if (labels == ChordLabels.None) {
            0f
        } else {
            with(density) {
                val style = theme.typography.nodeLabel
                val widest = matrix.groups.maxOfOrNull { group ->
                    val text = if (labels == ChordLabels.OutsideWithValue) {
                        "${group.label}  ${valueFormatter.format(group.total)}"
                    } else {
                        group.label
                    }
                    textMeasurer.measure(text, style, maxLines = 1).size.width.toFloat()
                } ?: 0f
                // Capped, so one pathologically long group name costs its own
                // label rather than shrinking the circle to nothing.
                (widest + theme.dimensions.labelPadding.toPx())
                    .coerceAtMost(MAX_LABEL_INSET.toPx())
            }
        }
    }

    PolarChartCore(
        layers = { polar ->
            val geometry = ChordLayout.layout(
                matrix = matrix,
                startAngle = polar.startAngle,
                sweepAngle = polar.sweepAngle,
                direction = polar.direction,
                padAngle = pad,
            )
            listOf(
                ChordLayer(
                    id = "chord",
                    matrix = matrix,
                    geometry = geometry,
                    seriesId = seriesId,
                    seriesName = seriesName,
                    valueFormatter = valueFormatter,
                    labels = labels,
                    bandThickness = thickness,
                    groupColorOf = groupColor?.let { accessor ->
                        { index -> groups.getOrNull(index)?.let(accessor) }
                    },
                ),
            )
        },
        modifier = modifier,
        // The groups' band is drawn inward from the outer radius and the ribbons
        // hang inside it, so the ring is the whole disc rather than an annulus.
        innerRadiusRatio = 0f,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        direction = direction,
        legend = legend,
        legendItems = remember(matrix, groupColor) {
            matrix.groups.map { group ->
                ChartKeyItem(
                    id = group.id,
                    label = group.label,
                    paletteIndex = group.index,
                    colorOverride = groupColor?.let { accessor ->
                        groups.getOrNull(group.index)?.let(accessor)
                    },
                )
            }
        },
        animation = animation,
        tapSelects = tapSelects,
        clearOnTapOutside = clearOnTapOutside,
        state = state,
        valueFormatter = valueFormatter,
        onSelectionChanged = onSelectionChanged,
        tooltip = tooltip,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        isEmpty = matrix.isEmpty,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
        renderMode = renderMode,
        staticOptions = staticOptions,
        centerContent = centerContent,
        radiusInset = labelInset,
    )
}

/** However long a group's name, a chord diagram keeps most of its circle. */
private val MAX_LABEL_INSET = 72.dp
