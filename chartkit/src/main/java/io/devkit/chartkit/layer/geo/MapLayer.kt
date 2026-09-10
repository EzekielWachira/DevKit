package io.devkit.chartkit.layer.geo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.coordinate.GeoCoordinates
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geo.ProjectedBounds
import io.devkit.chartkit.geo.ProjectedFeature
import io.devkit.chartkit.geo.ProjectedGeometry
import io.devkit.chartkit.geo.ProjectedPoint
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.layer.label.LabelPlacer
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartSelectionDetails
import io.devkit.chartkit.model.ChartX
import kotlin.math.abs

/** When a map writes region names on itself. */
enum class GeoLabels {

    /** Never. The default for a dense map. */
    None,

    /**
     * Where the region is large enough for its name to fit inside it, and
     * where nothing has already claimed the space.
     *
     * Measured, not estimated, and twice: the label must fit within the
     * region's own on-screen box, and it must survive ChartKit's shared label
     * collision engine. Regions are offered to that engine **largest first**,
     * so when two names compete the bigger region keeps its label — which is
     * the one a reader can attribute correctly anyway.
     *
     * Both tests are made against the *current* transform, so zooming in
     * reveals labels that did not fit before. That is the zoom-awareness a
     * world map needs: at full extent a few countries are named, and by the
     * time a reader has zoomed to a continent, all of them are.
     */
    Auto,

    /** Only the selected region. */
    SelectedOnly,

    /**
     * Every region that has a label, whether or not it fits.
     *
     * For a map of a dozen regions where the caller knows they fit. On a
     * hundred-county map this overlaps badly, which is why it is not the
     * default.
     */
    All,
}

/**
 * One feature's appearance, resolved per frame.
 *
 * Deliberately separate from [ProjectedFeature]. The geography is projected
 * once and never again; the appearance changes whenever the value, the scale,
 * the selection or the animation clock does. Keeping them apart is what lets a
 * year-over-year colour transition animate at sixty frames a second without
 * reprojecting a single vertex.
 *
 * @param value the joined statistic, or `null` when there is none — either
 *   because the join found no record or because this is a base map with no
 *   data at all. [MapLayer] draws a `null` [fill] as the theme's "no data"
 *   colour, so a base map supplies a fill and leaves the value absent.
 * @param fill `null` when nothing could be placed on the colour scale.
 * @param stroke the region's border, or `null` for the theme's.
 * @param strokeWidth the border's width, or `null` for the theme's.
 */
internal class GeoFeatureStyle(
    val value: Double?,
    val fill: Color?,
    val label: String,
    val key: String,
    val item: Any?,
    val stroke: Color? = null,
    val strokeWidth: Dp? = null,
    val opacity: Float = 1f,
)

/**
 * How one feature should look, as a caller describes it.
 *
 * The public half of [GeoFeatureStyle], for the `style` resolver on a base map.
 * Every field is optional and `null` means "leave it to the theme", so a caller
 * who wants only to thicken one border says only that.
 *
 * ### What this is for
 *
 * Datasets carry more than one kind of line. A file that distinguishes a
 * national boundary from a coastline from a disputed segment, or one that marks
 * which regions are in scope for a report, can be styled from its own
 * properties:
 *
 * ```kotlin
 * map(world, style = { feature ->
 *     when (feature.properties.string("type")) {
 *         "disputed" -> GeoStyle(stroke = amber, strokeWidth = 1.dp)
 *         "coastline" -> GeoStyle(stroke = slate)
 *         else -> null                       // the theme's own treatment
 *     }
 * })
 * ```
 *
 * ChartKit has no opinion about what those properties mean — it does not know
 * what a coastline is. It renders what the dataset says and what you ask for.
 */
data class GeoStyle(
    val fill: Color? = null,
    val stroke: Color? = null,
    val strokeWidth: Dp? = null,
    /** Multiplies whatever alpha the fill and stroke already carry. */
    val opacity: Float = 1f,
)

/**
 * Geographic features, drawn.
 *
 * ```text
 * GeoJSON / TopoJSON → projected paths (cached)
 *                                        ↘
 *                                          draw
 *                                        ↗
 * whatever decides colour ──────────────
 * ```
 *
 * ### This is the map engine; choropleth is one way of using it
 *
 * The only thing that makes a shaded thematic map different from a plain
 * outline map is where the fills come from — a colour scale over joined values,
 * or a single theme colour. Everything else is identical: the same projected
 * geometry, the same path cache, the same viewport culling, the same
 * point-in-polygon hit test, the same labels, the same selection treatment and
 * the same accessibility summary.
 *
 * So there is one layer, and [io.devkit.chartkit.charts.ChoroplethMap],
 * [io.devkit.chartkit.charts.WorldMap] and `GeoChart`'s `map` and `choropleth`
 * builders all construct it with different [GeoFeatureStyle] maps. A second
 * renderer for outline maps would have been a second set of bugs in the
 * antimeridian handling, the hole handling and the hit test.
 *
 * ### One Canvas, not one composable per region
 *
 * A county map is fifty paths; a world map is two hundred; a census-tract map
 * is thousands. As composables that would be a layout node and a semantics node
 * each, and a recomposition per selection change. As a canvas it is one draw
 * pass over paths that were built once.
 *
 * ### Holes are holes
 *
 * Each polygon's rings go into one [Path] with [PathFillType.EvenOdd], so an
 * inner ring subtracts. The hole is genuinely unfilled rather than painted in
 * the background colour — which would be wrong the instant anything is drawn
 * behind the map, and wrong for an enclave, where the hole is another country.
 *
 * ### Paths are cached against geometry, not against values
 *
 * [areaPaths] is keyed on the projected geometry and the coordinate transform.
 * A new statistic, a new colour scale, a selection or an animation frame all
 * reuse it; only a new projection, a new dataset or a resize rebuild it.
 */
@Suppress("LongParameterList")
internal class MapLayer(
    override val id: String,
    private val geometry: ProjectedGeometry,
    private val styles: Map<Int, GeoFeatureStyle>,
    private val seriesId: String,
    private val seriesName: String,
    private val labels: GeoLabels,
    private val valueFormatter: ChartValueFormatter,
    /**
     * How near a tap must come to a line or a marker to select it, in dp.
     *
     * Areas are selected by containment and need no tolerance. A route two
     * pixels wide would be unselectable without one.
     */
    private val strokeTolerance: Dp = DEFAULT_STROKE_TOLERANCE,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    /**
     * Screen-space paths, one per feature, rebuilt only when the transform
     * changes.
     *
     * Keyed on the coordinates rather than on the geometry alone, because a
     * zoom, a pan or a resize moves every vertex — but a value change does not,
     * and that is the case worth being fast.
     */
    private var cachedAreas: Map<Int, Path>? = null
    private var cachedStrokes: Map<Int, Path>? = null
    private var cacheKey: Any? = null

    private fun buildPaths(coordinates: GeoCoordinates) {
        val key = TransformKey(
            plot = coordinates.plotArea,
            zoom = coordinates.zoom,
            panX = coordinates.panX,
            panY = coordinates.panY,
            geometry = geometry,
        )
        if (cacheKey == key && cachedAreas != null) return

        val areas = HashMap<Int, Path>(geometry.features.size)
        val strokes = HashMap<Int, Path>()
        geometry.features.forEach { feature ->
            if (feature.polygons.isNotEmpty()) {
                val path = Path().apply { fillType = PathFillType.EvenOdd }
                var any = false
                feature.polygons.forEach { polygon ->
                    if (addRing(path, polygon.outer, coordinates)) any = true
                    polygon.holes.forEach { addRing(path, it, coordinates) }
                }
                if (any) areas[feature.index] = path
            }
            if (feature.lines.isNotEmpty()) {
                val path = Path()
                var any = false
                feature.lines.forEach { line ->
                    if (addOpenPath(path, line, coordinates)) any = true
                }
                if (any) strokes[feature.index] = path
            }
        }
        cachedAreas = areas
        cachedStrokes = strokes
        cacheKey = key
    }

    private fun addRing(
        path: Path,
        ring: List<ProjectedPoint>,
        coordinates: GeoCoordinates,
    ): Boolean {
        if (ring.size < 3) return false
        val started = addOpenPath(path, ring, coordinates)
        if (started) path.close()
        return started
    }

    private fun addOpenPath(
        path: Path,
        points: List<ProjectedPoint>,
        coordinates: GeoCoordinates,
    ): Boolean {
        var started = false
        points.forEach { point ->
            val screen = coordinates.screenOf(point)
            if (!screen.isFinite) return@forEach
            if (started) path.lineTo(screen.x, screen.y) else path.moveTo(screen.x, screen.y)
            started = true
        }
        return started
    }

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val coordinates = context.geo
        if (!coordinates.isDrawable || geometry.isEmpty) return

        val reveal = context.reveal.coerceIn(0f, 1f)
        val colors = context.colors.geo
        val defaultBorderWidth = context.px(context.dimensions.geoBorderWidth)
        val selectedIndex = selectedIndex(context)
        buildPaths(coordinates)
        val areas = cachedAreas.orEmpty()
        val strokes = cachedStrokes.orEmpty()
        val visible = coordinates.visibleExtent()
        val markerRadius = context.px(context.dimensions.geoFeatureMarkerRadius)

        geometry.features.forEach { feature ->
            // Culled by box. At zoom 1 the visible extent covers the whole map,
            // so nothing is culled and the test costs four comparisons; zoomed
            // in, it is what keeps a thousand-feature map at frame rate.
            if (!overlaps(feature, visible)) return@forEach
            val style = styles[feature.index]
            val borderWidth = style?.strokeWidth?.let { context.px(it) } ?: defaultBorderWidth
            val border = style?.stroke
                ?: if (style?.fill == null) colors.missingBorder else colors.border
            // The caller's opacity folds into the reveal, so a half-transparent
            // region fades in from nothing to half rather than to opaque.
            val alpha = reveal * (style?.opacity ?: 1f).coerceIn(0f, 1f)

            areas[feature.index]?.let { path ->
                val fill = style?.fill ?: colors.missing
                scope.drawPath(path, fill.copy(alpha = fill.alpha * alpha))
                if (borderWidth > 0f) {
                    scope.drawPath(
                        path = path,
                        color = border.copy(alpha = border.alpha * alpha),
                        style = Stroke(width = borderWidth),
                    )
                }
            }

            // A line has no interior, so its own colour is its *stroke*: the
            // fill a choropleth would have given it is what it is drawn with.
            strokes[feature.index]?.let { path ->
                val color = style?.fill ?: style?.stroke ?: colors.route
                val width = style?.strokeWidth?.let { context.px(it) }
                    ?: context.px(context.dimensions.geoRouteWidth)
                scope.drawPath(
                    path = path,
                    color = color.copy(alpha = color.alpha * alpha),
                    style = Stroke(width = width),
                )
            }

            if (markerRadius > 0f) {
                feature.markers.forEach { marker ->
                    val screen = coordinates.screenOf(marker)
                    if (!screen.isFinite) return@forEach
                    val color = style?.fill ?: colors.point
                    scope.drawCircle(
                        color = color.copy(alpha = color.alpha * alpha),
                        radius = markerRadius * reveal,
                        center = Offset(screen.x, screen.y),
                    )
                }
            }
        }

        // The selected region is stroked last, over every neighbour, so its
        // outline is not half-covered by whichever region happens to be drawn
        // after it.
        if (selectedIndex != null && reveal >= 1f) {
            val selectedWidth = context.px(context.dimensions.geoSelectedBorderWidth)
            areas[selectedIndex]?.let { path ->
                scope.drawPath(path, context.colors.selectionHighlight)
                scope.drawPath(
                    path = path,
                    color = context.colors.selectionGuide,
                    style = Stroke(width = selectedWidth),
                )
            }
            strokes[selectedIndex]?.let { path ->
                scope.drawPath(
                    path = path,
                    color = context.colors.selectionGuide,
                    style = Stroke(width = selectedWidth),
                )
            }
        }

        if (reveal >= 1f && labels != GeoLabels.None) {
            drawLabels(scope, context, coordinates, selectedIndex, visible)
        }
    }

    private fun overlaps(
        feature: ProjectedFeature,
        visible: ProjectedBounds,
    ): Boolean {
        val box = feature.bounds
        if (box.isEmpty) return false
        return box.maxX >= visible.minX && box.minX <= visible.maxX &&
            box.maxY >= visible.minY && box.minY <= visible.maxY
    }

    /**
     * Region names, largest region first.
     *
     * The ordering is the whole of the priority rule: [LabelPlacer] is
     * first-come-first-served, so offering the big regions first is what makes
     * a crowded map name the countries a reader would have looked for.
     */
    private fun drawLabels(
        scope: DrawScope,
        context: ChartRenderContext,
        coordinates: GeoCoordinates,
        selectedIndex: Int?,
        visible: ProjectedBounds,
    ) {
        val style = context.typography.geoLabel.copy(color = context.colors.geo.label)
        val placer = LabelPlacer(coordinates.plotArea, capacity = geometry.features.size)

        val ordered = if (labels == GeoLabels.Auto) {
            geometry.features.sortedByDescending { boxArea(it) }
        } else {
            geometry.features
        }

        ordered.forEach { feature ->
            if (labels == GeoLabels.SelectedOnly && feature.index != selectedIndex) return@forEach
            if (!overlaps(feature, visible)) return@forEach
            val text = styles[feature.index]?.label?.takeIf { it.isNotBlank() } ?: return@forEach
            val anchor = feature.labelPoint?.let(coordinates::screenOf) ?: return@forEach
            if (!anchor.isFinite || !coordinates.plotArea.contains(anchor)) return@forEach

            val layout = context.textMeasurer.measure(text, style, maxLines = 1)
            val left = anchor.x - layout.size.width / 2f
            val top = anchor.y - layout.size.height / 2f

            if (labels == GeoLabels.Auto) {
                // Measured against the region's own on-screen box. A label
                // wider than the county it names reads as belonging to the
                // neighbour it spills into, which is worse than no label — and
                // truncating to an ellipsis at this size labels nothing.
                val topLeft = coordinates.screenOf(
                    ProjectedPoint(feature.bounds.minX, feature.bounds.maxY),
                )
                val bottomRight = coordinates.screenOf(
                    ProjectedPoint(feature.bounds.maxX, feature.bounds.minY),
                )
                val width = abs(bottomRight.x - topLeft.x)
                val height = abs(bottomRight.y - topLeft.y)
                if (layout.size.width > width || layout.size.height > height) return@forEach
                // The shared collision engine, so "too crowded" means the same
                // thing here as it does over a bar chart.
                if (!placer.place(
                        left,
                        top,
                        layout.size.width.toFloat(),
                        layout.size.height.toFloat(),
                    )
                ) {
                    return@forEach
                }
            }

            // A halo behind the glyphs, so a name stays legible over the dark
            // end of the ramp as well as the light end. Cheaper and more
            // robust than choosing a text colour per region's luminance.
            scope.drawText(
                textLayoutResult = layout,
                topLeft = Offset(left, top),
                color = context.colors.geo.labelHalo,
                drawStyle = Stroke(width = context.px(HALO_WIDTH)),
            )
            // `Fill`, stated rather than left to the default. `drawText`
            // applies a non-null `drawStyle` to the layout's own paint, and a
            // null one means "leave whatever is there" — so omitting it here
            // inherits the halo's `Stroke` and paints the label as a fattened
            // outline in the text colour, which at this size merges the letters
            // into an unreadable blob.
            scope.drawText(
                textLayoutResult = layout,
                topLeft = Offset(left, top),
                drawStyle = Fill,
            )
        }
    }

    private fun boxArea(feature: ProjectedFeature): Double {
        val box = feature.bounds
        if (box.isEmpty) return 0.0
        return (box.maxX - box.minX) * (box.maxY - box.minY)
    }

    private fun selectedIndex(context: ChartRenderContext): Int? =
        context.selection
            ?.takeIf { it.seriesId == seriesId }
            ?.pointIndex
            ?.takeIf { index -> styles.containsKey(index) || geometry.features.any { it.index == index } }

    /**
     * The feature under a tap.
     *
     * ```text
     * screen point → projected point → index candidates → point-in-polygon
     *                                                   → nearest line or marker
     * ```
     *
     * Converted to projected space **once**, then tested against geometry that
     * was projected at load. The alternative — projecting every polygon into
     * screen space to test it — would redo the whole map's arithmetic per tap.
     *
     * Candidates come back smallest-box-first, so a tap in a region nested
     * inside a larger one selects the smaller. Areas win over lines and markers
     * at the same point only when the pointer is genuinely inside one; a route
     * crossing a country is selectable where it is drawn.
     */
    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val coordinates = context.geo
        if (!coordinates.isDrawable || !coordinates.plotArea.contains(point)) return null
        val projected = coordinates.projectedAt(point)
        if (!projected.isFinite) return null

        // The tolerance is in screen pixels and the test is in projected units,
        // so it is converted through the current scale — which means a route
        // stays equally easy to tap at every zoom level.
        val scale = coordinates.scale
        val tolerance = if (scale > 0.0) context.px(strokeTolerance) / scale else 0.0

        var nearestStroke: ProjectedFeature? = null
        var nearestDistance = Double.POSITIVE_INFINITY

        geometry.index.candidates(projected.x, projected.y).forEach { candidate ->
            if (candidate.contains(projected.x, projected.y)) {
                return selectionFor(candidate, coordinates)
            }
        }

        // Lines and markers have no interior, so they are found by proximity —
        // and by scanning, because a stroke near the pointer may belong to a
        // feature whose bounding box the pointer is outside.
        if (tolerance > 0.0) {
            geometry.features.forEach { feature ->
                if (feature.lines.isEmpty() && feature.markers.isEmpty()) return@forEach
                val distance = feature.distanceToStrokes(projected.x, projected.y)
                if (distance <= tolerance && distance < nearestDistance) {
                    nearestDistance = distance
                    nearestStroke = feature
                }
            }
        }

        return nearestStroke?.let { selectionFor(it, coordinates) }
    }

    /** The selection for a feature. */
    private fun selectionFor(
        feature: ProjectedFeature,
        coordinates: GeoCoordinates,
    ): AnyChartSelection {
        val style = styles[feature.index]
        val anchor = feature.labelPoint?.let(coordinates::screenOf)
            ?: ChartOffset(coordinates.plotArea.centerX, coordinates.plotArea.centerY)
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = feature.index,
            x = ChartX.Category(style?.label ?: feature.feature.id.orEmpty()),
            // Zero when there is no record — but `details.hasValue` says so, and
            // every readout checks it. A `Double?` here would have meant making
            // `ChartSelection.y` nullable for every chart in the library.
            y = style?.value ?: 0.0,
            item = style?.item,
            position = anchor,
            details = ChartSelectionDetails.Geo(
                featureId = feature.feature.id,
                featureKey = style?.key.orEmpty(),
                featureLabel = style?.label.orEmpty(),
                properties = feature.feature.properties,
                hasValue = style?.value != null,
                bounds = feature.feature.bounds,
            ),
        )
    }

    /**
     * Every region, with its value.
     *
     * Capped by the announcement builder, which falls back to a range past
     * [io.devkit.chartkit.accessibility.ChartAccessibility.MAX_ANNOUNCED_POINTS]
     * — a screen reader reading two thousand census tracts in sequence is not
     * access.
     */
    override fun describe(): List<ChartLayerSummary> {
        if (geometry.isEmpty) return emptyList()
        val entries = geometry.features.map { feature ->
            val style = styles[feature.index]
            ChartLayerEntry(
                label = style?.label.orEmpty(),
                value = style?.value,
                detail = describeRegion(style, valueFormatter),
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
    ): String? = describeRegion(styles[selection.pointIndex], formatter)

    /**
     * A region as a sentence: its name, and its value or the absence of one.
     *
     * Strictly factual. No "high", no "underperforming", no quintile adjective
     * — those are interpretations ChartKit has not been given the vocabulary
     * for, and a confidently wrong one is worse than none to a reader who
     * cannot see the map.
     */
    private fun describeRegion(style: GeoFeatureStyle?, formatter: ChartValueFormatter): String {
        val name = style?.label?.takeIf { it.isNotBlank() } ?: "Unnamed region"
        val value = style?.value ?: return "$name: no data"
        return "$name: ${formatter.format(value)}"
    }

    /** What invalidates the path cache. */
    private data class TransformKey(
        val plot: ChartRect,
        val zoom: Float,
        val panX: Float,
        val panY: Float,
        val geometry: ProjectedGeometry,
    )

    private companion object {
        /**
         * The halo stroke behind a map label.
         *
         * Deliberately narrow. The stroke is centred on the glyph outline, so
         * only half of it shows; wider than this and the fattened outlines of
         * adjacent letters meet, which is worse than no halo at all.
         */
        val HALO_WIDTH: Dp = 2.dp

        /** How near a tap must come to a route to select it. */
        val DEFAULT_STROKE_TOLERANCE: Dp = 12.dp
    }
}
