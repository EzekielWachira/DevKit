package io.devkit.chartkit.accessibility

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.stats.BoxStatistics
import io.devkit.chartkit.theme.ChartKitTheme

/**
 * A chart's data as rows and columns.
 *
 * ### Why a chart needs one
 *
 * A `Canvas` is, to a screen reader, one rectangle. ChartKit's generated
 * summary makes that rectangle *describable* — how many series, what range,
 * what is selected — and for most charts that is what a reader wants. What it
 * cannot do is let somebody read the fortieth value, or compare the third
 * series' March against the first's, because a single description is read
 * start to finish and cannot be navigated.
 *
 * A table can. So the table is the escape hatch, and it is a **separate,
 * optional** composable rather than something every chart renders invisibly:
 * a hidden table attached to every chart would double the semantics tree of
 * every screen for a facility most of them do not need.
 *
 * ```kotlin
 * var showTable by remember { mutableStateOf(false) }
 *
 * LineChart(data = revenue, x = { it.month }, y = { it.amount })
 * TextButton(onClick = { showTable = !showTable }) { Text("View data table") }
 * if (showTable) {
 *     ChartDataTableView(
 *         table = chartDataTable(revenue, category = { it.month }, value = { it.amount }),
 *     )
 * }
 * ```
 *
 * @param columns the header row.
 * @param rows the body, each the same length as [columns].
 * @param caption what the table is of, announced before it.
 */
@Immutable
data class ChartDataTable(
    val columns: List<String>,
    val rows: List<List<String>>,
    val caption: String? = null,
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    /**
     * The whole table as one string.
     *
     * For a `contentDescription`, a share sheet, or a log line. Long by
     * construction — which is why [ChartDataTableView] exists, and why this is
     * not what a chart announces by default.
     */
    fun asText(): String = buildString {
        caption?.let {
            append(it)
            append(". ")
        }
        rows.forEach { row ->
            columns.forEachIndexed { index, column ->
                append(column)
                append(": ")
                append(row.getOrNull(index).orEmpty())
                if (index < columns.size - 1) append(", ")
            }
            append(". ")
        }
    }
}

/**
 * A table of one or more series: category, series, value.
 *
 * Typed accessors, no reflection — the same lambdas the chart itself was given,
 * so the table cannot disagree with what was drawn.
 */
fun <T> chartDataTable(
    series: List<ChartSeries<T>>,
    category: (T) -> Any?,
    value: (T) -> Number?,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    caption: String? = null,
): ChartDataTable {
    val multi = series.size > 1
    return ChartDataTable(
        columns = if (multi) listOf("Category", "Series", "Value") else listOf("Category", "Value"),
        rows = series.flatMap { s ->
            s.data.map { item ->
                val text = value(item)?.toDouble()?.let(valueFormatter::format) ?: "no value"
                if (multi) {
                    listOf(category(item).toString(), s.name.ifBlank { s.id }, text)
                } else {
                    listOf(category(item).toString(), text)
                }
            }
        },
        caption = caption,
    )
}

/** A table of a single list: category and value. */
@JvmName("chartDataTableSingle")
fun <T> chartDataTable(
    data: List<T>,
    category: (T) -> Any?,
    value: (T) -> Number?,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    caption: String? = null,
): ChartDataTable = chartDataTable(
    series = listOf(ChartSeries(id = "series", name = "", data = data)),
    category = category,
    value = value,
    valueFormatter = valueFormatter,
    caption = caption,
)

/**
 * A table of price periods: date, open, high, low, close, and optionally volume.
 *
 * A specialised adapter rather than a generic one, because a candle is four
 * numbers and a generic "category, value" table would have to choose one of
 * them. Typed, like every other accessor in ChartKit.
 */
@Suppress("LongParameterList")
fun <T> ohlcDataTable(
    data: List<T>,
    date: (T) -> Any?,
    open: (T) -> Number?,
    high: (T) -> Number?,
    low: (T) -> Number?,
    close: (T) -> Number?,
    volume: ((T) -> Number?)? = null,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    caption: String? = null,
): ChartDataTable {
    fun format(value: Number?): String =
        value?.toDouble()?.takeIf { it.isFinite() }?.let(valueFormatter::format) ?: "no value"

    return ChartDataTable(
        columns = buildList {
            add("Date")
            add("Open")
            add("High")
            add("Low")
            add("Close")
            if (volume != null) add("Volume")
        },
        rows = data.map { item ->
            buildList {
                add(date(item).toString())
                add(format(open(item)))
                add(format(high(item)))
                add(format(low(item)))
                add(format(close(item)))
                if (volume != null) add(format(volume(item)))
            }
        },
        caption = caption,
    )
}

/** A table of box-plot statistics: the five numbers and the outlier count. */
fun <T> boxPlotDataTable(
    data: List<T>,
    label: (T) -> String,
    statistics: (T) -> BoxStatistics?,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    caption: String? = null,
): ChartDataTable = ChartDataTable(
    columns = listOf("Category", "Minimum", "Q1", "Median", "Q3", "Maximum", "Outliers"),
    rows = data.mapNotNull { item ->
        val summary = statistics(item) ?: return@mapNotNull null
        listOf(
            label(item),
            valueFormatter.format(summary.minimum),
            valueFormatter.format(summary.q1),
            valueFormatter.format(summary.median),
            valueFormatter.format(summary.q3),
            valueFormatter.format(summary.maximum),
            summary.outliers.size.toString(),
        )
    },
    caption = caption,
)

/**
 * Renders a [ChartDataTable] as text a screen reader can navigate.
 *
 * ### One node per row, not per cell
 *
 * Each row is a single semantics node reading "Category: January, Value:
 * 24,000". A node per cell would be technically richer and practically worse:
 * a reader would have to swipe through three nodes to learn one fact, and would
 * lose the column name by the time they reached the number, because the header
 * row is somewhere else entirely. Repeating the column name inside each row is
 * what makes the value readable out of context.
 *
 * Wide tables scroll horizontally rather than compressing their columns, which
 * would truncate exactly the long labels that most needed reading.
 */
@Composable
fun ChartDataTableView(
    table: ChartDataTable,
    modifier: Modifier = Modifier,
    columnWidth: androidx.compose.ui.unit.Dp = 96.dp,
) {
    if (table.isEmpty) return
    val typography = ChartKitTheme.typography
    val colors = ChartKitTheme.colors

    Column(modifier.horizontalScroll(rememberScrollState())) {
        table.caption?.let { caption ->
            Text(
                text = caption,
                style = typography.axisTitle,
                color = colors.axisTitle,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            // The header is read as part of every row, so announcing it again
            // on its own would make a screen reader read the column names twice
            // before reaching any data.
            modifier = Modifier.clearAndSetSemantics { },
        ) {
            table.columns.forEach { column ->
                Text(
                    text = column,
                    style = typography.legendLabel.copy(fontWeight = FontWeight.Medium),
                    color = colors.axisTitle,
                    modifier = Modifier.width(columnWidth),
                )
            }
        }
        table.rows.forEach { row ->
            val description = table.columns.mapIndexed { index, column ->
                "$column: ${row.getOrNull(index).orEmpty()}"
            }.joinToString(", ")

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.semantics { contentDescription = description },
            ) {
                row.forEach { cell ->
                    Text(
                        text = cell,
                        style = typography.axisLabel,
                        color = colors.axisLabel,
                        modifier = Modifier.width(columnWidth),
                    )
                }
            }
        }
    }
}
