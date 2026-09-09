package io.devkit.chartkit.gauge

import io.devkit.chartkit.geometry.ChartMath
import io.devkit.chartkit.layer.polar.GaugeBand

/**
 * What a gauge does with a band that does not fit its range.
 *
 * A band from 160 to 250 on a gauge that stops at 200 is not obviously a
 * mistake — a caller may have one set of thresholds and several gauges — but
 * drawing it as though the arc extended to 250 would be. Every option here
 * produces geometry that is honest about the arc it is on.
 */
enum class GaugeBandOverflow {

    /** Trim the band to the gauge's range. The default. */
    Clamp,

    /** Leave a band that reaches outside the range undrawn. */
    Skip,

    /** A band outside the range is a programming error. Throws [GaugeException]. */
    Reject,
}

/**
 * What a gauge does when two bands cover the same values.
 *
 * Overlapping bands are ambiguous by construction: at a value in both, the
 * colour behind the needle is whichever was drawn last, and "which band am I
 * in" has two answers. So the behaviour is stated rather than left to draw
 * order.
 */
enum class GaugeBandOverlap {

    /**
     * Later bands are drawn over earlier ones, and the **first** match wins for
     * [GaugeBandResolution.bandAt]. The default.
     *
     * Deterministic without being restrictive: a caller layering a narrow
     * "target" band over a broad "acceptable" one gets what they drew, and the
     * status lookup still has one answer.
     */
    LastDrawnWins,

    /** Overlapping bands are a programming error. Throws [GaugeException]. */
    Reject,
}

/** How a band's arc is drawn, where it should not simply follow the track. */
data class GaugeBandStyle(
    /** `null` takes the theme's ordered band colours by position. */
    val color: Int? = null,
    val alpha: Float = 1f,
    /**
     * The band's thickness as a fraction of the track's.
     *
     * Relative rather than absolute so a band keeps its proportion when the
     * gauge is resized — the same rule the rest of the dial's geometry follows.
     */
    val thickness: Float = 1f,
    /**
     * Where the band sits across the track, `0` at the inner edge and `1` at
     * the outer.
     *
     * `0.5` centres it, which is what a band thinner than the track usually
     * wants. Combined with [thickness] it also expresses a band drawn just
     * outside the arc as a thin rail.
     */
    val position: Float = 0.5f,
    /** Rounded ends, for the modern KPI look. */
    val rounded: Boolean = false,
) {
    init {
        require(alpha in 0f..1f) { "Band alpha must be in 0..1, was $alpha" }
        require(thickness > 0f && thickness.isFinite()) {
            "Band thickness must be a positive fraction of the track, was $thickness"
        }
    }

    companion object {
        val Default: GaugeBandStyle = GaugeBandStyle()
    }
}

/** One band, resolved onto the arc it will actually be drawn on. */
class ResolvedGaugeBand(
    val band: GaugeBand,
    /** The band's position in the caller's own list, for palette slots. */
    val sourceIndex: Int,
    /** The drawn range, after any clamping. */
    val from: Double,
    val to: Double,
    val startAngle: Float,
    val sweepAngle: Float,
    val style: GaugeBandStyle,
    /** True when the band reached outside the gauge and was trimmed. */
    val clamped: Boolean,
) {
    val label: String? get() = band.label
    operator fun contains(value: Double): Boolean = value in from..to
}

/**
 * Every band a gauge will draw, plus the lookup a status readout needs.
 *
 * ### Why the lookup lives here
 *
 * "Which band is the value in" is asked in four places — the arc's colour, the
 * accessibility statement, the tooltip and the caller's own status chip — and
 * three of them are outside the layer that draws the bands. Answering it once,
 * against the same resolved geometry that was drawn, is what stops a chart
 * saying "Warning" while colouring the arc as normal.
 */
class GaugeBandResolution(
    val bands: List<ResolvedGaugeBand>,
    /** Bands the policy dropped, and why, for diagnostics. */
    val skipped: List<String>,
) {
    val isEmpty: Boolean get() = bands.isEmpty()

    /**
     * The band containing [value], or `null`.
     *
     * The **first** match in declaration order under
     * [GaugeBandOverlap.LastDrawnWins], so the answer does not depend on
     * drawing order even though the picture does.
     */
    fun bandAt(value: Double): ResolvedGaugeBand? =
        if (!value.isFinite()) null else bands.firstOrNull { value in it }

    companion object {

        /**
         * Resolves [bands] against [scale], applying the overflow and overlap
         * policies.
         *
         * @param styles a style per band, by index. Missing entries take
         *   [GaugeBandStyle.Default].
         */
        fun of(
            bands: List<GaugeBand>,
            scale: GaugeScale,
            overflow: GaugeBandOverflow = GaugeBandOverflow.Clamp,
            overlap: GaugeBandOverlap = GaugeBandOverlap.LastDrawnWins,
            styles: Map<Int, GaugeBandStyle> = emptyMap(),
        ): GaugeBandResolution {
            if (bands.isEmpty()) return GaugeBandResolution(emptyList(), emptyList())

            val resolved = ArrayList<ResolvedGaugeBand>(bands.size)
            val skipped = ArrayList<String>()

            bands.forEachIndexed { index, band ->
                val low = band.low
                val high = band.high

                // A band whose bounds are equal covers no values. Drawn, it is
                // an invisible hairline that still claims a slot in the legend
                // and still answers `bandAt` — so it is dropped and reported
                // rather than left to puzzle somebody.
                if (high - low <= ChartMath.EPSILON) {
                    skipped += "Band ${describe(band, index)} has zero width."
                    return@forEachIndexed
                }
                if (high < scale.min || low > scale.max) {
                    when (overflow) {
                        GaugeBandOverflow.Reject -> throw GaugeException(
                            "Band ${describe(band, index)} lies entirely outside the gauge's " +
                                "range [${scale.min}, ${scale.max}].",
                        )
                        else -> {
                            skipped += "Band ${describe(band, index)} is outside the gauge's range."
                            return@forEachIndexed
                        }
                    }
                }

                val partlyOutside = low < scale.min || high > scale.max
                if (partlyOutside) {
                    when (overflow) {
                        GaugeBandOverflow.Reject -> throw GaugeException(
                            "Band ${describe(band, index)} reaches outside the gauge's range " +
                                "[${scale.min}, ${scale.max}].",
                        )
                        GaugeBandOverflow.Skip -> {
                            skipped += "Band ${describe(band, index)} reaches outside the range."
                            return@forEachIndexed
                        }
                        GaugeBandOverflow.Clamp -> Unit
                    }
                }

                val drawnFrom = low.coerceIn(scale.min, scale.max)
                val drawnTo = high.coerceIn(scale.min, scale.max)
                if (drawnTo - drawnFrom <= ChartMath.EPSILON) {
                    skipped += "Band ${describe(band, index)} has no width inside the range."
                    return@forEachIndexed
                }

                val startAngle = scale.angleAtFraction((drawnFrom - scale.min) / scale.span)
                val endFraction = (drawnTo - scale.min) / scale.span
                val startFraction = (drawnFrom - scale.min) / scale.span
                resolved += ResolvedGaugeBand(
                    band = band,
                    sourceIndex = index,
                    from = drawnFrom,
                    to = drawnTo,
                    startAngle = startAngle,
                    sweepAngle = (scale.sweepAngle * (endFraction - startFraction)).toFloat(),
                    style = styles[index] ?: GaugeBandStyle(color = band.color),
                    clamped = partlyOutside,
                )
            }

            if (overlap == GaugeBandOverlap.Reject) {
                for (i in resolved.indices) {
                    for (j in i + 1 until resolved.size) {
                        val a = resolved[i]
                        val b = resolved[j]
                        if (a.from < b.to - ChartMath.EPSILON && b.from < a.to - ChartMath.EPSILON) {
                            throw GaugeException(
                                "Bands [${a.from}, ${a.to}] and [${b.from}, ${b.to}] overlap, and " +
                                    "the gauge is set to GaugeBandOverlap.Reject. At a value in " +
                                    "both, neither the colour nor the status has one answer.",
                            )
                        }
                    }
                }
            }

            return GaugeBandResolution(resolved, skipped)
        }

        private fun describe(band: GaugeBand, index: Int): String =
            band.label?.let { "\"$it\"" } ?: "#$index [${band.from}, ${band.to}]"
    }
}
