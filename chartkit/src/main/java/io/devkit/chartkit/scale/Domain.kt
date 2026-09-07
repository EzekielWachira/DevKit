package io.devkit.chartkit.scale

import io.devkit.chartkit.geometry.ChartMath
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A closed numeric interval, ready to be mapped onto a pixel range.
 *
 * Always well-ordered and always finite: the factories below repair a domain
 * rather than propagating one that cannot be divided by. That repair is the
 * whole reason the type exists — `[5, 5]` from a constant series and `[0, 0]`
 * from a single zero-valued point are both ordinary inputs, and both produce a
 * zero-width span that every mapping downstream would turn into `NaN`.
 */
data class NumericDomain(val min: Double, val max: Double) {

    /** `max - min`. Zero for a degenerate domain; never negative. */
    val span: Double get() = max - min

    /** True when the interval has no width and cannot be divided by directly. */
    val isDegenerate: Boolean get() = span < ChartMath.EPSILON

    /** True when the interval straddles or touches zero. */
    val includesZero: Boolean get() = min <= 0.0 && max >= 0.0

    operator fun contains(value: Double): Boolean = value in min..max

    /** Widens the domain to include [value]. */
    fun expandToInclude(value: Double): NumericDomain {
        if (!value.isFinite()) return this
        return NumericDomain(min(min, value), max(max, value))
    }

    /** Widens the domain to include zero, for charts with a meaningful baseline. */
    fun expandToIncludeZero(): NumericDomain = expandToInclude(0.0)

    /**
     * Adds [fraction] of the current span to each end.
     *
     * Applied to a degenerate domain it does nothing, because a fraction of
     * zero is zero — [resolved] handles that case instead.
     */
    fun padded(fraction: Double): NumericDomain {
        if (fraction <= 0.0 || isDegenerate) return this
        val amount = span * fraction
        return NumericDomain(min - amount, max + amount)
    }

    /**
     * A domain that is always safe to divide by.
     *
     * A degenerate interval is widened around its own value — by 1 for `[0, 0]`,
     * or by 10% of the magnitude otherwise — so a constant series draws as a
     * flat line across the middle of the plot rather than collapsing onto an
     * edge or vanishing into a `NaN`.
     */
    fun resolved(): NumericDomain {
        if (!min.isFinite() || !max.isFinite()) return Default
        val ordered = if (min <= max) this else NumericDomain(max, min)
        if (!ordered.isDegenerate) return ordered
        val magnitude = abs(ordered.min)
        val halfWidth = if (magnitude < ChartMath.EPSILON) 1.0 else magnitude * 0.1
        return NumericDomain(ordered.min - halfWidth, ordered.max + halfWidth)
    }

    companion object {

        /** What an empty dataset scales against, so axes still draw. */
        val Default: NumericDomain = NumericDomain(0.0, 1.0)

        /**
         * The tightest domain covering [values], ignoring non-finite entries.
         *
         * Returns `null` when nothing usable was supplied, which callers read as
         * "there is no data" rather than as an error.
         */
        fun of(values: Iterable<Double>): NumericDomain? {
            var minimum = Double.POSITIVE_INFINITY
            var maximum = Double.NEGATIVE_INFINITY
            var seen = false
            for (value in values) {
                if (!value.isFinite()) continue
                seen = true
                if (value < minimum) minimum = value
                if (value > maximum) maximum = value
            }
            return if (seen) NumericDomain(minimum, maximum) else null
        }
    }
}

/**
 * How an axis decides which numeric interval to show.
 *
 * The default differs by chart type on purpose. A bar encodes its value as a
 * length from a baseline, so a bar chart that does not include zero draws bars
 * whose relative heights lie — `[98, 100]` renders one bar twice the other.
 * A line encodes position, not length, so forcing zero onto a line of values
 * between `98` and `100` flattens the only variation worth seeing. Bars
 * therefore default to [IncludeZero] and lines to [Auto]; both are overridable,
 * and neither is decided inside a chart implementation.
 */
sealed interface DomainPolicy {

    /**
     * Fit the data, then add breathing room.
     *
     * @param padding the fraction of the data span added to each end.
     */
    data class Auto(val padding: Double = 0.05) : DomainPolicy {
        init {
            require(padding >= 0.0 && padding.isFinite()) {
                "Domain padding must be a finite fraction >= 0, was $padding"
            }
        }
    }

    /** Fit the data and zero, then add breathing room away from the baseline. */
    data class IncludeZero(val padding: Double = 0.05) : DomainPolicy {
        init {
            require(padding >= 0.0 && padding.isFinite()) {
                "Domain padding must be a finite fraction >= 0, was $padding"
            }
        }
    }

    /**
     * Use exactly this interval, whatever the data does.
     *
     * Values outside it are still drawn unless the scale clamps; that is a
     * separate decision, made by [LinearScale.clamp].
     */
    data class Fixed(val min: Double, val max: Double) : DomainPolicy {
        init {
            require(min.isFinite() && max.isFinite()) {
                "A fixed domain needs finite bounds, was [$min, $max]"
            }
            require(min < max) { "A fixed domain needs min < max, was [$min, $max]" }
        }
    }

    /** Fit the data but pin one or both ends. */
    data class Bounded(val min: Double? = null, val max: Double? = null) : DomainPolicy {
        init {
            require(min == null || min.isFinite()) { "Domain min must be finite, was $min" }
            require(max == null || max.isFinite()) { "Domain max must be finite, was $max" }
            require(min == null || max == null || min < max) {
                "Domain bounds must satisfy min < max, were [$min, $max]"
            }
        }
    }

    companion object {
        /** The line and area default. */
        val Default: DomainPolicy = Auto()

        /** The bar default: a bar length is only honest measured from zero. */
        val Baseline: DomainPolicy = IncludeZero()
    }
}

/** Applies a [DomainPolicy] to the interval the data actually occupies. */
fun DomainPolicy.apply(dataDomain: NumericDomain?): NumericDomain {
    val data = dataDomain ?: NumericDomain.Default
    return when (this) {
        is DomainPolicy.Auto -> data.padded(padding).resolved()
        is DomainPolicy.IncludeZero -> data.expandToIncludeZero().let { withZero ->
            // Pad away from the baseline only. Padding both ends would lift the
            // zero line off the axis, and a bar chart whose baseline floats is
            // reporting a length from nowhere.
            val padAmount = withZero.span * padding
            when {
                padAmount < ChartMath.EPSILON -> withZero
                withZero.min >= 0.0 -> NumericDomain(withZero.min, withZero.max + padAmount)
                withZero.max <= 0.0 -> NumericDomain(withZero.min - padAmount, withZero.max)
                else -> NumericDomain(withZero.min - padAmount, withZero.max + padAmount)
            }
        }.resolved()

        is DomainPolicy.Fixed -> NumericDomain(min, max)

        is DomainPolicy.Bounded -> NumericDomain(
            min = min ?: data.min,
            max = max ?: data.max,
        ).resolved()
    }
}
