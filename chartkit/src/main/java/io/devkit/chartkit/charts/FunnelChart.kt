package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.layer.flow.FunnelBand
import io.devkit.chartkit.layer.flow.FunnelLabels
import io.devkit.chartkit.layer.flow.FunnelLayer
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.theme.ChartKitTheme
import io.devkit.chartkit.transform.FunnelStage
import io.devkit.chartkit.transform.FunnelTransform

/**
 * A funnel chart over the caller's own stages.
 *
 * ```kotlin
 * FunnelChart(
 *     data = stages,
 *     label = { it.name },
 *     value = { it.users },
 *     labels = FunnelLabels.LabelAndConversion,
 *     modifier = Modifier.fillMaxWidth().height(280.dp),
 * )
 * ```
 *
 * ```text
 * ▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇  Visited     12,000  100%
 *   ▇▇▇▇▇▇▇▇▇▇      Signed up    4,800   40%
 *     ▇▇▇▇▇▇        Activated    3,100   65%
 *       ▇▇          Subscribed     940   30%
 * ```
 *
 * ### Conversion, drop-off and share are always computed
 *
 * All four figures — the count, the share of the first stage, the conversion
 * from the previous one and the number lost — are available whether or not they
 * are drawn, and the tooltip, the labels and the accessibility announcement
 * each read the one they need from the same transform. Computing them in three
 * places is how they come to disagree.
 *
 * @param neckFraction the narrowest a stage is drawn, as a fraction of the
 *   widest. Non-zero, because a stage of a handful of users would otherwise be
 *   an invisible sliver with a label floating beside it.
 * @param orientation vertical by default. Horizontal is the same geometry
 *   turned ninety degrees, not a second implementation.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun <T> FunnelChart(
    data: List<T>,
    label: (T) -> String,
    value: (T) -> Number?,
    modifier: Modifier = Modifier,
    labels: FunnelLabels = FunnelLabels.LabelAndValue,
    orientation: ChartOrientation = ChartOrientation.Vertical,
    neckFraction: Float = 0.06f,
    color: ((T) -> Int?)? = null,
    legend: LegendPosition = LegendPosition.None,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    animation: ChartAnimation = ChartAnimation.Default,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter, showSeriesNames = false)
    },
    seriesId: String = ChartDefaults.SINGLE_SERIES_ID,
    seriesName: String = "Funnel",
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    require(neckFraction in 0f..1f) {
        "The funnel neck must be a fraction of the widest stage, was $neckFraction"
    }
    val density = LocalDensity.current
    val theme = ChartKitTheme.current

    val stages = remember(data, label, value) {
        FunnelTransform.resolve(data, label, value)
    }

    @Suppress("UNCHECKED_CAST")
    val typedColor = color as ((Any?) -> Int?)?

    PlanarChartCore(
        layers = { coordinates ->
            val spacing = with(density) { theme.dimensions.funnelStageSpacing.toPx() }
            listOf(
                FunnelLayer(
                    id = "funnel",
                    bands = funnelBands(
                        stages = stages,
                        bounds = coordinates.contentBounds,
                        orientation = orientation,
                        neckFraction = neckFraction,
                        spacing = spacing,
                    ),
                    seriesId = seriesId,
                    seriesName = seriesName,
                    valueFormatter = valueFormatter,
                    labels = labels,
                    colorOverrideOf = typedColor?.let { accessor ->
                        { stage -> accessor(stage.item) }
                    },
                    orientation = orientation,
                ),
            )
        },
        modifier = modifier,
        legend = legend,
        legendItems = remember(stages) {
            stages.map { stage ->
                ChartKeyItem(
                    id = "$seriesId-${stage.sourceIndex}",
                    label = stage.label,
                    paletteIndex = stage.sourceIndex,
                    colorOverride = typedColor?.invoke(stage.item),
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
        isEmpty = stages.isEmpty() || stages.all { it.value <= 0.0 },
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
        onSelectionChanged = onSelectionChanged?.let { callback ->
            { erased -> callback(erased?.asTyped()) }
        },
        tooltip = tooltip?.let { slot -> { erased -> slot(erased.asTypedTooltip()) } },
    )
}

/**
 * Trapezoids for the stages, laid out along the funnel's own direction.
 *
 * Each stage's leading edge is its own width and its trailing edge is the next
 * stage's, so the slope between two bands *is* the drop-off between them. The
 * last stage is drawn square, having nothing to narrow toward.
 *
 * Pure geometry over a rectangle: no Compose, no state, and computed inside the
 * layer builder's `remember`, so it is recomputed on a data or size change and
 * not on a selection.
 */
internal fun funnelBands(
    stages: List<FunnelStage>,
    bounds: ChartRect,
    orientation: ChartOrientation,
    neckFraction: Float,
    spacing: Float,
): List<FunnelBand> {
    if (stages.isEmpty() || bounds.isEmpty) return emptyList()
    val widest = stages.maxOf { it.value }
    if (widest <= 0.0) return emptyList()

    // Every stage is at least `neckFraction` wide. A stage of three users in a
    // funnel that started at a million would otherwise be a sub-pixel sliver
    // that cannot be seen, tapped or labelled.
    fun widthFraction(value: Double): Float =
        (neckFraction + (1f - neckFraction) * (value / widest).toFloat()).coerceIn(0f, 1f)

    val vertical = orientation.isVertical
    val extent = if (vertical) bounds.height else bounds.width
    val across = if (vertical) bounds.width else bounds.height
    val step = extent / stages.size

    return stages.mapIndexed { index, stage ->
        val leading = widthFraction(stage.value) * across
        val trailing = stages.getOrNull(index + 1)
            ?.let { widthFraction(it.value) * across }
            ?: leading

        val start = (if (vertical) bounds.top else bounds.left) + step * index
        val end = start + (step - spacing).coerceAtLeast(1f)
        val centre = if (vertical) bounds.centerX else bounds.centerY

        val bandBounds = if (vertical) {
            ChartRect(bounds.left, start, bounds.right, end)
        } else {
            ChartRect(start, bounds.top, end, bounds.bottom)
        }

        val shape = Path().apply {
            if (vertical) {
                moveTo(centre - leading / 2f, start)
                lineTo(centre + leading / 2f, start)
                lineTo(centre + trailing / 2f, end)
                lineTo(centre - trailing / 2f, end)
            } else {
                moveTo(start, centre - leading / 2f)
                lineTo(start, centre + leading / 2f)
                lineTo(end, centre + trailing / 2f)
                lineTo(end, centre - trailing / 2f)
            }
            close()
        }
        FunnelBand(stage = stage, bounds = bandBounds, shape = shape)
    }
}
