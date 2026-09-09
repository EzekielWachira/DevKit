package io.devkit.chartkit.axis

/**
 * How much room a chart's axes are allowed to take.
 *
 * ### The problem this solves
 *
 * Three value axes are perfectly readable on a tablet and ruinous on a phone in
 * portrait: at 360dp, three gutters of tick labels and three rotated titles can
 * take more width than the plot they surround, and the chart becomes a picture
 * of its own axes. Something has to give, and the two things a chart must never
 * do are overlap the text and silently drop an axis — the first is illegible,
 * and the second removes a quantity from the chart without telling anybody.
 *
 * So what gives is *density*: fewer ticks, shorter numbers. The axes all stay,
 * every one keeps its title and unit, and the reader loses resolution rather
 * than information. Where even that is not enough, the chart reports an
 * [AxisDiagnostic] instead of rendering something unreadable.
 */
enum class AxisDensity {

    /** Every axis at full detail, whatever the width. */
    Full,

    /** Fewer ticks and abbreviated numbers, always. */
    Compact,

    /** Full where there is room; compact where there is not. The default. */
    Auto,
    ;

    internal fun isCompact(availableWidth: Float, axisCount: Int, widthPerAxis: Float): Boolean =
        when (this) {
            Full -> false
            Compact -> true
            // One axis is never cramped by its own gutter — a single axis costs
            // one label column whatever the width — so compaction only starts
            // once axes are competing with the plot for the same pixels.
            Auto -> axisCount >= 2 && availableWidth < widthPerAxis * (axisCount + 1)
        }
}
