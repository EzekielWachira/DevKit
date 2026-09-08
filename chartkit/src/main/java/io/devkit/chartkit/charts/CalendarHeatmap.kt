package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.intl.Locale as ComposeLocale
import androidx.compose.ui.unit.Dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.axis.AxisLabelOverflow
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.formatter.ChartDateFormatters
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.CalendarGeometry
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.layer.heatmap.HeatmapCell
import io.devkit.chartkit.layer.heatmap.HeatmapCellLabels
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.scale.ColorScale
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.theme.ChartColorScales
import java.util.Locale
import java.util.TimeZone

/**
 * A contribution-style activity grid: weeks across, weekdays down.
 *
 * ```kotlin
 * data class Commit(val date: Long, val count: Int)
 *
 * CalendarHeatmap(
 *     data = commits,
 *     date = { it.date },
 *     value = { it.count },
 * )
 * ```
 *
 * ### The week starts where the locale says
 *
 * Not on Sunday, and not on Monday either. `java.util.Calendar` already knows
 * the first day of the week for every locale Android ships, and hardcoding
 * either convention puts a British reader's Sundays at the top of the grid or
 * an American reader's Mondays — and in both cases every weekday label is wrong
 * by a row.
 *
 * ### Dates are days, in a stated time zone
 *
 * A day is a time-zone-dependent bucket of instants: an event at 23:30 UTC
 * belongs to a different day in Nairobi than in New York. [timeZone] says which
 * one, and defaults to the device's. A chart of server-side data usually wants
 * to name one explicitly, so the grid does not reshuffle when the reader
 * travels.
 *
 * ### A day with no data is not a day with zero
 *
 * Days inside the range that the data does not mention are painted in the
 * theme's "no measurement" colour, distinct from the low end of the ramp — a
 * day before the account existed and a day with no activity are different
 * facts. Pass `showMissing = false` to leave them unpainted.
 *
 * @param date reads the day, as epoch milliseconds. `Long`, `java.util.Date`
 *   and anything else convertible are handled by [dateToMillis]; the default
 *   accepts a `Number` of millis or a `java.util.Date`.
 * @param from the first day shown. `null` takes the earliest in the data.
 * @param to the last. `null` takes the latest.
 */
@Suppress("LongParameterList")
@Composable
fun <T> CalendarHeatmap(
    data: List<T>,
    date: (T) -> Any?,
    value: (T) -> Number?,
    modifier: Modifier = Modifier,
    from: Long? = null,
    to: Long? = null,
    timeZone: TimeZone = TimeZone.getDefault(),
    colorScale: ColorScale? = null,
    showMissing: Boolean = true,
    cellCornerRadius: Dp? = null,
    seriesName: String = "",
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    dateFormatPattern: String = "d MMM yyyy",
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
    val composeLocale = ComposeLocale.current
    val locale = remember(composeLocale) { Locale.forLanguageTag(composeLocale.toLanguageTag()) }

    val grid = remember(data, date, value, from, to, locale, timeZone) {
        buildCalendarGrid(data, date, value, from, to, locale, timeZone)
    }

    val defaultScale = ChartColorScales.continuous(
        remember(grid) {
            NumericDomain.of(grid.heatmap.cells.mapNotNull { it.value }) ?: NumericDomain.Default
        },
    )

    val dateFormatter = remember(dateFormatPattern, locale, timeZone) {
        ChartDateFormatters.pattern(dateFormatPattern, locale, timeZone)
    }

    HeatmapCore(
        grid = grid.heatmap,
        items = data,
        modifier = modifier,
        colorScale = colorScale ?: defaultScale,
        showMissing = showMissing,
        cellLabels = HeatmapCellLabels.None,
        cellCornerRadius = cellCornerRadius,
        seriesName = seriesName.ifBlank { dateFormatter.format(grid.firstMillis) },
        // Every week column is labelled, but only the weeks a month begins in
        // have a label to draw. Uniform thinning would land the month names on
        // whichever columns the stride happened to pick, which is worse than
        // no labels at all.
        columnAxis = ChartAxis(
            showTicks = false,
            labelOverflow = AxisLabelOverflow.None,
            categoryFormatter = { week -> grid.monthLabelAt(week) },
        ),
        rowAxis = ChartAxis(showLine = false, showTicks = false),
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

/** A calendar grid, plus the month labels its columns are annotated with. */
internal class CalendarHeatmapGrid(
    val heatmap: HeatmapGrid,
    val firstMillis: Long,
    private val monthLabels: Map<String, String>,
) {
    /** The month name starting at week column [week], or an empty string. */
    fun monthLabelAt(week: String): String = monthLabels[week].orEmpty()
}

/**
 * Reads epoch milliseconds out of whatever the caller's `date` lambda returned.
 *
 * Type inspection, never reflection — the same rule the rest of ChartKit
 * follows. `java.time` is absent for the same reason it is absent from
 * [io.devkit.chartkit.model.ChartXResolver]: `LocalDate` is API 26 and
 * ChartKit's floor is 24, so a consumer on those types passes
 * `date.toEpochDay() * 86_400_000L` and keeps the time-zone decision where it
 * belongs.
 */
internal fun dateToMillis(value: Any?): Long? = when (value) {
    null -> null
    is Number -> value.toLong()
    is java.util.Date -> value.time
    is java.util.Calendar -> value.timeInMillis
    else -> null
}

/** Lays the caller's dated observations out on a week-by-weekday grid. */
@Suppress("LongParameterList")
internal fun <T> buildCalendarGrid(
    data: List<T>,
    date: (T) -> Any?,
    value: (T) -> Number?,
    from: Long?,
    to: Long?,
    locale: Locale,
    timeZone: TimeZone,
): CalendarHeatmapGrid {
    val days = data.mapIndexed { index, item ->
        index to dateToMillis(date(item))?.let { CalendarGeometry.epochDayOf(it, timeZone) }
    }
    val present = days.mapNotNull { it.second }

    val firstDay = from?.let { CalendarGeometry.epochDayOf(it, timeZone) }
        ?: present.minOrNull()
        ?: 0L
    val lastDay = to?.let { CalendarGeometry.epochDayOf(it, timeZone) }
        ?: present.maxOrNull()
        ?: firstDay

    val calendar = CalendarGeometry.grid(firstDay, lastDay, locale, timeZone)

    val columnLabels = List(calendar.weekCount) { it.toString() }
    // Row 0 is the bottom of the value axis, so the weekday order is reversed
    // to put the locale's first day at the top of the grid.
    val weekdayNames = calendar.weekdayLabels(locale)
    val rowLabels = weekdayNames.reversed()

    val cellsByKey = HashMap<Int, HeatmapCell>(data.size)
    days.forEach { (index, epochDay) ->
        if (epochDay == null) return@forEach
        val cell = calendar.cellOf(epochDay) ?: return@forEach
        val row = rowLabels.size - 1 - cell.weekday
        if (row < 0 || cell.week >= columnLabels.size) return@forEach
        val measurement = value(data[index])?.toDouble()?.takeIf { it.isFinite() }
        val slot = cell.week * rowLabels.size + row
        val existing = cellsByKey[slot]
        cellsByKey[slot] = HeatmapCell(
            column = cell.week,
            row = row,
            // Several observations on one day sum, which is what a count grid
            // means: three commits on Tuesday is a Tuesday with three commits.
            value = when {
                existing?.value == null -> measurement
                measurement == null -> existing.value
                else -> existing.value + measurement
            },
            sourceIndex = existing?.sourceIndex?.takeIf { it >= 0 } ?: index,
        )
    }

    val cells = ArrayList<HeatmapCell>(columnLabels.size * rowLabels.size)
    for (row in rowLabels.indices) {
        for (column in columnLabels.indices) {
            val slot = column * rowLabels.size + row
            cells += cellsByKey[slot] ?: HeatmapCell(column, row, null, -1)
        }
    }

    val monthLabels = calendar.monthLabels(locale).associate { it.week.toString() to it.label }

    return CalendarHeatmapGrid(
        heatmap = HeatmapGrid(columnLabels, rowLabels, cells),
        firstMillis = CalendarGeometry.epochMillisOf(firstDay),
        monthLabels = monthLabels,
    )
}
