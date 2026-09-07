package io.devkit.chartkit.interaction

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.ChartRect
import kotlin.math.abs

/**
 * What a chart does in response to the gestures the coordinator recognises.
 *
 * Deliberately expressed as *intent* rather than as pointer events: "pan by a
 * fraction of the plot", not "the finger moved 37 pixels". That is what lets
 * the coordinator stay free of chart geometry, and what stops each chart type
 * reimplementing the same conversion slightly differently.
 *
 * @param onPan called with the drag as a signed fraction of the plot's domain
 *   extent, in the direction the finger moved. The chart converts it into a
 *   viewport move — which means multiplying by the current viewport width and
 *   inverting, because content follows the finger.
 * @param onZoom called with a multiplicative factor and the focal point as a
 *   fraction of the visible plot, so the chart can keep the value under the
 *   fingers where it was.
 */
internal class ChartGestureCallbacks(
    val onTap: (ChartOffset) -> Unit = {},
    val onScrubStart: (ChartOffset) -> Unit = {},
    val onScrub: (ChartOffset) -> Unit = {},
    val onScrubEnd: () -> Unit = {},
    val onPan: (deltaFraction: Double) -> Unit = {},
    val onZoom: (factor: Double, focusFraction: Double) -> Unit = { _, _ -> },
    val onRangeStart: (ChartOffset) -> Unit = {},
    val onRangeChange: (ChartOffset) -> Unit = {},
    val onRangeEnd: () -> Unit = {},
)

/** What the current gesture turned out to be. */
private enum class GestureMode { Undecided, Scrub, Pan, Range, Zoom }

/**
 * One pointer handler for every chart gesture ChartKit supports.
 *
 * ### Why one, and not one per feature
 *
 * Tap, scrub, pan, pinch and range selection all arrive on the same pointer
 * stream. Implemented as five `pointerInput` modifiers they would each claim
 * the stream on their own terms, and the result is the familiar mess: a pinch
 * that also scrubs, a scrub that fights the parent's scroll, a tap swallowed by
 * whichever detector ran first. Arbitrating once, here, is what makes the
 * behaviour describable.
 *
 * ### Priority
 *
 * ```text
 * two fingers            → zoom (and pan, from the centroid)
 * one finger past slop   → whatever ChartInteraction.dragMode says
 * up without passing slop → tap
 * ```
 *
 * A second finger wins outright: pinching is unambiguous, and a scrub that
 * continued underneath it would move the selection while the reader was
 * zooming. When the second finger lifts, a chart that can pan keeps panning
 * with the remaining one rather than snapping back to scrubbing mid-gesture.
 *
 * ### Slop, and not stealing the parent's scroll
 *
 * Slop is measured **along the domain axis only** — horizontally for a vertical
 * chart. A vertical drag therefore never passes slop, is never consumed, and
 * the surrounding scrollable keeps it. That is what lets a chart sit in a
 * scrolling screen without trapping the finger.
 *
 * @param plotArea the region gestures are interpreted within; taps outside it
 *   are reported so the chart can clear its selection.
 * @param isZoomedIn read at the moment a drag begins, for
 *   [ChartDragMode.PanWhenZoomed].
 */
internal fun Modifier.chartGestures(
    key: Any?,
    interaction: ChartInteraction,
    orientation: ChartOrientation,
    plotArea: ChartRect,
    isZoomedIn: () -> Boolean,
    callbacks: ChartGestureCallbacks,
): Modifier {
    if (interaction.isInert || plotArea.isEmpty) return this

    return pointerInput(key, interaction, orientation) {
        val touchSlop = viewConfiguration.touchSlop
        val domainExtent = if (orientation.isVertical) plotArea.width else plotArea.height
        val domainOrigin = if (orientation.isVertical) plotArea.left else plotArea.top

        fun along(change: PointerInputChange): Float =
            if (orientation.isVertical) change.position.x else change.position.y

        fun focusFraction(x: Float, y: Float): Double {
            val position = if (orientation.isVertical) x else y
            if (domainExtent <= 0f) return 0.5
            return ((position - domainOrigin) / domainExtent).toDouble().coerceIn(0.0, 1.0)
        }

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var mode = GestureMode.Undecided
            var pastSlop = false
            var lastAlong = along(down)

            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break

                // ---- two fingers: zoom, and pan from the centroid ----------
                if (pressed.size >= 2 && interaction.zoomEnabled) {
                    if (mode != GestureMode.Zoom) {
                        // Abandon whatever the single finger was doing, so a
                        // scrub selection or a half-drawn range does not
                        // survive underneath the pinch.
                        when (mode) {
                            GestureMode.Scrub -> callbacks.onScrubEnd()
                            GestureMode.Range -> callbacks.onRangeEnd()
                            else -> Unit
                        }
                        mode = GestureMode.Zoom
                    }
                    pastSlop = true

                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    val centroid = event.calculateCentroid(useCurrent = true)

                    if (zoom.isFinite() && zoom > 0f && abs(zoom - 1f) > 1e-4f) {
                        callbacks.onZoom(
                            zoom.toDouble(),
                            focusFraction(centroid.x, centroid.y),
                        )
                    }
                    val panAlong = if (orientation.isVertical) pan.x else pan.y
                    if (domainExtent > 0f && abs(panAlong) > 0f) {
                        callbacks.onPan((panAlong / domainExtent).toDouble())
                    }
                    event.changes.forEach { it.consume() }
                    lastAlong = along(pressed.first())
                    continue
                }

                val change = pressed.first()

                // ---- one finger left after a pinch: keep panning -----------
                if (mode == GestureMode.Zoom) {
                    if (interaction.movesViewport && domainExtent > 0f) {
                        val delta = along(change) - lastAlong
                        if (delta != 0f) callbacks.onPan((delta / domainExtent).toDouble())
                        change.consume()
                    }
                    lastAlong = along(change)
                    continue
                }

                // ---- one finger: decide what the drag means ----------------
                if (!pastSlop) {
                    val travelled = abs(along(change) - along(down))
                    if (travelled <= touchSlop) continue
                    pastSlop = true
                    mode = when (interaction.dragMode) {
                        ChartDragMode.None -> GestureMode.Undecided
                        ChartDragMode.Scrub -> GestureMode.Scrub
                        ChartDragMode.Pan -> GestureMode.Pan
                        ChartDragMode.Range -> GestureMode.Range
                        // Resolved once, when the drag starts, rather than per
                        // frame: a drag that changed meaning halfway through
                        // because a zoom limit was reached would be unusable.
                        ChartDragMode.PanWhenZoomed ->
                            if (isZoomedIn()) GestureMode.Pan else GestureMode.Scrub
                    }
                    when (mode) {
                        GestureMode.Scrub -> callbacks.onScrubStart(down.chartOffset())
                        GestureMode.Range -> callbacks.onRangeStart(down.chartOffset())
                        else -> Unit
                    }
                }

                when (mode) {
                    GestureMode.Scrub -> {
                        callbacks.onScrub(change.chartOffset())
                        change.consume()
                    }
                    GestureMode.Pan -> {
                        if (domainExtent > 0f) {
                            val delta = along(change) - lastAlong
                            if (delta != 0f) callbacks.onPan((delta / domainExtent).toDouble())
                        }
                        change.consume()
                    }
                    GestureMode.Range -> {
                        callbacks.onRangeChange(change.chartOffset())
                        change.consume()
                    }
                    else -> Unit
                }
                lastAlong = along(change)
            }

            // A press that never travelled is a tap, whatever mode was
            // configured — selection stays available in every interaction mode.
            if (!pastSlop && interaction.tapSelects) {
                callbacks.onTap(down.chartOffset())
            }
            when (mode) {
                GestureMode.Scrub -> callbacks.onScrubEnd()
                GestureMode.Range -> callbacks.onRangeEnd()
                else -> Unit
            }
        }
    }
}

private fun PointerInputChange.chartOffset(): ChartOffset =
    ChartOffset(position.x, position.y)
