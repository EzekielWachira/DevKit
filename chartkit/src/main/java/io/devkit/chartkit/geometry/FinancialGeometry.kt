package io.devkit.chartkit.geometry

import kotlin.math.abs

/**
 * Which way a period closed.
 *
 * Named by what happened, not by a colour. The theme decides what an increase
 * looks like — see [io.devkit.chartkit.theme.ChartFinancialColors] for why
 * "green means up" is not baked into anything ChartKit draws.
 */
enum class PriceDirection {
    Increase,
    Decrease,

    /** Closed where it opened, or direction could not be determined. */
    Neutral,
    ;

    internal companion object {
        fun of(open: Double, close: Double): PriceDirection = when {
            !open.isFinite() || !close.isFinite() -> Neutral
            close > open -> Increase
            close < open -> Decrease
            else -> Neutral
        }
    }
}

/**
 * One period's open, high, low and close, normalised and validated.
 *
 * ### Internal, and why the public API does not require it
 *
 * A caller charts their own `Candle`, `Bar` or `PriceTick` through five
 * lambdas, exactly as a line chart reads its own model through two. This is
 * what those lambdas are normalised *into*, once per data change, and it never
 * appears in a signature a consumer writes.
 *
 * @param sourceIndex the position in the caller's own list.
 * @param volume the period's volume, or `null` when the caller supplied none.
 *   Carried on the candle rather than kept in a parallel series so a linked
 *   volume chart and its price chart cannot disagree about which period a bar
 *   belongs to.
 */
internal data class OhlcPoint(
    val sourceIndex: Int,
    val domainValue: Double,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double?,
) {
    val direction: PriceDirection get() = PriceDirection.of(open, close)

    /** `close - open`. What a tooltip reports as the period's change. */
    val change: Double get() = close - open

    /** The change as a fraction of the open, or `null` when the open was zero. */
    val changeFraction: Double?
        get() = if (abs(open) < ChartMath.EPSILON) null else (close - open) / open
}

/**
 * What to do with a period whose four prices are inconsistent.
 *
 * A candle with `high < low`, or a high below its own close, describes
 * something that did not happen. Drawing it produces a body sticking out of its
 * own wick, which reads as a rendering fault rather than as bad data — so the
 * behaviour is a stated policy.
 */
enum class OhlcPolicy {

    /**
     * Widen the extremes to contain the open and close. The default.
     *
     * `high = max(open, high, low, close)` and `low = min(…)`. The repair is
     * conservative — it only ever widens the range, never moves a traded price
     * — and it is the reading under which the four numbers become consistent
     * with the smallest change. Applied silently, because the alternative for
     * a live feed with an occasional bad tick is a chart that refuses to draw.
     */
    Repair,

    /** Drop the period, leaving a gap. */
    Skip,

    /** Throw, for a caller who would rather find out at the call site. */
    Reject,
}

/**
 * Turns the caller's four price lambdas into validated [OhlcPoint]s.
 *
 * Runs once per data change, never in a draw pass. Periods whose prices are
 * missing or non-finite are dropped whatever the policy: there is no candle to
 * repair when there is no number.
 *
 * The caller's order is preserved. Financial series are usually already
 * ascending in time, and silently sorting one that is not would produce a chart
 * disagreeing with the list beside it.
 */
@Suppress("LongParameterList")
internal fun normalizeOhlc(
    count: Int,
    domainValue: (Int) -> Double,
    open: (Int) -> Double?,
    high: (Int) -> Double?,
    low: (Int) -> Double?,
    close: (Int) -> Double?,
    volume: ((Int) -> Double?)? = null,
    policy: OhlcPolicy = OhlcPolicy.Repair,
): List<OhlcPoint> {
    val result = ArrayList<OhlcPoint>(count)
    for (index in 0 until count) {
        val o = open(index)
        val h = high(index)
        val l = low(index)
        val c = close(index)
        val x = domainValue(index)
        if (o == null || h == null || l == null || c == null ||
            !o.isFinite() || !h.isFinite() || !l.isFinite() || !c.isFinite() || !x.isFinite()
        ) {
            if (policy == OhlcPolicy.Reject) {
                throw IllegalArgumentException(
                    "Period $index has a missing or non-finite price " +
                        "(open=$o, high=$h, low=$l, close=$c). Filter it out, or use " +
                        "OhlcPolicy.Skip to leave a gap.",
                )
            }
            continue
        }

        val consistent = h >= l && h >= o && h >= c && l <= o && l <= c
        if (!consistent) {
            when (policy) {
                OhlcPolicy.Reject -> throw IllegalArgumentException(
                    "Period $index is inconsistent: high ($h) and low ($l) do not contain " +
                        "open ($o) and close ($c). Use OhlcPolicy.Repair to widen the range, " +
                        "or OhlcPolicy.Skip to drop the period.",
                )
                OhlcPolicy.Skip -> continue
                OhlcPolicy.Repair -> Unit
            }
        }

        val resolvedHigh = if (consistent) h else maxOf(o, h, l, c)
        val resolvedLow = if (consistent) l else minOf(o, h, l, c)

        result += OhlcPoint(
            sourceIndex = index,
            domainValue = x,
            open = o,
            high = resolvedHigh,
            low = resolvedLow,
            close = c,
            volume = volume?.invoke(index)?.takeIf { it.isFinite() },
        )
    }
    return result
}

/**
 * How wide one period's mark should be, in pixels.
 *
 * ### Why the median gap and not the mean
 *
 * A financial series has gaps — weekends, holidays, a halted session — and the
 * mean spacing is dragged wide by them, producing candles that overlap through
 * the dense stretches. The median spacing is the spacing of a *typical*
 * adjacent pair, which is what the reader sees as "one period".
 *
 * @param fraction how much of that spacing the mark occupies, leaving the rest
 *   as the gutter between candles.
 * @param minimum the floor. A zoomed-out series of two thousand candles gives
 *   each less than a pixel; drawing them at a hairline keeps the shape of the
 *   series readable, where rounding to zero would erase it.
 */
internal fun periodWidth(
    positions: FloatArray,
    fraction: Float,
    minimum: Float,
    fallback: Float,
): Float {
    if (positions.size < 2) return maxOf(minimum, fallback * fraction)
    val gaps = FloatArray(positions.size - 1) { abs(positions[it + 1] - positions[it]) }
    gaps.sort()
    val median = gaps[gaps.size / 2]
    if (!median.isFinite() || median <= 0f) return maxOf(minimum, fallback * fraction)
    return maxOf(minimum, median * fraction)
}
