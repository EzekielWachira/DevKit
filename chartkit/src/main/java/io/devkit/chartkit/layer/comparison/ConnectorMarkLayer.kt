package io.devkit.chartkit.layer.comparison

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.devkit.chartkit.coordinate.CartesianCoordinates
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartMath
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
import kotlin.math.abs

/** One category's mark: a value, and optionally where it started. */
internal class ConnectorMarkEntry(
    val label: String,
    /** `null` for a lollipop, whose stem starts at the baseline. */
    val start: Double?,
    val end: Double,
    val item: Any?,
    val paletteIndex: Int,
    val colorOverride: Int?,
)

/**
 * What the marks mean.
 *
 * The two shapes share everything except how many ends they have, so they share
 * an implementation. Two layers would have been two hit tests, two reveals, two
 * accessibility adapters and two places for the marker sizing to drift.
 */
enum class ConnectorMarkKind {

    /**
     * Two markers joined by a bar: before against after, actual against target.
     *
     * The **distance** is the reading. Two bars side by side show the same
     * numbers and make the reader compute the gap themselves.
     */
    Dumbbell,

    /**
     * A thin stem from the baseline to a single marker.
     *
     * A bar chart with the ink removed. Better than bars when the categories
     * are many and the values are close together, where a row of wide bars is
     * mostly fill and the tops are what is being compared.
     */
    Lollipop,
}

/**
 * Dumbbell and lollipop marks, one per category.
 *
 * ### On the Cartesian engine
 *
 * The category scale, the value scale, the grid, the axes, the viewport, the
 * tooltip, the crosshair and the annotations all come from the shared engine.
 * What is here is a stem and one or two markers.
 *
 * Orientation-agnostic, like the bar layer: everything is expressed along the
 * domain and value axes, so a horizontal lollipop chart is this code with the
 * orientation flipped rather than a second implementation.
 */
@Suppress("LongParameterList")
internal class ConnectorMarkLayer(
    override val id: String,
    private val entries: List<ConnectorMarkEntry>,
    private val kind: ConnectorMarkKind,
    private val seriesId: String,
    private val seriesName: String,
    private val startLabel: String,
    private val endLabel: String,
    private val baseline: Double,
    private val valueFormatter: ChartValueFormatter,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val coordinates = context.cartesian
        val categories = coordinates.categories ?: return
        if (coordinates.plotArea.isEmpty || entries.isEmpty()) return

        val reveal = context.reveal.coerceIn(0f, 1f)
        val stemWidth = context.px(
            if (kind == ConnectorMarkKind.Dumbbell) {
                context.dimensions.dumbbellConnectorWidth
            } else {
                context.dimensions.lollipopStemWidth
            },
        )
        val markerRadius = context.px(
            if (kind == ConnectorMarkKind.Dumbbell) {
                context.dimensions.dumbbellMarkerRadius
            } else {
                context.dimensions.lollipopMarkerRadius
            },
        )

        entries.forEachIndexed { index, entry ->
            val centre = categories.positionAt(index)
            val from = coordinates.positionOfValue(entry.start ?: baseline)
            val toFull = coordinates.positionOfValue(entry.end)
            if (!from.isFinite() || !toFull.isFinite()) return@forEachIndexed
            // The stem grows from its own origin, which for a dumbbell is the
            // "before" marker and for a lollipop is the axis. Growing both from
            // the axis would slide a dumbbell's start marker through values the
            // data never had.
            val to = ChartMath.lerp(from, toFull, reveal)

            val stemColour = context.colors.comparison.stem
            val endColour = entry.colorOverride?.let { Color(it) }
                ?: context.colors.seriesColor(entry.paletteIndex)

            val a = coordinates.pointAt(centre, from)
            val b = coordinates.pointAt(centre, to)
            scope.drawLine(
                color = stemColour,
                start = Offset(a.x, a.y),
                end = Offset(b.x, b.y),
                strokeWidth = stemWidth,
                cap = StrokeCap.Round,
            )

            if (kind == ConnectorMarkKind.Dumbbell) {
                scope.drawCircle(
                    color = context.colors.comparison.target,
                    radius = markerRadius,
                    center = Offset(a.x, a.y),
                )
                // A ring inside the start marker, so the two ends differ in
                // *shape* as well as colour — a reader who cannot tell the two
                // colours apart can still tell before from after.
                scope.drawCircle(
                    color = context.colors.comparison.connector,
                    radius = markerRadius * INNER_RING,
                    center = Offset(a.x, a.y),
                )
            }
            scope.drawCircle(endColour, markerRadius, Offset(b.x, b.y))

            if (isSelected(context, index)) {
                scope.drawCircle(
                    color = context.colors.selectionGuide,
                    radius = markerRadius + context.px(context.dimensions.selectionGuideWidth) * 3f,
                    center = Offset(b.x, b.y),
                    style = Stroke(width = context.px(context.dimensions.selectionGuideWidth) * 2f),
                )
            }
        }
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
        val position = coordinates.positionOfValue(entry.end)
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = index,
            x = ChartX.Category(entry.label),
            y = entry.end,
            item = entry.item,
            position = coordinates.pointAt(categories.positionAt(index), position),
        )
    }

    /** Both ends of a dumbbell, so the tooltip reports the comparison. */
    override fun tooltipEntriesAt(
        selection: AnyChartSelection,
        context: ChartRenderContext,
    ): List<ChartTooltipEntry<Any?>> {
        val entry = entries.getOrNull(selection.pointIndex) ?: return emptyList()
        if (kind != ConnectorMarkKind.Dumbbell || entry.start == null) return emptyList()
        return listOf(
            ChartTooltipEntry(
                seriesId = "$seriesId-start",
                seriesName = startLabel,
                value = entry.start,
                item = entry.item,
                paletteIndex = entry.paletteIndex,
            ),
            ChartTooltipEntry(
                seriesId = "$seriesId-end",
                seriesName = endLabel,
                value = entry.end,
                item = entry.item,
                paletteIndex = entry.paletteIndex,
            ),
        )
    }

    override fun describe(): List<ChartLayerSummary> = listOf(
        ChartLayerSummary(
            seriesId = seriesId,
            seriesName = seriesName,
            pointCount = entries.size,
            entries = entries.map { entry ->
                ChartLayerEntry(
                    label = entry.label,
                    value = entry.end,
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
     * The reading, and — for a dumbbell — the gap that is its whole point.
     *
     * "Before 42, after 61, up 19" rather than two numbers a listener has to
     * subtract.
     */
    private fun describeEntry(entry: ConnectorMarkEntry, formatter: ChartValueFormatter): String {
        val start = entry.start
        if (kind != ConnectorMarkKind.Dumbbell || start == null) {
            return "${entry.label}: ${formatter.format(entry.end)}"
        }
        val delta = entry.end - start
        val direction = when {
            delta > 0.0 -> "up"
            delta < 0.0 -> "down"
            else -> "unchanged"
        }
        return buildString {
            append(entry.label)
            append(": ")
            append(startLabel)
            append(' ')
            append(formatter.format(start))
            append(", ")
            append(endLabel)
            append(' ')
            append(formatter.format(entry.end))
            append(", ")
            append(direction)
            if (delta != 0.0) {
                append(' ')
                append(formatter.format(abs(delta)))
            }
        }
    }

    private companion object {
        const val INNER_RING = 0.45f
    }
}

/** The value a lollipop's stem rises from, given the axis' own interval. */
internal fun lollipopBaseline(coordinates: CartesianCoordinates): Double {
    val domain = coordinates.valueScale.domain
    // Zero when it is on the axis, and the axis' own floor otherwise. A stem
    // drawn from a zero that is off-screen would leave every lollipop starting
    // at the same clipped edge, which says nothing.
    return if (domain.includesZero) 0.0 else domain.min
}
