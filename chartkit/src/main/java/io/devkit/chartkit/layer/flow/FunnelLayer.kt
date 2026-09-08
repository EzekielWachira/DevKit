package io.devkit.chartkit.layer.flow

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.layer.hierarchy.percentage
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartX
import io.devkit.chartkit.transform.FunnelStage

/** What a funnel writes on a stage wide enough for it. */
enum class FunnelLabels {
    None,

    /** The stage's name. */
    Label,

    /** Name and count. The default. */
    LabelAndValue,

    /** Name, count and its share of the first stage. */
    LabelAndPercentage,

    /** Name, count and the conversion from the previous stage. */
    LabelAndConversion,
}

/** One stage's band, in pixels. */
internal class FunnelBand(
    val stage: FunnelStage,
    /** The full band, for hit testing and labelling. */
    val bounds: ChartRect,
    /** The trapezoid actually painted, narrowing toward the next stage. */
    val shape: Path,
)

/**
 * Stage-by-stage drop-off.
 *
 * ### A trapezoid, not a bar
 *
 * Each stage narrows toward the next one's width, so the *slope* between two
 * bands is the drop-off. A stack of rectangles would show the same numbers and
 * hide the thing a funnel exists to show, which is where people are lost.
 *
 * ### Increases are drawn honestly
 *
 * A stage larger than the one before it widens. Real funnels do this — a stage
 * counted from a different source, a re-entry, a cohort that grew — and a
 * renderer that clamped it would report a loss where there was a gain. See
 * [io.devkit.chartkit.transform.FunnelTransform] for the arithmetic.
 */
@Suppress("LongParameterList")
internal class FunnelLayer(
    override val id: String,
    private val bands: List<FunnelBand>,
    private val seriesId: String,
    private val seriesName: String,
    private val valueFormatter: ChartValueFormatter,
    private val labels: FunnelLabels,
    private val colorOverrideOf: ((FunnelStage) -> Int?)? = null,
    private val orientation: ChartOrientation = ChartOrientation.Vertical,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        if (bands.isEmpty()) return
        val reveal = context.reveal.coerceIn(0f, 1f)
        val plot = context.planar.contentBounds

        bands.forEachIndexed { index, band ->
            // The reveal fills the funnel stage by stage rather than fading the
            // whole thing in: a funnel is read top to bottom, and that is the
            // order the numbers arrive in.
            val stageProgress = ((reveal * bands.size) - index).coerceIn(0f, 1f)
            if (stageProgress <= 0f) return@forEachIndexed

            val colour = colorOverrideOf?.invoke(band.stage)?.let { Color(it) }
                ?: context.colors.seriesColor(index)
            scope.clipToProgress(band.bounds, stageProgress, orientation) {
                drawPath(band.shape, colour)
            }

            if (isSelected(band, context)) {
                scope.drawPath(band.shape, context.colors.selectionHighlight)
                scope.drawPath(
                    path = band.shape,
                    color = context.colors.selectionGuide,
                    style = Stroke(width = context.px(context.dimensions.selectionGuideWidth) * 2f),
                )
            }
        }

        if (reveal >= 1f && labels != FunnelLabels.None) {
            bands.forEach { drawStageLabel(scope, context, it, plot) }
        }
    }

    private fun drawStageLabel(
        scope: DrawScope,
        context: ChartRenderContext,
        band: FunnelBand,
        plot: ChartRect,
    ) {
        val text = labelFor(band.stage) ?: return
        val style = context.typography.nodeLabel.copy(color = context.colors.flow.label)
        val layout = context.textMeasurer.measure(text, style, maxLines = 2)
        if (layout.size.height > band.bounds.height) return
        val left = (band.bounds.centerX - layout.size.width / 2f)
            .coerceIn(plot.left, (plot.right - layout.size.width).coerceAtLeast(plot.left))
        scope.drawText(
            textLayoutResult = layout,
            topLeft = Offset(left, band.bounds.centerY - layout.size.height / 2f),
        )
    }

    private fun labelFor(stage: FunnelStage): String? = when (labels) {
        FunnelLabels.None -> null
        FunnelLabels.Label -> stage.label
        FunnelLabels.LabelAndValue -> "${stage.label}  ${valueFormatter.format(stage.value)}"
        FunnelLabels.LabelAndPercentage ->
            "${stage.label}  ${valueFormatter.format(stage.value)}  " +
                percentage(stage.fractionOfFirst)

        FunnelLabels.LabelAndConversion -> {
            val conversion = stage.conversionFromPrevious
            if (conversion == null) {
                "${stage.label}  ${valueFormatter.format(stage.value)}"
            } else {
                "${stage.label}  ${valueFormatter.format(stage.value)}  " +
                    "${percentage(conversion)} of previous"
            }
        }
    }

    private fun isSelected(band: FunnelBand, context: ChartRenderContext): Boolean =
        context.selection?.let {
            it.seriesId == seriesId && it.pointIndex == band.stage.sourceIndex
        } == true

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        // The full band, not the painted trapezoid: the empty corners beside a
        // narrow stage still belong to it, and requiring a reader to hit a
        // 4-pixel-wide wedge is not a target.
        val band = bands.firstOrNull { it.bounds.contains(point) } ?: return null
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = band.stage.sourceIndex,
            x = ChartX.Category(band.stage.label),
            y = band.stage.value,
            item = band.stage.item,
            position = ChartOffset(band.bounds.centerX, band.bounds.top),
        )
    }

    override fun describe(): List<ChartLayerSummary> {
        if (bands.isEmpty()) return emptyList()
        return listOf(
            ChartLayerSummary(
                seriesId = seriesId,
                seriesName = seriesName,
                pointCount = bands.size,
                entries = bands.map { band ->
                    ChartLayerEntry(
                        label = band.stage.label,
                        value = band.stage.value,
                        detail = describeStage(band.stage, valueFormatter),
                    )
                },
            ),
        )
    }

    override fun describeSelection(
        selection: AnyChartSelection,
        formatter: ChartValueFormatter,
    ): String? {
        val band = bands.firstOrNull { it.stage.sourceIndex == selection.pointIndex } ?: return null
        return describeStage(band.stage, formatter)
    }

    /**
     * The four figures a funnel is read for, in the order a reader wants them.
     *
     * The absolute number first, because it is the only one that is not
     * relative; then the share of the top, which places the stage in the whole
     * funnel; then the conversion and the loss, which are what happened *here*.
     */
    private fun describeStage(stage: FunnelStage, formatter: ChartValueFormatter): String =
        buildString {
            append(stage.label)
            append(": ")
            append(formatter.format(stage.value))
            append(", ")
            append(percentage(stage.fractionOfFirst))
            append(" of the first stage")
            stage.conversionFromPrevious?.let { conversion ->
                append(", ")
                append(percentage(conversion))
                append(" converted from the previous stage")
            }
            stage.dropOffCount?.takeIf { it > 0.0 }?.let { lost ->
                append(", ")
                append(formatter.format(lost))
                append(" lost")
            }
            append(".")
        }
}

/**
 * Runs [block] clipped to [fraction] of [bounds], measured along the funnel's
 * own direction.
 *
 * The reveal has to grow each stage the way the funnel is read — downward for a
 * vertical funnel, rightward for a horizontal one — and clipping is what lets
 * the trapezoid keep its shape while it grows. Scaling the path instead would
 * change its slope, which is the one thing the shape is carrying.
 */
private inline fun DrawScope.clipToProgress(
    bounds: ChartRect,
    fraction: Float,
    orientation: ChartOrientation,
    block: DrawScope.() -> Unit,
) {
    if (fraction >= 1f) {
        block()
        return
    }
    val rect = if (orientation.isVertical) {
        androidx.compose.ui.geometry.Rect(
            bounds.left,
            bounds.top,
            bounds.right,
            bounds.top + bounds.height * fraction,
        )
    } else {
        androidx.compose.ui.geometry.Rect(
            bounds.left,
            bounds.top,
            bounds.left + bounds.width * fraction,
            bounds.bottom,
        )
    }
    clipRect(
        left = rect.left,
        top = rect.top,
        right = rect.right,
        bottom = rect.bottom,
    ) { block() }
}
