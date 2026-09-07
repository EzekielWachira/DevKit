package io.devkit.chartkit.scale

import io.devkit.chartkit.geometry.ChartMath
import kotlin.math.floor
import kotlin.math.max

/**
 * Positions discrete categories as evenly spaced bands along an axis.
 *
 * ```
 * |<-- band 0 -->|<-- band 1 -->|<-- band 2 -->|
 *   [ pad|bar|pad ]
 * ```
 *
 * Placement comes from the category's **index** in the supplied list, never
 * from the string itself. Hashing a label to a position would make bar order
 * depend on the identity of the text, so renaming "Q1" to "Quarter 1" would
 * move the bar — and two categories that hash alike would occupy one band.
 * Order is the caller's, preserved exactly; duplicates are allowed and each
 * occurrence gets its own band.
 *
 * @param categories the labels, in the order they should appear.
 * @param categoryPadding the fraction of each band left empty, split evenly
 *   between its two sides. `0.2` leaves a bar occupying 80% of its band.
 */
class CategoryScale(
    val categories: List<String>,
    override val rangeStart: Float,
    override val rangeEnd: Float,
    val categoryPadding: Double = DEFAULT_CATEGORY_PADDING,
) : ChartScale<String> {

    init {
        require(categoryPadding >= 0.0 && categoryPadding < 1.0) {
            "Category padding must be in [0, 1), was $categoryPadding"
        }
    }

    /** The number of bands. */
    val count: Int get() = categories.size

    private val rangeSpan: Float = rangeEnd - rangeStart

    /** The full width of one band, padding included. */
    val bandWidth: Float
        get() = if (count <= 0) 0f else ChartMath.finiteOr(rangeSpan / count, 0f)

    /** The width available to content inside one band, padding excluded. */
    val innerBandWidth: Float
        get() = max(0f, bandWidth * (1.0 - categoryPadding).toFloat())

    /** The centre of band [index], the position a bar or tick is anchored to. */
    fun positionAt(index: Int): Float {
        if (count <= 0) return rangeStart
        val clamped = index.coerceIn(0, count - 1)
        return rangeStart + bandWidth * (clamped + 0.5f)
    }

    /**
     * The centre of the band for [value], or the range start if it is unknown.
     *
     * Resolves by first occurrence, matching the band order.
     */
    override fun scale(value: String): Float {
        val index = categories.indexOf(value)
        return if (index >= 0) positionAt(index) else rangeStart
    }

    /** The content span of band [index]: `start` inclusive, `end` exclusive. */
    fun bandAt(index: Int): ClosedFloatingPointRange<Float> {
        val centre = positionAt(index)
        val half = innerBandWidth / 2f
        return (centre - half)..(centre + half)
    }

    /**
     * The band index under pixel [position], or `-1` when it falls outside.
     *
     * Uses the *full* band rather than its padded content, so a tap in the gap
     * between two bars still selects the nearer one. A tap that must land on
     * the bar itself is a rectangle hit test, and the bar layer does that
     * separately.
     */
    fun indexAt(position: Float): Int {
        if (count <= 0 || bandWidth <= 0f || !position.isFinite()) return -1
        val offset = position - rangeStart
        if (offset < 0f || offset > rangeSpan) return -1
        val index = floor(offset / bandWidth).toInt()
        return index.coerceIn(0, count - 1)
    }

    /** The band index nearest pixel [position], clamped into range. */
    fun nearestIndex(position: Float): Int {
        if (count <= 0) return -1
        if (!position.isFinite()) return 0
        val offset = position - rangeStart
        val index = floor(offset / max(bandWidth, Float.MIN_VALUE)).toInt()
        return index.coerceIn(0, count - 1)
    }

    override fun toString(): String =
        "CategoryScale(count=$count, range=[$rangeStart, $rangeEnd])"

    companion object {
        /** Leaves a visible gutter between bars without starving them of width. */
        const val DEFAULT_CATEGORY_PADDING: Double = 0.2
    }
}
