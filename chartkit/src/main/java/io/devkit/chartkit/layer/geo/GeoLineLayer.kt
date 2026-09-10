package io.devkit.chartkit.layer.geo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import io.devkit.chartkit.coordinate.GeoCoordinates
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geo.GeoCoordinate
import io.devkit.chartkit.geo.GeoGeometryMath
import io.devkit.chartkit.geo.ProjectedPoint
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartSelectionDetails
import io.devkit.chartkit.model.ChartX

/**
 * One route, already projected and already made safe to draw.
 *
 * @param paths the route's pieces. Normally one; two or more when the
 *   antimeridian split it, which is why this is a list rather than a single
 *   path — a Tokyo–Los Angeles flight is one route drawn as two strokes, and a
 *   renderer that could only hold one would draw it back across Asia.
 * @param source the coordinates as the caller supplied them, so a selection can
 *   hand them back unchanged.
 */
internal class GeoRoute(
    val index: Int,
    val paths: List<List<ProjectedPoint>>,
    val source: List<GeoCoordinate>,
    val color: Color,
    val width: Dp,
    val label: String,
    val value: Double?,
    val item: Any?,
)

/**
 * Paths drawn on a map: routes, flows, links, boundaries in their own right.
 *
 * ```text
 * your records → list of coordinates → antimeridian split → projection → stroke
 * ```
 *
 * ### Why a line is not a very thin polygon
 *
 * It has no interior, so "is the pointer inside it" is not a question that can
 * be asked. A route is selected by **proximity** instead — the perpendicular
 * distance from the pointer to the nearest segment, against a tolerance in
 * screen pixels. That tolerance is converted through the current scale, so a
 * shipping lane is equally easy to tap at every zoom level rather than becoming
 * unhittable as the reader zooms out.
 *
 * ### Paths are cached against the transform
 *
 * Like [MapLayer]'s. A pan rebuilds them; a selection, a colour change or an
 * animation frame does not.
 */
internal class GeoLineLayer(
    override val id: String,
    private val routes: List<GeoRoute>,
    private val seriesId: String,
    private val seriesName: String,
    private val valueFormatter: ChartValueFormatter,
    private val slop: Dp,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    private var cachedPaths: Map<Int, Path>? = null
    private var cacheKey: Any? = null

    private fun paths(coordinates: GeoCoordinates): Map<Int, Path> {
        val key = TransformKey(
            coordinates.plotArea,
            coordinates.zoom,
            coordinates.panX,
            coordinates.panY,
        )
        cachedPaths?.let { if (cacheKey == key) return it }

        val built = HashMap<Int, Path>(routes.size)
        routes.forEach { route ->
            val path = Path()
            var any = false
            route.paths.forEach { piece ->
                var started = false
                piece.forEach { point ->
                    val screen = coordinates.screenOf(point)
                    if (!screen.isFinite) return@forEach
                    if (started) path.lineTo(screen.x, screen.y) else path.moveTo(screen.x, screen.y)
                    started = true
                    any = true
                }
            }
            if (any) built[route.index] = path
        }
        cachedPaths = built
        cacheKey = key
        return built
    }

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val coordinates = context.geo
        if (!coordinates.isDrawable || routes.isEmpty()) return

        val reveal = context.reveal.coerceIn(0f, 1f)
        val built = paths(coordinates)
        val selected = context.selection
            ?.takeIf { it.seriesId == seriesId }
            ?.pointIndex

        routes.forEach { route ->
            val path = built[route.index] ?: return@forEach
            val color = route.color
            scope.drawPath(
                path = path,
                color = color.copy(alpha = color.alpha * reveal),
                style = Stroke(width = context.px(route.width), cap = StrokeCap.Round),
            )
        }

        // Stroked again, over every other route, so the selected one is not
        // half-covered by whichever crosses it.
        if (selected != null && reveal >= 1f) {
            built[selected]?.let { path ->
                val route = routes.firstOrNull { it.index == selected }
                scope.drawPath(
                    path = path,
                    color = context.colors.selectionGuide,
                    style = Stroke(
                        width = context.px(route?.width ?: DEFAULT_WIDTH) +
                            context.px(context.dimensions.geoSelectedBorderWidth),
                        cap = StrokeCap.Round,
                    ),
                )
                scope.drawPath(
                    path = path,
                    color = route?.color ?: context.colors.selectionGuide,
                    style = Stroke(width = context.px(route?.width ?: DEFAULT_WIDTH), cap = StrokeCap.Round),
                )
            }
        }
    }

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val coordinates = context.geo
        if (!coordinates.isDrawable || !coordinates.plotArea.contains(point)) return null
        val projected = coordinates.projectedAt(point)
        if (!projected.isFinite) return null

        val scale = coordinates.scale
        if (scale <= 0.0) return null
        // Screen pixels into projected units, so the tolerance is a constant
        // number of pixels whatever the zoom.
        val tolerance = context.px(slop) / scale

        var nearest: GeoRoute? = null
        var nearestDistance = Double.POSITIVE_INFINITY
        routes.forEach { route ->
            route.paths.forEach { piece ->
                val distance = GeoGeometryMath.distanceToPath(piece, projected.x, projected.y)
                if (distance < nearestDistance) {
                    nearestDistance = distance
                    nearest = route
                }
            }
        }
        val hit = nearest ?: return null
        if (nearestDistance > tolerance) return null

        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = hit.index,
            x = ChartX.Category(hit.label),
            y = hit.value ?: 0.0,
            item = hit.item,
            position = point,
            details = ChartSelectionDetails.GeoRoute(
                label = hit.label,
                coordinates = hit.source,
                value = hit.value,
            ),
        )
    }

    override fun describe(): List<ChartLayerSummary> {
        if (routes.isEmpty()) return emptyList()
        val entries = routes.map { route ->
            ChartLayerEntry(
                label = route.label,
                value = route.value,
                detail = describeRoute(route, valueFormatter),
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
    ): String? = routes.firstOrNull { it.index == selection.pointIndex }
        ?.let { describeRoute(it, formatter) }

    private fun describeRoute(route: GeoRoute, formatter: ChartValueFormatter): String {
        val name = route.label.takeIf { it.isNotBlank() } ?: "Unnamed route"
        val value = route.value ?: return name
        return "$name: ${formatter.format(value)}"
    }

    private data class TransformKey(
        val plot: ChartRect,
        val zoom: Float,
        val panX: Float,
        val panY: Float,
    )

    private companion object {
        val DEFAULT_WIDTH: Dp = androidx.compose.ui.unit.Dp(1.5f)
    }
}
