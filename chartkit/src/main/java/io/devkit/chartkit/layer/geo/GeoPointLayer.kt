package io.devkit.chartkit.layer.geo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import io.devkit.chartkit.coordinate.GeoCoordinates
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geo.GeoCoordinate
import io.devkit.chartkit.geo.ProjectedPoint
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartSelectionDetails
import io.devkit.chartkit.model.ChartX
import kotlin.math.sqrt

/**
 * One mark on a map, already projected.
 *
 * Projected **once**, by the chart, and reused for every frame of a pan or a
 * zoom — the same split that keeps a choropleth's vertices out of the gesture
 * loop. The screen position is derived from [projected] by the coordinate
 * system on each frame, which is a multiply and an add.
 *
 * @param radius in pixels, resolved at build time from either the theme's
 *   default or a [io.devkit.chartkit.scale.SizeScale].
 * @param sizeValue the number behind [radius], for the tooltip and the table.
 * @param colorValue the number behind [color], likewise. Both are `null` when
 *   the encoding is not in use.
 */
internal class GeoMark(
    val index: Int,
    val coordinate: GeoCoordinate,
    val projected: ProjectedPoint,
    val radius: Float,
    val color: Color,
    val label: String,
    val sizeValue: Double?,
    val colorValue: Double?,
    val item: Any?,
)

/**
 * Marks placed on a map by longitude and latitude.
 *
 * ```text
 * your records → (longitude, latitude) → projection → viewport → circle
 *                          ↘ size value → SizeScale  → radius
 *                          ↘ colour value → ColorScale → fill
 * ```
 *
 * ### Points and bubbles are one layer
 *
 * A bubble map is a point map whose radius carries a number. There is no second
 * renderer for it: `points` and `bubbles` on `GeoChart` build this same layer,
 * differing only in whether a [io.devkit.chartkit.scale.SizeScale] resolved the
 * radii. Two layers would have meant two hit tests, two draw orders and two
 * places for the area-versus-radius mistake to be made.
 *
 * ### Area, not radius
 *
 * When a size encoding is used it goes through ChartKit's
 * [io.devkit.chartkit.scale.SizeScale], whose default mode maps value to
 * **area**. A reader judges a circle by the space it covers, so mapping value
 * straight onto radius makes a city twice the size look four times as large.
 * That is the single most common way a bubble map lies.
 *
 * ### Drawn largest first
 *
 * So a small mark inside a large one stays visible. The hit test then walks the
 * same list backwards, which makes the mark on **top** the one a tap selects —
 * render order and hit-test frontness are the same order read from opposite
 * ends, rather than two independent decisions that could disagree.
 */
internal class GeoPointLayer(
    override val id: String,
    private val marks: List<GeoMark>,
    private val seriesId: String,
    private val seriesName: String,
    private val valueFormatter: ChartValueFormatter,
    private val outlineWidth: Dp,
    private val slop: Dp,
    private val outline: Color? = null,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    /** Largest first, so nothing is buried. Computed once, not per frame. */
    private val painted: List<GeoMark> = marks.sortedByDescending { it.radius }

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val coordinates = context.geo
        if (!coordinates.isDrawable || painted.isEmpty()) return

        val reveal = context.reveal.coerceIn(0f, 1f)
        val strokeWidth = context.px(outlineWidth)
        val ring = outline ?: context.colors.geo.overlayPointOutline
        val plot = coordinates.plotArea
        val selected = context.selection
            ?.takeIf { it.seriesId == seriesId }
            ?.pointIndex

        painted.forEach { mark ->
            val screen = coordinates.screenOf(mark.projected)
            if (!screen.isFinite) return@forEach
            val radius = mark.radius * reveal
            if (radius <= 0f) return@forEach
            // Culled against the plot, grown by the mark's own radius so one
            // straddling the edge is still drawn — clipped, but present.
            if (screen.x + radius < plot.left || screen.x - radius > plot.right ||
                screen.y + radius < plot.top || screen.y - radius > plot.bottom
            ) {
                return@forEach
            }
            val centre = Offset(screen.x, screen.y)
            scope.drawCircle(
                color = mark.color.copy(alpha = mark.color.alpha * reveal),
                radius = radius,
                center = centre,
            )
            if (strokeWidth > 0f) {
                scope.drawCircle(
                    color = ring.copy(alpha = ring.alpha * reveal),
                    radius = radius,
                    center = centre,
                    style = Stroke(width = strokeWidth),
                )
            }
            if (mark.index == selected && reveal >= 1f) {
                scope.drawCircle(
                    color = context.colors.selectionGuide,
                    radius = radius + strokeWidth,
                    center = centre,
                    style = Stroke(width = context.px(context.dimensions.geoSelectedBorderWidth)),
                )
            }
        }
    }

    /**
     * The mark under a tap.
     *
     * Walked from the end of the painted list, so the mark drawn **last** — the
     * smallest, and the one actually on top — is offered first. A tap that
     * lands on no mark at all falls through to whatever layer is below, which
     * is how tapping between two cities still selects the country under them.
     */
    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val coordinates = context.geo
        if (!coordinates.isDrawable || !coordinates.plotArea.contains(point)) return null
        val tolerance = context.px(slop)

        for (index in painted.indices.reversed()) {
            val mark = painted[index]
            val screen = coordinates.screenOf(mark.projected)
            if (!screen.isFinite) continue
            val dx = screen.x - point.x
            val dy = screen.y - point.y
            // The mark's own radius, plus a forgiving margin: a four-pixel
            // circle is a legitimate way to draw a city and an illegitimate
            // thing to ask a finger to hit.
            val reach = mark.radius + tolerance
            if (sqrt(dx * dx + dy * dy) <= reach) return selectionFor(mark, screen)
        }
        return null
    }

    private fun selectionFor(mark: GeoMark, screen: ChartOffset): AnyChartSelection =
        ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = mark.index,
            x = ChartX.Category(mark.label),
            y = mark.sizeValue ?: mark.colorValue ?: 0.0,
            item = mark.item,
            position = screen,
            details = ChartSelectionDetails.GeoPoint(
                coordinate = mark.coordinate,
                label = mark.label,
                sizeValue = mark.sizeValue,
                colorValue = mark.colorValue,
            ),
        )

    override fun describe(): List<ChartLayerSummary> {
        if (marks.isEmpty()) return emptyList()
        val entries = marks.map { mark ->
            ChartLayerEntry(
                label = mark.label,
                value = mark.sizeValue ?: mark.colorValue,
                detail = describeMark(mark, valueFormatter),
            )
        }
        return listOf(
            ChartLayerSummary(
                seriesId = seriesId,
                seriesName = seriesName,
                pointCount = entries.size,
                entries = entries,
                missingCount = entries.count { it.value == null },
            ),
        )
    }

    override fun describeSelection(
        selection: AnyChartSelection,
        formatter: ChartValueFormatter,
    ): String? = marks.firstOrNull { it.index == selection.pointIndex }
        ?.let { describeMark(it, formatter) }

    /**
     * A mark as a sentence.
     *
     * The coordinate is deliberately **not** read out. "Nairobi, 1.29 degrees
     * south, 36.82 east" is not how anyone identifies a place, and a screen
     * reader working through fifty of them would be unusable. The name and the
     * number are what the mark encodes.
     */
    private fun describeMark(mark: GeoMark, formatter: ChartValueFormatter): String {
        val name = mark.label.takeIf { it.isNotBlank() } ?: "Unnamed point"
        val value = mark.sizeValue ?: mark.colorValue ?: return "$name: no value"
        return "$name: ${formatter.format(value)}"
    }
}
