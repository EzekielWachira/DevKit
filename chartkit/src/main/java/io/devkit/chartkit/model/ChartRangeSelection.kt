package io.devkit.chartkit.model

/**
 * How far a range selection has got.
 *
 * The distinction matters to a caller: a range still being dragged is a preview
 * worth reflecting on screen, and a completed one is a decision worth acting on
 * — running a query, filtering a list. Collapsing both into one callback makes
 * every drag frame look like a committed choice.
 */
enum class ChartRangeSelectionPhase {

    /** The pointer is down and the range is still changing. */
    InProgress,

    /** The pointer was lifted; this is the range the reader chose. */
    Completed,

    /** The selection was dismissed. */
    Cleared,
}

/**
 * An interval of the domain the reader dragged out.
 *
 * Expressed in **logical domain values**, not pixels: `Mar 12 – Apr 5`, not
 * `120px – 300px`. That is the whole point — a pixel range means nothing to the
 * application, changes when the chart is resized, and cannot be used to filter
 * anything.
 *
 * @param start the earlier edge, whichever direction the drag went.
 * @param end the later edge. Always `start <= end` after normalisation.
 * @param startFraction the start as a fraction of the full domain, which is
 *   what a viewport can be built from — "zoom to the selection" is
 *   `ChartViewport.between(range.startFraction, range.endFraction)`.
 * @param items the caller's own objects falling inside the range, in input
 *   order. Empty when the chart could not resolve them.
 * @param phase how far the gesture has got.
 */
data class ChartRangeSelection<out T>(
    val start: ChartX,
    val end: ChartX,
    val startFraction: Double,
    val endFraction: Double,
    val items: List<T>,
    val phase: ChartRangeSelectionPhase,
) {
    /** The range as text, for a readout or an accessibility announcement. */
    val label: String get() = "${start.rangeLabel()} to ${end.rangeLabel()}"

    /** True when the two edges resolved to the same domain position. */
    val isEmpty: Boolean get() = endFraction - startFraction <= 0.0
}

private fun ChartX.rangeLabel(): String = when (this) {
    is ChartX.Category -> label
    is ChartX.Numeric -> value.toString()
    is ChartX.Time -> epochMillis.toString()
}

internal typealias AnyChartRangeSelection = ChartRangeSelection<Any?>

@Suppress("UNCHECKED_CAST")
internal fun <T> AnyChartRangeSelection.typed(): ChartRangeSelection<T> =
    this as ChartRangeSelection<T>
