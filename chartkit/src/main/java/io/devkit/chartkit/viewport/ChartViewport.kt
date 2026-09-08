package io.devkit.chartkit.viewport

import androidx.compose.runtime.Immutable
import io.devkit.chartkit.geometry.ChartMath
import io.devkit.chartkit.scale.NumericDomain

/**
 * The part of the domain a chart is currently showing.
 *
 * ### Why a window over the domain, and not a canvas transform
 *
 * Zooming by scaling the canvas is one line and wrong in five places: the axis
 * labels scale into unreadable sizes, the tick values stop being round, stroke
 * widths grow with the zoom, hit testing lands on the wrong point, and the
 * tooltip anchors to a position the data is no longer at. ChartKit therefore
 * zooms by changing **which domain values the scales map**, and everything
 * downstream — axes, ticks, labels, geometry, hit testing, crosshair, range
 * selection — is recomputed from the narrowed domain and stays correct by
 * construction.
 *
 * ### Why a fraction of the domain
 *
 * The window is expressed as `[start, end]` in `0..1` of the full domain rather
 * than as absolute values. That is what lets one type serve a numeric axis, a
 * time axis and a category axis: a category axis has no continuous value to
 * store, but "the middle third of the bands" is meaningful on all three.
 * [visibleDomain] and [visibleCategoryRange] convert to logical values, and the
 * chart's state publishes those, so a caller reading "which dates am I showing"
 * gets dates.
 *
 * @param start the left edge, `0` being the start of the full domain.
 * @param end the right edge, `1` being its end.
 */
@Immutable
data class ChartViewport(
    val start: Double = 0.0,
    val end: Double = 1.0,
) {
    init {
        require(start.isFinite() && end.isFinite()) {
            "A viewport needs finite bounds, was [$start, $end]"
        }
        // Strictly positive, not "at least MIN_WIDTH": the clamping in
        // `zoomedBy` targets MIN_WIDTH exactly, and floating-point subtraction
        // lands a hair below it. Rejecting that would make the type refuse a
        // viewport its own zoom limit produces. MIN_WIDTH stays the floor the
        // mutators clamp to; the constructor only forbids an actual collapse.
        require(end - start > 0.0) {
            "A viewport must have width — [$start, $end] would collapse the domain"
        }
    }

    /** The fraction of the full domain on screen. */
    val width: Double get() = end - start

    /** `1` when fully zoomed out, `4` when a quarter of the domain is shown. */
    val zoom: Double get() = ChartMath.safeDiv(1.0, width, fallback = 1.0)

    /** True when the whole domain is visible, to within rounding. */
    val isFullyZoomedOut: Boolean
        get() = start <= MIN_WIDTH && end >= 1.0 - MIN_WIDTH

    /**
     * True when the window's right edge is at the end of the domain.
     *
     * What "following the latest data" means on a live chart, and how a
     * streaming adapter tells that the reader has panned back into the history:
     * a viewport that is no longer trailing is one somebody moved.
     */
    val isTrailing: Boolean get() = end >= 1.0 - MIN_WIDTH

    /**
     * Zoomed by [factor] about [focus], a fraction of the *viewport* width.
     *
     * Keeping the domain value under the focal point fixed is what makes a
     * pinch feel like a real analytical chart: the reader puts two fingers on
     * March and expects March to stay under their fingers. Zooming about the
     * centre instead makes the data slide away from the gesture.
     *
     * Clamped to [maxZoom] and to the full domain, so no gesture can produce a
     * viewport wider than the data or narrower than the limit.
     */
    fun zoomedBy(factor: Double, focus: Double = 0.5, maxZoom: Double = DEFAULT_MAX_ZOOM): ChartViewport {
        if (!factor.isFinite() || factor <= 0.0) return this
        val focalFraction = ChartMath.clamp(focus, 0.0, 1.0)
        // The domain value under the focal point, in full-domain coordinates.
        val anchor = start + width * focalFraction

        val minWidth = ChartMath.clamp(1.0 / maxZoom.coerceAtLeast(1.0), MIN_WIDTH, 1.0)
        val targetWidth = ChartMath.clamp(width / factor, minWidth, 1.0)

        // Rebuild around the anchor, keeping it at the same fraction of the
        // new window — that is the invariant a focal-point zoom has to hold.
        var newStart = anchor - targetWidth * focalFraction
        var newEnd = newStart + targetWidth
        if (newStart < 0.0) {
            newStart = 0.0
            newEnd = targetWidth
        }
        if (newEnd > 1.0) {
            newEnd = 1.0
            newStart = 1.0 - targetWidth
        }
        return ChartViewport(newStart.coerceAtLeast(0.0), newEnd.coerceAtMost(1.0))
    }

    /**
     * Panned by [delta] fractions of the *full* domain, clamped to it.
     *
     * Clamping rather than overscrolling: a chart that can be dragged past its
     * own data shows empty space where the reader expects values, and there is
     * nothing there to come back for.
     */
    fun pannedBy(delta: Double): ChartViewport {
        if (!delta.isFinite() || delta == 0.0) return this
        val bounded = ChartMath.clamp(delta, -start, 1.0 - end)
        if (bounded == 0.0) return this
        return ChartViewport(start + bounded, end + bounded)
    }

    /** The whole domain again. */
    fun reset(): ChartViewport = Full

    /** The absolute interval this window selects out of [full]. */
    fun visibleDomain(full: NumericDomain): NumericDomain {
        val resolved = full.resolved()
        return NumericDomain(
            min = resolved.min + resolved.span * start,
            max = resolved.min + resolved.span * end,
        )
    }

    /**
     * The band indices visible out of [count], inclusive.
     *
     * Rounded outward so a partly visible band is still drawn and still
     * labelled, rather than disappearing the moment its centre leaves the
     * window.
     */
    fun visibleCategoryRange(count: Int): IntRange {
        if (count <= 0) return IntRange.EMPTY
        val first = kotlin.math.floor(start * count).toInt().coerceIn(0, count - 1)
        val last = (kotlin.math.ceil(end * count).toInt() - 1).coerceIn(first, count - 1)
        return first..last
    }

    /** Where [fraction] of the full domain sits within this window, in `0..1`. */
    fun fractionWithin(fraction: Double): Double =
        ChartMath.safeDiv(fraction - start, width, fallback = 0.0)

    companion object {

        /** The whole domain. */
        val Full: ChartViewport = ChartViewport(0.0, 1.0)

        /**
         * The narrowest window the mutators will produce, as a fraction of the
         * domain.
         *
         * Not a style choice: much below this the scale's span underflows and
         * every mapped position becomes non-finite.
         */
        const val MIN_WIDTH: Double = 1e-6

        /**
         * How far in a chart zooms by default.
         *
         * Fifty times shows roughly two days of a hundred-day series — deep
         * enough to read individual points, shallow enough that a pinch cannot
         * strand the reader in a window with no data in it.
         */
        const val DEFAULT_MAX_ZOOM: Double = 50.0

        /** A window covering the last [fraction] of the domain. */
        fun trailing(fraction: Double): ChartViewport {
            val width = ChartMath.clamp(fraction, MIN_WIDTH * 2, 1.0)
            return ChartViewport(1.0 - width, 1.0)
        }

        /** The window between two fractions, ordered and clamped. */
        fun between(from: Double, to: Double): ChartViewport {
            val low = ChartMath.clamp(minOf(from, to), 0.0, 1.0)
            val high = ChartMath.clamp(maxOf(from, to), 0.0, 1.0)
            return if (high - low <= MIN_WIDTH) Full else ChartViewport(low, high)
        }
    }
}

/**
 * Zoom limits.
 *
 * @param maxZoom how far in the chart may go: `1` disables zooming in, `50`
 *   shows a fiftieth of the domain.
 * @param minZoom how far out. Fixed at `1` — the full domain — because a chart
 *   zoomed out past its own data is showing blank space, and the reader has no
 *   way to tell that from missing data.
 */
@Immutable
data class ChartZoomLimits(
    val maxZoom: Double = ChartViewport.DEFAULT_MAX_ZOOM,
) {
    init {
        require(maxZoom >= 1.0 && maxZoom.isFinite()) {
            "maxZoom must be at least 1 (fully zoomed out), was $maxZoom"
        }
    }

    val minZoom: Double get() = 1.0

    companion object {
        val Default: ChartZoomLimits = ChartZoomLimits()
    }
}
