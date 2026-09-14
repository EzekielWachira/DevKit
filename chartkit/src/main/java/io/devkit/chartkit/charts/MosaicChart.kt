package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartInsets
import io.devkit.chartkit.geometry.MosaicColumnSpec
import io.devkit.chartkit.geometry.MosaicLayout
import io.devkit.chartkit.layer.comparison.MosaicLabels
import io.devkit.chartkit.layer.comparison.MosaicLayer
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.theme.ChartKitTheme

/**
 * Variable-width stacked columns — the Marimekko, or mosaic plot.
 *
 * ```kotlin
 * MosaicChart(
 *     series = listOf(
 *         ChartSeries("enterprise", "Enterprise", enterpriseByRegion),
 *         ChartSeries("mid", "Mid-market", midByRegion),
 *         ChartSeries("smb", "SMB", smbByRegion),
 *     ),
 *     category = { it.region },
 *     value = { it.revenue },
 *     modifier = Modifier.fillMaxWidth().height(300.dp),
 * )
 * ```
 *
 * ```text
 * ┌────────┬───┬──────────────┬──┐
 * │        │   │              │  │   width  = the column's total
 * ├────────┼───┼──────────────┼──┤   height = a series' share of it
 * │        │   │              │  │
 * └────────┴───┴──────────────┴──┘
 * ```
 *
 * ### Two questions at once
 *
 * A hundred-percent stacked bar chart answers "what is each column made of" and
 * throws away how big the columns are. A plain stacked bar chart answers "how
 * big" and makes composition hard to compare, because every column is a
 * different height and the eye cannot compare segments that do not start level.
 * A mosaic gives width to the first question and height to the second — market
 * share by region where the regions are not the same size, spend by department
 * where the departments are not.
 *
 * ### It is not a treemap
 *
 * [Treemap] also encodes quantity as area, but it is free to put a rectangle
 * anywhere, so any two rectangles are hard to compare unless they happen to
 * share an edge. A mosaic keeps one categorical dimension on each axis, so every
 * cell in a row is comparable by height and every column by width. The cost is
 * that it takes exactly two dimensions where a treemap nests arbitrarily deep.
 *
 * ### Only positive values
 *
 * A column's width is a sum of parts and a cell's height is its share of that
 * sum; a negative part has no share of a total it reduces, and a negative total
 * has no width. Non-positive and non-finite values are left out — they are
 * counted nowhere and drawn nowhere, rather than being folded into a column
 * whose width would then mean nothing.
 *
 * ### Area is the value
 *
 * Width is proportional to a column's total and a cell's height is its share of
 * that total, so the two cancel and a cell covers an area proportional to its
 * own value wherever it sits. Two cells of equal value cover equal area across
 * the whole chart, which is what lets the eye compare them.
 *
 * There is deliberately no option to scale a column's *height* by its total as
 * well. It reads as a reasonable setting and destroys exactly that property:
 * the total would be encoded twice, and a cell's area would become proportional
 * to `total × value`, which is not a quantity anybody has.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun <T> MosaicChart(
    series: List<ChartSeries<T>>,
    category: (T) -> Any?,
    value: (T) -> Number?,
    modifier: Modifier = Modifier,
    labels: MosaicLabels = MosaicLabels.Columns,
    seriesColor: ((Int) -> Int?)? = null,
    columnSpacing: Dp? = null,
    cellSpacing: Dp? = null,
    legend: LegendPosition = LegendPosition.Bottom,
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
    seriesName: String = "Mosaic",
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    val density = LocalDensity.current
    val theme = ChartKitTheme.current

    // Columns in first-seen order across every series, so a series that only
    // covers some of them does not reorder the chart — and a category missing
    // from one series is a hole in that column rather than a shifted axis.
    val columns = remember(series, category) {
        LinkedHashSet<String>().apply {
            series.forEach { one -> one.data.forEach { add(category(it)?.toString().orEmpty()) } }
        }.toList()
    }

    val values = remember(series, columns, category, value) {
        series.map { one ->
            val byCategory = HashMap<String, Double>(columns.size)
            one.data.forEach { item ->
                val number = value(item)?.toDouble()
                if (number != null && number.isFinite() && number > 0.0) {
                    // Last value wins for a repeated category, matching what a
                    // reader sees in the list: the later row is the more recent
                    // statement. The same rule `alignToCategories` follows.
                    byCategory[category(item)?.toString().orEmpty()] = number
                }
            }
            columns.map { byCategory[it] }
        }
    }

    val sources = remember(series, columns, category) {
        series.map { one ->
            val byCategory = HashMap<String, Any?>(columns.size)
            one.data.forEach { item -> byCategory[category(item)?.toString().orEmpty()] = item }
            columns.map { byCategory[it] }
        }
    }

    val columnGap = with(density) { (columnSpacing ?: theme.dimensions.mosaicColumnSpacing).toPx() }
    val cellGap = with(density) { (cellSpacing ?: theme.dimensions.mosaicCellSpacing).toPx() }

    // Room under the columns for their names, measured from the font rather
    // than guessed at.
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val insets = remember(labels, theme, density) {
        with(density) {
            val padding = theme.dimensions.contentPadding.toPx()
            val gutter = if (labels == MosaicLabels.None) {
                0f
            } else {
                textMeasurer.measure("Ag", theme.typography.cellLabel, maxLines = 1)
                    .size.height.toFloat() + theme.dimensions.labelPadding.toPx() * 2f
            }
            ChartInsets(padding, padding, padding, padding + gutter)
        }
    }

    PlanarChartCore(
        layers = { coordinates ->
            val geometry = MosaicLayout.layout(
                columns = columns.map { MosaicColumnSpec(it) },
                values = values,
                bounds = coordinates.contentBounds,
                columnGap = columnGap,
                cellGap = cellGap,
            )
            listOf(
                MosaicLayer(
                    id = "mosaic",
                    geometry = geometry,
                    seriesNames = series.map { it.name },
                    items = { seriesIndex, columnIndex ->
                        sources.getOrNull(seriesIndex)?.getOrNull(columnIndex)
                    },
                    seriesId = seriesId,
                    seriesName = seriesName,
                    valueFormatter = valueFormatter,
                    labels = labels,
                    seriesColorOf = seriesColor,
                ),
            )
        },
        modifier = modifier,
        legend = legend,
        legendItems = remember(series, seriesColor) {
            series.mapIndexed { index, one ->
                ChartKeyItem(
                    id = one.id,
                    label = one.name,
                    paletteIndex = index,
                    colorOverride = seriesColor?.invoke(index),
                )
            }
        },
        animation = animation,
        tapSelects = tapSelects,
        clearOnTapOutside = clearOnTapOutside,
        state = state,
        valueFormatter = valueFormatter,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        renderMode = renderMode,
        staticOptions = staticOptions,
        isEmpty = columns.isEmpty() || values.all { row -> row.all { it == null } },
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
        onSelectionChanged = onSelectionChanged,
        tooltip = tooltip,
        contentInsets = insets,
    )
}
