package io.devkit.chartkit.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.devkit.chartkit.geometry.ChartInsets

/**
 * Keeps the plot areas of several charts lined up.
 *
 * ```kotlin
 * val group = rememberChartInteractionGroup()
 *
 * Column {
 *     CandlestickChart(..., viewportState = group.viewport, plotAlignment = group.alignment)
 *     VolumeChart(...,      viewportState = group.viewport, plotAlignment = group.alignment)
 * }
 * ```
 *
 * ```text
 * without                     with
 * 182 ┤▇▇▇▇▇▇▇▇▇▇▇▇▇▇        182 ┤▇▇▇▇▇▇▇▇▇▇▇
 *   4.1M ┤▇▇▇▇▇▇▇▇▇▇       4.1M ┤▇▇▇▇▇▇▇▇▇▇▇
 *        ↑ misaligned                       ↑ aligned
 * ```
 *
 * Two stacked charts whose value labels differ in width — "182" against
 * "4.1M" — get plot areas that start at different x. The reader then compares
 * two time axes that do not line up, which is precisely what a stacked
 * financial or monitoring dashboard exists to make possible.
 *
 * ### A measurement exchange, not a layout engine
 *
 * Each chart reports the gutters it *naturally* needs; the group publishes the
 * largest of each, and every chart pads its own gutter out to that. Nothing
 * here measures, arranges or draws — Compose still does all of it — and the
 * charts remain ordinary siblings that can be placed in any layout, wrapped in
 * cards, or separated by other content.
 *
 * ### One extra frame
 *
 * The first composition measures, the second aligns. That is inherent to
 * measuring something before agreeing on it, and it settles immediately —
 * there is no feedback loop, because a chart's *natural* gutter does not depend
 * on the padding added to it.
 */
@Stable
class ChartPlotAlignment internal constructor() {

    private val reported = mutableStateMapOf<Any, ChartInsets>()

    /** The gutters every member has agreed on, or zero before the first frame. */
    var insets: ChartInsets by mutableStateOf(ChartInsets.Zero)
        private set

    /** How many charts are participating. */
    val memberCount: Int get() = reported.size

    /**
     * Reports the gutters [key] needs without any alignment padding.
     *
     * Called by each chart on every layout. Keyed on an opaque per-chart
     * identity so a chart that leaves the composition can be forgotten and a
     * chart that resizes replaces its own entry rather than adding one.
     */
    internal fun report(key: Any, natural: ChartInsets) {
        val existing = reported[key]
        if (existing != null && existing.approximatelyEquals(natural)) return
        reported[key] = natural
        recompute()
    }

    /** Drops [key]'s contribution when its chart leaves the composition. */
    internal fun forget(key: Any) {
        if (reported.remove(key) != null) recompute()
    }

    /** The extra padding [natural] needs to reach the agreed gutters. */
    internal fun extraFor(natural: ChartInsets): ChartInsets = ChartInsets(
        left = (insets.left - natural.left).coerceAtLeast(0f),
        top = (insets.top - natural.top).coerceAtLeast(0f),
        right = (insets.right - natural.right).coerceAtLeast(0f),
        bottom = (insets.bottom - natural.bottom).coerceAtLeast(0f),
    )

    private fun recompute() {
        val values = reported.values
        insets = if (values.isEmpty()) {
            ChartInsets.Zero
        } else {
            ChartInsets(
                left = values.maxOf { it.left },
                top = values.maxOf { it.top },
                right = values.maxOf { it.right },
                bottom = values.maxOf { it.bottom },
            )
        }
    }
}

/** Remembers a [ChartPlotAlignment]. */
@Composable
fun rememberChartPlotAlignment(): ChartPlotAlignment = remember { ChartPlotAlignment() }

/**
 * True when two sets of gutters are the same to within a pixel.
 *
 * Text measurement is not exactly stable across recompositions — a rounding
 * difference of a fraction of a pixel is normal — and a strict comparison would
 * make every chart re-report on every frame, which would republish the maximum
 * and recompose the whole group.
 */
private fun ChartInsets.approximatelyEquals(other: ChartInsets): Boolean =
    kotlin.math.abs(left - other.left) < TOLERANCE &&
        kotlin.math.abs(top - other.top) < TOLERANCE &&
        kotlin.math.abs(right - other.right) < TOLERANCE &&
        kotlin.math.abs(bottom - other.bottom) < TOLERANCE

private const val TOLERANCE = 0.5f
