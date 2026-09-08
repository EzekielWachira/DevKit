package io.devkit.chartkit.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.devkit.chartkit.model.ChartRangeSelection
import io.devkit.chartkit.model.ChartX

/**
 * One thing the reader has selected, across a dashboard.
 *
 * ### Deliberately thin
 *
 * A dimension, a key, a label and which chart it came from. That is the whole
 * vocabulary, and it is thin on purpose: a richer schema — operators,
 * predicates, nested groups — would be ChartKit inventing a query language, and
 * every application would then have to translate between that language and the
 * one its data already speaks.
 *
 * @param dimension what was selected *about*: `"region"`, `"month"`,
 *   `"service"`. The caller's own name; ChartKit only matches on it.
 * @param key the value selected. The caller's own object, compared with
 *   `equals` — so a data class, a string or an enum all work.
 * @param label a human-readable form, for a filter chip.
 * @param source which chart published it, so a chart can recognise its own.
 */
@Immutable
data class ChartFilter(
    val dimension: String,
    val key: Any?,
    val label: String? = null,
    val source: Any? = null,
)

/**
 * Selections shared across a dashboard's charts.
 *
 * ```kotlin
 * val filters = rememberChartFilterState()
 *
 * BarChart(
 *     data = byRegion, category = { it.region }, value = { it.revenue },
 *     onSelectionChanged = { selection ->
 *         filters.toggle(ChartFilter("region", selection?.item?.region))
 *     },
 * )
 *
 * val visible = remember(orders, filters.filters) { filters.apply(orders) { it.region } }
 * LineChart(data = visible, x = { it.month }, y = { it.amount })
 * ```
 *
 * ### ChartKit coordinates; the application filters
 *
 * What is shared is *which selections are active*. The actual filtering happens
 * in the caller's own code, over the caller's own data, with the caller's own
 * semantics — because "filter orders by region" means joining a table in one
 * app and re-querying a server in another, and a charting library that owned
 * that decision would be wrong in both.
 *
 * ```text
 * Chart A selection
 *         ↓
 * ChartFilterState        ← ChartKit's part ends here
 *         ↓
 * the app transforms its data
 *         ↓
 * Charts B and C redraw
 * ```
 *
 * ### No feedback loops
 *
 * One piece of state is the source of truth and every chart reads it. A chart
 * writes only in response to a **gesture of its own**, and [ChartFilter.source]
 * lets it recognise its own publication — so a loop is structurally impossible
 * rather than merely unlikely. This is the same rule
 * [ChartSharedCrosshairState] follows, for the same reason.
 */
@Stable
class ChartFilterState internal constructor(
    private val multiSelect: Boolean,
) {
    /** Every active selection, in the order it was made. */
    var filters: List<ChartFilter> by mutableStateOf(emptyList())
        private set

    /** True when nothing is filtered and every chart should show everything. */
    val isEmpty: Boolean get() = filters.isEmpty()

    /** The active keys for [dimension]. */
    fun keysFor(dimension: String): List<Any?> =
        filters.filter { it.dimension == dimension }.map { it.key }

    /** True when [key] is selected in [dimension]. */
    fun isActive(dimension: String, key: Any?): Boolean =
        filters.any { it.dimension == dimension && it.key == key }

    /** True when [dimension] has any selection at all. */
    fun hasFilter(dimension: String): Boolean = filters.any { it.dimension == dimension }

    /**
     * Adds [filter], or removes it when it is already active.
     *
     * What a chart's `onSelectionChanged` calls. A `null` key clears the
     * dimension, which is what a tap on empty space means.
     */
    fun toggle(filter: ChartFilter) {
        if (filter.key == null) {
            clear(filter.dimension)
            return
        }
        filters = when {
            isActive(filter.dimension, filter.key) ->
                filters.filterNot { it.dimension == filter.dimension && it.key == filter.key }

            multiSelect -> filters + filter
            // Single-select replaces within the dimension but leaves the other
            // dimensions alone: selecting a region should not clear the month.
            else -> filters.filterNot { it.dimension == filter.dimension } + filter
        }
    }

    /** Replaces every selection in [dimension] with [keys]. */
    fun set(dimension: String, keys: List<ChartFilter>) {
        filters = filters.filterNot { it.dimension == dimension } + keys
    }

    /** Clears [dimension]. */
    fun clear(dimension: String) {
        filters = filters.filterNot { it.dimension == dimension }
    }

    /** Clears everything. */
    fun clearAll() {
        filters = emptyList()
    }

    /**
     * [items] with those matching every active dimension kept.
     *
     * A convenience for the common shape — one accessor per dimension, keys
     * compared with `equals` — and nothing more. An application whose filtering
     * is a server query, a join or a range test ignores this and reads
     * [filters] directly.
     *
     * Dimensions are combined with **and**; keys within a dimension with **or**.
     * That is what a reader means by selecting two regions and one month.
     */
    fun <T> apply(items: List<T>, vararg accessors: Pair<String, (T) -> Any?>): List<T> {
        if (filters.isEmpty()) return items
        val active = accessors.filter { hasFilter(it.first) }
        if (active.isEmpty()) return items
        return items.filter { item ->
            active.all { (dimension, accessor) -> accessor(item) in keysFor(dimension) }
        }
    }

    /** The single-dimension form of [apply]. */
    fun <T> apply(items: List<T>, dimension: String, accessor: (T) -> Any?): List<T> =
        apply(items, dimension to accessor)
}

/**
 * Remembers a [ChartFilterState].
 *
 * @param multiSelect whether a dimension can hold several selections at once.
 *   Off by default: one selection per dimension is what a tap on a bar means,
 *   and multi-select needs an affordance the application has to provide.
 */
@Composable
fun rememberChartFilterState(multiSelect: Boolean = false): ChartFilterState =
    remember(multiSelect) { ChartFilterState(multiSelect) }

/**
 * A selected window of a domain, shared across charts.
 *
 * ### Brush, and range selection
 *
 * A brush *is* a range selection — the same interval, dragged out the same way
 * — hoisted so more than one chart can see it. [ChartRangeSelection] stays the
 * per-chart type a callback hands back; this is where a dashboard keeps the one
 * the whole screen agrees on.
 *
 * ```kotlin
 * val brush = rememberChartBrushState()
 *
 * LineChart(
 *     data = readings, x = { it.at }, y = { it.value },
 *     interaction = ChartInteraction.RangeSelect,
 *     onRangeSelectionChanged = { brush.set(it) },
 * )
 * Text(brush.range?.let { "Selected ${it.items.size} readings" } ?: "Drag to select")
 * ```
 */
@Stable
class ChartBrushState internal constructor() {

    /** The selected interval, or `null`. */
    var range: ChartRangeSelection<Any?>? by mutableStateOf(null)
        private set

    val isActive: Boolean get() = range != null

    /** The interval's ends as domain values, or `null`. */
    val bounds: Pair<ChartX, ChartX>? get() = range?.let { it.start to it.end }

    /** Publishes a selection. What a chart's `onRangeSelectionChanged` calls. */
    fun set(selection: ChartRangeSelection<Any?>?) {
        range = selection
    }

    /** Drops the selection. */
    fun clear() {
        range = null
    }

    /**
     * Zooms [viewport] to the brushed window.
     *
     * The other half of a brush: dragging out an interval and then looking at
     * it. Kept as an explicit call rather than as automatic behaviour, because
     * "select these three days" and "zoom to these three days" are different
     * intentions and a chart cannot tell which one a drag meant.
     */
    fun zoom(viewport: ChartViewportState) {
        val selection = range ?: return
        if (selection.isEmpty) return
        viewport.viewport = io.devkit.chartkit.viewport.ChartViewport.between(
            selection.startFraction,
            selection.endFraction,
        )
    }
}

/** Remembers a [ChartBrushState]. */
@Composable
fun rememberChartBrushState(): ChartBrushState = remember { ChartBrushState() }
