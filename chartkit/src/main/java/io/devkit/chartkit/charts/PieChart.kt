package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.PolarDirection
import io.devkit.chartkit.geometry.PolarGeometry
import io.devkit.chartkit.geometry.PolarValuePolicy
import io.devkit.chartkit.geometry.computePolarSlices
import io.devkit.chartkit.layer.polar.SliceLabelContent
import io.devkit.chartkit.layer.polar.SliceLabelPosition
import io.devkit.chartkit.layer.polar.SliceLayer
import io.devkit.chartkit.layer.polar.SliceSeriesEntry
import io.devkit.chartkit.layer.custom.CustomPolarLayer
import io.devkit.chartkit.layer.custom.CustomPolarLayerRenderer
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChartState

/**
 * A pie chart over the caller's own data.
 *
 * ```kotlin
 * data class Expense(val category: String, val amount: Double)
 *
 * PieChart(
 *     data = expenses,
 *     value = { it.amount },
 *     label = { it.category },
 *     modifier = Modifier.fillMaxWidth().height(260.dp),
 * )
 * ```
 *
 * No conversion step and no slice type: `data` is a `List<Expense>` and stays
 * one, exactly as it does for a line or bar chart. Values are normalised, so
 * they need not sum to anything in particular — `40, 30, 20, 10` and
 * `0.4, 0.3, 0.2, 0.1` draw the same chart.
 *
 * ### Angles
 *
 * The first slice starts at **twelve o'clock** and the chart runs **clockwise**,
 * which is what a reader expects and what nobody should have to configure. Both
 * are overridable.
 *
 * ### Values a pie cannot represent
 *
 * A share of a whole is never negative, and `NaN` is not a share at all. Such
 * values are **dropped** by default — they contribute nothing to the total and
 * draw no slice, while keeping their legend row and their colour — rather than
 * being silently turned into a positive share of a total they reduce. Pass
 * [PolarValuePolicy.Reject] to have them throw instead.
 *
 * @param value the slice's magnitude.
 * @param label its name, used by the legend, the tooltip, slice labels and the
 *   accessibility summary.
 * @param innerRadiusRatio the hole, as a fraction of the outer radius. Zero
 *   here; [DonutChart] is this function with a hole.
 * @param sliceGap angular space between slices, in degrees. Taken out of each
 *   slice's own sweep, so the circle still closes.
 * @param color an optional per-item colour, for categories whose colour carries
 *   meaning. `null` takes the theme palette.
 */
@Suppress("LongParameterList")
@Composable
fun <T> PieChart(
    data: List<T>,
    value: (T) -> Number?,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    innerRadiusRatio: Float = 0f,
    startAngle: Float = 0f,
    sweepAngle: Float = PolarGeometry.FULL_CIRCLE,
    direction: PolarDirection = PolarDirection.Clockwise,
    sliceGap: Float = 0f,
    valuePolicy: PolarValuePolicy = PolarValuePolicy.Ignore,
    color: ((T) -> Int?)? = null,
    labelPosition: SliceLabelPosition = SliceLabelPosition.None,
    labelContent: SliceLabelContent = SliceLabelContent.LabelAndPercentage,
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
    val entries = remember(data, label, color) {
        data.mapIndexed { index, item ->
            SliceSeriesEntry(
                label = label(item),
                item = item,
                paletteIndex = index,
                colorOverride = color?.invoke(item),
            )
        }
    }

    val slices = remember(data, value, startAngle, sweepAngle, sliceGap, direction, valuePolicy) {
        computePolarSlices(
            values = data.map { value(it)?.toDouble() },
            startAngle = startAngle,
            totalSweep = sweepAngle,
            gapDegrees = sliceGap,
            direction = direction,
            policy = valuePolicy,
        )
    }

    // Empty means "nothing to draw", which covers an empty list and a dataset
    // whose every value was zero or unusable — both produce a ring of
    // zero-sweep slices, and drawing that is a blank circle with no
    // explanation.
    val isEmpty = slices.none { it.sweepAngle > 0f }

    PolarChartCore(
        layers = {
            listOf(
                SliceLayer(
                    id = "slices",
                    slices = slices,
                    entries = entries,
                    seriesId = seriesId,
                    seriesName = seriesName,
                    labelPosition = labelPosition,
                    labelContent = labelContent,
                    valueFormatter = valueFormatter,
                ),
            ) + customLayers.map(::CustomPolarLayerRenderer)
        },
        modifier = modifier,
        innerRadiusRatio = innerRadiusRatio,
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
        isEmpty = isEmpty,
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

/**
 * A donut chart: a pie with a hole, and optionally something in it.
 *
 * ```kotlin
 * DonutChart(
 *     data = usage,
 *     value = { it.value },
 *     label = { it.label },
 *     centerContent = { Text("72%", style = MaterialTheme.typography.headlineMedium) },
 * )
 * ```
 *
 * Not a separate renderer. It calls [PieChart] with a non-zero
 * [innerRadiusRatio], because a donut *is* a pie with an inner radius — the
 * difference lives in the coordinate system, not in the drawing.
 *
 * [centerContent] is a Compose slot laid out inside the hole, so it can hold
 * anything: a total, a percentage, an icon, a small KPI. It takes no pointer
 * input, so the hole stays inert and no slice loses a tap to it.
 */
@Suppress("LongParameterList")
@Composable
fun <T> DonutChart(
    data: List<T>,
    value: (T) -> Number?,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    innerRadiusRatio: Float = DEFAULT_DONUT_INNER_RATIO,
    startAngle: Float = 0f,
    sweepAngle: Float = PolarGeometry.FULL_CIRCLE,
    direction: PolarDirection = PolarDirection.Clockwise,
    sliceGap: Float = 0f,
    valuePolicy: PolarValuePolicy = PolarValuePolicy.Ignore,
    color: ((T) -> Int?)? = null,
    labelPosition: SliceLabelPosition = SliceLabelPosition.None,
    labelContent: SliceLabelContent = SliceLabelContent.LabelAndPercentage,
    legend: LegendPosition = LegendPosition.Bottom,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    animation: ChartAnimation = ChartAnimation.Default,
    tapSelects: Boolean = true,
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
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
    centerContent: (@Composable () -> Unit)? = null,
) {
    PieChart(
        data = data,
        value = value,
        label = label,
        modifier = modifier,
        innerRadiusRatio = innerRadiusRatio,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        direction = direction,
        sliceGap = sliceGap,
        valuePolicy = valuePolicy,
        color = color,
        labelPosition = labelPosition,
        labelContent = labelContent,
        legend = legend,
        valueFormatter = valueFormatter,
        animation = animation,
        tapSelects = tapSelects,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        state = state,
        onSelectionChanged = onSelectionChanged,
        tooltip = tooltip,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
        centerContent = centerContent,
        renderMode = renderMode,
        staticOptions = staticOptions,
        customLayers = customLayers,
    )
}

/** Wide enough to read as a ring, narrow enough to leave the slices legible. */
const val DEFAULT_DONUT_INNER_RATIO: Float = 0.6f
