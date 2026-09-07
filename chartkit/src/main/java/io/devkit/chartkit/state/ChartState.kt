package io.devkit.chartkit.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateSet
import androidx.compose.runtime.mutableStateSetOf
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.model.ChartRangeSelection
import io.devkit.chartkit.model.ChartSelection

/**
 * A chart's interaction state, hoisted.
 *
 * Charts work without one — pass nothing and the chart remembers its own — but
 * hoisting it is what lets a screen read the selection, drive it from elsewhere,
 * or keep two charts in step:
 *
 * ```kotlin
 * val state = rememberChartState<Revenue>()
 *
 * LineChart(data = revenue, x = { it.month }, y = { it.amount }, state = state)
 * Text("Selected: ${state.selection?.item?.month ?: "none"}")
 * ```
 *
 * Only what a caller has a reason to read or write is exposed. Measured plot
 * bounds, cached geometry and scales stay inside the chart: they change every
 * frame during an animation, and publishing them would make every consumer's
 * recomposition scope depend on the chart's own layout.
 */
@Stable
class ChartState<T> internal constructor(
    initialSelection: ChartSelection<T>? = null,
    initialHiddenSeries: Set<String> = emptySet(),
) {

    /** The current selection, or `null` when nothing is selected. */
    var selection: ChartSelection<T>? by mutableStateOf(initialSelection)
        internal set

    /**
     * Where the pointer is inside the plot while scrubbing, in pixels, or
     * `null` when it is not down.
     *
     * Exposed for custom overlays that want to follow the finger rather than
     * the selected point — a crosshair that tracks continuously between data
     * points, for instance.
     */
    var pointerPosition: ChartOffset? by mutableStateOf(null)
        internal set

    /**
     * The domain interval the reader has dragged out, or `null`.
     *
     * Lives alongside the point selection rather than replacing it: a chart can
     * have a selected point *and* a selected range, and they answer different
     * questions — "what is this value" against "what happened between these
     * two dates".
     */
    var rangeSelection: ChartRangeSelection<T>? by mutableStateOf(null)
        internal set

    private val hidden: SnapshotStateSet<String> =
        mutableStateSetOf<String>().apply { addAll(initialHiddenSeries) }

    /**
     * Ids of series hidden through the legend or through [setSeriesVisible].
     *
     * Returns an immutable **copy**, not a live view. That matters more than it
     * looks: charts key `remember` blocks on this, and a `remember` keyed on a
     * live mutable set never invalidates — the previous key and the new one are
     * the same object, so they compare equal however much the contents changed,
     * and the chart would keep drawing a series the reader had just hidden.
     * A copy compares by content, which is the behaviour every caller assumes.
     *
     * Reading it inside composition still subscribes to the underlying state,
     * so a change still triggers recomposition.
     */
    val hiddenSeriesIds: Set<String> get() = hidden.toSet()

    /** Whether the series with [seriesId] is currently drawn. */
    fun isSeriesVisible(seriesId: String): Boolean = seriesId !in hidden

    /**
     * Shows or hides a series.
     *
     * Hiding a series does not change the others' colours: palette slots are
     * assigned from the declared order, not from what is visible, so a legend
     * toggle never recolours the chart.
     */
    fun setSeriesVisible(seriesId: String, visible: Boolean) {
        require(seriesId.isNotBlank()) { "A series id cannot be blank" }
        if (visible) hidden.remove(seriesId) else hidden.add(seriesId)
        // A selection pointing at a series nobody can see is a tooltip about
        // nothing.
        if (!visible && selection?.seriesId == seriesId) selection = null
    }

    /** Flips the visibility of [seriesId]. What a legend tap calls. */
    fun toggleSeries(seriesId: String) {
        setSeriesVisible(seriesId, !isSeriesVisible(seriesId))
    }

    /** Sets the selection programmatically, e.g. from a list elsewhere on screen. */
    fun select(selection: ChartSelection<T>?) {
        this.selection = selection
    }

    /** Clears the selection and dismisses the tooltip. */
    fun clearSelection() {
        selection = null
        pointerPosition = null
    }

    /** Sets the range programmatically — to restore a saved filter, say. */
    fun selectRange(range: ChartRangeSelection<T>?) {
        rangeSelection = range
    }

    /** Drops the range selection. */
    fun clearRangeSelection() {
        rangeSelection = null
    }
}

/**
 * Remembers a [ChartState].
 *
 * @param initiallyHiddenSeries ids to start with hidden.
 */
@Composable
fun <T> rememberChartState(
    initiallyHiddenSeries: Set<String> = emptySet(),
): ChartState<T> = remember { ChartState(initialHiddenSeries = initiallyHiddenSeries) }

/**
 * A [ChartState] whose *series visibility* survives configuration changes and
 * process death.
 *
 * Only the visibility. The selection is deliberately not saved: it references
 * the caller's own data object, which ChartKit cannot serialise and has no
 * business trying to, and a pointer gesture is not screen state worth restoring
 * three seconds after the process was killed. Hidden series are plain strings
 * and genuinely are a preference the reader expressed, so those are kept.
 */
@Composable
fun <T> rememberSaveableChartState(
    initiallyHiddenSeries: Set<String> = emptySet(),
): ChartState<T> = rememberSaveable(
    saver = listSaver(
        save = { state -> state.hiddenSeriesIds.toList() },
        restore = { hidden -> ChartState<T>(initialHiddenSeries = hidden.toSet()) },
    ),
) {
    ChartState(initialHiddenSeries = initiallyHiddenSeries)
}
