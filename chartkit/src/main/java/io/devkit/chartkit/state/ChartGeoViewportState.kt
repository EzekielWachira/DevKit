package io.devkit.chartkit.state

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.geo.GeoBounds

/**
 * How far the reader may drag the geography off the plot.
 *
 * A map is not a scrollable document: dragging it until it is off screen leaves
 * the reader looking at nothing, with no indication of which way to drag back.
 * So panning is bounded — and the only real question is how hard.
 */
enum class GeoPanConstraint {

    /**
     * The geography's edges may not come inside the plot. The default.
     *
     * Zoomed out there is nothing to pan at all, because the map already fits;
     * zoomed in, the limit is exactly how far the enlarged map extends past the
     * plot's edge. The reader can never see past the map.
     */
    Strict,

    /**
     * As [Strict], plus a margin of the plot's own size.
     *
     * Room to drag a coastal region away from the edge so a tooltip or a label
     * near it has somewhere to go, without ever losing the map entirely.
     */
    Soft,

    /**
     * Unbounded.
     *
     * For a caller doing their own framing — an animated tour, a synchronised
     * pair of maps — where ChartKit's idea of "too far" would fight theirs.
     */
    None,
}

/**
 * A map's camera: how far in, and where.
 *
 * ```kotlin
 * val camera = rememberChartGeoViewportState()
 *
 * ChoroplethMap(geometry = counties, data = rates, viewportState = camera, ...)
 * Button(onClick = { camera.reset() }, enabled = !camera.isReset) { Text("Reset") }
 * ```
 *
 * ### Why not [ChartViewportState]
 *
 * The Cartesian viewport is a window over **one** domain, expressed as a pair
 * of fractions, and it pans along a single axis. A map zooms about a point and
 * pans in two dimensions at once, and its "fully out" state is a fit to the
 * geometry rather than a fixed `0..1`. Forcing both into one type would have
 * meant a viewport whose second axis is meaningless for every chart but this
 * one — so this is a separate, small state object, and
 * [io.devkit.chartkit.coordinate.GeoCoordinates] reads it exactly as the
 * Cartesian coordinates read theirs.
 *
 * ### Pan is in plot fractions
 *
 * Not in pixels and not in degrees. Pixels would make a saved camera wrong on a
 * different screen; degrees would make a pan mean different distances at
 * different zooms. A fraction of the plot survives both, which is what lets
 * [rememberSaveableChartGeoViewportState] restore a rotated device to the same
 * view.
 */
@Stable
class ChartGeoViewportState internal constructor(
    initialZoom: Float = 1f,
    initialPanX: Float = 0f,
    initialPanY: Float = 0f,
    maxZoom: Float = DEFAULT_MAX_ZOOM,
    val panConstraint: GeoPanConstraint = GeoPanConstraint.Strict,
) {

    /** Magnification. `1` fits the whole geometry into the plot. */
    var zoom: Float by mutableFloatStateOf(initialZoom.coerceIn(MIN_ZOOM, maxZoom))
        private set

    /** Horizontal pan, as a fraction of the plot's width. */
    var panX: Float by mutableFloatStateOf(initialPanX)
        private set

    /** Vertical pan, as a fraction of the plot's height. */
    var panY: Float by mutableFloatStateOf(initialPanY)
        private set

    /** How far in the reader may pinch. */
    var maxZoom: Float by mutableFloatStateOf(maxZoom)
        internal set

    /**
     * The geographic extent of the whole map, published by the chart.
     *
     * Here so a caller can ask what they are looking at without holding the
     * geometry themselves, and so [focusOn] can be given a region rather than a
     * zoom number.
     */
    var geometryBounds: GeoBounds? by mutableStateOf(null)
        internal set

    /** True when the whole map is showing — nothing to reset. */
    val isReset: Boolean
        get() = zoom <= MIN_ZOOM + ZOOM_EPSILON && panX == 0f && panY == 0f

    /**
     * Multiplies the zoom by [factor], keeping the geography under [focusX],
     * [focusY] — plot fractions, `0.5, 0.5` being the centre — in place.
     *
     * Anchoring to the focus is what makes a pinch feel attached to the
     * fingers: zooming about the plot's centre instead would slide whatever the
     * reader was pinching out from under them.
     *
     * Called on every gesture frame, so it does no animation of its own.
     */
    fun zoomBy(factor: Float, focusX: Float = 0.5f, focusY: Float = 0.5f) {
        if (!factor.isFinite() || factor <= 0f) return
        val next = (zoom * factor).coerceIn(MIN_ZOOM, maxZoom)
        val applied = next / zoom
        if (applied == 1f) return

        // The focus, measured from the plot's centre. After scaling by
        // `applied`, the pan must move by the amount that point would otherwise
        // have travelled.
        val offsetX = focusX - 0.5f
        val offsetY = focusY - 0.5f
        panX = clampPan((panX - offsetX) * applied + offsetX, next)
        panY = clampPan((panY - offsetY) * applied + offsetY, next)
        zoom = next
    }

    /** Pans by fractions of the plot's own size. */
    fun panBy(deltaX: Float, deltaY: Float) {
        if (!deltaX.isFinite() || !deltaY.isFinite()) return
        panX = clampPan(panX + deltaX, zoom)
        panY = clampPan(panY + deltaY, zoom)
    }

    /** Sets the camera directly, clamped. */
    fun setCamera(zoom: Float, panX: Float, panY: Float) {
        this.zoom = zoom.coerceIn(MIN_ZOOM, maxZoom)
        this.panX = clampPan(panX, this.zoom)
        this.panY = clampPan(panY, this.zoom)
    }

    /**
     * Sets the zoom directly, keeping the geography under the focus in place.
     *
     * The absolute form of [zoomBy], for a caller driving the camera from a
     * slider or a stepper rather than from a pinch. Expressed as a factor
     * relative to the current zoom, so the anchoring is identical and there is
     * one implementation of it.
     */
    fun zoomTo(zoom: Float, focusX: Float = 0.5f, focusY: Float = 0.5f) {
        if (!zoom.isFinite() || zoom <= 0f) return
        val target = zoom.coerceIn(MIN_ZOOM, maxZoom)
        if (this.zoom <= 0f) return
        zoomBy(target / this.zoom, focusX, focusY)
    }

    /** Fits the whole map again. */
    fun reset() {
        zoom = MIN_ZOOM
        panX = 0f
        panY = 0f
    }

    /**
     * Fits the whole geometry into the plot.
     *
     * The same thing as [reset], and named separately because it is the same
     * thing for a reason worth stating: zoom `1` on a map **is** the fit, since
     * [io.devkit.chartkit.coordinate.GeoCoordinates] scales the projected
     * extent to the plot before the camera is applied. There is no separate
     * "fitted" state that could drift out of step with the camera.
     */
    fun fitToGeometry() {
        reset()
    }

    /**
     * Eases to a camera over [animation]'s data-change duration.
     *
     * For programmatic moves — "focus the selected county", "reset" — where a
     * jump loses the reader's sense of where they went. Gestures deliberately
     * do not animate.
     */
    suspend fun animateTo(
        zoom: Float,
        panX: Float,
        panY: Float,
        animation: ChartAnimation = ChartAnimation.Default,
    ) {
        val targetZoom = zoom.coerceIn(MIN_ZOOM, maxZoom)
        val targetPanX = clampPan(panX, targetZoom)
        val targetPanY = clampPan(panY, targetZoom)
        if (!animation.enabled) {
            setCamera(targetZoom, targetPanX, targetPanY)
            return
        }
        val fromZoom = this.zoom
        val fromPanX = this.panX
        val fromPanY = this.panY
        val progress = Animatable(0f)
        progress.animateTo(1f, animation.dataChangeSpec()) {
            this@ChartGeoViewportState.zoom = fromZoom + (targetZoom - fromZoom) * value
            this@ChartGeoViewportState.panX = fromPanX + (targetPanX - fromPanX) * value
            this@ChartGeoViewportState.panY = fromPanY + (targetPanY - fromPanY) * value
        }
        setCamera(targetZoom, targetPanX, targetPanY)
    }

    /** Eases back to the whole map. */
    suspend fun animateToReset(animation: ChartAnimation = ChartAnimation.Default) {
        animateTo(MIN_ZOOM, 0f, 0f, animation)
    }

    /**
     * The camera the chart should adopt to focus [bounds], published by the
     * chart on the next layout.
     *
     * Requested rather than computed here, because turning a geographic box
     * into a zoom needs the projection and the plot size, and this object has
     * neither — deliberately: a camera that knew about pixels could not be
     * saved and restored across a rotation.
     */
    var pendingFocus: GeoBounds? by mutableStateOf(null)
        private set

    /** Focuses [bounds] on the next frame. */
    fun focusOn(bounds: GeoBounds?) {
        pendingFocus = bounds?.takeIf { !it.isEmpty }
    }

    internal fun consumeFocus(): GeoBounds? = pendingFocus?.also { pendingFocus = null }

    /**
     * Keeps the pan from sliding the geometry off the plot.
     *
     * At zoom 1 the only valid pan is none: the map already fits, and letting
     * the reader drag it into a corner would be a bug, not a feature. Zoomed in,
     * the bound is how far the enlarged map extends past the plot's edge.
     */
    private fun clampPan(value: Float, atZoom: Float): Float {
        if (!value.isFinite()) return 0f
        if (panConstraint == GeoPanConstraint.None) return value
        val edge = ((atZoom - 1f) / 2f).coerceAtLeast(0f)
        val limit = if (panConstraint == GeoPanConstraint.Soft) edge + SOFT_MARGIN else edge
        return value.coerceIn(-limit, limit)
    }

    companion object {
        /** Fully zoomed out: the whole geometry fitted to the plot. */
        const val MIN_ZOOM: Float = 1f

        /** Far enough to read a city inside a national map, and no further. */
        const val DEFAULT_MAX_ZOOM: Float = 12f

        private const val ZOOM_EPSILON: Float = 1e-4f

        /** How far past the strict edge [GeoPanConstraint.Soft] allows. */
        private const val SOFT_MARGIN: Float = 0.25f
    }
}

/** Remembers a [ChartGeoViewportState]. */
@Composable
fun rememberChartGeoViewportState(
    initialZoom: Float = ChartGeoViewportState.MIN_ZOOM,
    maxZoom: Float = ChartGeoViewportState.DEFAULT_MAX_ZOOM,
    panConstraint: GeoPanConstraint = GeoPanConstraint.Strict,
): ChartGeoViewportState = remember(panConstraint) {
    ChartGeoViewportState(initialZoom, maxZoom = maxZoom, panConstraint = panConstraint)
}

/**
 * A [ChartGeoViewportState] that survives configuration changes.
 *
 * Three floats in plot-relative units, so the restored view is the same view on
 * a rotated screen — which a pixel pan would not have been.
 */
@Composable
fun rememberSaveableChartGeoViewportState(
    initialZoom: Float = ChartGeoViewportState.MIN_ZOOM,
    maxZoom: Float = ChartGeoViewportState.DEFAULT_MAX_ZOOM,
    panConstraint: GeoPanConstraint = GeoPanConstraint.Strict,
): ChartGeoViewportState = rememberSaveable(
    panConstraint,
    saver = listSaver(
        save = { listOf(it.zoom, it.panX, it.panY) },
        restore = { saved ->
            ChartGeoViewportState(
                initialZoom = saved[0],
                initialPanX = saved[1],
                initialPanY = saved[2],
                maxZoom = maxZoom,
                panConstraint = panConstraint,
            )
        },
    ),
) {
    ChartGeoViewportState(initialZoom, maxZoom = maxZoom, panConstraint = panConstraint)
}
