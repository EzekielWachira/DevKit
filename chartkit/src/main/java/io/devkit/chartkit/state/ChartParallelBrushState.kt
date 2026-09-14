package io.devkit.chartkit.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Which slice of each axis a parallel-coordinates chart is filtered to.
 *
 * ```kotlin
 * val brushes = rememberParallelBrushState()
 *
 * ParallelCoordinatesChart(data = cars, dimensions = specs, brushState = brushes)
 * Text("${brushes.activeCount} filters")
 * Button(onClick = { brushes.clearAll() }) { Text("Reset") }
 * ```
 *
 * ### Brushing is the interaction the chart exists for
 *
 * A parallel-coordinates plot of any size is a thicket. Its value is not in
 * reading one line but in asking "which rows are high here *and* low there",
 * and the way that question is asked is by dragging a range down one axis and
 * seeing which lines survive on the others. Without it the chart is a picture;
 * with it, it is a query.
 *
 * ### Ranges are in value space, not pixels
 *
 * A brush means "between 1,200kg and 1,600kg", not "between these two rows of
 * the screen". Stored as values, it survives a rotation, a resize and a
 * different density, and it can be set from code against numbers the caller
 * recognises rather than against a layout they cannot see.
 */
@Stable
class ChartParallelBrushState internal constructor() {

    /** Each brushed axis, by index, as an inclusive interval in that axis' own units. */
    var ranges: Map<Int, ClosedFloatingPointRange<Double>> by mutableStateOf(emptyMap())
        private set

    /**
     * The axis a drag is currently on, or `null`.
     *
     * Held so the layer can draw the handle being dragged differently from the
     * brushes already set, which is the only feedback a finger gets that the
     * gesture landed on the axis it aimed at.
     */
    var activeAxis: Int? by mutableStateOf(null)
        internal set

    /** How many axes are filtered. */
    val activeCount: Int get() = ranges.size

    /** True when nothing is filtered. */
    val isEmpty: Boolean get() = ranges.isEmpty()

    /** Filters [axisIndex] to [range]. A degenerate range clears it instead. */
    fun brush(axisIndex: Int, range: ClosedFloatingPointRange<Double>) {
        if (!range.start.isFinite() || !range.endInclusive.isFinite()) return
        // A tap on an axis is a drag of zero length. Treating it as a filter
        // admitting one exact value would hide every row, which reads as a bug
        // rather than as a filter.
        if (range.endInclusive - range.start <= 0.0) {
            clear(axisIndex)
            return
        }
        ranges = ranges + (axisIndex to range)
    }

    /** Removes the filter on [axisIndex]. */
    fun clear(axisIndex: Int) {
        if (axisIndex !in ranges) return
        ranges = ranges - axisIndex
    }

    /** Removes every filter. */
    fun clearAll() {
        if (ranges.isEmpty()) return
        ranges = emptyMap()
    }

    /**
     * Whether a row survives every brush.
     *
     * A row **missing** the value an axis is brushed on is excluded. The
     * alternative — letting it through because nothing contradicts the filter —
     * would put rows in the answer to "weight between 1,200 and 1,600" whose
     * weight is unknown, which is not what anybody means by that question.
     */
    fun admits(valuesByAxis: (Int) -> Double?): Boolean {
        if (ranges.isEmpty()) return true
        return ranges.all { (axis, range) ->
            val value = valuesByAxis(axis)
            value != null && value.isFinite() && value in range
        }
    }
}

/** Remembers a [ChartParallelBrushState]. */
@Composable
fun rememberParallelBrushState(): ChartParallelBrushState =
    remember { ChartParallelBrushState() }
