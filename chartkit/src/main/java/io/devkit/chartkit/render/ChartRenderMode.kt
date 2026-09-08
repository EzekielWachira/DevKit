package io.devkit.chartkit.render

/**
 * Whether a chart is being read on screen or captured as a picture of itself.
 *
 * ```kotlin
 * LineChart(
 *     data = revenue, x = { it.month }, y = { it.amount },
 *     renderMode = ChartRenderMode.Static,
 *     modifier = Modifier.chartCapture(capture),
 * )
 * ```
 *
 * ### Why a mode and not a set of flags
 *
 * A report render is not "animation off". It is animation off *and* gestures
 * off *and* transient selection suppressed *and* labels drawn in full *and* no
 * dependence on pointer position — five decisions that are only correct
 * together. Exposed as five parameters they would be set in four different
 * combinations across a codebase, and a chart captured with three of them right
 * would produce a PDF with a tooltip frozen in the middle of it.
 *
 * The mode changes nothing about the data, the scales or the geometry. A chart
 * rendered statically and the same chart rendered interactively and left alone
 * draw identical pixels; that is the point.
 */
enum class ChartRenderMode {

    /**
     * The normal behaviour: gestures, tooltips, selection and animation.
     */
    Interactive,

    /**
     * A deterministic frame, for export, print, PDF and screenshot tests.
     *
     * ```text
     * gestures       ignored — no pointer input is installed at all
     * animation      settled — drawn at its final state, never mid-reveal
     * selection      drawn only if the caller set it programmatically
     * tooltip        suppressed unless explicitly requested
     * crosshair      suppressed: it follows a pointer that is not there
     * labels         axis label thinning still applies; nothing is truncated
     *                that would not be truncated on screen
     * ```
     *
     * Nothing in a static render depends on a running animation clock or a
     * pointer position, so two captures of the same chart at the same size are
     * byte-identical — which is what makes a screenshot test worth writing.
     */
    Static,
    ;

    val isStatic: Boolean get() = this == Static

    /** True when the mode installs pointer input and lets gestures through. */
    val isInteractive: Boolean get() = this == Interactive
}

/**
 * What a static render still shows.
 *
 * Separate from [ChartRenderMode] because the answers differ by report: a
 * marketing chart wants no selection at all, and an annotated figure in a
 * report wants the one point the author selected programmatically, with its
 * tooltip.
 *
 * @param showSelection draw a selection that was set programmatically. On by
 *   default — a caller who set `state.select(...)` and then captured the chart
 *   meant it. Selections made by a gesture cannot exist in a static render,
 *   because there are no gestures.
 * @param showTooltip draw the tooltip for that selection. Off by default: a
 *   tooltip is a transient overlay, and one frozen into a report reads as a
 *   screenshot taken by accident.
 * @param showCrosshair draw the crosshair through the selection. Off for the
 *   same reason.
 */
data class ChartStaticOptions(
    val showSelection: Boolean = true,
    val showTooltip: Boolean = false,
    val showCrosshair: Boolean = false,
) {
    companion object {
        /** Data and furniture only. The default. */
        val Default: ChartStaticOptions = ChartStaticOptions()

        /** Everything a reader would see mid-interaction, frozen. */
        val Annotated: ChartStaticOptions = ChartStaticOptions(
            showSelection = true,
            showTooltip = true,
            showCrosshair = true,
        )

        /** Nothing but the data. */
        val Bare: ChartStaticOptions = ChartStaticOptions(showSelection = false)
    }
}
