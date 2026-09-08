package io.devkit.chartkit.layer.comparison

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartMath
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.model.ChartX

/**
 * One qualitative range behind a bullet's measure.
 *
 * Ordered, unnamed unless the caller names them: whether a high number is good
 * is the application's knowledge, not ChartKit's. See
 * [io.devkit.chartkit.theme.ChartComparisonColors.qualitativeBands].
 */
data class BulletRange(val from: Double, val to: Double, val label: String? = null) {
    internal val low: Double get() = minOf(from, to)
    internal val high: Double get() = maxOf(from, to)
}

/** One row of a bullet graph. */
internal class BulletEntry(
    val label: String,
    val actual: Double,
    val target: Double?,
    val ranges: List<BulletRange>,
    val item: Any?,
    val paletteIndex: Int,
    val colorOverride: Int?,
)

/**
 * A measure against a target, on a background of qualitative ranges.
 *
 * ```text
 * Revenue  ░░░░░▒▒▒▒▓▓▓▓
 *          ██████████│
 *                    ↑ target
 * ```
 *
 * Stephen Few's bullet graph: the dense replacement for a gauge, which spends a
 * whole circle showing one number. Several of them stack into the space one
 * dial would take, and — because they share an axis — they can be compared.
 *
 * ### On the Cartesian engine
 *
 * A bullet is a category band with three marks in it. The axis, the scale, the
 * grid, the tooltip and the annotations are the shared ones; what is here is
 * the bar, the target tick and the range bands.
 *
 * ### Target as a tick, not a second bar
 *
 * A target drawn as a bar would compete with the measure for the reader's
 * attention and would make "did we hit it" a comparison of two lengths. As a
 * perpendicular tick, the answer is whether the bar has passed the line.
 */
@Suppress("LongParameterList")
internal class BulletLayer(
    override val id: String,
    private val entries: List<BulletEntry>,
    private val seriesId: String,
    private val seriesName: String,
    private val targetLabel: String,
    private val valueFormatter: ChartValueFormatter,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val coordinates = context.cartesian
        val categories = coordinates.categories ?: return
        if (coordinates.plotArea.isEmpty || entries.isEmpty()) return

        val reveal = context.reveal.coerceIn(0f, 1f)
        val band = categories.innerBandWidth
        val barExtent = minOf(context.px(context.dimensions.bulletBarThickness), band * MAX_BAR_SHARE)
        val baseline = coordinates.baseline

        entries.forEachIndexed { index, entry ->
            val centre = categories.positionAt(index)

            // The qualitative bands fill the whole row, behind everything.
            entry.ranges.forEachIndexed { rangeIndex, range ->
                val from = coordinates.positionOfValue(range.low)
                val to = coordinates.positionOfValue(range.high)
                if (!from.isFinite() || !to.isFinite()) return@forEachIndexed
                val rect = spanRect(context, centre, band, from, to)
                scope.drawRect(
                    color = context.colors.comparison.band(rangeIndex),
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                )
            }

            // The measure: a narrower bar centred in the row.
            val actualPosition = ChartMath.lerp(
                baseline,
                coordinates.positionOfValue(entry.actual),
                reveal,
            )
            if (actualPosition.isFinite()) {
                val rect = spanRect(context, centre, barExtent, baseline, actualPosition)
                scope.drawRect(
                    color = entry.colorOverride?.let { Color(it) }
                        ?: context.colors.seriesColor(entry.paletteIndex),
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                )
            }

            // The target, across the full row so it is unmistakably a threshold
            // rather than a shorter bar.
            entry.target?.let { target ->
                val position = coordinates.positionOfValue(target)
                if (!position.isFinite()) return@let
                val a = coordinates.pointAt(centre - band / 2f, position)
                val b = coordinates.pointAt(centre + band / 2f, position)
                scope.drawLine(
                    color = context.colors.comparison.target,
                    start = Offset(a.x, a.y),
                    end = Offset(b.x, b.y),
                    strokeWidth = context.px(context.dimensions.bulletTargetWidth),
                )
            }

            if (isSelected(context, index)) {
                val rect = spanRect(
                    context,
                    centre,
                    band,
                    coordinates.valueOf(ChartOffset(coordinates.plotArea.left, coordinates.plotArea.top)),
                    coordinates.valueOf(
                        ChartOffset(coordinates.plotArea.right, coordinates.plotArea.bottom),
                    ),
                )
                scope.drawRect(
                    color = context.colors.selectionGuide,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    style = Stroke(width = context.px(context.dimensions.selectionGuideWidth) * 2f),
                )
            }
        }
    }

    /** A rectangle spanning [extent] across the band and two positions along it. */
    private fun spanRect(
        context: ChartRenderContext,
        centre: Float,
        extent: Float,
        from: Float,
        to: Float,
    ): ChartRect {
        val coordinates = context.cartesian
        val a = coordinates.pointAt(centre - extent / 2f, from)
        val b = coordinates.pointAt(centre + extent / 2f, to)
        return ChartRect(a.x, a.y, b.x, b.y).normalized
    }

    private fun isSelected(context: ChartRenderContext, index: Int): Boolean =
        context.selection?.let { it.seriesId == seriesId && it.pointIndex == index } == true

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val coordinates = context.cartesian
        val categories = coordinates.categories ?: return null
        val index = categories.indexAt(coordinates.domainOf(point))
        if (index < 0) return null
        val entry = entries.getOrNull(index) ?: return null
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = index,
            x = ChartX.Category(entry.label),
            y = entry.actual,
            item = entry.item,
            position = coordinates.pointAt(
                categories.positionAt(index),
                coordinates.positionOfValue(entry.actual),
            ),
        )
    }

    /** The measure and its target together: the comparison is the reading. */
    override fun tooltipEntriesAt(
        selection: AnyChartSelection,
        context: ChartRenderContext,
    ): List<ChartTooltipEntry<Any?>> {
        val entry = entries.getOrNull(selection.pointIndex) ?: return emptyList()
        return buildList {
            add(
                ChartTooltipEntry(
                    seriesId = seriesId,
                    seriesName = entry.label,
                    value = entry.actual,
                    item = entry.item,
                    paletteIndex = entry.paletteIndex,
                ),
            )
            entry.target?.let { target ->
                add(
                    ChartTooltipEntry(
                        seriesId = "$seriesId-target",
                        seriesName = targetLabel,
                        value = target,
                        item = entry.item,
                        paletteIndex = entry.paletteIndex,
                    ),
                )
            }
        }
    }

    override fun describe(): List<ChartLayerSummary> = listOf(
        ChartLayerSummary(
            seriesId = seriesId,
            seriesName = seriesName,
            pointCount = entries.size,
            entries = entries.map { entry ->
                ChartLayerEntry(
                    label = entry.label,
                    value = entry.actual,
                    detail = describeEntry(entry, valueFormatter),
                )
            },
        ),
    )

    override fun describeSelection(
        selection: AnyChartSelection,
        formatter: ChartValueFormatter,
    ): String? {
        val entry = entries.getOrNull(selection.pointIndex) ?: return null
        return describeEntry(entry, formatter)
    }

    /**
     * The measure, the target, whether the target was met, and the band.
     *
     * "72, target 80, 8 below target" — the comparison, not two numbers a
     * listener has to hold and subtract.
     */
    private fun describeEntry(entry: BulletEntry, formatter: ChartValueFormatter): String =
        buildString {
            append(entry.label)
            append(": ")
            append(formatter.format(entry.actual))
            entry.target?.let { target ->
                append(", ")
                append(targetLabel.lowercase())
                append(' ')
                append(formatter.format(target))
                val gap = entry.actual - target
                append(", ")
                when {
                    gap >= 0.0 -> {
                        append(formatter.format(gap))
                        append(" above")
                    }
                    else -> {
                        append(formatter.format(-gap))
                        append(" below")
                    }
                }
            }
            entry.ranges.firstOrNull { entry.actual in it.low..it.high }
                ?.label
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    append(", ")
                    append(it)
                }
        }

    private companion object {
        /** The measure never fills the whole band; the ranges behind it must show. */
        const val MAX_BAR_SHARE = 0.5f
    }
}
