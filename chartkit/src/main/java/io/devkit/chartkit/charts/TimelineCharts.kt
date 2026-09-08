package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.annotation.ChartAnnotation
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartGrid
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.timeline.IntervalLabels
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.model.ChartXAxisKind
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.state.ChartPlotAlignment
import io.devkit.chartkit.state.ChartSharedCrosshairState
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.ChartViewportState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.state.rememberChartViewportState
import io.devkit.chartkit.timeline.TimelineDependency
import io.devkit.chartkit.timeline.TimelineModel
import io.devkit.chartkit.timeline.buildTimeline

/**
 * A timeline of point events.
 *
 * ```kotlin
 * TimelineChart(
 *     data = incidents,
 *     at = { it.timestamp },
 *     label = { it.title },
 *     lane = { it.service },
 * )
 * ```
 *
 * ```text
 * Auth      ●              ●
 * Payments        ●   ●          ●
 *           Jan 3   Jan 8   Jan 12
 * ```
 *
 * Times are epoch milliseconds — the same currency the rest of ChartKit's time
 * axes use, and for the reason set out in
 * [io.devkit.chartkit.timeline.TimelineEntry].
 *
 * A point timeline is [RangeChart] with no end accessor; the two share a layer
 * because an instant is an interval of no duration and drawing them separately
 * would have meant two hit tests and two accessibility adapters.
 */
@Suppress("LongParameterList")
@Composable
fun <T> TimelineChart(
    data: List<T>,
    at: (T) -> Long,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    lane: ((T) -> String)? = null,
    milestone: ((T) -> Boolean)? = null,
    color: ((T) -> Int?)? = null,
    timeAxis: ChartAxis = ChartAxis.Default,
    laneAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = ChartGrid.Vertical,
    legend: LegendPosition = LegendPosition.None,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.Default,
    annotations: List<ChartAnnotation> = emptyList(),
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    plotAlignment: ChartPlotAlignment? = null,
    viewportState: ChartViewportState = rememberChartViewportState(),
    sharedCrosshair: ChartSharedCrosshairState? = null,
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Tooltip(it, showSeriesNames = false)
    },
    seriesId: String = "timeline",
    seriesName: String = "Events",
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    RangeChart(
        data = data,
        start = at,
        label = label,
        end = { null },
        modifier = modifier,
        lane = lane,
        milestone = milestone,
        color = color,
        labels = IntervalLabels.None,
        timeAxis = timeAxis,
        laneAxis = laneAxis,
        grid = grid,
        legend = legend,
        animation = animation,
        interaction = interaction,
        annotations = annotations,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        renderMode = renderMode,
        staticOptions = staticOptions,
        plotAlignment = plotAlignment,
        viewportState = viewportState,
        sharedCrosshair = sharedCrosshair,
        state = state,
        onSelectionChanged = onSelectionChanged,
        tooltip = tooltip,
        seriesId = seriesId,
        seriesName = seriesName,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
    )
}

/**
 * Durations along a time axis, grouped into lanes.
 *
 * ```kotlin
 * RangeChart(
 *     data = bookings,
 *     start = { it.from },
 *     end = { it.to },
 *     lane = { it.room },
 *     label = { it.guest },
 * )
 * ```
 *
 * ```text
 * Room A   ▐████████▌      ▐██████▌
 * Room B        ▐██████████▌
 *          09:00   12:00   15:00
 * ```
 *
 * ### Not a project-management chart
 *
 * The same model draws bookings, shifts, machine uptime, appointments, deploy
 * windows and process durations. A "task", a "booking" and an "outage" are the
 * same shape and only the caller's lambdas differ; [GanttChart] adds progress
 * and milestones on top without changing any of it.
 *
 * ### Zoom, pan and a shared crosshair
 *
 * The time axis is an ordinary continuous domain axis, so everything the
 * Cartesian engine already does applies: pinch to zoom, drag to pan, a crosshair
 * that reads out the time under the pointer, annotations at a date, range
 * selection, and a viewport shared with another chart.
 *
 * ### Overlaps stack
 *
 * Two entries in one lane that overlap in time get separate rows within it.
 * Drawing them on top of each other would hide one, and hiding data is never
 * the right default for a chart whose whole content is when things happened.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun <T> RangeChart(
    data: List<T>,
    start: (T) -> Long,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    end: ((T) -> Long?)? = null,
    lane: ((T) -> String)? = null,
    progress: ((T) -> Number?)? = null,
    milestone: ((T) -> Boolean)? = null,
    color: ((T) -> Int?)? = null,
    dependencies: List<TimelineDependency> = emptyList(),
    labels: IntervalLabels = IntervalLabels.Inside,
    showProgress: Boolean = true,
    timeAxis: ChartAxis = ChartAxis.Default,
    laneAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = ChartGrid.Vertical,
    legend: LegendPosition = LegendPosition.None,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.Default,
    annotations: List<ChartAnnotation> = emptyList(),
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    plotAlignment: ChartPlotAlignment? = null,
    viewportState: ChartViewportState = rememberChartViewportState(),
    sharedCrosshair: ChartSharedCrosshairState? = null,
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Tooltip(it, showSeriesNames = false)
    },
    seriesId: String = "timeline",
    seriesName: String = "Timeline",
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    val model: TimelineModel = remember(data, start, end, lane, progress, milestone, color, dependencies) {
        buildTimeline(
            data = data,
            start = start,
            label = label,
            end = end,
            lane = lane,
            progress = progress,
            milestone = milestone,
            color = color,
            dependencies = dependencies,
        )
    }

    val layer = remember(model, labels, showProgress) {
        ResolvedLayer.Interval(
            key = "intervals",
            model = model,
            seriesId = seriesId,
            seriesName = seriesName,
            labels = labels,
            showProgress = showProgress,
            showDependencies = dependencies.isNotEmpty(),
            axisKind = ChartXAxisKind.Time,
        )
    }

    // The value axis carries lane rows, not a quantity — so it is labelled at
    // the integers the rows sit on, with the lane's own name. Supplying explicit
    // ticks is what
    // [io.devkit.chartkit.axis.ChartAxis.ticks] exists for, and it is the same
    // mechanism a heatmap's rows use.
    val resolvedLaneAxis = remember(laneAxis, model) {
        laneAxis.copy(
            ticks = List(model.rowCount) { it.toDouble() },
            valueFormatter = laneAxis.valueFormatter ?: laneLabelFormatter(model),
            // Rows are already as dense as they are; thinning them would drop a
            // lane's name and leave its bars unattributed.
            maxLabels = laneAxis.maxLabels ?: model.rowCount.coerceAtLeast(1),
        )
    }

    CartesianChartCore(
        layers = listOf(layer),
        modifier = modifier,
        orientation = ChartOrientation.Vertical,
        domainAxis = timeAxis,
        valueAxis = resolvedLaneAxis,
        grid = grid,
        // Fixed to the rows: an "auto" domain would pad above and below them,
        // leaving the first and last lanes floating inside the plot.
        valueDomainPolicy = DomainPolicy.Fixed(
            min = -0.5,
            max = (model.rowCount - 1).coerceAtLeast(0) + 0.5,
        ),
        legend = legend,
        legendTogglesSeries = false,
        animation = animation,
        interaction = interaction,
        crosshair = ChartDefaults.SelectionGuide,
        hitTestMode = HitTestMode.Contains,
        sharedTooltip = false,
        state = state.asErased(),
        viewportState = viewportState,
        sharedCrosshair = sharedCrosshair,
        annotations = remember(annotations) { resolveAnnotations(annotations, ChartXResolver.Time) },
        renderMode = renderMode,
        staticOptions = staticOptions,
        plotAlignment = plotAlignment,
        xResolver = ChartXResolver.Time,
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

/**
 * A Gantt-style task view: intervals with progress, milestones and lanes.
 *
 * ```kotlin
 * GanttChart(
 *     data = tasks,
 *     start = { it.start },
 *     end = { it.end },
 *     label = { it.name },
 *     lane = { it.workstream },
 *     progress = { it.percentComplete },
 *     milestone = { it.isGate },
 * )
 * ```
 *
 * ```text
 * Design    ▐███████▌
 * Build          ▐██████████▓▓▓▓▌
 * Launch                        ◆
 * ```
 *
 * ### A foundation, not a project-management product
 *
 * Tasks, progress, milestones, lanes and a dependency model. What it
 * deliberately does not attempt is scheduling, critical-path analysis, resource
 * levelling or automatic dependency routing — those are an application's
 * concerns, and a charting library that guessed at them would be wrong in ways
 * its users could not correct.
 *
 * [dependencies] are drawn as direct connectors between the two entries. Routing
 * them around the bars in between is an edge-routing pass over the whole chart;
 * doing it badly puts arrows through the tasks they connect, which is worse than
 * a straight line a reader can follow.
 */
@Suppress("LongParameterList")
@Composable
fun <T> GanttChart(
    data: List<T>,
    start: (T) -> Long,
    end: (T) -> Long?,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    lane: ((T) -> String)? = null,
    progress: ((T) -> Number?)? = null,
    milestone: ((T) -> Boolean)? = null,
    color: ((T) -> Int?)? = null,
    dependencies: List<TimelineDependency> = emptyList(),
    labels: IntervalLabels = IntervalLabels.Inside,
    timeAxis: ChartAxis = ChartAxis.Default,
    laneAxis: ChartAxis = ChartAxis.Default,
    grid: ChartGrid = ChartGrid.Vertical,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: ChartInteraction = ChartInteraction.Default,
    annotations: List<ChartAnnotation> = emptyList(),
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    plotAlignment: ChartPlotAlignment? = null,
    viewportState: ChartViewportState = rememberChartViewportState(),
    sharedCrosshair: ChartSharedCrosshairState? = null,
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Tooltip(it, showSeriesNames = false)
    },
    seriesId: String = "gantt",
    seriesName: String = "Tasks",
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    RangeChart(
        data = data,
        start = start,
        label = label,
        end = end,
        modifier = modifier,
        lane = lane,
        progress = progress,
        milestone = milestone,
        color = color,
        dependencies = dependencies,
        labels = labels,
        showProgress = true,
        timeAxis = timeAxis,
        laneAxis = laneAxis,
        grid = grid,
        animation = animation,
        interaction = interaction,
        annotations = annotations,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        renderMode = renderMode,
        staticOptions = staticOptions,
        plotAlignment = plotAlignment,
        viewportState = viewportState,
        sharedCrosshair = sharedCrosshair,
        state = state,
        onSelectionChanged = onSelectionChanged,
        tooltip = tooltip,
        seriesId = seriesId,
        seriesName = seriesName,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
    )
}

/**
 * Labels the value axis with lane names rather than row numbers.
 *
 * A lane occupying three rows is labelled once, on its first — repeating the
 * name three times would read as three lanes with the same name, and numbering
 * the rows would tell the reader nothing.
 */
private fun laneLabelFormatter(model: TimelineModel): ChartValueFormatter {
    val labels = arrayOfNulls<String>(model.rowCount)
    var offset = 0
    model.lanes.forEachIndexed { index, name ->
        if (offset < labels.size) labels[offset] = name
        offset += model.rowsPerLane.getOrElse(index) { 1 }
    }
    return ChartValueFormatter { value ->
        val row = Math.round(value).toInt()
        labels.getOrNull(row).orEmpty()
    }
}
