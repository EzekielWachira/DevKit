package io.devkit.chartkit.geometry

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A point in ChartKit's own coordinate space.
 *
 * Deliberately not `androidx.compose.ui.geometry.Offset`. Everything in
 * [io.devkit.chartkit.geometry], [io.devkit.chartkit.scale] and
 * [io.devkit.chartkit.layout] is plain Kotlin so the engine can be tested on
 * the JVM without Robolectric and moved to Compose Multiplatform later without
 * unpicking Android types from the maths. The Compose layer converts at the
 * boundary, which costs one allocation per drawn primitive and buys the
 * separation.
 */
data class ChartOffset(val x: Float, val y: Float) {

    /** True when both components are real numbers a renderer can use. */
    val isFinite: Boolean get() = x.isFinite() && y.isFinite()

    companion object {
        val Zero: ChartOffset = ChartOffset(0f, 0f)
    }
}

/**
 * An axis-aligned rectangle, in pixels, with the origin at the top left.
 *
 * The same convention as Compose and the Android canvas: `top` is the smaller
 * y. A rectangle whose [bottom] is above its [top] is not an error here — bar
 * geometry produces them naturally for negative values — so consumers read
 * [normalized] when they need a well-ordered rectangle to draw.
 */
data class ChartRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    val isFinite: Boolean
        get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()

    /**
     * True when the rectangle encloses a drawable area.
     *
     * A zero-width or zero-height plot area is the common case at the first
     * frame, before Compose has measured anything, and every layer checks this
     * rather than dividing by a width of `0`.
     */
    val isEmpty: Boolean get() = !isFinite || width <= 0f || height <= 0f

    /** The same rectangle with `left <= right` and `top <= bottom`. */
    val normalized: ChartRect
        get() = ChartRect(
            left = min(left, right),
            top = min(top, bottom),
            right = max(left, right),
            bottom = max(top, bottom),
        )

    fun contains(x: Float, y: Float): Boolean {
        val r = normalized
        return x >= r.left && x <= r.right && y >= r.top && y <= r.bottom
    }

    fun contains(offset: ChartOffset): Boolean = contains(offset.x, offset.y)

    /** Shrinks the rectangle by [insets], never past zero size. */
    fun inset(insets: ChartInsets): ChartRect = ChartRect(
        left = left + insets.left,
        top = top + insets.top,
        right = max(left + insets.left, right - insets.right),
        bottom = max(top + insets.top, bottom - insets.bottom),
    )

    companion object {
        val Zero: ChartRect = ChartRect(0f, 0f, 0f, 0f)

        fun fromSize(width: Float, height: Float): ChartRect = ChartRect(0f, 0f, width, height)
    }
}

/**
 * Edge insets in pixels.
 *
 * Used by the layout engine to carve axis gutters, the legend strip and the
 * chart's own content padding out of the composable's bounds. Individual layers
 * never see them — they are given a finished [ChartRect] plot area, which is
 * what stops each chart type inventing its own `leftPadding = 48.dp`.
 */
data class ChartInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    operator fun plus(other: ChartInsets): ChartInsets = ChartInsets(
        left = left + other.left,
        top = top + other.top,
        right = right + other.right,
        bottom = bottom + other.bottom,
    )

    companion object {
        val Zero: ChartInsets = ChartInsets()
    }
}

/**
 * Numeric guards shared by every layout and scale calculation.
 *
 * Charts divide by ranges that can legitimately be zero — a single data point,
 * a constant series, a plot area measured before layout — and every one of
 * those divisions produces a `NaN` or an `Infinity` that reaches a `drawPath`
 * and either draws nothing or throws inside the renderer. The failures are
 * indistinguishable from a rendering bug, so the arithmetic is centralised here
 * and the layers call it instead of dividing directly.
 */
internal object ChartMath {

    /** Below this, two floating-point values are treated as the same number. */
    const val EPSILON: Double = 1e-9

    /** [numerator] / [denominator], or [fallback] when the result is not finite. */
    fun safeDiv(numerator: Double, denominator: Double, fallback: Double = 0.0): Double {
        if (abs(denominator) < EPSILON) return fallback
        val result = numerator / denominator
        return if (result.isFinite()) result else fallback
    }

    /** [value] if it is a real number, otherwise [fallback]. */
    fun finiteOr(value: Double, fallback: Double): Double =
        if (value.isFinite()) value else fallback

    /** [value] if it is a real number, otherwise [fallback]. */
    fun finiteOr(value: Float, fallback: Float): Float =
        if (value.isFinite()) value else fallback

    fun lerp(start: Double, end: Double, fraction: Double): Double =
        start + (end - start) * fraction

    fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction

    fun clamp(value: Double, minimum: Double, maximum: Double): Double =
        when {
            !value.isFinite() -> minimum
            minimum > maximum -> minimum
            value < minimum -> minimum
            value > maximum -> maximum
            else -> value
        }

    fun clamp(value: Float, minimum: Float, maximum: Float): Float =
        when {
            !value.isFinite() -> minimum
            minimum > maximum -> minimum
            value < minimum -> minimum
            value > maximum -> maximum
            else -> value
        }

    /** True when the two values are within [EPSILON] of each other. */
    fun approximatelyEqual(a: Double, b: Double): Boolean = abs(a - b) < EPSILON
}
