package io.devkit.chartdemo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartGrid
import io.devkit.chartkit.charts.AreaChart
import io.devkit.chartkit.charts.BarChart
import io.devkit.chartkit.charts.CartesianChart
import io.devkit.chartkit.charts.ExperimentalChartKitApi
import io.devkit.chartkit.charts.HorizontalBarChart
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.geometry.BarGrouping
import io.devkit.chartkit.geometry.LineInterpolation
import io.devkit.chartkit.layer.line.PointMode
import io.devkit.chartkit.model.ChartSeries

/**
 * Every 0.1 chart type, with the switches that matter turned into controls.
 *
 * A gallery rather than one long scroll: each chart type gets the full width
 * and the same set of toggles, so the effect of turning the grid off or
 * switching to stacked bars is visible immediately and comparably. The controls
 * exist to make the API testable by hand — they are the fastest way to find out
 * whether a parameter does what its name says.
 */
@Composable
fun ChartGalleryScreen(modifier: Modifier = Modifier) {
    var kind by rememberSaveable { mutableStateOf(ChartKind.Line) }
    var showGrid by rememberSaveable { mutableStateOf(true) }
    var showPoints by rememberSaveable { mutableStateOf(false) }
    var showLegend by rememberSaveable { mutableStateOf(true) }
    var animate by rememberSaveable { mutableStateOf(true) }
    var valueLabels by rememberSaveable { mutableStateOf(false) }
    var smooth by rememberSaveable { mutableStateOf(false) }

    val animation = if (animate) ChartAnimation.Default else ChartAnimation.None
    val grid = if (showGrid) ChartKind.gridFor(kind) else ChartGrid.None
    val legend = if (showLegend) LegendPosition.Bottom else LegendPosition.None
    val pointMode = if (showPoints) PointMode.Always else PointMode.Auto
    val interpolation = if (smooth) LineInterpolation.Smooth else LineInterpolation.Linear
    val money = remember { ChartNumberFormatters.compact() }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Chart gallery", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Every ChartKit 0.1 chart, drawn from the sample's own data classes. " +
                "No conversion into a chart entry type anywhere below.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .testTag(ChartDemoTestTags.KindChips),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChartKind.entries.forEach { entry ->
                FilterChip(
                    selected = kind == entry,
                    onClick = { kind = entry },
                    label = { Text(entry.label) },
                    modifier = Modifier.testTag(ChartDemoTestTags.kindChip(entry.name)),
                )
            }
        }

        Text(kind.description, style = MaterialTheme.typography.bodySmall)

        Chart(
            kind = kind,
            grid = grid,
            legend = legend,
            pointMode = pointMode,
            interpolation = interpolation,
            animation = animation,
            valueLabels = valueLabels,
            formatter = money,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .testTag(ChartDemoTestTags.Chart),
        )

        HorizontalDivider()
        Text("Controls", style = MaterialTheme.typography.titleSmall)

        DemoToggle("Grid", showGrid) { showGrid = it }
        DemoToggle("Point markers", showPoints, enabled = kind.isLineLike) { showPoints = it }
        DemoToggle("Smooth interpolation", smooth, enabled = kind.isLineLike) { smooth = it }
        DemoToggle("Legend", showLegend) { showLegend = it }
        DemoToggle("Value labels", valueLabels, enabled = kind.supportsValueLabels) { valueLabels = it }
        DemoToggle("Animation", animate) { animate = it }
    }
}

@Suppress("LongParameterList")
@OptIn(ExperimentalChartKitApi::class)
@Composable
private fun Chart(
    kind: ChartKind,
    grid: ChartGrid,
    legend: LegendPosition,
    pointMode: PointMode,
    interpolation: LineInterpolation,
    animation: ChartAnimation,
    valueLabels: Boolean,
    formatter: io.devkit.chartkit.formatter.ChartValueFormatter,
    modifier: Modifier,
) {
    val revenueSeries = remember { ChartDemoData.revenueSeries().take(2) }
    val cohorts = remember { ChartDemoData.cohortSeries() }

    when (kind) {
        ChartKind.Line -> LineChart(
            data = ChartDemoData.revenue,
            x = { it.month },
            y = { it.amount },
            interpolation = interpolation,
            pointMode = pointMode,
            grid = grid,
            valueLabels = valueLabels,
            valueFormatter = formatter,
            animation = animation,
            xAxis = ChartAxis(title = "Month"),
            yAxis = ChartAxis(title = "Revenue"),
            modifier = modifier,
        )

        ChartKind.MultiLine -> LineChart(
            series = revenueSeries,
            x = { it.month },
            y = { it.amount },
            interpolation = interpolation,
            pointMode = pointMode,
            grid = grid,
            legend = legend,
            legendTogglesSeries = true,
            valueFormatter = formatter,
            animation = animation,
            modifier = modifier,
        )

        ChartKind.Area -> AreaChart(
            data = ChartDemoData.revenue,
            x = { it.month },
            y = { it.amount },
            interpolation = interpolation,
            pointMode = pointMode,
            grid = grid,
            valueFormatter = formatter,
            animation = animation,
            modifier = modifier,
        )

        ChartKind.MultiArea -> AreaChart(
            series = revenueSeries,
            x = { it.month },
            y = { it.amount },
            interpolation = interpolation,
            pointMode = pointMode,
            grid = grid,
            legend = legend,
            legendTogglesSeries = true,
            valueFormatter = formatter,
            animation = animation,
            modifier = modifier,
        )

        ChartKind.Bar -> BarChart(
            data = ChartDemoData.revenue,
            category = { it.month },
            value = { it.amount },
            grid = grid,
            valueLabels = valueLabels,
            valueFormatter = formatter,
            animation = animation,
            modifier = modifier,
        )

        ChartKind.NegativeBar -> BarChart(
            data = ChartDemoData.netMargin,
            category = { it.month },
            value = { it.amount },
            grid = grid,
            valueLabels = valueLabels,
            valueFormatter = formatter,
            animation = animation,
            modifier = modifier,
        )

        ChartKind.HorizontalBar -> HorizontalBarChart(
            data = ChartDemoData.productLines,
            category = { it.month },
            value = { it.amount },
            grid = grid,
            valueLabels = valueLabels,
            valueFormatter = formatter,
            animation = animation,
            modifier = modifier,
        )

        ChartKind.GroupedBar -> CohortBars(cohorts, BarGrouping.Grouped, grid, legend, valueLabels, animation, modifier)
        ChartKind.StackedBar -> CohortBars(cohorts, BarGrouping.Stacked, grid, legend, valueLabels, animation, modifier)
        ChartKind.PercentBar -> CohortBars(
            cohorts,
            BarGrouping.StackedPercent,
            grid,
            legend,
            valueLabels,
            animation,
            modifier,
        )

        ChartKind.Combined -> CartesianChart(
            modifier = modifier,
            grid = grid,
            legend = legend,
            valueAxis = ChartAxis(valueFormatter = formatter),
            animation = animation,
        ) {
            // Bars and a line, sharing one plot area, one pair of scales and
            // one selection. That is the whole reason the engine exists.
            bars(
                series = listOf(ChartSeries("actual", "Actual", ChartDemoData.revenue)),
                category = { it.month },
                value = { it.amount },
            )
            line(
                series = listOf(ChartSeries("target", "Target", ChartDemoData.forecast)),
                x = { it.month },
                y = { it.amount },
                pointMode = PointMode.Always,
            )
        }

        ChartKind.TimeSeries -> LineChart(
            data = ChartDemoData.readings,
            x = { it.atMillis },
            y = { it.celsius },
            xResolver = io.devkit.chartkit.model.ChartXResolver.Time,
            interpolation = interpolation,
            pointMode = pointMode,
            grid = grid,
            animation = animation,
            xAxis = ChartAxis(tickCount = 4),
            yAxis = ChartAxis(title = "°C"),
            modifier = modifier,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun CohortBars(
    series: List<ChartSeries<ChartDemoData.Cohort>>,
    grouping: BarGrouping,
    grid: ChartGrid,
    legend: LegendPosition,
    valueLabels: Boolean,
    animation: ChartAnimation,
    modifier: Modifier,
) {
    BarChart(
        series = series,
        category = { it.quarter },
        value = { it.customers },
        grouping = grouping,
        grid = grid,
        legend = legend,
        legendTogglesSeries = true,
        valueLabels = valueLabels,
        animation = animation,
        modifier = modifier,
    )
}

@Composable
internal fun DemoToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Switch(
            checked = checked && enabled,
            onCheckedChange = onChange,
            enabled = enabled,
            modifier = Modifier.testTag(ChartDemoTestTags.toggle(label)),
        )
    }
}

/** The chart types the gallery can show. */
internal enum class ChartKind(
    val label: String,
    val description: String,
    val isLineLike: Boolean = false,
    val supportsValueLabels: Boolean = true,
) {
    Line("Line", "One series, linear or smooth interpolation.", isLineLike = true),
    MultiLine(
        "Multi-line",
        "Three series sharing one pair of scales. Tap a legend entry to hide one.",
        isLineLike = true,
        supportsValueLabels = false,
    ),
    Area("Area", "A line closed to the zero baseline, not to the bottom edge.", isLineLike = true),
    MultiArea(
        "Multi-area",
        "Overlapping areas — each measured from the baseline, not stacked.",
        isLineLike = true,
        supportsValueLabels = false,
    ),
    Bar("Bar", "Vertical bars from a zero baseline."),
    NegativeBar("Negative bars", "Mixed signs, drawn either side of the baseline."),
    HorizontalBar("Horizontal", "The same geometry with the axes exchanged — long labels fit."),
    GroupedBar("Grouped", "Three series side by side within each category."),
    StackedBar("Stacked", "Additive segments; each sign accumulates separately."),
    PercentBar("100% stacked", "Each category normalised to its own total."),
    Combined("Combined", "Bars and a line on one engine, sharing scales and selection."),
    TimeSeries(
        "Time series",
        "Epoch millis on the x axis, with a gap where a reading is missing.",
        isLineLike = true,
        supportsValueLabels = false,
    ),
    ;

    companion object {
        /**
         * The value axis' grid, which is horizontal except on a horizontal
         * chart — where the value axis runs across the screen.
         */
        fun gridFor(kind: ChartKind): ChartGrid =
            if (kind == HorizontalBar) ChartGrid.Vertical else ChartGrid.Horizontal
    }
}

/** Test tags the sample's own instrumentation tests reach for. */
object ChartDemoTestTags {
    const val Chart = "chartdemo:chart"
    const val KindChips = "chartdemo:kinds"
    const val Selection = "chartdemo:selection"
    fun kindChip(name: String) = "chartdemo:kind:$name"
    fun toggle(label: String) = "chartdemo:toggle:$label"
}
