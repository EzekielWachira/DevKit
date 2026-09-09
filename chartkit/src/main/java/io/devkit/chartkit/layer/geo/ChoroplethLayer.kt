package io.devkit.chartkit.layer.geo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartSelectionDetails
import io.devkit.chartkit.model.ChartX

/** When a thematic map writes region names on itself. */
enum class GeoLabels {

    /** Never. The default for a dense map. */
    None,

    /**
     * Where the region is large enough for its name to fit inside it.
     *
     * Measured, not estimated: the label is drawn only when it fits within the
     * region's own on-screen box. A dense county map therefore labels the big
     * counties and leaves the rest to the tooltip, which is the only honest
     * behaviour without a full label-placement engine.
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
 * One region's thematic style, resolved per frame.
 *
 * Deliberately separate from [ProjectedFeature]. The geography is projected
 * once and never again; the style changes whenever the value, the scale, the
 * selection or the animation clock does. Keeping them apart is what lets a
 * year-over-year colour transition animate at sixty frames a second without
 * reprojecting a single vertex.
 *
 * @param value the joined statistic, or `null` when the join found no record.
 * @param fill `null` when the scale could not place the value, which the layer
 *   draws as the theme's "no data" colour.
 */
internal class FeatureStyle(
    val value: Double?,
    val fill: Color?,
    val label: String,
    val key: String,
    val item: Any?,
)

/**
 * Shaded geographic regions.
 *
 * ```text
 * GeoJSON → projected paths (cached)
 *                              ↘
 *                                draw
 *                              ↗
 * data → join → ColorScale → fill
 * ```
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
 * [paths] is keyed on the projected geometry and the coordinate transform. A
 * new statistic, a new colour scale, a selection or an animation frame all
 * reuse it; only a new projection, a new dataset or a resize rebuild it.
 */
@Suppress("LongParameterList")
internal class ChoroplethLayer(
    override val id: String,
    private val geometry: ProjectedGeometry,
    private val styles: Map<Int, FeatureStyle>,
    private val seriesId: String,
    private val seriesName: String,
    private val labels: GeoLabels,
    private val valueFormatter: ChartValueFormatter,
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
    private var cachedPaths: Map<Int, Path>? = null
    private var cacheKey: Any? = null

    private fun paths(coordinates: GeoCoordinates): Map<Int, Path> {
        val key = TransformKey(
            plot = coordinates.plotArea,
            zoom = coordinates.zoom,
            panX = coordinates.panX,
            panY = coordinates.panY,
            geometry = geometry,
        )
        cachedPaths?.let { if (cacheKey == key) return it }

        val built = HashMap<Int, Path>(geometry.features.size)
        geometry.features.forEach { feature ->
            val path = Path().apply { fillType = PathFillType.EvenOdd }
            var any = false
            feature.polygons.forEach { polygon ->
                if (addRing(path, polygon.outer, coordinates)) any = true
                polygon.holes.forEach { addRing(path, it, coordinates) }
            }
            if (any) built[feature.index] = path
        }
        cachedPaths = built
        cacheKey = key
        return built
    }

    private fun addRing(
        path: Path,
        ring: List<ProjectedPoint>,
        coordinates: GeoCoordinates,
    ): Boolean {
        if (ring.size < 3) return false
        var started = false
        ring.forEach { point ->
            val screen = coordinates.screenOf(point)
            if (!screen.isFinite) return@forEach
            if (started) path.lineTo(screen.x, screen.y) else path.moveTo(screen.x, screen.y)
            started = true
        }
        if (started) path.close()
        return started
    }

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val coordinates = context.geo
        if (!coordinates.isDrawable || geometry.isEmpty) return

        val reveal = context.reveal.coerceIn(0f, 1f)
        val colors = context.colors.geo
        val borderWidth = context.px(context.dimensions.geoBorderWidth)
        val selectedIndex = selectedIndex(context)
        val built = paths(coordinates)
        val visible = coordinates.visibleExtent()

        geometry.features.forEach { feature ->
            // Culled by box. At zoom 1 the visible extent covers the whole map,
            // so nothing is culled and the test costs four comparisons; zoomed
            // in, it is what keeps a thousand-feature map at frame rate.
            if (!overlaps(feature, visible)) return@forEach
            val path = built[feature.index] ?: return@forEach
            val style = styles[feature.index]

            val fill = style?.fill ?: colors.missing
            scope.drawPath(path, fill.copy(alpha = fill.alpha * reveal))

            if (borderWidth > 0f) {
                val border = if (style?.fill == null) colors.missingBorder else colors.border
                scope.drawPath(
                    path = path,
                    color = border.copy(alpha = border.alpha * reveal),
                    style = Stroke(width = borderWidth),
                )
            }
        }

        // The selected region is stroked last, over every neighbour, so its
        // outline is not half-covered by whichever region happens to be drawn
        // after it.
        if (selectedIndex != null && reveal >= 1f) {
            built[selectedIndex]?.let { path ->
                scope.drawPath(path, context.colors.selectionHighlight)
                scope.drawPath(
                    path = path,
                    color = context.colors.selectionGuide,
                    style = Stroke(width = context.px(context.dimensions.geoSelectedBorderWidth)),
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

    private fun drawLabels(
        scope: DrawScope,
        context: ChartRenderContext,
        coordinates: GeoCoordinates,
        selectedIndex: Int?,
        visible: ProjectedBounds,
    ) {
        val style = context.typography.geoLabel.copy(color = context.colors.geo.label)
        geometry.features.forEach { feature ->
            if (labels == GeoLabels.SelectedOnly && feature.index != selectedIndex) return@forEach
            if (!overlaps(feature, visible)) return@forEach
            val text = styles[feature.index]?.label?.takeIf { it.isNotBlank() } ?: return@forEach
            val anchor = feature.labelPoint?.let(coordinates::screenOf) ?: return@forEach
            if (!anchor.isFinite || !coordinates.plotArea.contains(anchor)) return@forEach

            val layout = context.textMeasurer.measure(text, style, maxLines = 1)

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
                val width = kotlin.math.abs(bottomRight.x - topLeft.x)
                val height = kotlin.math.abs(bottomRight.y - topLeft.y)
                if (layout.size.width > width || layout.size.height > height) return@forEach
            }

            val left = anchor.x - layout.size.width / 2f
            val top = anchor.y - layout.size.height / 2f
            // A halo behind the glyphs, so a name stays legible over the dark
            // end of the ramp as well as the light end. Cheaper and more
            // robust than choosing a text colour per region's luminance.
            scope.drawText(
                textLayoutResult = layout,
                topLeft = Offset(left, top),
                color = context.colors.geo.labelHalo,
                drawStyle = Stroke(width = context.px(HALO_WIDTH)),
            )
            scope.drawText(layout, topLeft = Offset(left, top))
        }
    }

    private fun selectedIndex(context: ChartRenderContext): Int? =
        context.selection
            ?.takeIf { it.seriesId == seriesId }
            ?.pointIndex
            ?.takeIf { index -> styles.containsKey(index) || geometry.features.any { it.index == index } }

    /**
     * The region under a tap.
     *
     * ```text
     * screen point → projected point → index candidates → point-in-polygon
     * ```
     *
     * Converted to projected space **once**, then tested against geometry that
     * was projected at load. The alternative — projecting every polygon into
     * screen space to test it — would redo the whole map's arithmetic per tap.
     *
     * Candidates come back smallest-box-first, so a tap in a region nested
     * inside a larger one selects the smaller.
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

        val hit = geometry.index.candidates(projected.x, projected.y)
            .firstOrNull { it.contains(projected.x, projected.y) }
            ?: return null
        return selectionFor(hit, coordinates)
    }

    /** The selection for a feature. */
    private fun selectionFor(feature: ProjectedFeature, coordinates: GeoCoordinates): AnyChartSelection {
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
    private fun describeRegion(style: FeatureStyle?, formatter: ChartValueFormatter): String {
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
        /** The halo stroke behind a map label. */
        val HALO_WIDTH: Dp = 3.dp
    }
}
