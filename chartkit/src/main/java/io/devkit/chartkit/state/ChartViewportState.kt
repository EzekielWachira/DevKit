package io.devkit.chartkit.state

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.viewport.ChartViewport
import io.devkit.chartkit.viewport.ChartZoomLimits

/**
 * The visible window of a zoomable chart, hoisted.
 *
 * ```kotlin
 * val viewport = rememberChartViewportState()
 *
 * LineChart(data = readings, x = { it.at }, y = { it.value }, viewportState = viewport)
 * Button(onClick = { viewport.reset() }) { Text("Reset zoom") }
 * ```
 *
 * A chart with no zoom configured never needs one: the charts default to
 * [ChartInteraction.Default], which does not zoom, and only create a viewport
 * state internally when one is not supplied. Simple charts stay simple.
 *
 * The state stores the window as a fraction of the domain — see [ChartViewport]
 * for why — and republishes it as logical values through [visibleDomain] and
 * [visibleCategoryRange], so a caller reading "which dates am I showing" gets
 * dates rather than fractions.
 */
@Stable
class ChartViewportState internal constructor(
    initialViewport: ChartViewport = ChartViewport.Full,
    limits: ChartZoomLimits = ChartZoomLimits.Default,
) {

    /**
     * The window currently displayed.
     *
     * Settable, so a caller can move it directly. The helpers below —
     * [zoomBy], [panBy], [reset], [showTrailing] — exist because they clamp,
     * and assigning a window wider than the domain is the one mistake worth
     * being saved from.
     */
    var viewport: ChartViewport by mutableStateOf(initialViewport)

    /** How far this chart may zoom in. */
    var limits: ChartZoomLimits by mutableStateOf(limits)
        internal set

    /**
     * The full domain of the data, published by the chart on every layout.
     *
     * Kept here so a caller can turn the fractional window into real values
     * without holding the data themselves. `null` before the first measurement,
     * or on a category axis, where [visibleCategoryRange] is the right question.
     */
    var fullDomain: NumericDomain? by mutableStateOf(null)
        internal set

    /** The number of category bands, when the domain is banded. */
    var categoryCount: Int by mutableIntStateOf(0)
        internal set

    /** `1` when fully zoomed out. */
    val zoom: Double get() = viewport.zoom

    /** True when the whole domain is on screen — nothing to reset or pan. */
    val isFullyZoomedOut: Boolean get() = viewport.isFullyZoomedOut

    /** The visible interval in domain units, or `null` on a category axis. */
    val visibleDomain: NumericDomain?
        get() = fullDomain?.let { viewport.visibleDomain(it) }

    /** The visible band indices, or an empty range on a continuous axis. */
    val visibleCategoryRange: IntRange
        get() = if (categoryCount > 0) viewport.visibleCategoryRange(categoryCount) else IntRange.EMPTY

    /**
     * Zooms by [factor] about [focus], a fraction of the current window.
     *
     * `factor > 1` zooms in. The gesture coordinator calls this directly on
     * every pinch frame, so it does no animation of its own — a zoom that
     * eased towards its target would lag behind the fingers driving it.
     */
    fun zoomBy(factor: Double, focus: Double = 0.5) {
        viewport = viewport.zoomedBy(factor, focus, limits.maxZoom)
    }

    /** Pans by [delta] fractions of the full domain, clamped to it. */
    fun panBy(delta: Double) {
        viewport = viewport.pannedBy(delta)
    }

    /** Shows the whole domain again. */
    fun reset() {
        viewport = ChartViewport.Full
    }

    /** Shows the last [fraction] of the domain — "the most recent 10%". */
    fun showTrailing(fraction: Double) {
        viewport = ChartViewport.trailing(fraction)
    }

    /**
     * Eases to [target] over [animation]'s data-change duration.
     *
     * For programmatic moves only — "show the last 30 days", "reset", "focus
     * the selected range" — where a jump would lose the reader's place. Gesture
     * driven zoom and pan deliberately do not animate.
     */
    suspend fun animateTo(target: ChartViewport, animation: ChartAnimation = ChartAnimation.Default) {
        if (!animation.enabled) {
            viewport = target
            return
        }
        val from = viewport
        val progress = Animatable(0f)
        progress.animateTo(1f, animation.dataChangeSpec()) {
            val fraction = value.toDouble()
            val start = from.start + (target.start - from.start) * fraction
            val end = from.end + (target.end - from.end) * fraction
            if (end - start > ChartViewport.MIN_WIDTH) {
                viewport = ChartViewport(start, end)
            }
        }
        viewport = target
    }

    /** Eases back to the whole domain. */
    suspend fun animateToFull(animation: ChartAnimation = ChartAnimation.Default) {
        animateTo(ChartViewport.Full, animation)
    }
}

/**
 * Remembers a [ChartViewportState].
 *
 * @param initialViewport the window to start at. [ChartViewport.trailing] is
 *   the usual choice for a long series whose recent end matters most.
 */
@Composable
fun rememberChartViewportState(
    initialViewport: ChartViewport = ChartViewport.Full,
    limits: ChartZoomLimits = ChartZoomLimits.Default,
): ChartViewportState = remember { ChartViewportState(initialViewport, limits) }

/**
 * A [ChartViewportState] that survives configuration changes and process death.
 *
 * The window is two numbers, so unlike a selection it genuinely can be saved —
 * and a reader who rotated the device while zoomed into March would be
 * surprised to find themselves back at the whole year.
 */
@Composable
fun rememberSaveableChartViewportState(
    initialViewport: ChartViewport = ChartViewport.Full,
    limits: ChartZoomLimits = ChartZoomLimits.Default,
): ChartViewportState = rememberSaveable(
    saver = listSaver(
        save = { listOf(it.viewport.start, it.viewport.end) },
        restore = { saved ->
            ChartViewportState(
                initialViewport = ChartViewport.between(saved[0], saved[1]),
                limits = limits,
            )
        },
    ),
) {
    ChartViewportState(initialViewport, limits)
}
