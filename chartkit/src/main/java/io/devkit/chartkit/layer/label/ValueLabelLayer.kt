package io.devkit.chartkit.layer.label

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.drawText
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.BarSlice
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartRenderContext

/**
 * Numbers drawn on the data itself.
 *
 * Off by default. Value labels are excellent on a six-bar chart and unreadable
 * on a sixty-bar one, and a library that turns them on for everybody produces
 * the second case far more often than the first — so the caller opts in, and
 * even then labels that would collide are dropped.
 *
 * Collision handling is deliberately conservative: a label is skipped when its
 * measured box would overlap one already placed, so what survives is always
 * legible. Nothing is shrunk, rotated or ellipsised, because a chart of
 * half-readable numbers is worse than a chart of fewer whole ones.
 */
internal class ValueLabelLayer(
    override val id: String,
    private val anchors: List<ValueLabelAnchor>,
    private val formatter: ChartValueFormatter,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = anchors.map { it.seriesId }.distinct()

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        if (anchors.isEmpty() || context.reveal < 1f) return
        val plot = context.coordinates.plotArea
        if (plot.isEmpty) return

        val style = context.typography.valueLabel.copy(color = context.colors.valueLabel)
        val padding = context.px(context.dimensions.labelPadding)
        val placed = ArrayList<FloatArray>(anchors.size)

        anchors.forEach { anchor ->
            val value = anchor.value ?: return@forEach
            val text = formatter.format(value)
            if (text.isEmpty()) return@forEach
            val measured = context.textMeasurer.measure(text, style)
            val width = measured.size.width.toFloat()
            val height = measured.size.height.toFloat()

            val left: Float
            val top: Float
            when (anchor.placement) {
                LabelPlacement.Above -> {
                    left = anchor.position.x - width / 2f
                    top = anchor.position.y - height - padding
                }
                LabelPlacement.Below -> {
                    left = anchor.position.x - width / 2f
                    top = anchor.position.y + padding
                }
                LabelPlacement.Trailing -> {
                    left = anchor.position.x + padding
                    top = anchor.position.y - height / 2f
                }
                LabelPlacement.Leading -> {
                    left = anchor.position.x - width - padding
                    top = anchor.position.y - height / 2f
                }
            }
            val box = floatArrayOf(left, top, left + width, top + height)

            if (box[0] < plot.left || box[2] > plot.right ||
                box[1] < plot.top || box[3] > plot.bottom
            ) {
                return@forEach
            }
            if (placed.any { it.overlaps(box) }) return@forEach

            placed += box
            scope.drawText(measured, topLeft = Offset(left, top))
        }
    }

    private fun FloatArray.overlaps(other: FloatArray): Boolean =
        this[0] < other[2] && other[0] < this[2] && this[1] < other[3] && other[1] < this[3]
}

/** Which side of its anchor a label sits on. */
internal enum class LabelPlacement { Above, Below, Leading, Trailing }

/** Where one value label wants to sit, and what it says. */
internal data class ValueLabelAnchor(
    val seriesId: String,
    val position: ChartOffset,
    val value: Double?,
    val placement: LabelPlacement,
)

/** Label anchors just above each bar's far end. */
internal fun barLabelAnchors(
    slices: List<BarSlice>,
    seriesIds: List<String>,
    vertical: Boolean,
): List<ValueLabelAnchor> = slices.mapNotNull { slice ->
    val seriesId = seriesIds.getOrNull(slice.seriesIndex) ?: return@mapNotNull null
    val rect = slice.rect
    // Outside the bar's far end, in the direction the bar points, so a label
    // never sits on top of the fill it describes.
    val positive = slice.value >= 0.0
    val position = if (vertical) {
        ChartOffset(rect.centerX, if (positive) rect.top else rect.bottom)
    } else {
        ChartOffset(if (positive) rect.right else rect.left, rect.centerY)
    }
    val placement = when {
        vertical && positive -> LabelPlacement.Above
        vertical -> LabelPlacement.Below
        positive -> LabelPlacement.Trailing
        else -> LabelPlacement.Leading
    }
    ValueLabelAnchor(seriesId, position, slice.value, placement)
}
