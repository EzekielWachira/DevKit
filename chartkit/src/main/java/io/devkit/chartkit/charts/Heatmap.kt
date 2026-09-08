package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.axis.AxisLabelOverflow
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartGrid
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.heatmap.HeatmapCell
import io.devkit.chartkit.layer.heatmap.HeatmapCellLabels
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.scale.ColorScale
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.state.rememberChartViewportState
import io.devkit.chartkit.theme.ChartColorScales
import kotlin.math.roundToInt

/**
 * A grid of coloured cells over two categorical axes.
 *
 * ```kotlin
 * data class Activity(val day: String, val hour: String, val requests: Int)
 *
 * Heatmap(
 *     data = activity,
 *     x = { it.day },
 *     y = { it.hour },
 *     value = { it.requests },
 * )
 * ```
 *
 * Columns and rows are taken from the data in **input order**, first
 * occurrence first, and the first `y` listed appears at the top — the reading
 * order a table has. Sorting them would be a decision ChartKit has no basis for:
 * "Mon, Tue, Wed" is not alphabetical and is obviously right.
 *
 * ### Missing is not zero
 *
 * A cell the data does not contain is painted in the theme's "no measurement"
 * colour, distinct from the low end of the ramp. On an activity grid, "closed
 * on Sunday" and "open with no visitors" are different facts, and a heatmap
 * that paints them the same asserts one of them. Set [showMissing] to `false`
 * to leave such cells unpainted entirely.
 *
 * ### Accessibility
 *
 * The summary gives the grid's shape and its extremes rather than one node per
 * cell — a 200 × 24 matrix has 4,800 of them, and a screen reader given all of
 * them is given a way to spend an afternoon. A selected cell is announced in
 * full, and [io.devkit.chartkit.accessibility.ChartDataTable] renders the whole
 * matrix as a navigable table when one is genuinely wanted.
 *
 * @param colorScale how a value becomes a colour. `null` derives a continuous
 *   ramp from the theme across the data's own range. See [ColorScale] for
 *   threshold and quantized alternatives.
 * @param cellLabels whether values are written into the cells, where they fit.
 */
@Suppress("LongParameterList")
@Composable
fun <T> Heatmap(
    data: List<T>,
    x: (T) -> Any?,
    y: (T) -> Any?,
    value: (T) -> Number?,
    modifier: Modifier = Modifier,
    colorScale: ColorScale? = null,
    showMissing: Boolean = true,
    cellLabels: HeatmapCellLabels = HeatmapCellLabels.None,
    cellCornerRadius: Dp? = null,
    seriesName: String = "",
    xAxis: ChartAxis = ChartAxis.Default,
    yAxis: ChartAxis = ChartAxis.Default,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.TapOnly,
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
    val grid = remember(data, x, y, value) { buildHeatmapGrid(data, x, y, value) }
    val defaultScale = ChartColorScales.continuous(
        remember(grid) {
            NumericDomain.of(grid.cells.mapNotNull { it.value }) ?: NumericDomain.Default
        },
    )
    HeatmapCore(
        grid = grid,
        items = data,
        modifier = modifier,
        colorScale = colorScale ?: defaultScale,
        showMissing = showMissing,
        cellLabels = cellLabels,
        cellCornerRadius = cellCornerRadius,
        seriesName = seriesName,
        columnAxis = xAxis,
        rowAxis = yAxis,
        valueFormatter = valueFormatter,
        animation = animation,
        interaction = interaction,
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
    )
}

/** Columns, rows and cells, with every combination accounted for. */
internal class HeatmapGrid(
    val columnLabels: List<String>,
    val rowLabels: List<String>,
    val cells: List<HeatmapCell>,
)

/**
 * Builds the grid, filling in every combination the data did not contain.
 *
 * The absent cells are materialised rather than left out, so the layer can
 * paint them as "no measurement" and the accessibility summary can count them.
 * A grid with holes in it that were never represented could not tell the
 * difference between a cell nobody measured and a cell nobody drew.
 */
internal fun <T> buildHeatmapGrid(
    data: List<T>,
    x: (T) -> Any?,
    y: (T) -> Any?,
    value: (T) -> Number?,
): HeatmapGrid {
    val columns = LinkedHashSet<String>()
    val rows = LinkedHashSet<String>()
    data.forEach {
        columns += x(it).toString()
        rows += y(it).toString()
    }
    val columnLabels = columns.toList()
    // Reversed, because the value axis grows upward and a reader expects the
    // first row they listed at the top, as in a table.
    val rowLabels = rows.toList().reversed()

    val present = HashMap<Long, HeatmapCell>(data.size)
    data.forEachIndexed { index, item ->
        val column = columnLabels.indexOf(x(item).toString())
        val row = rowLabels.indexOf(y(item).toString())
        if (column < 0 || row < 0) return@forEachIndexed
        val measurement = value(item)?.toDouble()?.takeIf { it.isFinite() }
        // Last write wins for a repeated coordinate, matching what a reader
        // sees in the list: the later row is the more recent statement.
        present[key(column, row)] = HeatmapCell(column, row, measurement, index)
    }

    val cells = ArrayList<HeatmapCell>(columnLabels.size * rowLabels.size)
    for (row in rowLabels.indices) {
        for (column in columnLabels.indices) {
            cells += present[key(column, row)] ?: HeatmapCell(column, row, null, -1)
        }
    }
    return HeatmapGrid(columnLabels, rowLabels, cells)
}

private fun key(column: Int, row: Int): Long = column.toLong() shl 32 or (row.toLong() and 0xFFFFFFFFL)

/**
 * The shared body of [Heatmap] and [CalendarHeatmap].
 *
 * The rows sit at integer positions on the **value** axis, which is what lets a
 * two-categorical-axis chart run on the Cartesian coordinate system every other
 * chart uses — see [io.devkit.chartkit.layer.heatmap.HeatmapLayer] for why that
 * beats generalising the coordinate system itself.
 */
@Suppress("LongParameterList")
@Composable
internal fun <T> HeatmapCore(
    grid: HeatmapGrid,
    items: List<T>,
    modifier: Modifier,
    colorScale: ColorScale,
    showMissing: Boolean,
    cellLabels: HeatmapCellLabels,
    cellCornerRadius: Dp?,
    seriesName: String,
    columnAxis: ChartAxis,
    rowAxis: ChartAxis,
    valueFormatter: ChartValueFormatter,
    animation: ChartAnimation,
    interaction: ChartInteraction,
    accessibility: ChartAccessibility,
    accessibilitySummary: (() -> String)?,
    state: ChartState<T>,
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)?,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)?,
    isLoading: Boolean,
    error: Throwable?,
    loadingContent: @Composable () -> Unit,
    emptyContent: @Composable () -> Unit,
    errorContent: @Composable (Throwable) -> Unit,
) {
    val rowCount = grid.rowLabels.size
    val layers = remember(grid, colorScale, cellLabels, showMissing, cellCornerRadius, seriesName, items) {
        listOf(
            ResolvedLayer.Heatmap(
                key = "heatmap",
                cells = grid.cells,
                columnLabels = grid.columnLabels,
                rowLabels = grid.rowLabels,
                colorScale = colorScale,
                items = items,
                seriesId = ChartDefaults.SINGLE_SERIES_ID,
                seriesName = seriesName,
                cellLabels = cellLabels,
                showMissing = showMissing,
                cornerRadius = cellCornerRadius,
            ),
        )
    }

    // The row axis is a numeric axis labelled by name: one tick per row, at the
    // integer the row sits on, and the label looked up rather than formatted.
    val resolvedRowAxis = remember(rowAxis, grid.rowLabels) {
        rowAxis.copy(
            ticks = List(rowCount) { it.toDouble() },
            valueFormatter = rowAxis.valueFormatter ?: ChartValueFormatter { value ->
                grid.rowLabels.getOrNull(value.roundToInt()).orEmpty()
            },
            domain = rowAxis.domain
                ?: DomainPolicy.Fixed(-0.5, (rowCount - 1).coerceAtLeast(0) + 0.5),
            labelOverflow = AxisLabelOverflow.Skip,
        )
    }

    CartesianChartCore(
        layers = layers,
        modifier = modifier,
        orientation = ChartOrientation.Vertical,
        domainAxis = columnAxis,
        valueAxis = resolvedRowAxis,
        // The cells tile the plot; a grid line behind them is never visible and
        // one drawn over them would look like a border the data does not have.
        grid = ChartGrid.None,
        valueDomainPolicy = resolvedRowAxis.domain
            ?: DomainPolicy.Fixed(-0.5, (rowCount - 1).coerceAtLeast(0) + 0.5),
        legend = io.devkit.chartkit.components.legend.LegendPosition.None,
        legendTogglesSeries = false,
        animation = animation,
        interaction = interaction,
        crosshair = CrosshairConfig.None,
        hitTestMode = HitTestMode.Contains,
        sharedTooltip = true,
        state = state.asErased(),
        viewportState = rememberChartViewportState(),
        sharedCrosshair = null,
        annotations = emptyList(),
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
