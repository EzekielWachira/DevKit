package io.devkit.chartkit.geometry

/**
 * One cell: a series' share of one column.
 *
 * @param columnIndex which column it belongs to.
 * @param seriesIndex which series it belongs to.
 * @param value the caller's own number, untouched by the layout.
 * @param bounds where it lands, in pixels.
 */
class MosaicCell internal constructor(
    val columnIndex: Int,
    val seriesIndex: Int,
    val value: Double,
    val bounds: ChartRect,
)

/**
 * One column: its own extent, and its share of the whole.
 *
 * @param total everything in the column, which is what its width is measured by.
 * @param fraction that total as a share of every column's, which is what a
 *   label reports. Kept here rather than recomputed at the label, so the number
 *   written under a column and the width drawn above it cannot disagree.
 */
class MosaicColumn internal constructor(
    val index: Int,
    val label: String,
    val item: Any?,
    val total: Double,
    val fraction: Double,
    val bounds: ChartRect,
)

/** Where every column and cell sits, for one frame. */
class MosaicGeometry internal constructor(
    val columns: List<MosaicColumn>,
    val cells: List<MosaicCell>,
) {
    /** The cell at [point], or `null`. */
    fun cellAt(point: ChartOffset): MosaicCell? =
        cells.firstOrNull { it.bounds.contains(point) }

    /** The column containing [x], or `null`. */
    fun columnAt(x: Float): MosaicColumn? =
        columns.firstOrNull { x >= it.bounds.left && x <= it.bounds.right }
}

/**
 * Variable-width stacked columns — the Marimekko, or mosaic plot.
 *
 * ```text
 * ┌────────┬───┬──────────────┬──┐
 * │        │   │              │  │   width  = the column's total
 * ├────────┼───┼──────────────┼──┤   height = a series' share of it
 * │        │   │              │  │   area   = the value itself
 * └────────┴───┴──────────────┴──┘
 * ```
 *
 * ### Area is the value, and that is why the height is fixed
 *
 * A column's width is proportional to its total and a cell's height is its
 * share of that total, so the two cancel: a cell covers
 * `k × total × (value / total)` — that is, `k × value` — whatever column it sits
 * in. Two cells of equal value cover equal area across the whole chart, which is
 * what lets the eye compare them at all.
 *
 * Scaling a column's *height* by its total as well, so that short columns were
 * small ones, would read as a reasonable option and destroy exactly this: the
 * total would be encoded twice and a cell's area would become proportional to
 * `total × value`, which is not a quantity anybody has. So it is not offered.
 *
 * ### What it adds over a stacked bar chart
 *
 * A hundred-percent stacked bar chart answers "what is each segment made of"
 * and throws away how big the segments are; a plain stacked bar chart answers
 * "how big" and makes composition hard to compare because every column is a
 * different height. A mosaic gives width to the first question and height to
 * the second, so both are readable at once — market share by region where the
 * regions are not the same size, spend by department where the departments are
 * not.
 *
 * ### It is not a treemap
 *
 * A treemap also encodes quantity as area, but it is free to place a rectangle
 * anywhere, which makes any two rectangles hard to compare unless they happen to
 * share an edge. A mosaic keeps one categorical dimension on each axis, so every
 * cell in a row is comparable by height and every column by width. The cost is
 * that it only takes two dimensions where a treemap nests arbitrarily deep.
 *
 * Plain Kotlin: no Compose, no `android.graphics`. The proportions and the hit
 * rectangles are the parts worth testing directly.
 */
object MosaicLayout {

    /**
     * Places every column and cell inside [bounds].
     *
     * @param values indexed `[seriesIndex][columnIndex]`; `null` where a series
     *   has no value in a column.
     * @param columnGap pixels left between two columns.
     * @param cellGap pixels left between two cells in one column.
     */
    @Suppress("LongParameterList")
    fun layout(
        columns: List<MosaicColumnSpec>,
        values: List<List<Double?>>,
        bounds: ChartRect,
        columnGap: Float = 0f,
        cellGap: Float = 0f,
    ): MosaicGeometry {
        if (bounds.isEmpty || columns.isEmpty() || values.isEmpty()) {
            return MosaicGeometry(emptyList(), emptyList())
        }

        val totals = DoubleArray(columns.size)
        for (column in columns.indices) {
            values.forEach { series ->
                val value = series.getOrNull(column)
                if (value != null && value.isFinite() && value > 0.0) totals[column] += value
            }
        }
        val grandTotal = totals.sum()
        if (grandTotal <= 0.0) return MosaicGeometry(emptyList(), emptyList())

        // Gaps come out of the drawable width rather than being added to it, so
        // the columns still tile the plot exactly and their widths stay
        // proportional to their totals.
        val gapCount = (columns.size - 1).coerceAtLeast(0)
        val usableWidth = (bounds.width - columnGap * gapCount).coerceAtLeast(0f)

        val placedColumns = ArrayList<MosaicColumn>(columns.size)
        val cells = ArrayList<MosaicCell>(columns.size * values.size)
        var left = bounds.left

        columns.forEachIndexed { columnIndex, spec ->
            val total = totals[columnIndex]
            val fraction = total / grandTotal
            val width = (usableWidth * fraction).toFloat()

            // Every column fills the height. That is not a stylistic choice:
            // width already carries the column's total, so scaling the height by
            // it as well would encode the same number twice and break the
            // property below.
            val columnHeight = bounds.height
            val columnTop = bounds.top
            val columnBounds = ChartRect(left, columnTop, left + width, bounds.bottom)

            placedColumns += MosaicColumn(
                index = columnIndex,
                label = spec.label,
                item = spec.item,
                total = total,
                fraction = fraction,
                bounds = columnBounds,
            )

            if (total > 0.0 && width > 0f) {
                val present = values.indices.count { series ->
                    values[series].getOrNull(columnIndex)?.takeIf { it.isFinite() && it > 0.0 } != null
                }
                val innerGaps = (present - 1).coerceAtLeast(0)
                val usableHeight = (columnHeight - cellGap * innerGaps).coerceAtLeast(0f)
                var top = columnTop
                values.forEachIndexed { seriesIndex, series ->
                    val value = series.getOrNull(columnIndex)
                        ?.takeIf { it.isFinite() && it > 0.0 }
                        ?: return@forEachIndexed
                    val height = (usableHeight * (value / total)).toFloat()
                    cells += MosaicCell(
                        columnIndex = columnIndex,
                        seriesIndex = seriesIndex,
                        value = value,
                        bounds = ChartRect(left, top, left + width, top + height),
                    )
                    top += height + cellGap
                }
            }
            left += width + columnGap
        }

        return MosaicGeometry(placedColumns, cells)
    }
}

/** One column's identity, before it has been measured. */
class MosaicColumnSpec(
    val label: String,
    val item: Any? = null,
)
