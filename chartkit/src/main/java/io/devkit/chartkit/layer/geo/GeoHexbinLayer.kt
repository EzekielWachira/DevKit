package io.devkit.chartkit.layer.geo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geo.GeoBin
import io.devkit.chartkit.geo.GeoHexbin
import io.devkit.chartkit.geo.HexAggregate
import io.devkit.chartkit.geo.ProjectedPoint
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.HexGrid
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartX
import io.devkit.chartkit.scale.ColorScale
import io.devkit.chartkit.scale.NumericDomain

/** A tap that landed on one bin. */
data class GeoHexbinSelection(
    val count: Int,
    val value: Double?,
    /** The caller's own records that fell in this bin, in their own order. */
    val items: List<Any?>,
)

/**
 * Point density as a hexagonal grid.
 *
 * ```text
 *    ╱‾╲ ╱‾╲       one hexagon = the records near here
 *    ╲_╱ ╲_╱       colour      = how many, or what they come to
 *    ╱‾╲ ╱‾╲
 * ```
 *
 * ### Why this instead of more points
 *
 * A point map of any density stops being a map of points and becomes a blob.
 * The marks overlap, the overlaps are opaque, and the reader cannot tell two
 * records from two hundred. Binning answers the question the picture could
 * actually support — "how many are near here" — instead of the one it could
 * not.
 *
 * ### The bins do not move
 *
 * They are computed in projected space and cached against the projection's base
 * scale, which depends on the plot size and the map's extent but **not** on
 * zoom or pan. So a pan changes no numbers and a zoom magnifies the same bins
 * rather than recomputing them — and neither does any work in the gesture loop.
 * See [GeoHexbin] for the trade this makes against the screen-space
 * convention.
 *
 * ### A bin with nothing to aggregate is not a bin of zero
 *
 * Under `Sum`, `Mean` or `Max`, a bin whose records all lack a measurement has
 * no total. It is drawn in the map's "no data" colour rather than at the bottom
 * of the scale, where it would sit beside the genuinely low places — the same
 * rule the choropleth follows.
 */
@Suppress("LongParameterList")
internal class GeoHexbinLayer(
    override val id: String,
    private val points: List<ProjectedPoint>,
    private val values: List<Double?>?,
    private val items: List<Any?>,
    private val binSize: Dp,
    private val aggregate: HexAggregate,
    private val colorScale: ColorScale?,
    private val fallbackColor: Color,
    private val seriesId: String,
    private val seriesName: String,
    private val valueFormatter: ChartValueFormatter,
    private val strokeWidth: Dp,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    private var cachedBins: List<GeoBin>? = null
    private var cachedRadius: Double = Double.NaN
    private var cachedScale: ColorScale? = null

    /**
     * The bins for this projection, computed once per base scale.
     *
     * The radius is derived from the base scale — `scale / zoom` — rather than
     * from the current one, which is what keeps the bins still while a reader
     * zooms. Keyed on that radius so a resize rebins and a gesture does not.
     */
    private fun bins(context: ChartRenderContext): List<GeoBin> {
        val geo = context.geo
        val baseScale = if (geo.zoom != 0f) geo.scale / geo.zoom else geo.scale
        if (baseScale <= 0.0) return emptyList()
        val radius = context.px(binSize) / baseScale
        val cached = cachedBins
        if (cached != null && radius == cachedRadius) return cached
        val built = GeoHexbin.bin(points, radius, aggregate, values)
        cachedBins = built
        cachedRadius = radius
        cachedScale = null
        return built
    }

    /** A bin's on-screen radius: the projected radius through the live scale. */
    private fun screenRadius(context: ChartRenderContext): Float {
        val geo = context.geo
        val baseScale = if (geo.zoom != 0f) geo.scale / geo.zoom else geo.scale
        if (baseScale <= 0.0) return 0f
        return (context.px(binSize) / baseScale * geo.scale).toFloat()
    }

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val geo = context.geo
        if (!geo.isDrawable) return
        val placed = bins(context)
        if (placed.isEmpty()) return
        val radius = screenRadius(context)
        if (radius <= 0f) return

        val reveal = context.reveal.coerceIn(0f, 1f)
        val selected = context.selection?.takeIf { it.seriesId == seriesId }?.pointIndex ?: -1
        val plot = geo.plotArea

        placed.forEachIndexed { index, bin ->
            val center = geo.screenOf(bin.center)
            if (!center.isFinite) return@forEachIndexed
            // Cheap rejection before building a path: a world map zoomed into
            // one country has most of its bins off screen.
            if (center.x + radius < plot.left || center.x - radius > plot.right) return@forEachIndexed
            if (center.y + radius < plot.top || center.y - radius > plot.bottom) return@forEachIndexed

            val colour = colorOf(bin, context)
            scope.drawPath(hexagon(center, radius * reveal), colour)
            scope.drawPath(
                path = hexagon(center, radius * reveal),
                color = if (index == selected) context.colors.selectionGuide else colour,
                style = Stroke(
                    width = context.px(strokeWidth) * if (index == selected) SELECTED_STROKE else 1f,
                ),
            )
        }
    }

    private fun colorOf(bin: GeoBin, context: ChartRenderContext): Color {
        val value = bin.value ?: return context.colors.geo.missing
        val scale = colorScale ?: derivedScale(context)
        return scale?.colorAt(value) ?: fallbackColor
    }

    /**
     * A ramp over what the bins actually came to.
     *
     * Derived here rather than where the chart was declared, because the bins
     * do not exist until there is a projection to bin in. The first draft built
     * a scale from the *record count* instead — the only number available that
     * early — and a map of two thousand records whose busiest cell held nine
     * put every cell in the bottom half of a percent of the ramp. The whole map
     * came out one colour.
     *
     * Recomputed only when the bins are, which is on a resize and not on a
     * gesture. A caller who needs the ramp fixed across two maps passes one.
     */
    private fun derivedScale(context: ChartRenderContext): ColorScale? {
        cachedScale?.let { return it }
        val present = (cachedBins ?: return null).mapNotNull { it.value }
        val domain = NumericDomain.of(present) ?: return null
        return ColorScale.Continuous(
            domain = domain,
            colors = listOf(context.colors.heatmap.low, context.colors.heatmap.high),
        ).also { cachedScale = it }
    }

    private fun hexagon(center: ChartOffset, radius: Float): Path {
        val corners = HexGrid.corners(center.x, center.y, radius)
        return Path().apply {
            corners.forEachIndexed { index, corner ->
                if (index == 0) moveTo(corner.x, corner.y) else lineTo(corner.x, corner.y)
            }
            close()
        }
    }

    /**
     * The bin under the finger.
     *
     * Tested against the hexagon itself rather than a circle through its
     * corners or its edges. A hexagon's corners reach about 15% further than
     * its edges, so a circular test either refuses taps on the corners or
     * accepts taps in the gaps between cells — and on a tiled grid every one of
     * those gaps belongs to a neighbour.
     */
    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val geo = context.geo
        if (!geo.isDrawable) return null
        val radius = screenRadius(context)
        if (radius <= 0f) return null
        val placed = bins(context)

        placed.forEachIndexed { index, bin ->
            val center = geo.screenOf(bin.center)
            if (!center.isFinite) return@forEachIndexed
            if (HexGrid.contains(center.x, center.y, radius, point.x, point.y)) {
                return selectionFor(index, bin, center)
            }
        }
        return null
    }

    private fun selectionFor(index: Int, bin: GeoBin, center: ChartOffset): AnyChartSelection =
        ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = index,
            x = ChartX.Category(label(bin)),
            y = bin.value ?: bin.count.toDouble(),
            item = GeoHexbinSelection(
                count = bin.count,
                value = bin.value,
                items = bin.indices.mapNotNull { items.getOrNull(it) },
            ),
            position = center,
        )

    private fun label(bin: GeoBin): String = when (aggregate) {
        HexAggregate.Count -> "${bin.count} here"
        else -> "${bin.count} here, ${bin.value?.let { valueFormatter.format(it) } ?: "no value"}"
    }

    override fun describeSelection(
        selection: AnyChartSelection,
        formatter: ChartValueFormatter,
    ): String? {
        val target = selection.item as? GeoHexbinSelection ?: return null
        // The count is always said, even when the aggregate is something else:
        // a mean of 40 over two records and over two hundred are very different
        // claims, and the colour alone cannot tell them apart.
        val records = if (target.count == 1) "1 record" else "${target.count} records"
        return when (aggregate) {
            HexAggregate.Count -> records
            else -> "$records, ${aggregateName()} " +
                (target.value?.let { formatter.format(it) } ?: "no value")
        }
    }

    private fun aggregateName(): String = when (aggregate) {
        HexAggregate.Count -> "count"
        HexAggregate.Sum -> "total"
        HexAggregate.Mean -> "mean"
        HexAggregate.Max -> "maximum"
    }

    override fun describe(): List<ChartLayerSummary> {
        val placed = cachedBins ?: return emptyList()
        return listOf(
            ChartLayerSummary(
                seriesId = seriesId,
                seriesName = seriesName,
                pointCount = placed.size,
                missingCount = placed.count { it.value == null },
                // The bins, not the records: a hexbin map exists because there
                // were too many records to read one at a time, and reading them
                // out one at a time here would recreate exactly that problem.
                entries = placed.map { bin ->
                    ChartLayerEntry(
                        label = label(bin),
                        value = bin.value,
                    )
                },
            ),
        )
    }

    private companion object {
        const val SELECTED_STROKE = 3f
    }
}
