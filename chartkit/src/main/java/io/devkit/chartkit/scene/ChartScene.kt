package io.devkit.chartkit.scene

import androidx.compose.ui.graphics.Color
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect

/** How a shape is painted. */
enum class PaintStyle {
    Fill,
    Stroke,
}

/** Where a text node's [ChartSceneNode.Text.position] sits within the text. */
enum class TextAnchor {
    Start,
    Middle,
    End,
}

/**
 * One primitive of a renderer-neutral chart picture.
 *
 * ### Why a scene at all
 *
 * A Compose `DrawScope` is a set of instructions, not a description: once a
 * layer has called `drawPath`, nothing remains to serialise. A scene is the
 * same picture stated as data, so it can be written as SVG, compared in a test,
 * or measured — none of which a draw call allows.
 *
 * ### Small on purpose
 *
 * Seven primitives, chosen to cover what ChartKit's own layers actually draw.
 * A scene model that tried to mirror every `DrawScope` capability — shaders,
 * blend modes, image brushes, layer effects — would be a second rendering API
 * to maintain, and SVG could not express most of it anyway.
 */
sealed interface ChartSceneNode {

    /** A straight segment. */
    data class Line(
        val from: ChartOffset,
        val to: ChartOffset,
        val color: Color,
        val strokeWidth: Float,
        /** `null` for a solid line; otherwise on/off lengths. */
        val dash: FloatArray? = null,
    ) : ChartSceneNode {
        override fun equals(other: Any?): Boolean =
            other is Line && from == other.from && to == other.to && color == other.color &&
                strokeWidth == other.strokeWidth && dash.contentEqualsOrNull(other.dash)

        override fun hashCode(): Int {
            var result = from.hashCode()
            result = 31 * result + to.hashCode()
            result = 31 * result + color.hashCode()
            result = 31 * result + strokeWidth.hashCode()
            result = 31 * result + (dash?.contentHashCode() ?: 0)
            return result
        }
    }

    /** An axis-aligned rectangle, optionally rounded. */
    data class Rect(
        val bounds: ChartRect,
        val color: Color,
        val style: PaintStyle = PaintStyle.Fill,
        val strokeWidth: Float = 0f,
        val cornerRadius: Float = 0f,
    ) : ChartSceneNode

    /** A circle. */
    data class Circle(
        val center: ChartOffset,
        val radius: Float,
        val color: Color,
        val style: PaintStyle = PaintStyle.Fill,
        val strokeWidth: Float = 0f,
    ) : ChartSceneNode

    /**
     * A wedge or arc of a circle.
     *
     * Angles are in ChartKit's convention — zero at twelve o'clock, increasing
     * clockwise — and the renderer converts. See
     * [io.devkit.chartkit.geometry.PolarGeometry].
     */
    data class Arc(
        val center: ChartOffset,
        val innerRadius: Float,
        val outerRadius: Float,
        val startAngle: Float,
        val sweepAngle: Float,
        val color: Color,
        val style: PaintStyle = PaintStyle.Fill,
        val strokeWidth: Float = 0f,
    ) : ChartSceneNode

    /**
     * A polyline or polygon through [points].
     *
     * A list of points rather than an arbitrary path command list: everything
     * ChartKit draws as a path is a polyline, a filled area under one, or a
     * closed polygon, and the three are distinguishable by [closed] and [style].
     * Curved interpolation is flattened into points by the layer that produced
     * it, which is also what makes the scene comparable in a test.
     */
    data class Path(
        val points: List<ChartOffset>,
        val color: Color,
        val style: PaintStyle = PaintStyle.Stroke,
        val strokeWidth: Float = 0f,
        val closed: Boolean = false,
        val dash: FloatArray? = null,
    ) : ChartSceneNode {
        override fun equals(other: Any?): Boolean =
            other is Path && points == other.points && color == other.color &&
                style == other.style && strokeWidth == other.strokeWidth &&
                closed == other.closed && dash.contentEqualsOrNull(other.dash)

        override fun hashCode(): Int {
            var result = points.hashCode()
            result = 31 * result + color.hashCode()
            result = 31 * result + style.hashCode()
            result = 31 * result + strokeWidth.hashCode()
            result = 31 * result + closed.hashCode()
            result = 31 * result + (dash?.contentHashCode() ?: 0)
            return result
        }
    }

    /**
     * A run of text.
     *
     * @param position the **baseline** anchor, so a renderer with different font
     *   metrics still puts the text where the chart measured it.
     * @param fontSizePx the size the chart measured with, in pixels.
     */
    data class Text(
        val text: String,
        val position: ChartOffset,
        val color: Color,
        val fontSizePx: Float,
        val anchor: TextAnchor = TextAnchor.Start,
        val fontWeight: Int = 400,
        /** Degrees clockwise about [position]. */
        val rotationDegrees: Float = 0f,
    ) : ChartSceneNode

    /** A named group, for readable output and for clipping. */
    data class Group(
        val id: String,
        val children: List<ChartSceneNode>,
        val clip: ChartRect? = null,
    ) : ChartSceneNode
}

/**
 * A chart stated as data rather than as draw calls.
 *
 * @param unexportedLayers layers that had no scene representation and are
 *   therefore **absent** from this picture.
 *
 * Reported rather than hidden. A vector export that silently dropped a
 * candlestick series would be worse than no export at all — the file opens, it
 * looks like a chart, and it is missing the data. A caller checks the list and
 * falls back to a raster capture when it is not empty.
 */
class ChartScene(
    val width: Float,
    val height: Float,
    val nodes: List<ChartSceneNode>,
    val background: Color? = null,
    val unexportedLayers: List<String> = emptyList(),
) {
    /** True when every layer the chart drew is represented here. */
    val isComplete: Boolean get() = unexportedLayers.isEmpty()

    /** Every node, flattened out of its groups. For tests and measurement. */
    fun flatten(): List<ChartSceneNode> = buildList {
        fun visit(node: ChartSceneNode) {
            if (node is ChartSceneNode.Group) node.children.forEach(::visit) else add(node)
        }
        nodes.forEach(::visit)
    }
}

/**
 * Collects nodes into a scene.
 *
 * Handed to a layer's `renderScene`, so a layer contributes its own geometry
 * without knowing whether the result will be written as SVG, compared in a test
 * or thrown away.
 */
class ChartSceneBuilder internal constructor() {

    private val nodes = ArrayList<ChartSceneNode>()
    private val unexported = ArrayList<String>()

    fun add(node: ChartSceneNode) {
        nodes += node
    }

    /** Collects [block]'s nodes into a named group. */
    fun group(id: String, clip: ChartRect? = null, block: ChartSceneBuilder.() -> Unit) {
        val nested = ChartSceneBuilder().apply(block)
        if (nested.nodes.isEmpty() && nested.unexported.isEmpty()) return
        nodes += ChartSceneNode.Group(id, nested.nodes, clip)
        unexported += nested.unexported
    }

    /** Records that [layerId] could not be represented. */
    fun unexported(layerId: String) {
        unexported += layerId
    }

    internal fun build(width: Float, height: Float, background: Color?): ChartScene =
        ChartScene(width, height, nodes.toList(), background, unexported.toList())
}

/** Builds a scene. */
fun buildChartScene(
    width: Float,
    height: Float,
    background: Color? = null,
    block: ChartSceneBuilder.() -> Unit,
): ChartScene = ChartSceneBuilder().apply(block).build(width, height, background)

private fun FloatArray?.contentEqualsOrNull(other: FloatArray?): Boolean = when {
    this == null -> other == null
    other == null -> false
    else -> contentEquals(other)
}
