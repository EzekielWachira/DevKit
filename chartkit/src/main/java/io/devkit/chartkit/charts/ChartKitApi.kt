package io.devkit.chartkit.charts

/**
 * Marks the low-level composition grammar, whose shape may change before 1.0.
 *
 * Scoped deliberately narrowly. Annotating the whole library experimental would
 * make `LineChart` — the API most consumers will only ever use, and the one
 * least likely to change — require an opt-in, which trains people to add the
 * suppression everywhere and stops the marker meaning anything. Only
 * [CartesianChart] and its layer DSL carry it, because those are where a real
 * multi-layer grammar will want room to move.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "ChartKit's layer composition API is experimental and may change before 1.0.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class ExperimentalChartKitApi

/**
 * Whether ChartKit may reorder the caller's data.
 *
 * It may not, by default. A line chart does need x-ordered points to draw a
 * meaningful path, but silently sorting the developer's list produces a chart
 * that disagrees with the list they are looking at, and the disagreement is
 * invisible. Leaving the order alone makes an unsorted dataset look wrong on
 * screen, which is a bug report rather than a mystery.
 */
enum class ChartDataOrder {

    /** Draw in the order supplied. The default. */
    InputOrder,

    /** Sort ascending by the resolved x value before drawing. */
    SortedByX,
}
