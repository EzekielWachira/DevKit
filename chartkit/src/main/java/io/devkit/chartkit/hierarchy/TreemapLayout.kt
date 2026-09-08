package io.devkit.chartkit.hierarchy

import io.devkit.chartkit.geometry.ChartRect
import kotlin.math.max
import kotlin.math.min

/**
 * One node's rectangle.
 *
 * @param node the node it was computed for.
 * @param bounds the rectangle in pixels, already inset by the padding its
 *   ancestors reserved.
 * @param isLeaf whether anything is drawn inside it. A branch's own rectangle
 *   is the frame its children are packed into, and is drawn as a container
 *   rather than as a value.
 */
class TreemapTile(
    val node: HierarchyNode,
    val bounds: ChartRect,
    val isLeaf: Boolean,
)

/**
 * How much room a level leaves around and above its children.
 *
 * @param padding space inset on every side of a branch before its children are
 *   packed, so nested levels read as nested rather than as one flat grid.
 * @param headerHeight extra space taken off the top of a branch, for its own
 *   label. Zero for the deepest drawn level, which has no children to make room
 *   for.
 * @param tileGap space left between sibling tiles.
 */
class TreemapSpacing(
    val padding: Float = 2f,
    val headerHeight: Float = 0f,
    val tileGap: Float = 1f,
)

/**
 * Packs a hierarchy into nested rectangles whose areas are proportional to
 * their values.
 *
 * ### Squarified, not sliced
 *
 * The naive algorithm — divide the strip, take a slice per child, recurse —
 * is a few lines and produces rectangles of aspect ratio 200:1 as soon as one
 * child dominates. A 3-pixel-wide sliver cannot be labelled, cannot be tapped
 * and cannot be compared by eye to anything, which defeats the point of the
 * chart. This is the squarified algorithm of Bruls, Huizing and van Wijk: rows
 * are grown child by child for as long as adding one *improves* the worst
 * aspect ratio in the row, and closed when it stops.
 *
 * ```text
 * sliced                    squarified
 * ┌─┬─┬───────────────┐     ┌────────┬──────┐
 * │ │ │               │     │        ├──┬───┤
 * │ │ │               │     │        │  │   │
 * └─┴─┴───────────────┘     └────────┴──┴───┘
 * ```
 *
 * ### Pure Kotlin
 *
 * Nothing here touches Compose. The packing is arithmetic over a rectangle, so
 * it is tested on the JVM — bounds conservation, non-overlap and area
 * proportionality are properties worth asserting, and none of them need a
 * device.
 */
object TreemapLayout {

    /**
     * Rectangles for [root] and everything beneath it, down to [maxDepth]
     * levels below it.
     *
     * The root's own tile is included, so a caller can draw the frame the
     * children sit in. Nodes with no value are omitted: a zero-area rectangle
     * is invisible and would still take a tap target.
     *
     * @param maxDepth levels *below* [root] to lay out. `1` is a flat treemap
     *   of the root's immediate children.
     */
    fun layout(
        root: HierarchyNode,
        bounds: ChartRect,
        maxDepth: Int = Int.MAX_VALUE,
        spacing: TreemapSpacing = TreemapSpacing(),
    ): List<TreemapTile> {
        if (bounds.isEmpty || root.value <= 0.0) return emptyList()
        val tiles = ArrayList<TreemapTile>()
        place(root, bounds.normalized, 0, maxDepth, spacing, tiles)
        return tiles
    }

    private fun place(
        node: HierarchyNode,
        bounds: ChartRect,
        level: Int,
        maxDepth: Int,
        spacing: TreemapSpacing,
        into: MutableList<TreemapTile>,
    ) {
        val drawsChildren = level < maxDepth && node.children.isNotEmpty() && node.value > 0.0
        into += TreemapTile(node, bounds, isLeaf = !drawsChildren)
        if (!drawsChildren) return

        // The frame the children are packed into: inset on every side, and cut
        // down from the top by whatever the branch reserved for its own label.
        val inner = ChartRect(
            left = bounds.left + spacing.padding,
            top = bounds.top + spacing.padding + spacing.headerHeight,
            right = bounds.right - spacing.padding,
            bottom = bounds.bottom - spacing.padding,
        )
        if (inner.isEmpty) return

        val drawable = node.children.filter { it.value > 0.0 }
        if (drawable.isEmpty()) return

        val rects = squarify(drawable.map { it.value }, inner)
        drawable.forEachIndexed { index, child ->
            val rect = rects.getOrNull(index) ?: return@forEachIndexed
            val gapped = rect.deflate(spacing.tileGap / 2f)
            if (gapped.isEmpty) return@forEachIndexed
            place(child, gapped, level + 1, maxDepth, spacing, into)
        }
    }

    /**
     * Packs [values] into [bounds], preserving order and area proportions.
     *
     * Returns one rectangle per value, in the same order. The returned
     * rectangles tile [bounds] exactly: they do not overlap, and their union is
     * the whole of it — which is what makes "this rectangle is twice that one"
     * a statement about the data rather than about rounding.
     */
    fun squarify(values: List<Double>, bounds: ChartRect): List<ChartRect> {
        val total = values.sumOf { max(0.0, it) }
        if (values.isEmpty() || total <= 0.0 || bounds.isEmpty) {
            return List(values.size) { ChartRect.Zero }
        }

        val area = bounds.width.toDouble() * bounds.height.toDouble()
        // Everything below works in *pixel area*, so a row's aspect ratio is
        // computed from the same units as the rectangle it is placed in.
        val areas = values.map { max(0.0, it) / total * area }

        val result = arrayOfNulls<ChartRect>(values.size)
        var free = bounds
        var index = 0

        while (index < areas.size) {
            val shortSide = min(free.width, free.height).toDouble()
            if (shortSide <= 0.0) {
                // No room left — the remainder collapses onto the edge rather
                // than being dropped, so every value still has a rectangle and
                // the caller's indices still line up.
                while (index < areas.size) {
                    result[index] = ChartRect(free.left, free.top, free.left, free.top)
                    index++
                }
                break
            }

            // Grow the row while the worst aspect ratio in it keeps improving.
            var rowEnd = index + 1
            var rowArea = areas[index]
            var worst = worstAspect(areas, index, rowEnd, rowArea, shortSide)
            while (rowEnd < areas.size) {
                val nextArea = rowArea + areas[rowEnd]
                val nextWorst = worstAspect(areas, index, rowEnd + 1, nextArea, shortSide)
                if (nextWorst > worst) break
                rowEnd++
                rowArea = nextArea
                worst = nextWorst
            }

            free = layRow(areas, index, rowEnd, rowArea, free, result)
            index = rowEnd
        }

        return result.map { it ?: ChartRect.Zero }
    }

    /**
     * The worst aspect ratio among the entries `[from, to)` laid across a strip
     * of length [shortSide].
     *
     * The quantity the algorithm minimises. Always `>= 1`, so "worse" is
     * unambiguously "larger" whichever way round the rectangle is.
     */
    private fun worstAspect(
        areas: List<Double>,
        from: Int,
        to: Int,
        rowArea: Double,
        shortSide: Double,
    ): Double {
        if (rowArea <= 0.0 || shortSide <= 0.0) return Double.MAX_VALUE
        var minimum = Double.MAX_VALUE
        var maximum = 0.0
        for (i in from until to) {
            val value = areas[i]
            if (value < minimum) minimum = value
            if (value > maximum) maximum = value
        }
        if (maximum <= 0.0) return Double.MAX_VALUE
        val squared = shortSide * shortSide
        val rowSquared = rowArea * rowArea
        return max(squared * maximum / rowSquared, rowSquared / (squared * minimum))
    }

    /**
     * Places `[from, to)` as one row along the shorter side of [free], and
     * returns what is left.
     */
    private fun layRow(
        areas: List<Double>,
        from: Int,
        to: Int,
        rowArea: Double,
        free: ChartRect,
        into: Array<ChartRect?>,
    ): ChartRect {
        val horizontal = free.width <= free.height
        return if (horizontal) {
            // The row runs left to right and is as tall as its area demands.
            val height = (rowArea / free.width).toFloat().coerceAtMost(free.height)
            var cursor = free.left
            for (i in from until to) {
                val width = if (rowArea <= 0.0) {
                    0f
                } else {
                    (areas[i] / rowArea * free.width).toFloat()
                }
                val right = if (i == to - 1) free.right else min(cursor + width, free.right)
                into[i] = ChartRect(cursor, free.top, right, free.top + height)
                cursor = right
            }
            ChartRect(free.left, free.top + height, free.right, free.bottom)
        } else {
            val width = (rowArea / free.height).toFloat().coerceAtMost(free.width)
            var cursor = free.top
            for (i in from until to) {
                val height = if (rowArea <= 0.0) {
                    0f
                } else {
                    (areas[i] / rowArea * free.height).toFloat()
                }
                val bottom = if (i == to - 1) free.bottom else min(cursor + height, free.bottom)
                into[i] = ChartRect(free.left, cursor, free.left + width, bottom)
                cursor = bottom
            }
            ChartRect(free.left + width, free.top, free.right, free.bottom)
        }
    }
}

/** The rectangle shrunk by [amount] on every side, never past zero size. */
internal fun ChartRect.deflate(amount: Float): ChartRect {
    if (amount <= 0f) return this
    val left = left + amount
    val top = top + amount
    return ChartRect(
        left = left,
        top = top,
        right = max(left, right - amount),
        bottom = max(top, bottom - amount),
    )
}
