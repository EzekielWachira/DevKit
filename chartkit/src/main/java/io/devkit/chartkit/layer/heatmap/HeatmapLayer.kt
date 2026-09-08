package io.devkit.chartkit.layer.heatmap

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
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
import io.devkit.chartkit.scale.ColorScale

/**
 * One cell of a heatmap.
 *
 * @param value the intensity, or `null` for a cell with no measurement.
 * @param sourceIndex the caller's own list position, or `-1` for a cell the
 *   grid implies but the data did not contain.
 */
internal data class HeatmapCell(
    val column: Int,
    val row: Int,
    val value: Double?,
    val sourceIndex: Int,
)

/** Whether a heatmap writes its values into its cells. */
enum class HeatmapCellLabels {

    /** Never. The default: the colour is the encoding. */
    None,

    /**
     * Where the cell is large enough to hold the text.
     *
     * Measured, not guessed. A label is drawn only if it fits inside the cell
     * with room to spare — so a twelve-by-seven grid on a tablet is labelled
     * and the same data on a phone is not, rather than both being labelled and
     * one being illegible.
     */
    Auto,
}

/**
 * A grid of coloured cells.
 *
 * ### Why the rows are a numeric axis
 *
 * A heatmap has categories on both axes, and [io.devkit.chartkit.coordinate.CartesianCoordinates]
 * has one category axis and one value axis. Rather than generalise the
 * coordinate system — which every other layer would then have to handle — the
 * rows are placed on the value axis at integer positions, with the axis
 * labelled from the row names. The cell at row *r* spans `r ± 0.5`, so the grid
 * tiles exactly and the existing scale, layout, viewport and hit-testing code
 * all work unchanged.
 *
 * ### Missing is not zero
 *
 * A cell whose value is `null` is painted in the theme's "no measurement"
 * colour, which is deliberately not the low end of the ramp. A heatmap that
 * paints an absent cell as zero asserts a measurement nobody took — and on an
 * activity grid, "closed on Sunday" and "open with no visitors" are entirely
 * different facts.
 *
 * Everything is drawn on the chart's own canvas. A composable per cell would
 * put a layout node on each of a 52 × 7 calendar's 364 cells, and on each of a
 * 200 × 24 matrix's 4,800.
 */
internal class HeatmapLayer(
    override val id: String,
    private val cells: List<HeatmapCell>,
    private val columnLabels: List<String>,
    private val rowLabels: List<String>,
    private val colorScale: ColorScale,
    private val items: List<Any?>,
    private val seriesId: String,
    private val seriesName: String,
    private val cellLabels: HeatmapCellLabels,
    private val valueFormatter: io.devkit.chartkit.formatter.ChartValueFormatter,
    private val showMissing: Boolean,
    private val cornerRadiusOverride: androidx.compose.ui.unit.Dp? = null,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val coordinates = context.cartesian
        val plot = coordinates.plotArea
        val categories = coordinates.categories ?: return
        if (plot.isEmpty || cells.isEmpty()) return

        val colors = context.colors.heatmap
        val spacing = context.px(context.dimensions.heatmapCellSpacing)
        val radius = context.px(cornerRadiusOverride ?: context.dimensions.heatmapCellCornerRadius)
        val reveal = context.reveal.coerceIn(0f, 1f)
        val selection = context.selection
        val labelStyle = context.typography.cellLabel.copy(color = context.colors.tooltipContent)

        cells.forEach { cell ->
            if (cell.value == null && !showMissing) return@forEach
            val rect = rectFor(cell, categories, coordinates, spacing) ?: return@forEach
            // Culled against the plot: a zoomed heatmap has most of its cells
            // off screen, and each one costs a `drawRoundRect` otherwise.
            if (rect.right < plot.left || rect.left > plot.right ||
                rect.bottom < plot.top || rect.top > plot.bottom
            ) {
                return@forEach
            }

            val colour = cell.value?.let { colorScale.colorAt(it) } ?: colors.missing
            // Intensity fades in rather than the cells growing: a grid of
            // shrunken cells reads as a broken layout, not as an animation.
            val revealed = if (reveal >= 1f) colour else colour.copy(alpha = colour.alpha * reveal)

            scope.drawRoundRect(
                color = revealed,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, rect.height),
                cornerRadius = CornerRadius(radius, radius),
            )
            if (colors.cellBorder.alpha > 0f) {
                scope.drawRoundRect(
                    color = colors.cellBorder,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width = 1f),
                )
            }

            if (selection?.seriesId == seriesId && selection.pointIndex == indexOf(cell)) {
                scope.drawRoundRect(
                    color = context.colors.selectionGuide,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width = context.px(context.dimensions.selectionGuideWidth) * 2f),
                )
            }

            if (cellLabels == HeatmapCellLabels.Auto && cell.value != null && reveal >= 1f) {
                val text = valueFormatter.format(cell.value)
                if (text.isEmpty()) return@forEach
                val layout = context.textMeasurer.measure(text, labelStyle)
                // Measured against the cell, with a margin. A label that only
                // just fits touches its neighbours and reads as one string.
                if (layout.size.width <= rect.width * LABEL_FIT_FRACTION &&
                    layout.size.height <= rect.height * LABEL_FIT_FRACTION
                ) {
                    scope.drawText(
                        layout,
                        topLeft = Offset(
                            rect.centerX - layout.size.width / 2f,
                            rect.centerY - layout.size.height / 2f,
                        ),
                    )
                }
            }
        }
    }

    private fun rectFor(
        cell: HeatmapCell,
        categories: io.devkit.chartkit.scale.CategoryScale,
        coordinates: io.devkit.chartkit.coordinate.CartesianCoordinates,
        spacing: Float,
    ): ChartRect? {
        val centreDomain = categories.positionAt(cell.column)
        val halfBand = categories.bandWidth / 2f
        // The row spans half a unit either side of its integer position, which
        // is what makes the grid tile with no gaps and no overlap.
        val top = coordinates.positionOfValue(cell.row + 0.5)
        val bottom = coordinates.positionOfValue(cell.row - 0.5)
        if (!top.isFinite() || !bottom.isFinite() || !centreDomain.isFinite()) return null

        val a = coordinates.pointAt(centreDomain - halfBand, top)
        val b = coordinates.pointAt(centreDomain + halfBand, bottom)
        val rect = ChartRect(a.x, a.y, b.x, b.y).normalized
        val inset = spacing / 2f
        val left = rect.left + inset
        val right = rect.right - inset
        val topEdge = rect.top + inset
        val bottomEdge = rect.bottom - inset
        // Never inset a cell out of existence: on a dense grid the spacing can
        // exceed the cell, and a zero-size cell is an invisible measurement.
        return if (right > left && bottomEdge > topEdge) {
            ChartRect(left, topEdge, right, bottomEdge)
        } else {
            rect
        }
    }

    private fun indexOf(cell: HeatmapCell): Int = cell.row * columnLabels.size + cell.column

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val coordinates = context.cartesian
        val categories = coordinates.categories ?: return null
        val column = categories.indexAt(coordinates.domainOf(point))
        if (column < 0) return null
        val row = Math.round(coordinates.valueAt(coordinates.valueOf(point))).toInt()
        if (row < 0 || row >= rowLabels.size) return null
        val cell = cells.firstOrNull { it.column == column && it.row == row } ?: return null
        return selectionFor(cell, context)
    }

    private fun selectionFor(cell: HeatmapCell, context: ChartRenderContext): AnyChartSelection? {
        val coordinates = context.cartesian
        val categories = coordinates.categories ?: return null
        val centre = categories.positionAt(cell.column)
        val top = coordinates.positionOfValue(cell.row + 0.5)
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = indexOf(cell),
            // The pair identifies the cell, so it is what a tooltip heads with.
            x = ChartX.Category(
                "${columnLabels.getOrElse(cell.column) { cell.column.toString() }}, " +
                    rowLabels.getOrElse(cell.row) { cell.row.toString() },
            ),
            y = cell.value ?: Double.NaN,
            item = cell.sourceIndex.takeIf { it >= 0 }?.let { items.getOrNull(it) },
            position = coordinates.pointAt(centre, top),
        )
    }

    override fun tooltipEntriesAt(
        selection: AnyChartSelection,
        context: ChartRenderContext,
    ): List<ChartTooltipEntry<Any?>> {
        if (selection.seriesId != seriesId) return emptyList()
        val cell = cells.firstOrNull { indexOf(it) == selection.pointIndex } ?: return emptyList()
        val value = cell.value ?: return emptyList()
        return listOf(
            ChartTooltipEntry(
                seriesId = seriesId,
                seriesName = rowLabels.getOrElse(cell.row) { seriesName },
                value = value,
                item = cell.sourceIndex.takeIf { it >= 0 }?.let { items.getOrNull(it) },
                paletteIndex = 0,
            ),
        )
    }

    /**
     * A summary, not four thousand cells.
     *
     * A screen reader given one node per cell of a 200 × 24 matrix is given a
     * way to spend an afternoon. What is announced instead is the grid's shape,
     * the range of its values and where the extremes are — plus, through the
     * selection, whatever cell the reader has actually chosen. An application
     * needing the full matrix in an accessible form renders
     * [io.devkit.chartkit.accessibility.ChartDataTable] beside the chart.
     */
    override fun describe(): List<ChartLayerSummary> {
        val present = cells.mapNotNull { it.value }
        val highest = cells.filter { it.value != null }.maxByOrNull { it.value!! }
        val lowest = cells.filter { it.value != null }.minByOrNull { it.value!! }
        return listOf(
            ChartLayerSummary(
                seriesId = seriesId,
                seriesName = seriesName,
                pointCount = cells.size,
                entries = buildList {
                    add(
                        ChartLayerEntry(
                            label = "Grid",
                            value = null,
                            detail = "${columnLabels.size} columns by ${rowLabels.size} rows, " +
                                "${present.size} measured cells" +
                                if (cells.size > present.size) {
                                    ", ${cells.size - present.size} with no data"
                                } else {
                                    ""
                                },
                        ),
                    )
                    highest?.let {
                        add(
                            ChartLayerEntry(
                                label = "Highest",
                                value = it.value,
                                detail = "Highest: ${cellName(it)}, ${valueFormatter.format(it.value!!)}",
                            ),
                        )
                    }
                    lowest?.let {
                        add(
                            ChartLayerEntry(
                                label = "Lowest",
                                value = it.value,
                                detail = "Lowest: ${cellName(it)}, ${valueFormatter.format(it.value!!)}",
                            ),
                        )
                    }
                },
            ),
        )
    }

    private fun cellName(cell: HeatmapCell): String =
        "${columnLabels.getOrElse(cell.column) { cell.column.toString() }} " +
            rowLabels.getOrElse(cell.row) { cell.row.toString() }

    private companion object {
        /** How much of a cell a label may occupy before it is dropped. */
        const val LABEL_FIT_FRACTION = 0.8f
    }
}
