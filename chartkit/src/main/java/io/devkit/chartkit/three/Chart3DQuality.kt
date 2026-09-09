package io.devkit.chartkit.three

/**
 * How finely a curved surface is approximated.
 *
 * ### Why a chart-wide setting rather than a pie option
 *
 * Nothing about this is specific to a pie. Any future 3D shape with a curve in
 * it — a cylinder, a torus, a lathed funnel — faces the same trade and should
 * face it with the same vocabulary, so the setting lives beside the camera and
 * the projection rather than in one chart's parameter list.
 *
 * ### What [Auto] actually does
 *
 * It is not a fixed segment count. The tessellator is given a *tolerance* — how
 * far a straight chord may fall short of the arc it replaces, in screen pixels
 * — and works back to a segment count from the radius the shape is drawn at. A
 * small chart therefore gets fewer segments than a large one without anybody
 * configuring it, which is the behaviour a fixed count cannot have.
 *
 * See [ArcTessellator3D.segmentsFor].
 */
enum class Chart3DQuality(
    /** The largest gap allowed between a chord and its arc, in screen pixels. */
    internal val tolerancePx: Double,
) {

    /** Half a pixel of error: invisible at any size, and the default. */
    Auto(0.5),

    /** Visibly faceted on a large chart. For a dense dashboard tile. */
    Low(2.0),

    /** A pixel and a half. Smooth at ordinary sizes. */
    Medium(1.5),

    /** A fifth of a pixel. For an export, or a chart that fills a tablet. */
    High(0.2),
}
