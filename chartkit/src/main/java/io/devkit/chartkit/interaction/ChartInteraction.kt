package io.devkit.chartkit.interaction

/**
 * Which gestures select a data point.
 *
 * Charts differ in what makes sense. A bar is a target you can hit, so tapping
 * is natural and dragging adds little. A line's points are a few pixels wide,
 * and on a time series the useful gesture is dragging along the plot while the
 * selection follows the nearest x — which is why line and area charts default
 * to [TapAndScrub] and bars to [Tap].
 */
enum class ChartSelectionMode {

    /** No selection, no tooltip, no pointer input at all. */
    None,

    /** A tap selects the nearest item. */
    Tap,

    /** Dragging across the plot selects as the pointer moves. */
    Scrub,

    /** Both. The default for line and area charts. */
    TapAndScrub,
    ;

    internal val allowsTap: Boolean get() = this == Tap || this == TapAndScrub
    internal val allowsScrub: Boolean get() = this == Scrub || this == TapAndScrub
}

/**
 * How a pointer position is matched to data.
 *
 * The distinction is the difference between a bar chart and a line chart, not a
 * styling preference: a bar occupies real area and should only be selected when
 * the pointer is on it, while a line point is a few pixels across and would be
 * almost impossible to hit exactly.
 */
enum class HitTestMode {

    /** The pointer must land inside the item. Bars. */
    Contains,

    /** The nearest item along the domain axis wins. Lines and areas. */
    NearestDomain,
}

/**
 * When a selection is dropped.
 *
 * @param clearOnTapOutside a tap in empty plot space clears the selection.
 * @param clearOnScrubEnd lifting the finger after a scrub clears it.
 *   Off by default: after dragging to a point, the reader usually wants the
 *   tooltip to stay long enough to read.
 */
data class ChartSelectionBehaviour(
    val clearOnTapOutside: Boolean = true,
    val clearOnScrubEnd: Boolean = false,
) {
    companion object {
        val Default: ChartSelectionBehaviour = ChartSelectionBehaviour()
    }
}
