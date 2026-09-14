package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.PolarAreaGeometry
import io.devkit.chartkit.geometry.PolarAreaScaling
import io.devkit.chartkit.geometry.PolarDirection
import io.devkit.chartkit.geometry.PolarGeometry
import io.devkit.chartkit.layer.polar.PolarAreaEntry
import io.devkit.chartkit.layer.polar.PolarAreaLabels
import io.devkit.chartkit.layer.polar.PolarAreaLayer
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.theme.ChartKitTheme

/**
 * Equal-angle wedges of unequal radius — the polar area diagram, or Nightingale
 * rose.
 *
 * ```kotlin
 * PolarAreaChart(
 *     data = monthlyRainfall,
 *     category = { it.month },
 *     value = { it.millimetres },
 *     modifier = Modifier.fillMaxWidth().height(320.dp),
 * )
 * ```
 *
 * ```text
 *       ╱│╲        every wedge takes the same angle
 *      ╱ │ ╲       and reaches out by its own value
 *     ╱──┼──╲
 * ```
 *
 * ### When this rather than a pie
 *
 * A pie gives every slice the same radius and varies the angle, so it can only
 * say what share of a total each slice is — and it is therefore wrong whenever
 * the values do not *have* a meaningful total. This varies the radius instead,
 * which lets it show monthly rainfall, deaths by cause or wind by direction,
 * none of which sum to anything a reader wants. It also reads around a cycle:
 * twelve equal wedges are twelve months, and the shape closes where the year
 * does.
 *
 * ### Area carries the value, not radius
 *
 * The default is [PolarAreaScaling.Area], where the radius is the **square
 * root** of the value. A wedge is read by how much ink it covers, and area
 * grows with the square of the radius — so scaling the radius linearly doubles
 * the apparent size of a doubled value twice over, and a series from 1 to 4
 * looks like one from 1 to 16. Nightingale's own diagrams are
 * area-proportional; the later "coxcombs" that are not are why the form has a
 * reputation for exaggerating.
 *
 * [PolarAreaScaling.Radius] is offered for the cases where a radial distance
 * genuinely *is* the quantity — a reach, a range in kilometres — because
 * refusing it would only push callers into pre-transforming their data, which
 * hides the decision rather than removing it.
 *
 * ### A zero and a missing value look the same
 *
 * Both reach a radius of zero and there is no ink at zero radius to tell them
 * apart. A choropleth has a colour to spare for "no data"; a radius encoding has
 * nowhere to put the distinction, so this does not invent one. The
 * accessibility summary says "no value" where a value is absent, which is the
 * one place the difference survives.
 *
 * @param maxValue the value reaching the full radius, or `null` for the data's
 *   own largest. Fix it when two roses must be comparable — without it each
 *   chart rescales to itself, and two roses of very different magnitudes look
 *   identical.
 * @param rings how many concentric value rings to draw. They are placed through
 *   the same scaling as the wedges, so they are not evenly spaced.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun <T> PolarAreaChart(
    data: List<T>,
    category: (T) -> String,
    value: (T) -> Number?,
    modifier: Modifier = Modifier,
    scaling: PolarAreaScaling = PolarAreaScaling.Area,
    maxValue: Double? = null,
    rings: Int = 4,
    startAngle: Float = 0f,
    sweepAngle: Float = PolarGeometry.FULL_CIRCLE,
    direction: PolarDirection = PolarDirection.Clockwise,
    labels: PolarAreaLabels = PolarAreaLabels.Outside,
    color: ((T) -> Int?)? = null,
    legend: LegendPosition = LegendPosition.None,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    animation: ChartAnimation = ChartAnimation.Default,
    tapSelects: Boolean = true,
    clearOnTapOutside: Boolean = true,
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

    val entries = remember(data, category, value, color) {
        data.mapIndexed { index, item ->
            PolarAreaEntry(
                label = category(item),
                value = value(item)?.toDouble(),
                item = item,
                paletteIndex = index,
                colorOverride = color?.invoke(item),
            )
        }
    }

    val resolvedMax = remember(entries, maxValue) {
        maxValue ?: entries.mapNotNull { it.value }.filter { it.isFinite() && it > 0.0 }.maxOrNull()
            ?: 0.0
    }

    // The names sit outside the circle, so the circle has to be smaller than the
    // square it is inscribed in. Measured from the widest name rather than
    // guessed at, and capped so one long category costs its own label rather
    // than the whole chart.
    val textMeasurer = rememberTextMeasurer()
    val labelInset = remember(entries, labels, theme, density) {
        if (labels == PolarAreaLabels.None) {
            0f
        } else {
            with(density) {
                val widest = entries.maxOfOrNull { entry ->
                    textMeasurer.measure(entry.label, theme.typography.sliceLabel, maxLines = 1)
                        .size.width.toFloat()
                } ?: 0f
                (widest + theme.dimensions.labelPadding.toPx()).coerceAtMost(MAX_LABEL_INSET.toPx())
            }
        }
    }

    PolarChartCore(
        layers = { polar ->
            val slices = PolarAreaGeometry.slices(
                values = entries.map { it.value },
                outerRadius = polar.outerRadius,
                startAngle = polar.startAngle,
                sweepAngle = polar.sweepAngle,
                direction = polar.direction,
                scaling = scaling,
                maxValue = resolvedMax.takeIf { it > 0.0 },
            )
            listOf(
                PolarAreaLayer(
                    id = "polar-area",
                    entries = entries,
                    slices = slices,
                    seriesId = seriesId,
                    seriesName = seriesName,
                    valueFormatter = valueFormatter,
                    labels = labels,
                    scaling = scaling,
                    ringCount = rings,
                    maxValue = resolvedMax,
                ),
            )
        },
        modifier = modifier,
        // Wedges start at the centre, so there is no hole for an inner radius
        // to leave.
        innerRadiusRatio = 0f,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        direction = direction,
        legend = legend,
        legendItems = remember(entries) {
            entries.mapIndexed { index, entry ->
                ChartKeyItem(
                    id = "$seriesId-$index",
                    label = entry.label,
                    paletteIndex = entry.paletteIndex,
                    colorOverride = entry.colorOverride,
                )
            }
        },
        animation = animation,
        tapSelects = tapSelects,
        clearOnTapOutside = clearOnTapOutside,
        state = state.asErased(),
        valueFormatter = valueFormatter,
        onSelectionChanged = onSelectionChanged?.let { callback ->
            { erased -> callback(erased?.asTyped()) }
        },
        tooltip = tooltip?.let { slot -> { erased -> slot(erased.asTypedTooltip()) } },
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        isEmpty = entries.isEmpty(),
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

/** However long a category's name, a rose keeps most of its circle. */
private val MAX_LABEL_INSET = 64.dp
