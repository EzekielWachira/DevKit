package io.devkit.chartkit.layer.statistical

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.model.ChartX
import io.devkit.chartkit.stats.BoxStatistics

/** One category's box, with the caller's own object behind it. */
internal class BoxEntry(
    val label: String,
    val statistics: BoxStatistics,
    val item: Any?,
    val paletteIndex: Int,
    val colorOverride: Int?,
)

/**
 * Box-and-whisker marks, one per category.
 *
 * The five numbers arrive already computed — from the caller, or from
 * [BoxStatistics.from] — so this layer does no statistics at all. That split is
 * what makes the quartile method testable without rendering, and what lets an
 * application that already has its own quartiles draw them unchanged.
 *
 * Orientation-agnostic like the bar layer: the box runs along the value axis
 * and is centred in its category band, so a horizontal box plot is the same
 * code with the orientation flipped.
 */
internal class BoxPlotLayer(
    override val id: String,
    private val entries: List<BoxEntry>,
    private val seriesId: String,
    private val seriesName: String,
    private val valueFormatter: io.devkit.chartkit.formatter.ChartValueFormatter,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val coordinates = context.cartesian
        val plot = coordinates.plotArea
        val categories = coordinates.categories ?: return
        if (plot.isEmpty || entries.isEmpty()) return

        val colors = context.colors.statistical
        val band = categories.innerBandWidth
        val boxExtent = band * context.dimensions.boxPlotWidthFraction
        val capExtent = band * context.dimensions.whiskerCapFraction
        val stroke = context.px(context.dimensions.lineWidth)
        val reveal = context.reveal.coerceIn(0f, 1f)
        val vertical = coordinates.orientation.isVertical
        val selection = context.selection

        entries.forEachIndexed { index, entry ->
            val statistics = entry.statistics
            if (!statistics.isValid) return@forEachIndexed
            val centre = categories.positionAt(index)

            // The whole mark grows out of its own median, so a box plot
            // arriving reads as a distribution opening up rather than as five
            // separate lines sliding in.
            val medianPosition = coordinates.positionOfValue(statistics.median)
            fun at(value: Double): Float =
                medianPosition + (coordinates.positionOfValue(value) - medianPosition) * reveal

            val q1 = at(statistics.q1)
            val q3 = at(statistics.q3)
            val low = at(statistics.minimum)
            val high = at(statistics.maximum)

            val boxColour = entry.colorOverride?.let { Color(it).copy(alpha = 0.35f) } ?: colors.box
            val borderColour = entry.colorOverride?.let { Color(it) } ?: colors.boxBorder

            // ---- whisker stem and caps -------------------------------------
            scope.drawValueLine(coordinates, centre, low, high, colors.boxBorder, stroke)
            scope.drawCrossLine(coordinates, centre, capExtent, low, colors.boxBorder, stroke)
            scope.drawCrossLine(coordinates, centre, capExtent, high, colors.boxBorder, stroke)

            // ---- the interquartile box -------------------------------------
            val boxRect = rectFor(coordinates, centre, boxExtent, q1, q3, vertical)
            scope.drawRect(
                color = boxColour,
                topLeft = Offset(boxRect.left, boxRect.top),
                size = Size(boxRect.width, boxRect.height),
            )
            scope.drawRect(
                color = borderColour,
                topLeft = Offset(boxRect.left, boxRect.top),
                size = Size(boxRect.width, boxRect.height),
                style = Stroke(width = stroke),
            )

            // ---- the median ------------------------------------------------
            scope.drawCrossLine(
                coordinates, centre, boxExtent, at(statistics.median),
                colors.median, stroke * MEDIAN_STROKE_FACTOR,
            )

            // ---- outliers ---------------------------------------------------
            val outlierRadius = context.px(context.dimensions.outlierRadius)
            statistics.outliers.forEach { value ->
                if (!value.isFinite()) return@forEach
                val position = at(value)
                val point = coordinates.pointAt(centre, position)
                scope.drawCircle(colors.outlier, outlierRadius, Offset(point.x, point.y))
            }

            if (selection?.seriesId == seriesId && selection.pointIndex == index) {
                scope.drawRect(
                    color = context.colors.selectionHighlight,
                    topLeft = Offset(boxRect.left, boxRect.top),
                    size = Size(boxRect.width, boxRect.height),
                )
                scope.drawRect(
                    color = context.colors.selectionGuide,
                    topLeft = Offset(boxRect.left, boxRect.top),
                    size = Size(boxRect.width, boxRect.height),
                    style = Stroke(width = context.px(context.dimensions.selectionGuideWidth) * 2f),
                )
            }
        }
    }

    /** A line along the value axis at a fixed domain position — the whisker stem. */
    private fun DrawScope.drawValueLine(
        coordinates: io.devkit.chartkit.coordinate.CartesianCoordinates,
        domainPosition: Float,
        fromValue: Float,
        toValue: Float,
        colour: Color,
        width: Float,
    ) {
        val a = coordinates.pointAt(domainPosition, fromValue)
        val b = coordinates.pointAt(domainPosition, toValue)
        drawLine(colour, Offset(a.x, a.y), Offset(b.x, b.y), width)
    }

    /** A line across the domain axis at a fixed value — a cap, or the median. */
    @Suppress("LongParameterList")
    private fun DrawScope.drawCrossLine(
        coordinates: io.devkit.chartkit.coordinate.CartesianCoordinates,
        domainCentre: Float,
        domainExtent: Float,
        valuePosition: Float,
        colour: Color,
        width: Float,
    ) {
        val half = domainExtent / 2f
        val a = coordinates.pointAt(domainCentre - half, valuePosition)
        val b = coordinates.pointAt(domainCentre + half, valuePosition)
        drawLine(colour, Offset(a.x, a.y), Offset(b.x, b.y), width)
    }

    private fun rectFor(
        coordinates: io.devkit.chartkit.coordinate.CartesianCoordinates,
        domainCentre: Float,
        domainExtent: Float,
        fromValue: Float,
        toValue: Float,
        vertical: Boolean,
    ): io.devkit.chartkit.geometry.ChartRect {
        val half = domainExtent / 2f
        return if (vertical) {
            io.devkit.chartkit.geometry.ChartRect(
                left = domainCentre - half,
                top = minOf(fromValue, toValue),
                right = domainCentre + half,
                bottom = maxOf(fromValue, toValue),
            )
        } else {
            io.devkit.chartkit.geometry.ChartRect(
                left = minOf(fromValue, toValue),
                top = domainCentre - half,
                right = maxOf(fromValue, toValue),
                bottom = domainCentre + half,
            )
        }.normalized
    }

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val coordinates = context.cartesian
        val categories = coordinates.categories ?: return null
        // By band, not by rectangle: the box is a thin mark and the whole
        // category belongs to it, which is what a reader is aiming at.
        val index = categories.indexAt(coordinates.domainOf(point))
        if (index < 0 || index >= entries.size) return null
        return selectionFor(index, context)
    }

    private fun selectionFor(index: Int, context: ChartRenderContext): AnyChartSelection? {
        val entry = entries.getOrNull(index) ?: return null
        val coordinates = context.cartesian
        val categories = coordinates.categories ?: return null
        val centre = categories.positionAt(index)
        val top = coordinates.positionOfValue(entry.statistics.q3)
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = index,
            x = ChartX.Category(entry.label),
            // The median is the single number a box plot is read for, so it is
            // the one a selection reports as its value.
            y = entry.statistics.median,
            item = entry.item,
            position = coordinates.pointAt(centre, top),
        )
    }

    /**
     * The five-number summary, as tooltip rows.
     *
     * Five entries rather than one, because a box plot's "value" is not a
     * number — reporting only the median would leave the reader with the least
     * of what the mark shows.
     */
    override fun tooltipEntriesAt(
        selection: AnyChartSelection,
        context: ChartRenderContext,
    ): List<ChartTooltipEntry<Any?>> {
        if (selection.seriesId != seriesId) return emptyList()
        val entry = entries.getOrNull(selection.pointIndex) ?: return emptyList()
        val statistics = entry.statistics
        fun row(name: String, value: Double) = ChartTooltipEntry(
            seriesId = seriesId,
            seriesName = name,
            value = value,
            item = entry.item,
            paletteIndex = entry.paletteIndex,
        )
        return listOf(
            row("Maximum", statistics.maximum),
            row("Q3", statistics.q3),
            row("Median", statistics.median),
            row("Q1", statistics.q1),
            row("Minimum", statistics.minimum),
        )
    }

    override fun describe(): List<ChartLayerSummary> = listOf(
        ChartLayerSummary(
            seriesId = seriesId,
            seriesName = seriesName,
            pointCount = entries.size,
            entries = entries.map { entry ->
                val s = entry.statistics
                ChartLayerEntry(
                    label = entry.label,
                    value = s.median,
                    detail = buildString {
                        append(entry.label)
                        append(": minimum ")
                        append(valueFormatter.format(s.minimum))
                        append(", first quartile ")
                        append(valueFormatter.format(s.q1))
                        append(", median ")
                        append(valueFormatter.format(s.median))
                        append(", third quartile ")
                        append(valueFormatter.format(s.q3))
                        append(", maximum ")
                        append(valueFormatter.format(s.maximum))
                        if (s.outliers.isNotEmpty()) {
                            append(", ")
                            append(s.outliers.size)
                            append(if (s.outliers.size == 1) " outlier" else " outliers")
                        }
                    },
                )
            },
        ),
    )

    private companion object {
        /** The median is the number readers take from the mark; it is drawn heavier. */
        const val MEDIAN_STROKE_FACTOR = 1.6f
    }
}
