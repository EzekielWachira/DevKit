package io.devkit.chartkit.stats

/**
 * The five-number summary a box plot draws, plus its outliers.
 *
 * ### Why this is a public type
 *
 * A box plot is drawn from statistics, and an application very often already
 * has them — computed by the database, by a reporting service, by an analysis
 * pipeline whose definitions the organisation has agreed on. Forcing every
 * caller to hand ChartKit the raw samples so that ChartKit can recompute
 * quartiles by [ChartStatistics]' own definition would mean the chart quietly
 * disagreeing with the numbers printed next to it.
 *
 * So both routes exist and neither is second class: [from] computes a summary
 * from samples, and this constructor accepts one that already exists.
 *
 * @param minimum the lower whisker end.
 * @param q1 the first quartile — the bottom of the box.
 * @param median the line inside the box.
 * @param q3 the third quartile — the top of the box.
 * @param maximum the upper whisker end.
 * @param outliers individual values drawn as points beyond the whiskers.
 * @param sampleCount how many usable observations the summary came from, or
 *   `0` when it was supplied rather than computed.
 */
data class BoxStatistics(
    val minimum: Double,
    val q1: Double,
    val median: Double,
    val q3: Double,
    val maximum: Double,
    val outliers: List<Double> = emptyList(),
    val sampleCount: Int = 0,
) {
    /** True when every one of the five numbers is real and correctly ordered. */
    val isValid: Boolean
        get() = minimum.isFinite() && q1.isFinite() && median.isFinite() &&
            q3.isFinite() && maximum.isFinite() &&
            minimum <= q1 && q1 <= median && median <= q3 && q3 <= maximum

    /** `q3 - q1`. */
    val iqr: Double get() = q3 - q1

    /** The full extent drawn, outliers included — what the value axis must cover. */
    val displayRange: ClosedFloatingPointRange<Double>
        get() {
            var low = minimum
            var high = maximum
            outliers.forEach {
                if (it.isFinite()) {
                    if (it < low) low = it
                    if (it > high) high = it
                }
            }
            return if (low <= high) low..high else high..low
        }

    companion object {

        /**
         * The summary of [samples], by [ChartStatistics]' documented method.
         *
         * Non-finite entries are dropped first. A sample with nothing usable in
         * it yields `null` rather than a summary of `NaN`s, so the caller draws
         * an empty category instead of an invisible box.
         *
         * @param outlierPolicy how points beyond the fences are treated.
         */
        fun from(
            samples: Iterable<Double>,
            outlierPolicy: OutlierPolicy = OutlierPolicy.Tukey(),
        ): BoxStatistics? {
            val sorted = ChartStatistics.finiteSorted(samples)
            if (sorted.isEmpty()) return null
            val quartiles = ChartStatistics.quartiles(sorted)
            return when (outlierPolicy) {
                is OutlierPolicy.None -> BoxStatistics(
                    minimum = sorted.first(),
                    q1 = quartiles.q1,
                    median = quartiles.median,
                    q3 = quartiles.q3,
                    maximum = sorted.last(),
                    outliers = emptyList(),
                    sampleCount = sorted.size,
                )

                is OutlierPolicy.Tukey -> {
                    val whiskers = ChartStatistics.whiskers(sorted, outlierPolicy.multiplier)
                    BoxStatistics(
                        minimum = whiskers.lower,
                        q1 = quartiles.q1,
                        median = quartiles.median,
                        q3 = quartiles.q3,
                        maximum = whiskers.upper,
                        outliers = ChartStatistics.outliers(sorted, outlierPolicy.multiplier).toList(),
                        sampleCount = sorted.size,
                    )
                }
            }
        }
    }
}

/**
 * How a box plot decides what counts as an outlier.
 *
 * Never applied to a [BoxStatistics] the caller supplied: those numbers were
 * computed somewhere else, under whatever definition that place uses, and
 * re-deriving them here would silently overwrite a decision ChartKit was not
 * party to.
 */
sealed interface OutlierPolicy {

    /**
     * Values beyond `q1 - k·IQR` and `q3 + k·IQR`, with `k` = [multiplier].
     * The default, at Tukey's conventional `1.5`.
     */
    data class Tukey(
        val multiplier: Double = ChartStatistics.DEFAULT_OUTLIER_MULTIPLIER,
    ) : OutlierPolicy {
        init {
            require(multiplier >= 0.0 && multiplier.isFinite()) {
                "The outlier multiplier must be a finite value >= 0, was $multiplier"
            }
        }
    }

    /** No outliers: whiskers run to the extremes of the sample. */
    data object None : OutlierPolicy

    companion object {
        val Default: OutlierPolicy = Tukey()
    }
}
