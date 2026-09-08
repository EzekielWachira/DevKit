package io.devkit.chartkit.timeline

import kotlin.math.max

/**
 * One entry on a timeline: an instant or an interval, in a lane.
 *
 * A single model for both, because a point event and a duration answer the same
 * question — "what happened, when, and to which thing" — and splitting them
 * would mean two layouts, two hit tests and two accessibility adapters for a
 * difference of one nullable field.
 *
 * ### Epoch milliseconds
 *
 * Times are `Long` epoch milliseconds, the same currency
 * [io.devkit.chartkit.model.ChartX.Time] uses. `java.time` is API 26 and
 * ChartKit's floor is 24, so naming `Instant` here would either raise the floor
 * or oblige consumers to enable desugaring. A caller on `LocalDate` passes
 * `date.toEpochDay() * 86_400_000L` and keeps the time-zone decision where it
 * belongs.
 *
 * @param end `null` for a point event. Otherwise the interval's exclusive end.
 * @param lane which row the entry is drawn in. Entries sharing a lane share a
 *   row, which is what makes "room 3", "the build agent" or "Priya" a line
 *   across the chart.
 * @param progress `0..1` completion, drawn as an overlay inside the interval.
 *   `null` draws no progress. Meaningless for a point event and ignored there.
 * @param isMilestone marks a moment rather than a span — a release, a gate, a
 *   deadline. Drawn as a marker even when an [end] is present.
 */
class TimelineEntry(
    val label: String,
    val start: Long,
    val end: Long? = null,
    val lane: String,
    val progress: Double? = null,
    val isMilestone: Boolean = false,
    val sourceIndex: Int = 0,
    val item: Any? = null,
    val colorOverride: Int? = null,
) {
    /** True when the entry occupies a span rather than an instant. */
    val isInterval: Boolean get() = end != null && end > start

    /** The span in milliseconds; zero for a point event. */
    val durationMillis: Long get() = if (end == null) 0L else max(0L, end - start)

    /** The instant a point event or milestone is anchored at. */
    val anchor: Long get() = start
}

/**
 * A dependency between two timeline entries.
 *
 * Modelled but not routed. Drawing dependency arrows well means an edge-routing
 * pass that avoids every other bar, and doing it badly means arrows crossing
 * through the tasks they connect — so ChartKit carries the relationship, draws
 * a direct connector where both ends are visible, and leaves elaborate routing
 * to a caller who genuinely needs it.
 *
 * @param fromIndex the index into the resolved entry list of the predecessor.
 */
class TimelineDependency(
    val fromIndex: Int,
    val toIndex: Int,
    val label: String? = null,
)

/**
 * Entries grouped into lanes, with the interval they collectively span.
 *
 * @param lanes lane names in first-appearance order, which keeps the caller's
 *   own ordering rather than sorting alphabetically — "Backlog, In progress,
 *   Done" is an order, and alphabetising it destroys the information.
 * @param rows one row index per entry. Entries in a lane that overlap in time
 *   are given separate rows within it, so two bookings of the same room at the
 *   same hour are both visible rather than one hiding the other.
 * @param rowsPerLane how many rows each lane needed.
 */
class TimelineModel(
    val entries: List<TimelineEntry>,
    val lanes: List<String>,
    val rows: IntArray,
    val rowsPerLane: IntArray,
    val dependencies: List<TimelineDependency> = emptyList(),
) {
    val isEmpty: Boolean get() = entries.isEmpty()

    /** The total number of rows across every lane. */
    val rowCount: Int get() = rowsPerLane.sum()

    /** The first row belonging to [laneIndex], counting from the top. */
    fun rowOffset(laneIndex: Int): Int {
        var offset = 0
        for (index in 0 until laneIndex.coerceAtMost(rowsPerLane.size)) offset += rowsPerLane[index]
        return offset
    }

    /** The absolute row of entry [entryIndex], across every lane. */
    fun absoluteRow(entryIndex: Int): Int {
        val entry = entries.getOrNull(entryIndex) ?: return 0
        val lane = lanes.indexOf(entry.lane).coerceAtLeast(0)
        return rowOffset(lane) + rows.getOrElse(entryIndex) { 0 }
    }

    /** The interval the whole timeline covers, or `null` when it is empty. */
    fun extent(): LongRange? {
        if (entries.isEmpty()) return null
        var minimum = Long.MAX_VALUE
        var maximum = Long.MIN_VALUE
        entries.forEach { entry ->
            if (entry.start < minimum) minimum = entry.start
            val finish = entry.end ?: entry.start
            if (finish > maximum) maximum = finish
        }
        return minimum..maximum
    }
}

/**
 * Normalises the caller's own events into a [TimelineModel].
 *
 * ```kotlin
 * val model = buildTimeline(
 *     data = bookings,
 *     start = { it.from },
 *     end = { it.to },
 *     lane = { it.room },
 *     label = { it.guest },
 * )
 * ```
 *
 * Not tied to project management. The same model draws bookings, shifts,
 * machine uptime, appointments, deploy windows and process durations — a
 * "task", a "booking" and an "outage" are the same shape, and only the caller's
 * lambdas differ.
 *
 * ### Overlap
 *
 * Two entries in one lane that overlap in time are stacked onto separate rows
 * within that lane, by a greedy first-fit sweep in start order. Drawing them on
 * top of each other would hide one of them, and hiding data is never the right
 * default for a chart whose whole content is when things happened.
 */
@Suppress("LongParameterList")
fun <T> buildTimeline(
    data: List<T>,
    start: (T) -> Long,
    label: (T) -> String,
    end: ((T) -> Long?)? = null,
    lane: ((T) -> String)? = null,
    progress: ((T) -> Number?)? = null,
    milestone: ((T) -> Boolean)? = null,
    color: ((T) -> Int?)? = null,
    dependencies: List<TimelineDependency> = emptyList(),
    stackOverlaps: Boolean = true,
): TimelineModel {
    if (data.isEmpty()) {
        return TimelineModel(emptyList(), emptyList(), IntArray(0), IntArray(0), emptyList())
    }

    val entries = data.mapIndexed { index, item ->
        val from = start(item)
        val to = end?.invoke(item)
        TimelineEntry(
            label = label(item),
            start = from,
            // An interval that ends before it starts is a data error, not a
            // negative bar: it is read as a point event at its start, which is
            // the only part of it that is unambiguous.
            end = to?.takeIf { it > from },
            lane = lane?.invoke(item) ?: DEFAULT_LANE,
            progress = progress?.invoke(item)?.toDouble()?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0),
            isMilestone = milestone?.invoke(item) ?: false,
            sourceIndex = index,
            item = item,
            colorOverride = color?.invoke(item),
        )
    }

    val lanes = LinkedHashSet<String>().apply { entries.forEach { add(it.lane) } }.toList()
    val rows = IntArray(entries.size)
    val rowsPerLane = IntArray(lanes.size) { 1 }

    if (stackOverlaps) {
        lanes.forEachIndexed { laneIndex, laneName ->
            // First-fit in start order: an entry goes in the first row whose
            // last occupant has already finished. Optimal for interval graphs,
            // and one pass.
            val rowEnds = ArrayList<Long>()
            entries.withIndex()
                .filter { it.value.lane == laneName }
                .sortedBy { it.value.start }
                .forEach { (index, entry) ->
                    val finish = entry.end ?: entry.start
                    val row = rowEnds.indexOfFirst { it <= entry.start }
                    if (row >= 0) {
                        rowEnds[row] = finish
                        rows[index] = row
                    } else {
                        rowEnds += finish
                        rows[index] = rowEnds.size - 1
                    }
                }
            rowsPerLane[laneIndex] = max(1, rowEnds.size)
        }
    }

    return TimelineModel(entries, lanes, rows, rowsPerLane, dependencies)
}

/** The lane an entry with no lane accessor belongs to. */
internal const val DEFAULT_LANE: String = "Events"
