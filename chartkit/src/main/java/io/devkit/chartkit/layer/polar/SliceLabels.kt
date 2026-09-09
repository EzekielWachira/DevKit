package io.devkit.chartkit.layer.polar

import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.layer.label.LabelPlacer

/**
 * Where an outside slice label goes, once something else has decided which
 * slice it belongs to and where that slice's rim is.
 *
 * ### Shared between the flat pie and the 3D one, deliberately
 *
 * The two charts disagree about only one thing: how the rim point is found. A
 * flat pie reads it off its own circle; a 3D pie projects it out of the scene.
 * Everything after that — which side of the chart the text hangs on, how far
 * past the leader line it sits, whether it fits inside the plot and whether it
 * collides with a label already placed — is the same decision, and a second
 * copy of it is how two charts over the same data end up dropping different
 * labels.
 *
 * The collision rule itself lives in [LabelPlacer], which the bar chart's value
 * labels and the 3D column chart's also use. One rule, four callers.
 */
internal fun placeOutsideLabel(
    placer: LabelPlacer,
    /** Where the leader line ends, just outside the rim. */
    to: ChartOffset,
    /** True when the slice is on the right-hand half, so the text hangs right. */
    rightHalf: Boolean,
    gap: Float,
    width: Float,
    height: Float,
): ChartOffset? {
    val left = if (rightHalf) to.x + gap else to.x - gap - width
    val top = to.y - height / 2f
    if (!placer.place(left, top, width, height)) return null
    return ChartOffset(left, top)
}
