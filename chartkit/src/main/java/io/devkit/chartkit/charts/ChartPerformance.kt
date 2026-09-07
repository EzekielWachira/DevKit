package io.devkit.chartkit.charts

/**
 * Thresholds that keep a large dataset from becoming pathological.
 *
 * ChartKit 0.1 does not downsample and does not claim to render a million
 * points. What it does claim is that nothing in the engine degrades
 * *catastrophically* as the dataset grows — no composable per point, no path
 * rebuilt per frame, no linear scan per pointer move on sorted data. These
 * thresholds are where the remaining trade-offs are made explicit rather than
 * hidden.
 *
 * @param pointMarkerThreshold above this many points in a series,
 *   [io.devkit.chartkit.layer.line.PointMode.Auto] stops drawing markers.
 *   Beyond it markers merge into a band and cost a draw call each.
 * @param maxAnimatedPoints above this many points in total, a data change snaps
 *   instead of interpolating. Interpolating rebuilds the line's path on every
 *   frame of the transition, which is affordable for a few hundred points and
 *   not for tens of thousands — and the animation is the part worth losing.
 */
data class ChartPerformance(
    val pointMarkerThreshold: Int = 40,
    val maxAnimatedPoints: Int = 500,
) {
    init {
        require(pointMarkerThreshold >= 0) { "pointMarkerThreshold cannot be negative" }
        require(maxAnimatedPoints >= 0) { "maxAnimatedPoints cannot be negative" }
    }

    companion object {
        val Default: ChartPerformance = ChartPerformance()
    }
}
