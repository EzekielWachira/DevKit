package io.devkit.chartkit.stats

import io.devkit.chartkit.geometry.ChartMath

/**
 * How wide the kernel is when estimating a density.
 *
 * The single parameter that decides whether a violin looks like one hump or
 * three, so it is stated rather than hidden — but it has a defensible automatic
 * value, so it need not be stated.
 */
sealed interface KernelBandwidth {

    /** Silverman's robust rule of thumb. See [ChartStatistics.silvermanBandwidth]. */
    data object Auto : KernelBandwidth

    /** An explicit bandwidth, in the data's own units. */
    data class Fixed(val value: Double) : KernelBandwidth {
        init {
            require(value > 0.0 && value.isFinite()) {
                "A kernel bandwidth must be a finite value > 0, was $value"
            }
        }
    }

    /**
     * Silverman's bandwidth scaled by [factor].
     *
     * The usable middle ground: `0.5` for a curve that follows the data more
     * closely, `2.0` for a smoother one, without having to know the units.
     */
    data class Scaled(val factor: Double) : KernelBandwidth {
        init {
            require(factor > 0.0 && factor.isFinite()) {
                "A bandwidth scale factor must be a finite value > 0, was $factor"
            }
        }
    }
}

/**
 * A density curve: densities sampled at evenly spaced points.
 *
 * @param positions the values the density was evaluated at, ascending.
 * @param densities the estimated density at each position, parallel to
 *   [positions] and never negative.
 * @param bandwidth the bandwidth actually used, so a caller can report it.
 * @param sampleCount how many usable observations produced the curve.
 * @param isDegenerate true when the sample had no spread to estimate over — a
 *   constant sample, or a single observation. The curve is then a single spike
 *   at that value, and a violin draws it as a line rather than pretending to a
 *   distribution it does not have.
 */
data class DensityCurve(
    val positions: DoubleArray,
    val densities: DoubleArray,
    val bandwidth: Double,
    val sampleCount: Int,
    val isDegenerate: Boolean,
) {
    val size: Int get() = positions.size

    /** The largest density in the curve, `0` for an empty one. */
    val peak: Double get() = densities.maxOrNull() ?: 0.0

    val isEmpty: Boolean get() = positions.isEmpty()

    // Arrays are compared by content: two curves over the same data are the
    // same curve, and `remember` keys on this.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DensityCurve) return false
        return positions.contentEquals(other.positions) &&
            densities.contentEquals(other.densities) &&
            bandwidth == other.bandwidth &&
            sampleCount == other.sampleCount &&
            isDegenerate == other.isDegenerate
    }

    override fun hashCode(): Int {
        var result = positions.contentHashCode()
        result = 31 * result + densities.contentHashCode()
        result = 31 * result + bandwidth.hashCode()
        result = 31 * result + sampleCount
        result = 31 * result + isDegenerate.hashCode()
        return result
    }

    companion object {
        val Empty: DensityCurve = DensityCurve(
            positions = DoubleArray(0),
            densities = DoubleArray(0),
            bandwidth = 0.0,
            sampleCount = 0,
            isDegenerate = true,
        )
    }
}

/**
 * Kernel density estimation, with a Gaussian kernel.
 *
 * ```text
 * f̂(x) = (1 / n h) · Σ K((x - xᵢ) / h)
 * ```
 *
 * Plain Kotlin, so the curve a violin plot draws is verifiable without
 * rendering anything — which matters, because the failure mode of a density
 * estimator is a plausible-looking wrong shape rather than a crash.
 *
 * ### What it does not do
 *
 * No boundary correction and no adaptive bandwidth. A Gaussian kernel spreads
 * density past the extremes of the sample, so a strictly non-negative quantity
 * shows a little density below zero. [estimate] clips the evaluation range to
 * the sample's own extent plus a stated number of bandwidths, which bounds the
 * effect; it does not remove it. A violin of a bounded quantity should be read
 * with that in mind, and the box plot beside it read for the actual extremes.
 */
object DensityEstimator {

    /**
     * The density of [samples], evaluated at [resolution] evenly spaced points.
     *
     * Non-finite entries are dropped. A sample with fewer than two usable
     * values, or with no spread, yields a degenerate curve — see
     * [DensityCurve.isDegenerate] — rather than a division by a bandwidth of
     * zero.
     *
     * The evaluation range runs [TAIL_BANDWIDTHS] bandwidths beyond the
     * sample's extremes, which is where a Gaussian kernel's contribution has
     * fallen to a fraction of a percent. Extending further adds a flat tail;
     * stopping at the extremes truncates the curve mid-slope and makes the
     * violin look chopped off.
     */
    fun estimate(
        samples: Iterable<Double>,
        bandwidth: KernelBandwidth = KernelBandwidth.Auto,
        resolution: Int = DEFAULT_RESOLUTION,
    ): DensityCurve {
        require(resolution >= 2) { "A density curve needs at least 2 sample points, was $resolution" }
        val sorted = ChartStatistics.finiteSorted(samples)
        if (sorted.isEmpty()) return DensityCurve.Empty

        val h = when (bandwidth) {
            KernelBandwidth.Auto -> ChartStatistics.silvermanBandwidth(sorted)
            is KernelBandwidth.Fixed -> bandwidth.value
            is KernelBandwidth.Scaled -> ChartStatistics.silvermanBandwidth(sorted) * bandwidth.factor
        }

        // No spread, or a single observation: a spike at the value. Reported as
        // degenerate so the caller can draw it honestly instead of receiving a
        // flat curve that suggests a uniform distribution.
        if (!h.isFinite() || h <= ChartMath.EPSILON) {
            val value = sorted.first()
            return DensityCurve(
                positions = doubleArrayOf(value),
                densities = doubleArrayOf(1.0),
                bandwidth = 0.0,
                sampleCount = sorted.size,
                isDegenerate = true,
            )
        }

        val from = sorted.first() - TAIL_BANDWIDTHS * h
        val to = sorted.last() + TAIL_BANDWIDTHS * h
        val step = (to - from) / (resolution - 1)

        val positions = DoubleArray(resolution)
        val densities = DoubleArray(resolution)
        val scale = 1.0 / (sorted.size * h)

        for (index in 0 until resolution) {
            val x = from + step * index
            var sum = 0.0
            for (sample in sorted) {
                val u = (x - sample) / h
                // Beyond four standard deviations the Gaussian contributes less
                // than 0.01% and costs an `exp` per sample per evaluation point.
                if (u > KERNEL_CUTOFF || u < -KERNEL_CUTOFF) continue
                sum += ChartStatistics.gaussianKernel(u)
            }
            positions[index] = x
            densities[index] = (sum * scale).coerceAtLeast(0.0)
        }

        return DensityCurve(
            positions = positions,
            densities = densities,
            bandwidth = h,
            sampleCount = sorted.size,
            isDegenerate = false,
        )
    }

    /** Enough points for a smooth outline, few enough to evaluate per category. */
    const val DEFAULT_RESOLUTION: Int = 64

    /** How far past the sample's extremes the curve is evaluated, in bandwidths. */
    const val TAIL_BANDWIDTHS: Double = 3.0

    /** Where the Gaussian kernel is truncated, in standard deviations. */
    private const val KERNEL_CUTOFF: Double = 4.0
}
