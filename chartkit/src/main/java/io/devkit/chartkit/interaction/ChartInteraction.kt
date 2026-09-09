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


/**
 * What a **single-finger drag** does.
 *
 * The one genuinely exclusive choice in ChartKit's interaction model, and the
 * reason this is an enum rather than a set of booleans: one finger moving
 * across the plot cannot simultaneously scrub, pan and drag out a range. Three
 * booleans would let a caller ask for all three and get whichever the
 * implementation happened to check first.
 *
 * Everything else composes freely. Tapping always selects, a pinch always
 * zooms when zoom is enabled, and the crosshair follows whatever the drag is
 * doing.
 */
enum class ChartDragMode {

    /** Nothing. Taps still select; the chart still scrolls inside a parent. */
    None,

    /**
     * Move the selection to the nearest point as the finger travels. The
     * default for line and area charts.
     */
    Scrub,

    /** Move the viewport. Only meaningful once the chart is zoomed in. */
    Pan,

    /** Drag out an interval of the domain. */
    Range,

    /**
     * Pan while zoomed in, scrub when fully zoomed out.
     *
     * The behaviour a reader of an analytical chart expects without being
     * told: at full extent there is nothing to pan, so the drag reads values;
     * once zoomed, the drag moves the window. The mode is explicit because the
     * switch is a real behaviour change, not a hidden heuristic.
     */
    PanWhenZoomed,
}

/**
 * How a chart responds to touch.
 *
 * One object rather than a scatter of independent flags, because the gestures
 * genuinely interact: a pinch and a pan share a pointer stream, a drag can only
 * mean one thing, and a tap has to survive all of it. Collecting them here is
 * what makes [ChartGestureCoordinator] able to arbitrate rather than letting
 * several `pointerInput` modifiers fight.
 *
 * ```kotlin
 * // A read-only chart
 * interaction = ChartInteraction.None
 *
 * // Tap and scrub — the default for lines
 * interaction = ChartInteraction.Default
 *
 * // An analytical chart: pinch to zoom, drag to pan, tap to inspect
 * interaction = ChartInteraction.Explorable
 *
 * // Drag out a date range
 * interaction = ChartInteraction(dragMode = ChartDragMode.Range)
 * ```
 *
 * @param tapSelects a tap selects the nearest item.
 * @param dragMode what a single-finger drag does.
 * @param zoomEnabled a two-finger pinch changes the viewport.
 * @param behaviour when a selection is dropped.
 */
data class ChartInteraction(
    val tapSelects: Boolean = true,
    val dragMode: ChartDragMode = ChartDragMode.Scrub,
    val zoomEnabled: Boolean = false,
    val behaviour: ChartSelectionBehaviour = ChartSelectionBehaviour.Default,
) {
    /** True when nothing consumes pointer input. */
    internal val isInert: Boolean
        get() = !tapSelects && dragMode == ChartDragMode.None && !zoomEnabled

    /** Whether the viewport can move at all — pinch, or a drag that pans. */
    internal val movesViewport: Boolean
        get() = zoomEnabled ||
            dragMode == ChartDragMode.Pan ||
            dragMode == ChartDragMode.PanWhenZoomed

    companion object {

        /** Tap to select, drag to scrub. What line and area charts use. */
        val Default: ChartInteraction = ChartInteraction()

        /** Tap only. What bar charts use — a bar is a target you can hit. */
        val TapOnly: ChartInteraction = ChartInteraction(dragMode = ChartDragMode.None)

        /** No pointer input at all. */
        val None: ChartInteraction = ChartInteraction(
            tapSelects = false,
            dragMode = ChartDragMode.None,
        )

        /** Pinch to zoom, drag to pan once zoomed, tap to inspect. */
        val Explorable: ChartInteraction = ChartInteraction(
            dragMode = ChartDragMode.PanWhenZoomed,
            zoomEnabled = true,
        )

        /** Drag out a domain range; pinch still zooms. */
        val RangeSelect: ChartInteraction = ChartInteraction(
            dragMode = ChartDragMode.Range,
            zoomEnabled = true,
        )
    }
}

/**
 * Whether a chart draws a crosshair, and in which directions.
 *
 * @param enabled draw it at all.
 * @param vertical a rule down the plot at the selection's domain position. The
 *   one that matters for a time series, and on by default.
 * @param horizontal a rule across the plot at the selection's value.
 * @param showAxisLabels put the selected values on the axes, in a small chip.
 *   Independent of the rules: a crosshair works without them.
 */
data class CrosshairConfig(
    val enabled: Boolean = false,
    val vertical: Boolean = true,
    val horizontal: Boolean = false,
    val showAxisLabels: Boolean = true,
    /**
     * Whether every value axis gets its own readout chip at the selection.
     *
     * Off by default, and deliberately: on a three-axis chart it puts three
     * chips in the gutters on top of a tooltip that already lists the same
     * three numbers, which is more ink than reading. Turn it on for a chart
     * with no tooltip, where the chips *are* the readout.
     *
     * ```text
     *  82 mm ┤                            ├ 14.2 °C
     *        │            ╷               │
     *        │            ╷               ├ 1,018 hPa
     * ```
     */
    val axisValueLabels: Boolean = false,
) {
    companion object {

        /** No crosshair; the selection is still marked on the data itself. */
        val None: CrosshairConfig = CrosshairConfig(enabled = false)

        /** A vertical guide with axis readouts — the time-series default. */
        val Vertical: CrosshairConfig = CrosshairConfig(enabled = true)

        /** Both rules, for reading a value off two axes at once. */
        val Both: CrosshairConfig = CrosshairConfig(enabled = true, horizontal = true)
    }
}
