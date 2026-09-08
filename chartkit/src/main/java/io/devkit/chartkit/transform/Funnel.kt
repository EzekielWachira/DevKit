package io.devkit.chartkit.transform

/**
 * One stage of a funnel, with the four figures a drop-off chart is read for.
 *
 * All four are computed whether or not they are drawn: the tooltip, the labels
 * and the accessibility announcement each want a different one, and computing
 * them in three places is how they come to disagree.
 *
 * @param value the stage's own count.
 * @param fractionOfFirst the stage as a share of the first stage. `1` for the
 *   first stage itself.
 * @param conversionFromPrevious the share of the *previous* stage that reached
 *   this one. `null` for the first stage, which converted from nothing.
 * @param dropOffFromPrevious `1 - conversionFromPrevious`, kept as its own
 *   figure because "we lost 38%" is the sentence a funnel exists to support.
 * @param dropOffCount the absolute number lost since the previous stage.
 */
class FunnelStage(
    val label: String,
    val value: Double,
    val fractionOfFirst: Double,
    val conversionFromPrevious: Double?,
    val dropOffFromPrevious: Double?,
    val dropOffCount: Double?,
    val sourceIndex: Int,
    val item: Any?,
)

/**
 * Turns stage counts into funnel stages with their conversion metrics.
 *
 * ```text
 * Visited      12,000   100%
 * Signed up     4,800    40%   ↓ 60%
 * Activated     3,100    26%   ↓ 35%
 * Subscribed      940     8%   ↓ 70%
 * ```
 *
 * ### A funnel need not decrease
 *
 * Real data goes up. A stage counted from a different source, a re-entry, a
 * cohort that grew — all produce a stage larger than the one before it, and a
 * transform that assumed monotonic decline would report a negative drop-off as
 * though it were a loss. So an increase is reported honestly: the conversion is
 * above `1`, and the drop-off is negative. [isMonotonic] lets a caller check,
 * and nothing here silently reorders or clamps.
 */
object FunnelTransform {

    fun <T> resolve(
        data: List<T>,
        label: (T) -> String,
        value: (T) -> Number?,
    ): List<FunnelStage> {
        if (data.isEmpty()) return emptyList()
        val values = data.map { value(it)?.toDouble()?.takeIf { v -> v.isFinite() && v >= 0.0 } ?: 0.0 }
        val first = values.firstOrNull() ?: 0.0

        return data.mapIndexed { index, item ->
            val current = values[index]
            val previous = values.getOrNull(index - 1)
            val conversion = when {
                previous == null -> null
                // A previous stage of zero has no share for this one to be of.
                // Reporting it as infinity or as 100% would both be inventions.
                previous <= 0.0 -> null
                else -> current / previous
            }
            FunnelStage(
                label = label(item),
                value = current,
                fractionOfFirst = if (first <= 0.0) 0.0 else current / first,
                conversionFromPrevious = conversion,
                dropOffFromPrevious = conversion?.let { 1.0 - it },
                dropOffCount = previous?.let { it - current },
                sourceIndex = index,
                item = item,
            )
        }
    }

    /** True when every stage is no larger than the one before it. */
    fun isMonotonic(stages: List<FunnelStage>): Boolean =
        stages.zipWithNext().all { (a, b) -> b.value <= a.value }

    /** The overall conversion: the last stage as a share of the first. */
    fun overallConversion(stages: List<FunnelStage>): Double? {
        val first = stages.firstOrNull()?.value ?: return null
        val last = stages.lastOrNull()?.value ?: return null
        return if (first <= 0.0) null else last / first
    }
}
