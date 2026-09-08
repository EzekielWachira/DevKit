package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.PolarDirection
import io.devkit.chartkit.geometry.PolarGeometry
import io.devkit.chartkit.layer.polar.RadialBarEntry
import io.devkit.chartkit.layer.polar.RadialBarLayer
import io.devkit.chartkit.geometry.RadialRangePolicy
import io.devkit.chartkit.layer.custom.CustomPolarLayer
import io.devkit.chartkit.layer.custom.CustomPolarLayerRenderer
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.theme.ChartKitTheme

/**
 * Concentric progress rings, one per metric.
 *
 * ```kotlin
 * data class Metric(val name: String, val value: Double)
 *
 * RadialBarChart(
 *     data = metrics,
 *     value = { it.value },
 *     label = { it.name },
 *     maxValue = 100.0,
 * )
 * ```
 *
 * Built on the same polar coordinate system as [PieChart] and [DonutChart], and
 * on the same angle convention: the first ring starts at twelve o'clock and
 * runs clockwise.
 *
 * ### The range is yours
 *
 * [minValue] and [maxValue] name the domain a value's sweep is measured
 * against, and neither is fixed at a percentage. A chart of storage in
 * gigabytes, a score out of five or a temperature between −10 and 40 all work
 * without normalising the data first. `maxValue = null` derives the maximum
 * from the data, which is right for a comparison and wrong for a target — so
 * it is not the default.
 *
 * @param sweepAngle how much of the circle a full value covers. The default is
 *   a complete ring; `270` leaves a gauge-like gap at the bottom.
 * @param rangePolicy what to do with a value outside `[minValue, maxValue]`.
 */
@Suppress("LongParameterList")
@Composable
fun <T> RadialBarChart(
    data: List<T>,
    value: (T) -> Number?,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    minValue: Double = 0.0,
    maxValue: Double? = null,
    startAngle: Float = 0f,
    sweepAngle: Float = PolarGeometry.FULL_CIRCLE,
    direction: PolarDirection = PolarDirection.Clockwise,
    barThickness: Dp? = null,
    barSpacing: Dp? = null,
    rangePolicy: RadialRangePolicy = RadialRangePolicy.Clamp,
    color: ((T) -> Int?)? = null,
    legend: LegendPosition = LegendPosition.Bottom,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    animation: ChartAnimation = ChartAnimation.Default,
    tapSelects: Boolean = true,
    clearOnTapOutside: Boolean = true,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    customLayers: List<CustomPolarLayer> = emptyList(),
    accessibilitySummary: (() -> String)? = null,
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
    val dimensions = ChartKitTheme.dimensions
    val density = LocalDensity.current

    val entries = remember(data, value, label, color) {
        data.mapIndexed { index, item ->
            RadialBarEntry(
                label = label(item),
                value = value(item)?.toDouble(),
                item = item,
                paletteIndex = index,
                colorOverride = color?.invoke(item),
            )
        }
    }

    val resolvedMax = remember(entries, maxValue, minValue) {
        maxValue ?: entries.mapNotNull { it.value }.filter { it.isFinite() }.maxOrNull()
            // A dataset with no usable value still needs a range wide enough to
            // divide by; the tracks then draw empty, which is the truth.
            ?.takeIf { it > minValue }
            ?: (minValue + 1.0)
    }

    val thickness = with(density) { (barThickness ?: dimensions.radialBarThickness).toPx() }
    val spacing = with(density) { (barSpacing ?: dimensions.radialBarSpacing).toPx() }

    PolarChartCore(
        layers = {
            listOf(
                RadialBarLayer(
                    id = "radial",
                    entries = entries,
                    seriesId = seriesId,
                    seriesName = seriesName,
                    minValue = minValue,
                    maxValue = resolvedMax,
                    trackThickness = thickness,
                    trackSpacing = spacing,
                    rangePolicy = rangePolicy,
                    valueFormatter = valueFormatter,
                ),
            ) + customLayers.map(::CustomPolarLayerRenderer)
        },
        modifier = modifier,
        // Radial bars lay their tracks out from the outer radius inwards, so
        // the ring's inner edge is decided by the tracks themselves rather than
        // by a ratio.
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
    )
}
