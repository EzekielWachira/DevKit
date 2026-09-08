package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.layer.polar.RadarAxisSpec
import io.devkit.chartkit.layer.polar.RadarLayer
import io.devkit.chartkit.layer.polar.RadarNormalization
import io.devkit.chartkit.layer.polar.RadarSeriesEntry
import io.devkit.chartkit.layer.polar.RadarWebLayer
import io.devkit.chartkit.geometry.PolarDirection
import io.devkit.chartkit.geometry.PolarGeometry
import io.devkit.chartkit.layer.custom.CustomPolarLayer
import io.devkit.chartkit.layer.custom.CustomPolarLayerRenderer
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChartState

/**
 * A radar chart over the caller's own data.
 *
 * ```kotlin
 * data class Score(val skill: String, val value: Double)
 *
 * RadarChart(
 *     data = scores,
 *     metric = { it.skill },
 *     value = { it.value },
 * )
 * ```
 *
 * ### Built on the polar coordinates the pie charts use
 *
 * A radar chart is a polar chart whose **radius** carries a value and whose
 * **angle** carries a category — which is what
 * [io.devkit.chartkit.coordinate.PolarCoordinates] already describes. It cost
 * two layers and no new coordinate system, no new layout and no new interaction
 * model.
 *
 * ### Normalisation
 *
 * The single most misleading decision available in a radar chart, so it is a
 * parameter rather than an assumption. [RadarNormalization.PerAxis] scales each
 * spoke to its own metric's range, which is right when the metrics are not
 * commensurable — a latency in milliseconds beside a score out of ten — and is
 * the default because that is the usual case. [RadarNormalization.Shared]
 * gives every spoke one domain, which is right when they are comparable and
 * makes the polygon's shape mean something.
 *
 * Under either, the **area** of the polygon means nothing in particular and
 * should not be read as a total. That is true of every radar chart, and worth
 * knowing before choosing one.
 *
 * ### A missing metric is a gap
 *
 * A series with no value on a spoke leaves the polygon open there rather than
 * pulling it to the centre — drawing a zero would assert a measurement, and on
 * a radar chart that reads as the series being worst at that metric.
 *
 * @param metric names the spoke. Spokes are taken from the data in input order,
 *   first occurrence first.
 * @param valueRange fixes one range for every spoke, e.g. `0.0..100.0` for a
 *   chart of percentages. Overrides [normalization].
 */
@Suppress("LongParameterList")
@Composable
fun <T> RadarChart(
    data: List<T>,
    metric: (T) -> String,
    value: (T) -> Number?,
    modifier: Modifier = Modifier,
    seriesName: String = "",
    normalization: RadarNormalization = RadarNormalization.PerAxis,
    valueRange: ClosedFloatingPointRange<Double>? = null,
    rings: Int = DEFAULT_RADAR_RINGS,
    startAngle: Float = 0f,
    filled: Boolean = true,
    showPoints: Boolean = true,
    metricLabel: (String) -> String = { it },
    legend: LegendPosition = LegendPosition.None,
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
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter, showSeriesNames = true)
    },
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    RadarChart(
        series = remember(data, seriesName) {
            singleSeries(data, ChartDefaults.SINGLE_SERIES_ID, seriesName)
        },
        metric = metric,
        value = value,
        modifier = modifier,
        normalization = normalization,
        valueRange = valueRange,
        rings = rings,
        startAngle = startAngle,
        filled = filled,
        showPoints = showPoints,
        metricLabel = metricLabel,
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
        renderMode = renderMode,
        staticOptions = staticOptions,
        customLayers = customLayers,
    )
}

/**
 * A multi-series radar chart.
 *
 * ```kotlin
 * RadarChart(
 *     series = listOf(
 *         ChartSeries(id = "q1", name = "Q1", data = q1Scores),
 *         ChartSeries(id = "q2", name = "Q2", data = q2Scores),
 *     ),
 *     metric = { it.skill },
 *     value = { it.score },
 *     legend = LegendPosition.Bottom,
 * )
 * ```
 *
 * Comparing two or three series is what a radar chart is genuinely good at.
 * Beyond that the polygons overlap into a shape nobody can read, and a grouped
 * bar chart says the same thing better — which is a limitation of the chart
 * type rather than of this implementation, and not one a library can fix.
 */
@JvmName("RadarChartSeries")
@Suppress("LongParameterList")
@Composable
fun <T> RadarChart(
    series: List<ChartSeries<T>>,
    metric: (T) -> String,
    value: (T) -> Number?,
    modifier: Modifier = Modifier,
    normalization: RadarNormalization = RadarNormalization.PerAxis,
    valueRange: ClosedFloatingPointRange<Double>? = null,
    rings: Int = DEFAULT_RADAR_RINGS,
    startAngle: Float = 0f,
    filled: Boolean = true,
    showPoints: Boolean = true,
    metricLabel: (String) -> String = { it },
    legend: LegendPosition = if (series.size > 1) LegendPosition.Bottom else LegendPosition.None,
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
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter, showSeriesNames = true)
    },
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    val visibleSeries = remember(series, state.hiddenSeriesIds) {
        series.map { it.copy(visible = it.visible && state.isSeriesVisible(it.id)) }
    }

    // Spokes in input order, first occurrence first. Sorting them would reorder
    // the polygon and change its shape for no reason a reader can see.
    val metrics = remember(visibleSeries, metric) {
        LinkedHashSet<String>().apply {
            visibleSeries.forEach { s -> s.data.forEach { add(metric(it)) } }
        }.toList()
    }

    val seriesValues = remember(visibleSeries, metric, value, metrics) {
        visibleSeries.map { s ->
            val byMetric = HashMap<String, Double?>(metrics.size)
            val itemsByMetric = HashMap<String, Any?>(metrics.size)
            s.data.forEach { item ->
                val name = metric(item)
                byMetric[name] = value(item)?.toDouble()?.takeIf { it.isFinite() }
                itemsByMetric[name] = item
            }
            metrics.map { byMetric[it] } to metrics.map { itemsByMetric[it] }
        }
    }

    val axes = remember(metrics, seriesValues, normalization, valueRange) {
        val shared = valueRange?.let { NumericDomain(it.start, it.endInclusive) }
            ?: if (normalization == RadarNormalization.Shared) {
                NumericDomain.of(seriesValues.flatMap { it.first }.filterNotNull())
                    ?.let { NumericDomain(minOf(0.0, it.min), it.max) }
                    ?: NumericDomain.Default
            } else {
                null
            }

        metrics.mapIndexed { index, name ->
            RadarAxisSpec(
                label = name,
                domain = shared ?: run {
                    val values = seriesValues.mapNotNull { it.first.getOrNull(index) }
                    // Anchored at zero, so the centre of the chart means "none"
                    // rather than "the smallest anyone scored" — which would
                    // make the weakest series touch the centre on every spoke.
                    NumericDomain.of(values)
                        ?.let { NumericDomain(minOf(0.0, it.min), it.max) }
                        ?.resolved()
                        ?: NumericDomain.Default
                },
            )
        }
    }

    val entries = remember(visibleSeries, seriesValues) {
        visibleSeries.mapIndexed { index, s ->
            RadarSeriesEntry(
                seriesId = s.id,
                seriesName = s.name,
                seriesIndex = index,
                paletteIndex = index,
                colorOverride = s.color,
                values = seriesValues[index].first,
                items = seriesValues[index].second,
            )
        }
    }

    // The room the spoke labels need, measured the way the Cartesian axes
    // measure theirs. A fixed fraction cannot be right for both "Cost" and
    // "Reliability", and the failure mode of guessing too small is a metric
    // name that silently does not appear.
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = io.devkit.chartkit.theme.ChartKitTheme.typography.axisLabel
    val labelPadding = with(androidx.compose.ui.platform.LocalDensity.current) {
        io.devkit.chartkit.theme.ChartKitTheme.dimensions.labelPadding.toPx()
    }
    val labelReserve = remember(metrics, metricLabel, labelStyle, labelPadding, textMeasurer) {
        val measured = metrics.mapNotNull { name ->
            metricLabel(name).takeIf { it.isNotBlank() }?.let { textMeasurer.measure(it, labelStyle) }
        }
        if (measured.isEmpty()) {
            0f
        } else {
            // The horizontal extremes need the widest label's width and the
            // vertical ones its height; reserving the larger covers both.
            maxOf(
                measured.maxOf { it.size.width }.toFloat(),
                measured.maxOf { it.size.height }.toFloat(),
            ) + labelPadding * 3f
        }
    }

    PolarChartCore(
        layers = {
            listOf(
                RadarWebLayer(
                    axes = axes,
                    rings = rings.coerceAtLeast(1),
                    startAngle = startAngle,
                    labelFormatter = metricLabel,
                ),
                RadarLayer(
                    id = "radar",
                    axes = axes,
                    series = entries,
                    startAngle = startAngle,
                    filled = filled,
                    showPoints = showPoints,
                    valueFormatter = valueFormatter,
                ),
            ) + customLayers.map(::CustomPolarLayerRenderer)
        },
        modifier = modifier,
        // A radar has no hole: the centre is the origin of every spoke.
        innerRadiusRatio = 0f,
        startAngle = startAngle,
        sweepAngle = PolarGeometry.FULL_CIRCLE,
        direction = PolarDirection.Clockwise,
        legend = legend,
        legendItems = remember(entries) {
            entries.map { entry ->
                ChartKeyItem(
                    id = entry.seriesId,
                    label = entry.seriesName.ifBlank { entry.seriesId },
                    paletteIndex = entry.paletteIndex,
                    colorOverride = entry.colorOverride,
                )
            }
        },
        animation = animation,
        tapSelects = tapSelects,
        clearOnTapOutside = true,
        state = state.asErased(),
        valueFormatter = valueFormatter,
        onSelectionChanged = onSelectionChanged?.let { callback ->
            { erased -> callback(erased?.asTyped()) }
        },
        tooltip = tooltip?.let { slot -> { erased -> slot(erased.asTypedTooltip()) } },
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        isEmpty = metrics.size < 3 || entries.all { entry -> entry.values.all { it == null } },
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
        renderMode = renderMode,
        staticOptions = staticOptions,
        centerContent = null,
        // Measured, not guessed: the ring shrinks by whatever the widest metric
        // name actually needs, so "Reliability" and "Cost" are both drawn
        // rather than the long one being silently dropped.
        radiusInset = labelReserve,
    )
}

/**
 * Enough rings to read a value against, few enough not to crowd the polygons.
 *
 * A radar chart with three metrics is a triangle and with fewer is not a chart
 * at all, which is why [RadarChart] draws its empty state below three spokes
 * rather than a degenerate shape.
 */
const val DEFAULT_RADAR_RINGS: Int = 4
