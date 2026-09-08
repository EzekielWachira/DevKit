package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.annotation.ChartAnnotation
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartGrid
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.statistical.ViolinEntry
import io.devkit.chartkit.layer.statistical.ViolinOverlay
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.ChartViewportState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.state.rememberChartViewportState
import io.devkit.chartkit.stats.BoxStatistics
import io.devkit.chartkit.stats.DensityEstimator
import io.devkit.chartkit.stats.KernelBandwidth
import io.devkit.chartkit.stats.OutlierPolicy

/**
 * A violin plot: the estimated density of each category's distribution.
 *
 * ```kotlin
 * ViolinPlot(
 *     data = endpoints,
 *     label = { it.name },
 *     values = { it.latencies },
 * )
 * ```
 *
 * ### Widths are comparable across categories
 *
 * Every violin is scaled by the largest density **in the chart**, not by its
 * own. Normalising each to its own peak — which several tools do — makes every
 * category the same width and discards the fact that one distribution is more
 * concentrated than another, which is half of what a violin is for.
 *
 * ### What the estimate assumes
 *
 * A Gaussian kernel with a Silverman bandwidth. The kernel spreads density a
 * little past the extremes of the sample, so a strictly non-negative quantity
 * shows some density below zero; [DensityEstimator] documents the bound on
 * that. The [overlay] box or median line shows where the actual observations
 * were, which is why it is on by default.
 *
 * A category whose samples are all identical has no density to estimate. It is
 * drawn as a line at that value rather than as a flat violin, because a flat
 * violin claims a uniform distribution the data does not have.
 *
 * @param bandwidth the kernel width. [KernelBandwidth.Auto] is the robust
 *   rule of thumb; [KernelBandwidth.Scaled] adjusts it without needing to know
 *   the units.
 * @param overlay what is drawn inside the body — nothing, the median, or an
 *   inset box plot.
 */
@Suppress("LongParameterList")
@Composable
fun <T> ViolinPlot(
    data: List<T>,
    label: (T) -> String,
    values: (T) -> List<Number>,
    modifier: Modifier = Modifier,
    bandwidth: KernelBandwidth = KernelBandwidth.Auto,
    resolution: Int = DensityEstimator.DEFAULT_RESOLUTION,
    overlay: ViolinOverlay = ViolinOverlay.Box,
    outlierPolicy: OutlierPolicy = OutlierPolicy.Default,
    orientation: ChartOrientation = ChartOrientation.Vertical,
    seriesName: String = "",
    color: ((T) -> Int?)? = null,
    categoryAxis: ChartAxis = ChartAxis.Default,
    valueAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = if (orientation.isVertical) ChartGrid.Horizontal else ChartGrid.Vertical,
    valueDomain: DomainPolicy = DomainPolicy.Default,
    valueFormatter: ChartValueFormatter? = null,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.TapOnly,
    crosshair: CrosshairConfig = CrosshairConfig.None,
    viewportState: ChartViewportState = rememberChartViewportState(),
    annotations: List<ChartAnnotation> = emptyList(),
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
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
    val entries = remember(data, label, values, bandwidth, resolution, outlierPolicy, color) {
        data.mapIndexed { index, item ->
            val samples = values(item).map { it.toDouble() }
            ViolinEntry(
                label = label(item),
                curve = DensityEstimator.estimate(samples, bandwidth, resolution),
                // The summary drawn inside the body, and the numbers a tooltip
                // and a screen reader quote. A density curve has no numbers a
                // reader can state; these do.
                statistics = BoxStatistics.from(samples, outlierPolicy),
                item = item,
                paletteIndex = index,
                colorOverride = color?.invoke(item),
            )
        }
    }

    val layers = remember(entries, seriesName, overlay) {
        listOf(
            ResolvedLayer.Violin(
                key = "violin",
                entries = entries,
                seriesId = ChartDefaults.SINGLE_SERIES_ID,
                seriesName = seriesName,
                overlay = overlay,
            ),
        )
    }

    CartesianChartCore(
        layers = layers,
        modifier = modifier,
        orientation = orientation,
        domainAxis = categoryAxis,
        valueAxis = if (valueFormatter != null && valueAxis.valueFormatter == null) {
            valueAxis.copy(valueFormatter = valueFormatter)
        } else {
            valueAxis
        },
        grid = grid,
        valueDomainPolicy = valueAxis.domain ?: valueDomain,
        legend = io.devkit.chartkit.components.legend.LegendPosition.None,
        legendTogglesSeries = false,
        animation = animation,
        interaction = interaction,
        crosshair = crosshair,
        hitTestMode = HitTestMode.Contains,
        sharedTooltip = true,
        state = state.asErased(),
        viewportState = viewportState,
        sharedCrosshair = null,
        annotations = remember(annotations) {
            resolveAnnotations(annotations, ChartXResolver.Default)
        },
        onSelectionChanged = onSelectionChanged?.let { callback ->
            { erased -> callback(erased?.asTyped()) }
        },
        onRangeSelectionChanged = null,
        tooltip = tooltip?.let { slot -> { erased -> slot(erased.asTypedTooltip()) } },
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
    )
}
