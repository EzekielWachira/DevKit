package io.devkit.chartkit.scale

import io.devkit.chartkit.geometry.ChartMath
import kotlin.math.sqrt

/**
 * How a data value is turned into a visual size.
 *
 * The choice is not cosmetic. A reader judges a circle by its **area**, not by
 * its radius, so mapping a value straight onto a radius makes a bubble twice
 * the value look four times as large. That is the single most common way a
 * bubble chart lies, and it is the default here that prevents it.
 */
enum class SizeScaleMode {

    /**
     * Value maps to **area**; the radius is proportional to its square root.
     * The default.
     *
     * A value twice as large draws a bubble occupying twice the area, which is
     * what the reader perceives it as.
     */
    Area,

    /**
     * Value maps to radius directly.
     *
     * Exaggerates differences and is almost never the honest choice for a
     * quantity. Offered because a *radius* is occasionally the quantity itself
     * — a tolerance, a physical dimension — and because a library that only
     * offers the correct mapping still has to be able to say why.
     */
    Radius,
}

/**
 * Maps a numeric domain onto a range of visual sizes, in pixels.
 *
 * ```text
 * market cap 2M .. 900M   →   radius 4dp .. 32dp   (by area)
 * ```
 *
 * Plain Kotlin, and takes pixels rather than `Dp`: the conversion from the
 * theme's `Dp` bounds happens at the boundary, in the chart, where a `Density`
 * exists. That keeps the arithmetic — the part that decides whether a bubble
 * chart is honest — testable on the JVM.
 *
 * @param domain the values being mapped. Degenerate domains are resolved, so a
 *   dataset whose sizes are all equal draws every bubble at the midpoint of the
 *   range rather than dividing by zero.
 * @param minSize the size the domain minimum maps to. Not zero by default at
 *   any call site in ChartKit: a bubble of radius zero is an absent bubble, and
 *   a reader cannot distinguish "smallest" from "missing".
 * @param maxSize the size the domain maximum maps to.
 * @param mode whether the value drives area or radius.
 */
class SizeScale(
    domain: NumericDomain,
    val minSize: Float,
    val maxSize: Float,
    val mode: SizeScaleMode = SizeScaleMode.Area,
) {
    init {
        require(minSize >= 0f && minSize.isFinite()) {
            "A size scale's minimum must be a finite value >= 0, was $minSize"
        }
        require(maxSize >= minSize) {
            "A size scale's maximum ($maxSize) must be at least its minimum ($minSize)"
        }
    }

    /** The interval actually mapped — never degenerate, never non-finite. */
    val domain: NumericDomain = domain.resolved()

    /**
     * The size for [value], clamped into `[minSize, maxSize]`.
     *
     * Clamped rather than extrapolated: a bubble drawn larger than the scale's
     * maximum has no legend entry a reader can measure it against, and one
     * drawn at a negative radius does not draw at all. A missing or non-finite
     * value yields [minSize], because a bubble has to have a size to exist and
     * the smallest one makes the least claim.
     */
    fun size(value: Double?): Float {
        if (value == null || !value.isFinite()) return minSize
        val fraction = ChartMath.clamp(
            ChartMath.safeDiv(value - domain.min, domain.span),
            0.0,
            1.0,
        )
        return when (mode) {
            SizeScaleMode.Radius -> minSize + (maxSize - minSize) * fraction.toFloat()
            // Interpolate the *areas*, then take the radius back out of the
            // result. Interpolating the radii and calling it area-proportional
            // is the mistake this mode exists to avoid.
            SizeScaleMode.Area -> {
                val minArea = minSize * minSize
                val maxArea = maxSize * maxSize
                val area = minArea + (maxArea - minArea) * fraction.toFloat()
                if (area <= 0f) minSize else sqrt(area)
            }
        }
    }

    /**
     * Representative values and their sizes, for a size legend.
     *
     * Round numbers from the same generator the axes use, so a bubble legend
     * reads `10M, 20M, 30M` rather than `12.4M, 24.8M, 37.2M`.
     */
    fun legendStops(count: Int = 3): List<Pair<Double, Float>> {
        val ticks = TickGenerator.ticks(domain, count.coerceAtLeast(2))
        return ticks.map { it to size(it) }
    }

    override fun toString(): String =
        "SizeScale(domain=[${domain.min}, ${domain.max}], size=[$minSize, $maxSize], $mode)"
}
