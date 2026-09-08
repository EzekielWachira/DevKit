package io.devkit.chartkit.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.devkit.chartkit.model.ChartX

/**
 * A domain position shared between charts.
 *
 * ```kotlin
 * val group = rememberChartInteractionGroup()
 *
 * CandlestickChart(..., viewportState = group.viewport, sharedCrosshair = group.crosshair)
 * VolumeChart(...,      viewportState = group.viewport, sharedCrosshair = group.crosshair)
 * ```
 *
 * Dragging across either chart moves the guide on both.
 *
 * ### A domain value, not a pixel and not a fraction
 *
 * What is shared is the **logical x** — a date, a category, a number. Two
 * charts in a dashboard rarely hold the same dataset: one may cover a longer
 * period, or have gaps the other does not. Sharing a pixel would align them by
 * accident of layout, and sharing a fraction of each chart's own domain would
 * put the guides on different dates whenever the domains differ. Each chart
 * resolves the value through its own scale, so the guides land on the same
 * moment however differently the two charts are laid out.
 *
 * ### No callback bouncing
 *
 * One piece of state is the source of truth and every chart reads it. A chart
 * writes only in response to a **gesture of its own**, never in response to
 * reading a change — which is what makes an update loop structurally
 * impossible rather than merely unlikely. [source] records which chart
 * published, so a chart can tell its own publication from another's.
 */
@Stable
class ChartSharedCrosshairState internal constructor() {

    /** The shared domain position, or `null` when no chart is being scrubbed. */
    var domain: ChartX? by mutableStateOf(null)
        private set

    /**
     * The chart that last published, as an opaque identity.
     *
     * Exposed internally so a chart can recognise its own publication and leave
     * its selection alone; a chart driven by another's gesture selects its
     * nearest point at the shared domain instead.
     */
    internal var source: Any? by mutableStateOf(null)
        private set

    /** True while a chart is publishing a position. */
    val isActive: Boolean get() = domain != null

    /**
     * Publishes [domain] as the shared position.
     *
     * Called by a chart from its own gesture handling, and callable directly to
     * drive a group from elsewhere — a list selection, a playback cursor.
     */
    fun publish(domain: ChartX?, source: Any? = null) {
        this.domain = domain
        this.source = source
    }

    /** Drops the shared position, clearing every subscribed chart's guide. */
    fun clear() {
        domain = null
        source = null
    }
}

/** Remembers a [ChartSharedCrosshairState]. */
@Composable
fun rememberChartSharedCrosshairState(): ChartSharedCrosshairState =
    remember { ChartSharedCrosshairState() }

/**
 * A viewport and a crosshair position, shared by several charts.
 *
 * A convenience over the two states rather than a replacement for them:
 * [viewport] and [crosshair] are the same hoistable objects a single chart
 * uses, and either can still be created and passed on its own. What the group
 * adds is one call instead of two, and a name for the thing a dashboard's
 * charts have in common.
 *
 * ```kotlin
 * val group = rememberChartInteractionGroup()
 *
 * Column {
 *     CandlestickChart(
 *         data = candles, x = { it.time },
 *         open = { it.open }, high = { it.high }, low = { it.low }, close = { it.close },
 *         viewportState = group.viewport,
 *         sharedCrosshair = group.crosshair,
 *     )
 *     VolumeChart(
 *         data = candles, x = { it.time }, volume = { it.volume },
 *         viewportState = group.viewport,
 *         sharedCrosshair = group.crosshair,
 *     )
 * }
 * ```
 *
 * Selection is deliberately **not** in the group. Two charts over different
 * quantities have different y domains, and a shared selected *point* would mean
 * asserting that a price of 182 and a volume of 4.1 million are the same
 * selection. What they genuinely share is the x, and that is what [crosshair]
 * carries; each chart's own [ChartState] keeps its own selected point.
 */
@Stable
class ChartInteractionGroup internal constructor(
    val viewport: ChartViewportState,
    val crosshair: ChartSharedCrosshairState,
)

/** Remembers a [ChartInteractionGroup]. */
@Composable
fun rememberChartInteractionGroup(
    viewport: ChartViewportState = rememberChartViewportState(),
    crosshair: ChartSharedCrosshairState = rememberChartSharedCrosshairState(),
): ChartInteractionGroup = remember(viewport, crosshair) {
    ChartInteractionGroup(viewport, crosshair)
}
