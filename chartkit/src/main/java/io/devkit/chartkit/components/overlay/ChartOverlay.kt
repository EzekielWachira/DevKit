package io.devkit.chartkit.components.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.devkit.chartkit.geometry.ChartRect
import kotlin.math.roundToInt

/**
 * Where an overlay would rather sit relative to its anchor.
 *
 * A preference, not an instruction: [resolveOverlayOffset] moves the overlay
 * when the preferred side has no room. An overlay that insisted would leave the
 * chart at exactly the positions a reader cares about most — the first point,
 * the last point, the peak.
 */
enum class ChartOverlayPlacement {
    /** Above if it fits, below if it does not. The usual choice. */
    Auto,
    Above,
    Below,
    Start,
    End,
}

/**
 * Positions arbitrary Compose content against a point inside a chart.
 *
 * ### One engine, every chart
 *
 * Tooltips for lines, bars, pie slices, radial bars and crosshairs all come
 * through here. Chart-specific code supplies an **anchor** and the content;
 * none of it knows how an overlay is measured, flipped or clamped. That is what
 * stops six chart types growing six subtly different tooltip placements, and
 * what makes a custom Compose tooltip behave identically to the built-in one.
 *
 * ### Measured, not offset by a constant
 *
 * The content is measured first and positioned second. A tooltip nudged "16dp
 * up and to the right" is off-screen for any selection near the top-right
 * corner — which on a rising series is the most interesting point on it.
 *
 * @param anchor the point in the chart the content should indicate.
 * @param bounds the region the content must stay inside.
 * @param gap the space left between the anchor and the content.
 */
@Composable
internal fun ChartOverlay(
    anchor: io.devkit.chartkit.geometry.ChartOffset,
    bounds: ChartRect,
    gap: Float,
    placement: ChartOverlayPlacement = ChartOverlayPlacement.Auto,
    content: @Composable () -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val offset = remember(anchor, bounds, size, gap, placement) {
        resolveOverlayOffset(
            anchorX = anchor.x,
            anchorY = anchor.y,
            width = size.width,
            height = size.height,
            bounds = bounds,
            gap = gap,
            placement = placement,
        )
    }
    Box(
        Modifier
            // Unbounded, so the content measures at its natural size rather
            // than being squeezed by whatever the chart's own constraints are.
            .wrapContentSize(align = Alignment.TopStart, unbounded = true)
            .offset { offset }
            .onSizeChanged { size = it },
    ) {
        content()
    }
}

/**
 * The top-left corner an overlay of `width` × `height` should occupy.
 *
 * Pure, so the placement rules are testable without composing anything — which
 * matters, because the interesting cases are the corners and the oversized
 * content, and those are tedious to reach through a UI test.
 *
 * Resolution order: honour the preference if it fits, take the opposite side if
 * it does not, then clamp into [bounds]. The cross-axis is always centred on
 * the anchor and then pulled back inside.
 */
internal fun resolveOverlayOffset(
    anchorX: Float,
    anchorY: Float,
    width: Int,
    height: Int,
    bounds: ChartRect,
    gap: Float,
    placement: ChartOverlayPlacement = ChartOverlayPlacement.Auto,
): IntOffset {
    if (!anchorX.isFinite() || !anchorY.isFinite()) return IntOffset(0, 0)

    /** `coerceIn` throws when the range is empty, which oversized content makes. */
    fun clamp(value: Float, min: Float, max: Float): Float =
        value.coerceIn(min, max.coerceAtLeast(min))

    val above = anchorY - height - gap
    val below = anchorY + gap
    val start = anchorX - width - gap
    val end = anchorX + gap

    return when (placement) {
        ChartOverlayPlacement.Start, ChartOverlayPlacement.End -> {
            val preferStart = placement == ChartOverlayPlacement.Start
            val x = when {
                preferStart && start >= bounds.left -> start
                !preferStart && end + width <= bounds.right -> end
                preferStart -> end
                else -> start
            }
            IntOffset(
                clamp(x, bounds.left, bounds.right - width).roundToInt(),
                clamp(anchorY - height / 2f, bounds.top, bounds.bottom - height).roundToInt(),
            )
        }

        else -> {
            val preferAbove = placement != ChartOverlayPlacement.Below
            val y = when {
                preferAbove && above >= bounds.top -> above
                !preferAbove && below + height <= bounds.bottom -> below
                preferAbove && below + height <= bounds.bottom -> below
                !preferAbove && above >= bounds.top -> above
                else -> clamp(above, bounds.top, bounds.bottom - height)
            }
            IntOffset(
                clamp(anchorX - width / 2f, bounds.left, bounds.right - width).roundToInt(),
                clamp(y, bounds.top, bounds.bottom - height).roundToInt(),
            )
        }
    }
}
