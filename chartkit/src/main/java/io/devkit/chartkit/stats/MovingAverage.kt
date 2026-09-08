package io.devkit.chartkit.stats

/**
 * The two moving averages that are worth having in a *visualisation* library.
 *
 * ### Why only these two
 *
 * ChartKit is not a trading-analysis package. A moving average is here because
 * it is a **line**, and drawing a smoothed line beside a price series is a
 * charting task the layering system already does — the utility saves a caller
 * writing a windowed sum, and nothing more. RSI, MACD, Bollinger bands and the
 * rest are analysis: they carry parameter conventions, warm-up rules and
 * interpretations that belong in a domain library where they can be tested
 * against a reference, not in a chart renderer.
 *
 * Plain Kotlin, so both are verifiable on the JVM and portable.
 */
object MovingAverage {

    /**
     * The simple moving average of [values] over [period] samples.
     *
     * The result is **parallel to the input**, with `null` for the first
     * `period - 1` positions where the window is not yet full. Returning a
     * shorter list instead would leave the caller aligning two lists by hand,
     * which is exactly where an off-by-one puts the average a day early.
     *
     * A `null` or non-finite sample makes every window containing it `null`
     * rather than being skipped: an average over four of five days is not a
     * five-day average, and silently computing one would misreport the line.
     */
    fun simple(values: List<Double?>, period: Int): List<Double?> {
        require(period >= 1) { "A moving-average period must be at least 1, was $period" }
        if (values.isEmpty()) return emptyList()

        val result = arrayOfNulls<Double>(values.size)
        var sum = 0.0
        var missingInWindow = 0

        values.indices.forEach { index ->
            val entering = values[index]
            if (entering == null || !entering.isFinite()) missingInWindow++ else sum += entering

            if (index >= period) {
                val leaving = values[index - period]
                if (leaving == null || !leaving.isFinite()) missingInWindow-- else sum -= leaving
            }

            if (index >= period - 1 && missingInWindow == 0) {
                result[index] = sum / period
            }
        }
        return result.toList()
    }

    /**
     * The exponential moving average of [values] with the conventional
     * smoothing factor `2 / (period + 1)`.
     *
     * Seeded with the simple average of the first [period] samples — the
     * standard warm-up, and the reason the first `period - 1` entries are
     * `null` here too. Seeding with the first sample instead makes the early
     * part of the line depend heavily on one value, which is visible as a
     * hook at the start of the series.
     *
     * A missing sample **breaks** the average: the run restarts and warms up
     * again, rather than carrying a stale value forward across a gap.
     */
    fun exponential(values: List<Double?>, period: Int): List<Double?> {
        require(period >= 1) { "A moving-average period must be at least 1, was $period" }
        if (values.isEmpty()) return emptyList()

        val alpha = 2.0 / (period + 1)
        val result = arrayOfNulls<Double>(values.size)
        var previous: Double? = null
        var warmupSum = 0.0
        var warmupCount = 0

        values.indices.forEach { index ->
            val value = values[index]
            if (value == null || !value.isFinite()) {
                previous = null
                warmupSum = 0.0
                warmupCount = 0
                return@forEach
            }
            val running = previous
            if (running == null) {
                warmupSum += value
                warmupCount++
                if (warmupCount == period) {
                    previous = warmupSum / period
                    result[index] = previous
                }
            } else {
                previous = value * alpha + running * (1 - alpha)
                result[index] = previous
            }
        }
        return result.toList()
    }
}
