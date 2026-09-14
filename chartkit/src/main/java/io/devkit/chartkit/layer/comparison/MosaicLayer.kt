package io.devkit.chartkit.layer.comparison

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.MosaicCell
import io.devkit.chartkit.geometry.MosaicColumn
import io.devkit.chartkit.geometry.MosaicGeometry
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.model.ChartX

/** What a mosaic writes on itself. */
enum class MosaicLabels {

    /** Nothing. */
    None,

    /** The column names, under the columns. The default. */
    Columns,

    /** Column names with each column's share of the whole. */
    ColumnsWithShare,

    /** Column names, and each cell's share of its column written inside it. */
    ColumnsAndCells,
}

/** A tap that landed on one cell. */
data class MosaicCellSelection(
    val columnIndex: Int,
    val seriesIndex: Int,
    val columnLabel: String,
    val seriesName: String,
    val value: Double,
    /** The cell's share of its own column. */
    val shareOfColumn: Double,
    val item: Any?,
)

/**
 * Variable-width stacked columns.
 *
 * ### Two questions, one picture
 *
 * A column's **width** is its total and a cell's **height** is its share of that
 * column. A hundred-percent stacked bar chart answers the second question and
 * discards the first; a plain stacked bar chart answers the first and makes the
 * second hard, because every column is a different height and the eye cannot
 * compare segments that do not start level. Giving the two questions two
 * different axes is the whole of what this chart is for.
 *
 * ### Labels are measured, not estimated
 *
 * A column narrower than its own name is left unlabelled rather than being given
 * a truncated one: on a mosaic the narrow columns are exactly the ones whose
 * names collide, and a row of "…" says less than a legend does. The same rule
 * [io.devkit.chartkit.layer.hierarchy.TreemapLayer] follows, for the same
 * reason.
 *
 * @param geometry arrives already laid out. Selecting, hovering and animating
 *   the reveal do not touch it.
 */
@Suppress("LongParameterList")
internal class MosaicLayer(
    override val id: String,
    private val geometry: MosaicGeometry,
    private val seriesNames: List<String>,
    private val items: (Int, Int) -> Any?,
    private val seriesId: String,
    private val seriesName: String,
    private val valueFormatter: ChartValueFormatter,
    private val labels: MosaicLabels,
    private val seriesColorOf: ((Int) -> Int?)? = null,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val planar = context.planar
        if (!planar.isDrawable || geometry.cells.isEmpty()) return
        val reveal = context.reveal.coerceIn(0f, 1f)
        val selected = (context.selection?.item as? MosaicCellSelection)

        geometry.cells.forEach { cell ->
            val rect = cell.bounds
            if (rect.isEmpty) return@forEach
            val colour = seriesColour(cell.seriesIndex, context)
            // Revealed from the bottom of its own column rather than from the
            // bottom of the plot: a cell growing out of the plot floor would
            // slide past the cells below it on the way up.
            val height = rect.height * reveal
            scope.drawRect(
                color = colour,
                topLeft = Offset(rect.left, rect.bottom - height),
                size = Size(rect.width, height),
            )
            val isSelected = selected != null &&
                selected.columnIndex == cell.columnIndex &&
                selected.seriesIndex == cell.seriesIndex
            if (isSelected) {
                scope.drawRect(
                    color = context.colors.selectionGuide,
                    topLeft = Offset(rect.left, rect.bottom - height),
                    size = Size(rect.width, height),
                    style = Stroke(width = context.px(context.dimensions.selectionGuideWidth) * 2f),
                )
            }
        }

        if (reveal < 1f || labels == MosaicLabels.None) return
        if (labels == MosaicLabels.ColumnsAndCells) {
            geometry.cells.forEach { cell -> drawCellLabel(scope, context, cell) }
        }
        geometry.columns.forEach { column -> drawColumnLabel(scope, context, column) }
    }

    private fun drawColumnLabel(
        scope: DrawScope,
        context: ChartRenderContext,
        column: MosaicColumn,
    ) {
        if (column.bounds.isEmpty) return
        val text = if (labels == MosaicLabels.ColumnsWithShare) {
            "${column.label}  ${percent(column.fraction)}"
        } else {
            column.label
        }
        val style = context.typography.cellLabel.copy(color = context.colors.flow.label)
        val layout = context.textMeasurer.measure(text, style, maxLines = 1)
        val padding = context.px(context.dimensions.labelPadding)
        // A column narrower than its own name goes unlabelled. See the class
        // note: an ellipsis here says less than the legend already does.
        if (layout.size.width > column.bounds.width) return
        val plot = context.planar.plotArea
        val top = column.bounds.bottom + padding
        if (top + layout.size.height > plot.bottom) return
        scope.drawText(
            textLayoutResult = layout,
            topLeft = Offset(column.bounds.centerX - layout.size.width / 2f, top),
        )
    }

    private fun drawCellLabel(scope: DrawScope, context: ChartRenderContext, cell: MosaicCell) {
        val rect = cell.bounds
        if (rect.isEmpty) return
        val column = geometry.columns.getOrNull(cell.columnIndex) ?: return
        val share = if (column.total > 0.0) cell.value / column.total else 0.0
        val style = context.typography.cellLabel.copy(color = context.colors.hierarchy.tileLabel)
        val layout = context.textMeasurer.measure(percent(share), style, maxLines = 1)
        val padding = context.px(context.dimensions.labelPadding)
        if (layout.size.width + padding * 2f > rect.width) return
        if (layout.size.height + padding * 2f > rect.height) return
        scope.drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                rect.centerX - layout.size.width / 2f,
                rect.centerY - layout.size.height / 2f,
            ),
        )
    }

    private fun seriesColour(index: Int, context: ChartRenderContext): Color =
        seriesColorOf?.invoke(index)?.let { Color(it) } ?: context.colors.seriesColor(index)

    private fun percent(fraction: Double): String =
        "${(fraction * 100.0).let { kotlin.math.round(it * 10.0) / 10.0 }}%"

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val cell = geometry.cellAt(point) ?: return null
        return selectionFor(cell)
    }

    private fun selectionFor(cell: MosaicCell): AnyChartSelection? {
        val column = geometry.columns.getOrNull(cell.columnIndex) ?: return null
        val name = seriesNames.getOrNull(cell.seriesIndex) ?: return null
        val share = if (column.total > 0.0) cell.value / column.total else 0.0
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = cell.seriesIndex,
            pointIndex = cell.columnIndex,
            x = ChartX.Category(column.label),
            y = cell.value,
            item = MosaicCellSelection(
                columnIndex = cell.columnIndex,
                seriesIndex = cell.seriesIndex,
                columnLabel = column.label,
                seriesName = name,
                value = cell.value,
                shareOfColumn = share,
                item = items(cell.seriesIndex, cell.columnIndex),
            ),
            position = ChartOffset(cell.bounds.centerX, cell.bounds.top),
        )
    }

    /**
     * Every series' value in the selected **column**.
     *
     * A mosaic cell is read against the rest of its column — that is what its
     * height means — so a tooltip showing one cell alone would withhold the
     * comparison the chart was drawn to make.
     */
    override fun tooltipEntriesAt(
        selection: AnyChartSelection,
        context: ChartRenderContext,
    ): List<ChartTooltipEntry<Any?>> {
        val target = selection.item as? MosaicCellSelection ?: return emptyList()
        return geometry.cells
            .filter { it.columnIndex == target.columnIndex }
            .map { cell ->
                ChartTooltipEntry(
                    seriesId = seriesId,
                    seriesName = seriesNames.getOrNull(cell.seriesIndex).orEmpty(),
                    value = cell.value,
                    item = items(cell.seriesIndex, cell.columnIndex),
                    paletteIndex = cell.seriesIndex,
                )
            }
    }

    override fun describeSelection(
        selection: AnyChartSelection,
        formatter: ChartValueFormatter,
    ): String? {
        val target = selection.item as? MosaicCellSelection ?: return null
        val column = geometry.columns.getOrNull(target.columnIndex)
        // The value, its share of its column and the column's share of the
        // whole — the three numbers the two axes encode. A reader who cannot
        // see the rectangle has no other way to recover the widths.
        return "${target.seriesName} in ${target.columnLabel}: " +
            "${formatter.format(target.value)}, " +
            "${percent(target.shareOfColumn)} of the column, " +
            "and the column is ${percent(column?.fraction ?: 0.0)} of the total"
    }

    override fun describe(): List<ChartLayerSummary> = listOf(
        ChartLayerSummary(
            seriesId = seriesId,
            seriesName = seriesName,
            pointCount = geometry.columns.size,
            entries = geometry.columns.map { column ->
                ChartLayerEntry(
                    label = column.label,
                    value = column.total,
                    detail = "${column.label}: ${valueFormatter.format(column.total)}, " +
                        "${percent(column.fraction)} of the total",
                )
            },
        ),
    )

}
