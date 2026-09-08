package io.devkit.chartkit.coordinate

import io.devkit.chartkit.geo.GeoCoordinate
import io.devkit.chartkit.geo.GeoProjection
import io.devkit.chartkit.geo.ProjectedBounds
import io.devkit.chartkit.geo.ProjectedPoint
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import kotlin.math.min

/**
 * A geographic plot: a projection, a fitted extent, and a viewport over it.
 *
 * The third sibling of [CartesianCoordinates] and [PolarCoordinates], and the
 * reason a choropleth is a ChartKit chart rather than a picture of polygons.
 * Everything above the coordinate system — the layer model, the selection
 * model, the tooltip overlay, the legend, the animation clock, the theme
 * lookup, the accessibility summary, the capture modifier — is shared with
 * every other chart, exactly as it is for polar.
 *
 * ### The transform
 *
 * ```text
 * longitude / latitude
 *         ↓  projection
 * projected x / y            (whatever units the projection emits)
 *         ↓  fit             (uniform scale + centre, y flipped)
 * screen x / y
 *         ↓  viewport        (zoom about a focus, then pan)
 * final screen position
 * ```
 *
 * Four steps, each reversible, and the reason [screenOf] and [projectedAt] are
 * exact inverses — which is what makes hit testing correct after a zoom rather
 * than approximately correct.
 *
 * ### One scale, both axes
 *
 * The fit uses the **same** scale for x and y. Fitting each axis independently
 * would stretch the geography to fill the plot, and a country drawn 30% wider
 * than it is is not a map of that country. The consequence is letterboxing: a
 * tall region in a wide plot leaves space at the sides, and that is correct.
 *
 * ### The y flip
 *
 * Projections emit y increasing **northward**; screens increase downward. The
 * flip happens once, here, in [screenOf]. No projection, layer or hit test flips
 * a sign of its own — which is what stops one of them getting it wrong and
 * producing a map that is upside down only when zoomed.
 */
class GeoCoordinates(
    override val plotArea: ChartRect,
    val projection: GeoProjection,
    /** The projected extent being shown, before zoom. */
    val extent: ProjectedBounds,
    /** Magnification about the plot's centre. `1` fits the whole extent. */
    val zoom: Float = 1f,
    /** Pan, as a fraction of the plot's own size. */
    val panX: Float = 0f,
    val panY: Float = 0f,
    /** Space kept between the geography and the plot's edge, in pixels. */
    val padding: Float = 0f,
) : CoordinateSystem {

    /** True when there is a region to draw into and geography to draw. */
    val isDrawable: Boolean get() = !plotArea.isEmpty && !extent.isEmpty

    /** The plot minus the map padding — where geography is actually fitted. */
    val contentArea: ChartRect = ChartRect(
        left = plotArea.left + padding,
        top = plotArea.top + padding,
        right = plotArea.right - padding,
        bottom = plotArea.bottom - padding,
    )

    /**
     * Pixels per projected unit, at zoom 1.
     *
     * The smaller of the two axis ratios, so the whole extent fits inside the
     * content area on both axes.
     */
    private val baseScale: Double = if (contentArea.isEmpty || extent.isEmpty) {
        0.0
    } else {
        min(
            contentArea.width.toDouble() / extent.width,
            contentArea.height.toDouble() / extent.height,
        )
    }

    /** The effective scale, after zoom. */
    val scale: Double get() = baseScale * zoom

    private val centerX = (extent.minX + extent.maxX) / 2.0
    private val centerY = (extent.minY + extent.maxY) / 2.0

    private val offsetX = contentArea.centerX + panX * contentArea.width
    private val offsetY = contentArea.centerY + panY * contentArea.height

    /** The screen position of a projected point. */
    fun screenOf(point: ProjectedPoint): ChartOffset {
        if (!point.isFinite || scale <= 0.0) return NonFinite
        return ChartOffset(
            x = (offsetX + (point.x - centerX) * scale).toFloat(),
            // Negated: projected y grows north, screen y grows down.
            y = (offsetY - (point.y - centerY) * scale).toFloat(),
        )
    }

    /** The screen position of a coordinate, projected and fitted. */
    fun screenOf(coordinate: GeoCoordinate): ChartOffset =
        screenOf(projection.project(coordinate))

    /**
     * The projected point under a screen position.
     *
     * The exact inverse of [screenOf], which is what lets hit testing run in
     * projected space: the pointer is converted once, and every polygon test
     * then happens against geometry that was projected once at load.
     */
    fun projectedAt(offset: ChartOffset): ProjectedPoint {
        if (scale <= 0.0) return ProjectedPoint(Double.NaN, Double.NaN)
        return ProjectedPoint(
            x = centerX + (offset.x - offsetX) / scale,
            y = centerY - (offset.y - offsetY) / scale,
        )
    }

    /** The coordinate under a screen position, or `null` off the projection. */
    fun coordinateAt(offset: ChartOffset): GeoCoordinate? =
        projection.invert(projectedAt(offset))

    /**
     * The projected extent currently visible.
     *
     * What viewport culling tests against: a feature whose box misses this
     * cannot be on screen. At zoom 1 with no pan it is [extent] widened to the
     * plot's aspect ratio, so nothing is culled from an unzoomed map.
     */
    fun visibleExtent(): ProjectedBounds {
        if (scale <= 0.0) return extent
        val topLeft = projectedAt(ChartOffset(plotArea.left, plotArea.top))
        val bottomRight = projectedAt(ChartOffset(plotArea.right, plotArea.bottom))
        return ProjectedBounds(
            minX = min(topLeft.x, bottomRight.x),
            minY = min(topLeft.y, bottomRight.y),
            maxX = kotlin.math.max(topLeft.x, bottomRight.x),
            maxY = kotlin.math.max(topLeft.y, bottomRight.y),
        )
    }

    /**
     * The zoom at which [target] would exactly fill the content area.
     *
     * What "focus on this county" needs. Returned rather than applied, so the
     * caller decides whether to jump or animate — and so the viewport state
     * stays the single owner of the camera.
     */
    fun zoomToFit(target: ProjectedBounds): Float {
        if (target.isEmpty || extent.isEmpty || baseScale <= 0.0) return 1f
        val fit = min(
            contentArea.width.toDouble() / target.width,
            contentArea.height.toDouble() / target.height,
        )
        return (fit / baseScale).toFloat().coerceAtLeast(1f)
    }

    /**
     * The pan that would centre [target], at the zoom [zoomToFit] returns.
     *
     * Expressed in the same plot-fraction units [panX] and [panY] use, so the
     * result can be handed straight to the viewport state.
     */
    fun panToCentre(target: ProjectedBounds, atZoom: Float): Pair<Float, Float> {
        if (target.isEmpty || contentArea.isEmpty || baseScale <= 0.0) return 0f to 0f
        val effective = baseScale * atZoom
        val targetCentreX = (target.minX + target.maxX) / 2.0
        val targetCentreY = (target.minY + target.maxY) / 2.0
        // Solve `screenOf(centre) == contentArea.centre` for the pan.
        val dx = -(targetCentreX - centerX) * effective / contentArea.width
        val dy = (targetCentreY - centerY) * effective / contentArea.height
        return dx.toFloat() to dy.toFloat()
    }

    override fun toString(): String =
        "GeoCoordinates(${projection.name}, zoom=$zoom, plot=$plotArea)"

    private companion object {
        val NonFinite = ChartOffset(Float.NaN, Float.NaN)
    }
}
