package io.devkit.chartkit.scale

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import io.devkit.chartkit.geometry.ChartMath

/**
 * Maps a value onto a colour.
 *
 * The third kind of scale, alongside position and size, and the one a heatmap
 * is built on. It is a scale and not a heatmap detail on purpose: a colour
 * scale is equally the right way to shade scatter points by a third variable,
 * or to colour bars by severity, and a `Heatmap` that owned its own colour
 * logic would be the only chart able to do it.
 *
 * ### Missing is not zero
 *
 * Every implementation returns `null` for a value it cannot place — a missing
 * measurement, a `NaN`. Charts draw that as their theme's "no data" treatment,
 * which is visually distinct from the low end of the scale. A heatmap that
 * paints an absent cell with the colour of zero is asserting a measurement
 * nobody took.
 */
@Immutable
sealed interface ColorScale {

    /** The colour for [value], or `null` when it cannot be placed. */
    fun colorAt(value: Double?): Color?

    /**
     * Values and colours for a legend, low to high.
     *
     * Each implementation returns what it can honestly show: a continuous scale
     * returns samples along its ramp, a threshold scale returns its bands.
     */
    fun legendStops(count: Int = DEFAULT_LEGEND_STOPS): List<ColorStop>

    /**
     * A smooth ramp between two or more colours across a numeric domain.
     *
     * ```text
     * min ──────────────────────── max
     * low          mid            high
     * ```
     *
     * Interpolation is component-wise in sRGB, which is what a reader's screen
     * shows and what every other tool in this space does. It is not
     * perceptually uniform: a ramp through two distant hues passes through a
     * desaturated middle. Ramps between a light and a dark shade of one hue —
     * which is what [ChartColorScales] builds by default — do not have that
     * problem, and a caller who wants a perceptual ramp supplies its stops.
     *
     * @param colors at least two, in order from the domain minimum upward.
     */
    @Immutable
    data class Continuous(
        val domain: NumericDomain,
        val colors: List<Color>,
    ) : ColorScale {
        init {
            require(colors.size >= 2) {
                "A continuous colour scale needs at least two colours, had ${colors.size}"
            }
        }

        private val resolvedDomain: NumericDomain = domain.resolved()

        override fun colorAt(value: Double?): Color? {
            if (value == null || !value.isFinite()) return null
            val fraction = ChartMath.clamp(
                ChartMath.safeDiv(value - resolvedDomain.min, resolvedDomain.span),
                0.0,
                1.0,
            )
            return sampleRamp(colors, fraction.toFloat())
        }

        override fun legendStops(count: Int): List<ColorStop> {
            val stops = count.coerceAtLeast(2)
            return List(stops) { index ->
                val fraction = index.toDouble() / (stops - 1)
                val value = resolvedDomain.min + resolvedDomain.span * fraction
                ColorStop(value, sampleRamp(colors, fraction.toFloat()), null)
            }
        }
    }

    /**
     * Explicit bands with explicit colours.
     *
     * ```text
     * < 20    ▇ ok
     * 20–50   ▇ elevated
     * 50–80   ▇ high
     * >= 80   ▇ critical
     * ```
     *
     * For quantities whose meaning is categorical even though their values are
     * numeric — risk, severity, service level. A continuous ramp over those
     * invites a reader to interpolate between two states that have no in
     * between.
     *
     * @param thresholds the ascending upper bounds of all but the last band.
     * @param colors one more than [thresholds] — the band below the first
     *   threshold, each band between two, and the band above the last.
     * @param labels optional band names, used by legends and by accessibility.
     */
    @Immutable
    data class Threshold(
        val thresholds: List<Double>,
        val colors: List<Color>,
        val labels: List<String> = emptyList(),
    ) : ColorScale {
        init {
            require(thresholds.isNotEmpty()) { "A threshold scale needs at least one threshold" }
            require(thresholds.all { it.isFinite() }) { "Thresholds must all be finite" }
            require(thresholds.zipWithNext().all { (a, b) -> b > a }) {
                "Thresholds must be strictly ascending, were $thresholds"
            }
            require(colors.size == thresholds.size + 1) {
                "A threshold scale needs one more colour than it has thresholds: " +
                    "${thresholds.size} thresholds need ${thresholds.size + 1} colours, " +
                    "had ${colors.size}"
            }
        }

        /** The band [value] falls into, or `-1`. */
        fun bandOf(value: Double?): Int {
            if (value == null || !value.isFinite()) return -1
            return bandIndex(value, thresholds)
        }

        override fun colorAt(value: Double?): Color? =
            bandOf(value).takeIf { it >= 0 }?.let { colors[it] }

        override fun legendStops(count: Int): List<ColorStop> = colors.mapIndexed { index, color ->
            ColorStop(
                value = thresholds.getOrNull(index) ?: thresholds.last(),
                color = color,
                label = labels.getOrNull(index) ?: bandLabel(index),
            )
        }

        private fun bandLabel(index: Int): String = when {
            index == 0 -> "< ${thresholds.first()}"
            index == colors.size - 1 -> ">= ${thresholds.last()}"
            else -> "${thresholds[index - 1]} – ${thresholds[index]}"
        }
    }

    /**
     * A continuous ramp cut into [steps] equal bands.
     *
     * The middle ground between the other two: the domain is continuous, but
     * the reader is given a small number of distinguishable shades instead of
     * a gradient they cannot read a value off. Useful when the legend has to
     * be a key rather than a bar.
     */
    @Immutable
    data class Quantized(
        val domain: NumericDomain,
        val colors: List<Color>,
        val steps: Int,
    ) : ColorScale {
        init {
            require(colors.size >= 2) {
                "A quantized colour scale needs at least two ramp colours, had ${colors.size}"
            }
            require(steps >= 2) { "A quantized scale needs at least 2 steps, was $steps" }
        }

        private val resolvedDomain: NumericDomain = domain.resolved()

        /** The step [value] falls into, or `-1`. */
        fun stepOf(value: Double?): Int {
            if (value == null || !value.isFinite()) return -1
            val fraction = ChartMath.clamp(
                ChartMath.safeDiv(value - resolvedDomain.min, resolvedDomain.span),
                0.0,
                1.0,
            )
            return (fraction * steps).toInt().coerceIn(0, steps - 1)
        }

        override fun colorAt(value: Double?): Color? {
            val step = stepOf(value)
            if (step < 0) return null
            // The centre of each band, so the first and last steps are not the
            // extreme ends of the ramp — which would make a two-step scale
            // black and white.
            return sampleRamp(colors, ((step + 0.5f) / steps))
        }

        override fun legendStops(count: Int): List<ColorStop> = List(steps) { step ->
            val fraction = step.toDouble() / steps
            ColorStop(
                value = resolvedDomain.min + resolvedDomain.span * fraction,
                color = sampleRamp(colors, ((step + 0.5f) / steps)),
                label = null,
            )
        }
    }

    /**
     * A colour per category, by key.
     *
     * The odd one out — its domain is not numeric — but it belongs here because
     * a heatmap of states rather than magnitudes needs exactly this, and
     * because being a [ColorScale] is what lets it be passed wherever the
     * others are.
     */
    @Immutable
    data class Categorical(
        val keys: List<String>,
        val colors: List<Color>,
    ) : ColorScale {
        init {
            require(keys.isNotEmpty()) { "A categorical colour scale needs at least one key" }
            require(colors.isNotEmpty()) { "A categorical colour scale needs at least one colour" }
        }

        /** The colour for [key], wrapping the palette when there are more keys. */
        fun colorFor(key: String): Color? {
            val index = keys.indexOf(key)
            return if (index < 0) null else colors[index % colors.size]
        }

        /** Categorical scales are indexed by key; a numeric value indexes the list. */
        override fun colorAt(value: Double?): Color? {
            if (value == null || !value.isFinite()) return null
            val index = value.toInt()
            return if (index in keys.indices) colors[index % colors.size] else null
        }

        override fun legendStops(count: Int): List<ColorStop> = keys.mapIndexed { index, key ->
            ColorStop(index.toDouble(), colors[index % colors.size], key)
        }
    }

    companion object {
        /** Enough to read a ramp, few enough to fit beside a chart. */
        const val DEFAULT_LEGEND_STOPS: Int = 5
    }
}

/** One entry of a colour legend. */
@Immutable
data class ColorStop(val value: Double, val color: Color, val label: String?)

/**
 * The band [value] falls into, given ascending [thresholds].
 *
 * Bands are `[..., t0)`, `[t0, t1)`, …, `[tn, ...)` — half-open upward, so a
 * value exactly on a threshold belongs to the band that threshold opens. That
 * matches how thresholds are stated in practice: "80 and above is critical"
 * puts 80 in the critical band.
 *
 * Pure and separate from any colour, so the banding rule is verifiable on its
 * own.
 */
internal fun bandIndex(value: Double, thresholds: List<Double>): Int {
    if (!value.isFinite()) return -1
    var low = 0
    var high = thresholds.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (thresholds[mid] <= value) low = mid + 1 else high = mid
    }
    return low
}

/**
 * The colour at [fraction] of the way along a multi-stop ramp.
 *
 * Component-wise in sRGB, including alpha. Pure: no color-space conversion, no
 * Android graphics, nothing that needs a device to evaluate.
 */
internal fun sampleRamp(colors: List<Color>, fraction: Float): Color {
    if (colors.isEmpty()) return Color.Transparent
    if (colors.size == 1) return colors[0]
    val bounded = fraction.coerceIn(0f, 1f)
    val scaled = bounded * (colors.size - 1)
    val index = scaled.toInt().coerceIn(0, colors.size - 2)
    val local = scaled - index
    return lerpColor(colors[index], colors[index + 1], local)
}

/** Component-wise sRGB interpolation. */
internal fun lerpColor(from: Color, to: Color, fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * t,
        green = from.green + (to.green - from.green) * t,
        blue = from.blue + (to.blue - from.blue) * t,
        alpha = from.alpha + (to.alpha - from.alpha) * t,
    )
}
