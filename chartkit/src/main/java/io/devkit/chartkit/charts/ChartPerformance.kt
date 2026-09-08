package io.devkit.chartkit.charts

import io.devkit.chartkit.data.ChartDownsampling

/**
 * Thresholds that keep a large dataset from becoming pathological.
 *
 * Four mechanisms, each with a threshold, none of them adaptive: markers stop
 * being drawn, animation stops interpolating, off-screen data stops being
 * processed, and dense data is sampled down to what the display can show. All
 * four change what appears on screen, so all four are stated rather than
 * hidden, and all four can be turned off.
 *
 * ```text
 * source data
 *     ↓  cull        keep the viewport's window, plus overscan
 *     ↓  downsample   reduce to roughly what the plot's width can show
 *     ↓  geometry     build paths for what survives
 * ```
 *
 * Selection, tooltips and accessibility continue to work against the **source**
 * data throughout. Sampling changes what is drawn; it does not change what
 * exists, and a scrub across a sampled line still selects the observation
 * nearest the finger.
 *
 * @param pointMarkerThreshold above this many points in a series,
 *   [io.devkit.chartkit.layer.line.PointMode.Auto] stops drawing markers.
 *   Beyond it markers merge into a band and cost a draw call each.
 * @param maxAnimatedPoints above this many points in total, a data change snaps
 *   instead of interpolating. Interpolating rebuilds the line's path on every
 *   frame of the transition, which is affordable for a few hundred points and
 *   not for tens of thousands — and the animation is the part worth losing.
 * @param downsampling how a series denser than the display is reduced. See
 *   [ChartDownsampling]; the default samples only when the data actually
 *   outruns the plot's width.
 * @param cullToViewport whether off-screen data is skipped when the chart is
 *   zoomed in. On by default: zooming into a week of a five-year series leaves
 *   99% of the points outside the plot, and building geometry for them costs a
 *   path the renderer then clips away.
 * @param overscanFraction how much beyond the viewport is kept, as a fraction
 *   of the visible window. Enough that a pan does not expose an unbuilt edge
 *   between frames.
 */
data class ChartPerformance(
    val pointMarkerThreshold: Int = 40,
    val maxAnimatedPoints: Int = 500,
    val downsampling: ChartDownsampling = ChartDownsampling.Default,
    val cullToViewport: Boolean = true,
    val overscanFraction: Float = 0.15f,
) {
    init {
        require(pointMarkerThreshold >= 0) { "pointMarkerThreshold cannot be negative" }
        require(maxAnimatedPoints >= 0) { "maxAnimatedPoints cannot be negative" }
        require(overscanFraction >= 0f && overscanFraction.isFinite()) {
            "overscanFraction must be a finite value >= 0, was \$overscanFraction"
        }
    }

    companion object {

        /**
         * Sample when the data outruns the display, cull to the viewport, keep
         * markers and animation for small datasets.
         *
         * The thresholds are the whole of the automatic behaviour, and they are
         * stated rather than adaptive:
         *
         * ```text
         * ≤ 40 points     markers drawn
         * ≤ 500 points    a data change animates
         * > ~2 per pixel  LTTB sampling to the plot's width
         * zoomed in       only the visible window plus 15% is processed
         * ```
         */
        val Default: ChartPerformance = ChartPerformance()

        /**
         * Draw every point, cull nothing, sample nothing.
         *
         * For a chart where each observation must be individually present — a
         * scatter of forty measurements, a printed figure — and for verifying
         * that sampling is not what is causing a difference you are looking at.
         */
        val Exact: ChartPerformance = ChartPerformance(
            downsampling = ChartDownsampling.None,
            cullToViewport = false,
        )

        /**
         * For datasets in the tens of thousands and beyond: markers off,
         * animation off, envelope-preserving sampling.
         *
         * [io.devkit.chartkit.data.MinMaxDownsampler] rather than LTTB, because
         * at this size the reader is looking for spikes, and a min/max pass
         * cannot drop one.
         */
        val Dense: ChartPerformance = ChartPerformance(
            pointMarkerThreshold = 0,
            maxAnimatedPoints = 0,
            downsampling = ChartDownsampling.MinMax(2_000),
        )
    }
}
