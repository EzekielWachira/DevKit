package io.devkit.chartkit.export

import androidx.compose.ui.graphics.Color
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.PolarGeometry
import io.devkit.chartkit.scene.ChartScene
import io.devkit.chartkit.scene.ChartSceneNode
import io.devkit.chartkit.scene.PaintStyle
import io.devkit.chartkit.scene.TextAnchor
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Writes a [ChartScene] as SVG.
 *
 * ```kotlin
 * val svg = ChartSvg.render(scene)
 * ```
 *
 * ### What it covers
 *
 * Lines, rectangles, circles, arcs, paths and text — everything
 * [ChartSceneNode] can express, which is everything ChartKit's exportable
 * layers produce. Groups become `<g>` elements with their layer's id, so the
 * output is readable and editable in a vector tool rather than one flat pile of
 * shapes.
 *
 * ### What it cannot cover
 *
 * A custom Compose tooltip, an overlay card, an image or anything else composed
 * rather than drawn is **not** part of a scene and therefore not part of the
 * SVG — those are Compose content, and serialising them would mean
 * reimplementing Compose's layout and text rendering. Layers with no scene
 * representation are named in [ChartScene.unexportedLayers]; a caller that
 * finds it non-empty should use a raster capture instead of shipping a picture
 * with data missing from it.
 *
 * ### Fonts
 *
 * Text carries the size the chart measured with and a generic
 * `font-family="sans-serif"`. A viewer with different metrics will lay the glyphs
 * out slightly differently; the *positions* are exact because they were computed
 * by the chart, so labels stay on their ticks even when the glyphs differ.
 */
object ChartSvg {

    /**
     * The scene as an SVG document.
     *
     * @param scale multiplies the coordinate system. The output is vector, so
     *   this changes the document's nominal size rather than its fidelity —
     *   useful for matching a page size.
     * @param title an accessible name, written as `<title>`. Screen readers on
     *   the web read it, which is the SVG equivalent of the chart's own
     *   semantics.
     */
    fun render(
        scene: ChartScene,
        scale: Float = 1f,
        title: String? = null,
        description: String? = null,
    ): String {
        require(scale > 0f && scale.isFinite()) { "SVG scale must be positive, was $scale" }
        val width = scene.width * scale
        val height = scene.height * scale

        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
            append("width=\"${number(width)}\" height=\"${number(height)}\" ")
            append("viewBox=\"0 0 ${number(scene.width)} ${number(scene.height)}\">\n")
            title?.let { append("  <title>${escape(it)}</title>\n") }
            description?.let { append("  <desc>${escape(it)}</desc>\n") }
            scene.background?.takeIf { it.alpha > 0f }?.let {
                append(
                    "  <rect x=\"0\" y=\"0\" width=\"${number(scene.width)}\" " +
                        "height=\"${number(scene.height)}\" fill=\"${hex(it)}\"/>\n",
                )
            }
            scene.nodes.forEach { node -> appendNode(node, indent = "  ") }
            append("</svg>\n")
        }
    }

    private fun StringBuilder.appendNode(node: ChartSceneNode, indent: String) {
        when (node) {
            is ChartSceneNode.Group -> {
                append("$indent<g id=\"${escape(node.id)}\">\n")
                node.children.forEach { appendNode(it, "$indent  ") }
                append("$indent</g>\n")
            }

            is ChartSceneNode.Line -> {
                append(
                    "$indent<line x1=\"${number(node.from.x)}\" y1=\"${number(node.from.y)}\" " +
                        "x2=\"${number(node.to.x)}\" y2=\"${number(node.to.y)}\" " +
                        stroke(node.color, node.strokeWidth) + dash(node.dash) + "/>\n",
                )
            }

            is ChartSceneNode.Rect -> {
                val bounds = node.bounds.normalized
                append("$indent<rect x=\"${number(bounds.left)}\" y=\"${number(bounds.top)}\" ")
                append("width=\"${number(bounds.width)}\" height=\"${number(bounds.height)}\" ")
                if (node.cornerRadius > 0f) append("rx=\"${number(node.cornerRadius)}\" ")
                append(paint(node.color, node.style, node.strokeWidth))
                append("/>\n")
            }

            is ChartSceneNode.Circle -> {
                append(
                    "$indent<circle cx=\"${number(node.center.x)}\" " +
                        "cy=\"${number(node.center.y)}\" r=\"${number(node.radius)}\" " +
                        paint(node.color, node.style, node.strokeWidth) + "/>\n",
                )
            }

            is ChartSceneNode.Arc -> append("$indent${arcPath(node)}\n")

            is ChartSceneNode.Path -> {
                if (node.points.isEmpty()) return
                val d = buildString {
                    node.points.forEachIndexed { index, point ->
                        append(if (index == 0) "M" else "L")
                        append("${number(point.x)} ${number(point.y)}")
                        if (index < node.points.lastIndex) append(" ")
                    }
                    if (node.closed) append(" Z")
                }
                append(
                    "$indent<path d=\"$d\" " +
                        paint(node.color, node.style, node.strokeWidth) + dash(node.dash) + "/>\n",
                )
            }

            is ChartSceneNode.Text -> {
                val anchor = when (node.anchor) {
                    TextAnchor.Start -> "start"
                    TextAnchor.Middle -> "middle"
                    TextAnchor.End -> "end"
                }
                append("$indent<text x=\"${number(node.position.x)}\" ")
                append("y=\"${number(node.position.y)}\" ")
                append("fill=\"${hex(node.color)}\" ")
                append("font-size=\"${number(node.fontSizePx)}\" ")
                append("font-family=\"sans-serif\" ")
                if (node.fontWeight != DEFAULT_WEIGHT) {
                    append("font-weight=\"${node.fontWeight}\" ")
                }
                append("text-anchor=\"$anchor\"")
                if (abs(node.rotationDegrees) > 0.01f) {
                    append(
                        " transform=\"rotate(${number(node.rotationDegrees)} " +
                            "${number(node.position.x)} ${number(node.position.y)})\"",
                    )
                }
                append(" opacity=\"${number(node.color.alpha)}\"")
                append(">${escape(node.text)}</text>\n")
            }
        }
    }

    /**
     * An arc as a path.
     *
     * SVG has no arc *element*, only an arc command inside a path, and the
     * command takes an end point plus two flags rather than a sweep. The
     * conversion is here, once, rather than at every call site that wants a
     * wedge.
     */
    private fun arcPath(node: ChartSceneNode.Arc): String {
        val sweep = node.sweepAngle.coerceIn(-360f, 360f)
        if (abs(sweep) < 0.01f || node.outerRadius <= 0f) return ""

        // A full circle cannot be one arc command — start and end coincide, and
        // the renderer draws nothing — so it becomes two half arcs.
        if (abs(sweep) >= 359.99f && node.innerRadius <= 0f) {
            return "<circle cx=\"${number(node.center.x)}\" cy=\"${number(node.center.y)}\" " +
                "r=\"${number(node.outerRadius)}\" " +
                paint(node.color, node.style, node.strokeWidth) + "/>"
        }

        val start = node.startAngle
        val end = node.startAngle + sweep
        val outerStart = PolarGeometry.pointOnCircle(node.center, node.outerRadius, start)
        val outerEnd = PolarGeometry.pointOnCircle(node.center, node.outerRadius, end)
        val largeArc = if (abs(sweep) > 180f) 1 else 0
        val sweepFlag = if (sweep >= 0f) 1 else 0

        val d = buildString {
            if (node.innerRadius > 0f) {
                val innerEnd = PolarGeometry.pointOnCircle(node.center, node.innerRadius, end)
                val innerStart = PolarGeometry.pointOnCircle(node.center, node.innerRadius, start)
                append("M${point(outerStart)} ")
                append("A${number(node.outerRadius)} ${number(node.outerRadius)} 0 ")
                append("$largeArc $sweepFlag ${point(outerEnd)} ")
                append("L${point(innerEnd)} ")
                append("A${number(node.innerRadius)} ${number(node.innerRadius)} 0 ")
                append("$largeArc ${1 - sweepFlag} ${point(innerStart)} Z")
            } else {
                append("M${point(node.center)} ")
                append("L${point(outerStart)} ")
                append("A${number(node.outerRadius)} ${number(node.outerRadius)} 0 ")
                append("$largeArc $sweepFlag ${point(outerEnd)} Z")
            }
        }
        return "<path d=\"$d\" " + paint(node.color, node.style, node.strokeWidth) + "/>"
    }

    private fun point(offset: ChartOffset): String = "${number(offset.x)} ${number(offset.y)}"

    private fun paint(color: Color, style: PaintStyle, strokeWidth: Float): String = when (style) {
        PaintStyle.Fill -> "fill=\"${hex(color)}\" fill-opacity=\"${number(color.alpha)}\""
        PaintStyle.Stroke -> "fill=\"none\" " + stroke(color, strokeWidth)
    }

    private fun stroke(color: Color, width: Float): String =
        "stroke=\"${hex(color)}\" stroke-opacity=\"${number(color.alpha)}\" " +
            "stroke-width=\"${number(width)}\""

    private fun dash(pattern: FloatArray?): String =
        if (pattern == null || pattern.isEmpty()) {
            ""
        } else {
            " stroke-dasharray=\"${pattern.joinToString(" ") { number(it) }}\""
        }

    /**
     * `#rrggbb`, with the alpha carried separately.
     *
     * SVG's own eight-digit hex is a CSS Color 4 feature that older viewers and
     * several vector editors ignore, so opacity goes in its own attribute where
     * everything understands it.
     */
    private fun hex(color: Color): String {
        val red = (color.red * 255f).roundToInt().coerceIn(0, 255)
        val green = (color.green * 255f).roundToInt().coerceIn(0, 255)
        val blue = (color.blue * 255f).roundToInt().coerceIn(0, 255)
        return "#%02x%02x%02x".format(red, green, blue)
    }

    /** Two decimal places, without a trailing `.0`, and never in scientific notation. */
    private fun number(value: Float): String {
        if (!value.isFinite()) return "0"
        val rounded = (value * 100f).roundToInt() / 100f
        return if (rounded == rounded.toLong().toFloat()) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }

    private fun escape(text: String): String = buildString(text.length) {
        text.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(character)
            }
        }
    }

    private const val DEFAULT_WEIGHT = 400
}
