package io.devkit.chartkit.layer.annotation

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import io.devkit.chartkit.annotation.AnnotationLabelPlacement
import io.devkit.chartkit.annotation.AnnotationMarkerShape
import io.devkit.chartkit.annotation.AnnotationOrder
import io.devkit.chartkit.annotation.CalloutDirection
import io.devkit.chartkit.annotation.ChartAnnotation
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartX
import kotlin.math.abs

/**
 * An annotation whose domain positions have been resolved to [ChartX] values.
 *
 * Resolution happens once, when the chart's layers are built, using the same
 * [io.devkit.chartkit.model.ChartXResolver] the data went through — which is
 * what makes `verticalRule(at = "Mar")` land on the March band and
 * `verticalRule(at = releaseMillis)` land on the release date, with no separate
 * annotation type per axis kind.
 */
internal class ResolvedAnnotation(
    val annotation: ChartAnnotation,
    val domainStart: ChartX? = null,
    val domainEnd: ChartX? = null,
)

/**
 * Reference marks drawn in the chart's own coordinate space.
 *
 * ### Why one layer and not a feature of each chart
 *
 * A target line is the same mark on a line chart, a bar chart, a scatter and a
 * candlestick chart. Implemented as a parameter of `LineChart` it would exist
 * on one of them; implemented against the coordinate system it exists on all of
 * them, and a combined chart gets it once rather than once per layer.
 *
 * ### Two instances, not one with a flag
 *
 * A chart builds two of these — one behind the data and one in front — and puts
 * them at the right points in the render list. Ordering is a property of the
 * list, not something a layer should decide while drawing: a layer that skipped
 * half its own content depending on a mode would still have to be visited
 * twice, and would be harder to reason about than two lists.
 *
 * @param positionOfDomain the chart's own domain mapping, so an annotation on a
 *   category axis lands on a band and one on a time axis lands on a date.
 */
internal class AnnotationLayer(
    override val id: String,
    private val annotations: List<ResolvedAnnotation>,
    private val order: AnnotationOrder,
    private val positionOfDomain: (ChartX) -> Float?,
    private val valueFormatter: io.devkit.chartkit.formatter.ChartValueFormatter,
    /**
     * The value axis these annotations are stated in.
     *
     * One layer per axis, so each is handed the coordinates of the scale its
     * values mean something on: a rule at `30` on the temperature axis lands at
     * 30°C, not at 30 of whatever the primary axis measures. Grouping by axis
     * outside the layer is what keeps `positionOfValue` inside it a single
     * unqualified call.
     */
    override val valueAxisId: io.devkit.chartkit.axis.ChartAxisId =
        io.devkit.chartkit.axis.ChartAxisId.DefaultY,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = emptyList()

    /** Labels sit in the gutter when there is no room for them inside the plot. */
    override val clipToPlot: Boolean get() = false

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val coordinates = context.cartesian
        val plot = coordinates.plotArea
        if (plot.isEmpty || annotations.isEmpty()) return
        // Suppressed during the reveal: a target line over a chart still
        // drawing itself in points at a gap that is not there yet.
        if (context.reveal < 1f) return

        annotations.forEach { resolved ->
            when (val annotation = resolved.annotation) {
                is ChartAnnotation.HorizontalRule ->
                    drawHorizontalRule(scope, context, annotation)

                is ChartAnnotation.VerticalRule ->
                    drawVerticalRule(scope, context, annotation, resolved.domainStart)

                is ChartAnnotation.ValueRange ->
                    drawValueRange(scope, context, annotation)

                is ChartAnnotation.DomainRange ->
                    drawDomainRange(scope, context, annotation, resolved)

                is ChartAnnotation.Region ->
                    drawRegion(scope, context, annotation, resolved)

                is ChartAnnotation.EventMarker ->
                    drawEventMarker(scope, context, annotation, resolved.domainStart)

                is ChartAnnotation.Callout ->
                    drawCallout(scope, context, annotation, resolved.domainStart)

                is ChartAnnotation.Arrow ->
                    drawArrow(scope, context, annotation, resolved)

                is ChartAnnotation.LabelBox ->
                    drawLabelBox(scope, context, annotation, resolved.domainStart)
            }
        }
    }

    // ---- rules --------------------------------------------------------------

    private fun drawHorizontalRule(
        scope: DrawScope,
        context: ChartRenderContext,
        annotation: ChartAnnotation.HorizontalRule,
    ) {
        val coordinates = context.cartesian
        val plot = coordinates.plotArea
        val position = coordinates.positionOfValue(annotation.value)
        if (!position.isFinite()) return
        val a = coordinates.pointAt(coordinates.domainOf(ChartOffset(plot.left, plot.top)), position)
        val b = coordinates.pointAt(coordinates.domainOf(ChartOffset(plot.right, plot.bottom)), position)
        if (!withinPlot(plot, a, b)) return
        strokeRule(scope, context, annotation, Offset(a.x, a.y), Offset(b.x, b.y))
        drawLabel(scope, context, annotation, Offset(a.x, a.y), Offset(b.x, b.y), plot)
    }

    private fun drawVerticalRule(
        scope: DrawScope,
        context: ChartRenderContext,
        annotation: ChartAnnotation.VerticalRule,
        domain: ChartX?,
    ) {
        val coordinates = context.cartesian
        val plot = coordinates.plotArea
        val at = domain?.let(positionOfDomain) ?: return
        if (!at.isFinite()) return
        val a = coordinates.pointAt(at, coordinates.valueOf(ChartOffset(plot.left, plot.top)))
        val b = coordinates.pointAt(at, coordinates.valueOf(ChartOffset(plot.right, plot.bottom)))
        if (!withinPlot(plot, a, b)) return
        strokeRule(scope, context, annotation, Offset(a.x, a.y), Offset(b.x, b.y))
        drawLabel(scope, context, annotation, Offset(a.x, a.y), Offset(b.x, b.y), plot)
    }

    private fun strokeRule(
        scope: DrawScope,
        context: ChartRenderContext,
        annotation: ChartAnnotation,
        from: Offset,
        to: Offset,
    ) {
        val width = context.px(annotation.style.lineWidth ?: context.dimensions.annotationLineWidth)
        val colour = annotation.style.color ?: context.colors.annotation.line
        scope.drawLine(
            color = colour,
            start = from,
            end = to,
            strokeWidth = width,
            // Dashed by default: a reference line drawn like the data invites
            // the reader to take it for a series.
            pathEffect = if (annotation.style.dashed) {
                PathEffect.dashPathEffect(floatArrayOf(width * 5f, width * 4f))
            } else {
                null
            },
        )
    }

    // ---- bands and regions ---------------------------------------------------

    private fun drawValueRange(
        scope: DrawScope,
        context: ChartRenderContext,
        annotation: ChartAnnotation.ValueRange,
    ) {
        val coordinates = context.cartesian
        val plot = coordinates.plotArea
        val from = coordinates.positionOfValue(annotation.from)
        val to = coordinates.positionOfValue(annotation.to)
        if (!from.isFinite() || !to.isFinite()) return
        val rect = spanRect(coordinates, plot, valueFrom = from, valueTo = to)
        fillRegion(scope, context, annotation, rect, plot)
    }

    private fun drawDomainRange(
        scope: DrawScope,
        context: ChartRenderContext,
        annotation: ChartAnnotation.DomainRange,
        resolved: ResolvedAnnotation,
    ) {
        val coordinates = context.cartesian
        val plot = coordinates.plotArea
        val from = resolved.domainStart?.let(positionOfDomain) ?: return
        val to = resolved.domainEnd?.let(positionOfDomain) ?: return
        if (!from.isFinite() || !to.isFinite()) return
        val rect = spanRect(coordinates, plot, domainFrom = from, domainTo = to)
        fillRegion(scope, context, annotation, rect, plot)
    }

    private fun drawRegion(
        scope: DrawScope,
        context: ChartRenderContext,
        annotation: ChartAnnotation.Region,
        resolved: ResolvedAnnotation,
    ) {
        val coordinates = context.cartesian
        val plot = coordinates.plotArea
        val domainFrom = resolved.domainStart?.let(positionOfDomain) ?: return
        val domainTo = resolved.domainEnd?.let(positionOfDomain) ?: return
        val valueFrom = coordinates.positionOfValue(annotation.valueFrom)
        val valueTo = coordinates.positionOfValue(annotation.valueTo)
        if (!domainFrom.isFinite() || !domainTo.isFinite() ||
            !valueFrom.isFinite() || !valueTo.isFinite()
        ) {
            return
        }
        val a = coordinates.pointAt(domainFrom, valueFrom)
        val b = coordinates.pointAt(domainTo, valueTo)
        fillRegion(scope, context, annotation, ChartRect(a.x, a.y, b.x, b.y).normalized, plot)
    }

    /** A band across the whole plot in one direction, bounded in the other. */
    private fun spanRect(
        coordinates: io.devkit.chartkit.coordinate.CartesianCoordinates,
        plot: ChartRect,
        domainFrom: Float? = null,
        domainTo: Float? = null,
        valueFrom: Float? = null,
        valueTo: Float? = null,
    ): ChartRect {
        val domainLow = domainFrom ?: coordinates.domainOf(ChartOffset(plot.left, plot.top))
        val domainHigh = domainTo ?: coordinates.domainOf(ChartOffset(plot.right, plot.bottom))
        val valueLow = valueFrom ?: coordinates.valueOf(ChartOffset(plot.left, plot.top))
        val valueHigh = valueTo ?: coordinates.valueOf(ChartOffset(plot.right, plot.bottom))
        val a = coordinates.pointAt(domainLow, valueLow)
        val b = coordinates.pointAt(domainHigh, valueHigh)
        return ChartRect(a.x, a.y, b.x, b.y).normalized
    }

    private fun fillRegion(
        scope: DrawScope,
        context: ChartRenderContext,
        annotation: ChartAnnotation,
        rect: ChartRect,
        plot: ChartRect,
    ) {
        val clipped = ChartRect(
            left = rect.left.coerceIn(plot.left, plot.right),
            top = rect.top.coerceIn(plot.top, plot.bottom),
            right = rect.right.coerceIn(plot.left, plot.right),
            bottom = rect.bottom.coerceIn(plot.top, plot.bottom),
        )
        if (clipped.width <= 0f || clipped.height <= 0f) return

        val colour = annotation.style.color ?: context.colors.annotation.region
        scope.drawRect(
            color = colour,
            topLeft = Offset(clipped.left, clipped.top),
            size = Size(clipped.width, clipped.height),
        )
        // An outline as well as the wash. A region distinguished only by a tint
        // vanishes for a reader with low contrast sensitivity, and on a busy
        // chart it is easy to mistake for a series fill.
        scope.drawRect(
            color = (annotation.style.color ?: context.colors.annotation.line).copy(alpha = 0.6f),
            topLeft = Offset(clipped.left, clipped.top),
            size = Size(clipped.width, clipped.height),
            style = Stroke(
                width = context.px(
                    annotation.style.lineWidth ?: context.dimensions.annotationLineWidth,
                ),
            ),
        )
        drawLabel(
            scope,
            context,
            annotation,
            Offset(clipped.left, clipped.top),
            Offset(clipped.right, clipped.top),
            plot,
        )
    }

    // ---- markers -------------------------------------------------------------

    private fun drawEventMarker(
        scope: DrawScope,
        context: ChartRenderContext,
        annotation: ChartAnnotation.EventMarker,
        domain: ChartX?,
    ) {
        val position = markerPosition(context, annotation, domain) ?: return
        val plot = context.cartesian.plotArea
        val radius = context.px(context.dimensions.annotationMarkerRadius)
        val colour = annotation.style.color ?: context.colors.annotation.line

        scope.drawMarker(annotation.shape, Offset(position.x, position.y), radius, colour)
        // A hole in the middle of a round marker, so it reads as a mark rather
        // than as a data point of the series it sits over. The other shapes are
        // already distinct from anything a series draws.
        if (annotation.shape == AnnotationMarkerShape.Circle) {
            scope.drawCircle(
                context.colors.annotation.labelContent,
                radius * MARKER_HOLE_FRACTION,
                Offset(position.x, position.y),
            )
        }
        drawLabel(
            scope,
            context,
            annotation,
            Offset(position.x, position.y),
            Offset(position.x, position.y),
            plot,
        )
    }

    private fun markerPosition(
        context: ChartRenderContext,
        annotation: ChartAnnotation.EventMarker,
        domain: ChartX?,
    ): ChartOffset? = anchorPosition(context, annotation.value, domain)

    /**
     * Where a mark that names a domain position and an optional value sits.
     *
     * Shared by the marker, the call-out and the bare label, so a tap lands
     * where the mark was drawn rather than where a second calculation put it.
     */
    private fun anchorPosition(
        context: ChartRenderContext,
        value: Double?,
        domain: ChartX?,
    ): ChartOffset? {
        val coordinates = context.cartesian
        val plot = coordinates.plotArea
        val at = domain?.let(positionOfDomain) ?: return null
        if (!at.isFinite()) return null
        // No value: pinned to the top of the plot, where an event that has a
        // date but no magnitude belongs.
        val valuePosition = value?.let { coordinates.positionOfValue(it) }
            ?: coordinates.valueOf(ChartOffset(plot.left, plot.top))
        if (!valuePosition.isFinite()) return null
        val point = coordinates.pointAt(at, valuePosition)
        return if (plot.contains(point)) point else null
    }

    // ---- callouts, arrows and bare labels ------------------------------------

    /**
     * A mark, a connector and a label set away from it.
     *
     * The connector is the whole point: a label placed beside a busy plot is
     * ambiguous about which point it names, and a line removes the ambiguity
     * without moving the label back into the crowd.
     */
    private fun drawCallout(
        scope: DrawScope,
        context: ChartRenderContext,
        annotation: ChartAnnotation.Callout,
        domain: ChartX?,
    ) {
        val coordinates = context.cartesian
        val plot = coordinates.plotArea
        val at = domain?.let(positionOfDomain) ?: return
        if (!at.isFinite()) return
        val valuePosition = annotation.value?.let { coordinates.positionOfValue(it) }
            ?: coordinates.valueOf(ChartOffset(plot.left, plot.top))
        if (!valuePosition.isFinite()) return
        val anchor = coordinates.pointAt(at, valuePosition)
        if (!plot.contains(anchor)) return

        val colour = annotation.style.color ?: context.colors.annotation.line
        val radius = context.px(context.dimensions.annotationMarkerRadius)
        val length = context.px(annotation.connectorLength ?: context.dimensions.calloutConnectorLength)

        val target = when (annotation.direction) {
            CalloutDirection.Up -> Offset(anchor.x, anchor.y - length)
            CalloutDirection.Down -> Offset(anchor.x, anchor.y + length)
            CalloutDirection.Start -> Offset(anchor.x - length, anchor.y)
            CalloutDirection.End -> Offset(anchor.x + length, anchor.y)
        }

        scope.drawLine(
            color = colour,
            start = Offset(anchor.x, anchor.y),
            end = target,
            strokeWidth = context.px(
                annotation.style.lineWidth ?: context.dimensions.annotationLineWidth,
            ),
        )
        scope.drawMarker(annotation.shape, Offset(anchor.x, anchor.y), radius, colour)
        drawChip(scope, context, annotation.label, target, plot, annotation.direction)
    }

    /** A straight arrow between two points, with a head at the far end. */
    private fun drawArrow(
        scope: DrawScope,
        context: ChartRenderContext,
        annotation: ChartAnnotation.Arrow,
        resolved: ResolvedAnnotation,
    ) {
        val coordinates = context.cartesian
        val plot = coordinates.plotArea
        val fromDomain = resolved.domainStart?.let(positionOfDomain) ?: return
        val toDomain = resolved.domainEnd?.let(positionOfDomain) ?: return
        val fromValue = coordinates.positionOfValue(annotation.fromValue)
        val toValue = coordinates.positionOfValue(annotation.toValue)
        if (!fromDomain.isFinite() || !toDomain.isFinite() ||
            !fromValue.isFinite() || !toValue.isFinite()
        ) {
            return
        }
        val from = coordinates.pointAt(fromDomain, fromValue)
        val to = coordinates.pointAt(toDomain, toValue)
        if (!withinPlot(plot, from, to)) return

        val colour = annotation.style.color ?: context.colors.annotation.line
        val width = context.px(annotation.style.lineWidth ?: context.dimensions.annotationLineWidth)
        scope.drawLine(colour, Offset(from.x, from.y), Offset(to.x, to.y), strokeWidth = width)

        // The head is built from the segment's own direction, so it points the
        // right way whichever way round the two ends were given.
        val head = context.px(context.dimensions.annotationArrowHead)
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = kotlin.math.sqrt(dx * dx + dy * dy)
        if (length > 0f) {
            val ux = dx / length
            val uy = dy / length
            val baseX = to.x - ux * head
            val baseY = to.y - uy * head
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(to.x, to.y)
                lineTo(baseX - uy * head * ARROW_HALF_WIDTH, baseY + ux * head * ARROW_HALF_WIDTH)
                lineTo(baseX + uy * head * ARROW_HALF_WIDTH, baseY - ux * head * ARROW_HALF_WIDTH)
                close()
            }
            scope.drawPath(path, colour)
        }
        drawLabel(scope, context, annotation, Offset(from.x, from.y), Offset(to.x, to.y), plot)
    }

    /** A chip with no mark, at a point on the plot. */
    private fun drawLabelBox(
        scope: DrawScope,
        context: ChartRenderContext,
        annotation: ChartAnnotation.LabelBox,
        domain: ChartX?,
    ) {
        val coordinates = context.cartesian
        val plot = coordinates.plotArea
        val at = domain?.let(positionOfDomain) ?: return
        if (!at.isFinite()) return
        val valuePosition = annotation.value?.let { coordinates.positionOfValue(it) }
            ?: coordinates.valueOf(ChartOffset(plot.left, plot.top))
        if (!valuePosition.isFinite()) return
        val point = coordinates.pointAt(at, valuePosition)
        if (!plot.contains(point)) return
        drawChip(scope, context, annotation.label, Offset(point.x, point.y), plot, null)
    }

    /**
     * A label chip centred on a point, kept inside the plot.
     *
     * Shared by the call-out and the bare label, because "a chip that stays on
     * screen" is the same problem for both and solving it twice is how two
     * annotations come to sit differently.
     */
    private fun drawChip(
        scope: DrawScope,
        context: ChartRenderContext,
        text: String?,
        at: Offset,
        plot: ChartRect,
        direction: CalloutDirection?,
    ) {
        val label = text?.takeIf { it.isNotBlank() } ?: return
        val style = context.typography.annotationLabel
            .copy(color = context.colors.annotation.labelContent)
        val layout = context.textMeasurer.measure(label, style, maxLines = 2)
        val padding = context.px(context.dimensions.annotationLabelPadding)
        val boxWidth = layout.size.width + padding * 2f
        val boxHeight = layout.size.height + padding * 2f

        // The chip sits beyond the connector's end, in the direction it points,
        // so the line reaches the chip rather than ending inside it.
        val centre = when (direction) {
            CalloutDirection.Up -> Offset(at.x, at.y - boxHeight / 2f)
            CalloutDirection.Down -> Offset(at.x, at.y + boxHeight / 2f)
            CalloutDirection.Start -> Offset(at.x - boxWidth / 2f, at.y)
            CalloutDirection.End -> Offset(at.x + boxWidth / 2f, at.y)
            null -> at
        }

        val left = (centre.x - boxWidth / 2f)
            .coerceIn(plot.left, (plot.right - boxWidth).coerceAtLeast(plot.left))
        val top = (centre.y - boxHeight / 2f)
            .coerceIn(plot.top, (plot.bottom - boxHeight).coerceAtLeast(plot.top))

        scope.drawRoundRect(
            color = context.colors.annotation.labelContainer,
            topLeft = Offset(left, top),
            size = Size(boxWidth, boxHeight),
            cornerRadius = CornerRadius(padding, padding),
        )
        scope.drawText(layout, topLeft = Offset(left + padding, top + padding))
    }

    // ---- labels --------------------------------------------------------------

    @Suppress("LongParameterList")
    private fun drawLabel(
        scope: DrawScope,
        context: ChartRenderContext,
        annotation: ChartAnnotation,
        from: Offset,
        to: Offset,
        plot: ChartRect,
    ) {
        val text = annotation.label?.takeIf { it.isNotBlank() } ?: return
        if (annotation.style.labelPlacement == AnnotationLabelPlacement.None) return

        val style = context.typography.annotationLabel
            .copy(color = context.colors.annotation.labelContent)
        val layout: TextLayoutResult = context.textMeasurer.measure(text, style)
        val padding = context.px(context.dimensions.annotationLabelPadding)
        val boxWidth = layout.size.width + padding * 2f
        val boxHeight = layout.size.height + padding * 2f

        val anchor = when (annotation.style.labelPlacement) {
            AnnotationLabelPlacement.Start -> from
            AnnotationLabelPlacement.End -> to
            AnnotationLabelPlacement.Center -> Offset((from.x + to.x) / 2f, (from.y + to.y) / 2f)
            AnnotationLabelPlacement.None -> return
        }

        // Kept inside the plot. A target line at the top of the chart would
        // otherwise put its label above the composable's own bounds.
        val left = (anchor.x - boxWidth).coerceIn(plot.left, (plot.right - boxWidth).coerceAtLeast(plot.left))
        val top = (anchor.y - boxHeight / 2f)
            .coerceIn(plot.top, (plot.bottom - boxHeight).coerceAtLeast(plot.top))

        scope.drawRoundRect(
            color = context.colors.annotation.labelContainer,
            topLeft = Offset(left, top),
            size = Size(boxWidth, boxHeight),
            cornerRadius = CornerRadius(padding, padding),
        )
        scope.drawText(layout, topLeft = Offset(left + padding, top + padding))
    }

    // ---- export --------------------------------------------------------------

    /**
     * Rules, bands, regions and markers as scene primitives.
     *
     * Everything this layer draws reduces to a line, a rectangle, a circle, a
     * polygon or a run of text, so the scene representation is exact rather
     * than an approximation — which is the bar an export has to clear before it
     * is worth shipping.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    override fun renderScene(
        builder: io.devkit.chartkit.scene.ChartSceneBuilder,
        context: ChartRenderContext,
    ): Boolean {
        val coordinates = context.cartesian
        val plot = coordinates.plotArea
        if (plot.isEmpty || annotations.isEmpty()) return true

        val lineWidth = context.px(context.dimensions.annotationLineWidth)
        val markerRadius = context.px(context.dimensions.annotationMarkerRadius)

        builder.group(id) {
            annotations.forEach { resolved ->
                val annotation = resolved.annotation
                val colour = annotation.style.color ?: context.colors.annotation.line
                val dash = if (annotation.style.dashed) {
                    floatArrayOf(lineWidth * 5f, lineWidth * 4f)
                } else {
                    null
                }

                when (annotation) {
                    is ChartAnnotation.HorizontalRule -> {
                        val at = coordinates.positionOfValue(annotation.value)
                        if (!at.isFinite()) return@forEach
                        val a = coordinates.pointAt(coordinates.domainOf(ChartOffset(plot.left, plot.top)), at)
                        val b = coordinates.pointAt(
                            coordinates.domainOf(ChartOffset(plot.right, plot.bottom)),
                            at,
                        )
                        add(io.devkit.chartkit.scene.ChartSceneNode.Line(a, b, colour, lineWidth, dash))
                    }

                    is ChartAnnotation.VerticalRule -> {
                        val at = resolved.domainStart?.let(positionOfDomain) ?: return@forEach
                        if (!at.isFinite()) return@forEach
                        val a = coordinates.pointAt(at, coordinates.valueOf(ChartOffset(plot.left, plot.top)))
                        val b = coordinates.pointAt(
                            at,
                            coordinates.valueOf(ChartOffset(plot.right, plot.bottom)),
                        )
                        add(io.devkit.chartkit.scene.ChartSceneNode.Line(a, b, colour, lineWidth, dash))
                    }

                    is ChartAnnotation.ValueRange -> {
                        val from = coordinates.positionOfValue(annotation.from)
                        val to = coordinates.positionOfValue(annotation.to)
                        if (!from.isFinite() || !to.isFinite()) return@forEach
                        addRegion(
                            spanRect(coordinates, plot, valueFrom = from, valueTo = to),
                            annotation.style.color ?: context.colors.annotation.region,
                            colour,
                            lineWidth,
                        )
                    }

                    is ChartAnnotation.DomainRange -> {
                        val from = resolved.domainStart?.let(positionOfDomain) ?: return@forEach
                        val to = resolved.domainEnd?.let(positionOfDomain) ?: return@forEach
                        if (!from.isFinite() || !to.isFinite()) return@forEach
                        addRegion(
                            spanRect(coordinates, plot, domainFrom = from, domainTo = to),
                            annotation.style.color ?: context.colors.annotation.region,
                            colour,
                            lineWidth,
                        )
                    }

                    is ChartAnnotation.Region -> {
                        val domainFrom = resolved.domainStart?.let(positionOfDomain) ?: return@forEach
                        val domainTo = resolved.domainEnd?.let(positionOfDomain) ?: return@forEach
                        val valueFrom = coordinates.positionOfValue(annotation.valueFrom)
                        val valueTo = coordinates.positionOfValue(annotation.valueTo)
                        if (!domainFrom.isFinite() || !domainTo.isFinite() ||
                            !valueFrom.isFinite() || !valueTo.isFinite()
                        ) {
                            return@forEach
                        }
                        val a = coordinates.pointAt(domainFrom, valueFrom)
                        val b = coordinates.pointAt(domainTo, valueTo)
                        addRegion(
                            ChartRect(a.x, a.y, b.x, b.y).normalized,
                            annotation.style.color ?: context.colors.annotation.region,
                            colour,
                            lineWidth,
                        )
                    }

                    is ChartAnnotation.EventMarker -> {
                        val at = anchorPosition(context, annotation.value, resolved.domainStart)
                            ?: return@forEach
                        addMarker(annotation.shape, at, markerRadius, colour, lineWidth)
                    }

                    is ChartAnnotation.Callout -> {
                        val at = anchorPosition(context, annotation.value, resolved.domainStart)
                            ?: return@forEach
                        val length = context.px(
                            annotation.connectorLength ?: context.dimensions.calloutConnectorLength,
                        )
                        val target = when (annotation.direction) {
                            CalloutDirection.Up -> ChartOffset(at.x, at.y - length)
                            CalloutDirection.Down -> ChartOffset(at.x, at.y + length)
                            CalloutDirection.Start -> ChartOffset(at.x - length, at.y)
                            CalloutDirection.End -> ChartOffset(at.x + length, at.y)
                        }
                        add(io.devkit.chartkit.scene.ChartSceneNode.Line(at, target, colour, lineWidth))
                        addMarker(annotation.shape, at, markerRadius, colour, lineWidth)
                        addLabel(annotation.label, target, context)
                    }

                    is ChartAnnotation.Arrow -> {
                        val fromDomain = resolved.domainStart?.let(positionOfDomain) ?: return@forEach
                        val toDomain = resolved.domainEnd?.let(positionOfDomain) ?: return@forEach
                        val fromValue = coordinates.positionOfValue(annotation.fromValue)
                        val toValue = coordinates.positionOfValue(annotation.toValue)
                        if (!fromDomain.isFinite() || !toDomain.isFinite() ||
                            !fromValue.isFinite() || !toValue.isFinite()
                        ) {
                            return@forEach
                        }
                        val from = coordinates.pointAt(fromDomain, fromValue)
                        val to = coordinates.pointAt(toDomain, toValue)
                        add(io.devkit.chartkit.scene.ChartSceneNode.Line(from, to, colour, lineWidth))
                        addArrowHead(from, to, context.px(context.dimensions.annotationArrowHead), colour)
                    }

                    is ChartAnnotation.LabelBox -> {
                        val at = anchorPosition(context, annotation.value, resolved.domainStart)
                            ?: return@forEach
                        addLabel(annotation.label, at, context)
                    }
                }
            }
        }
        return true
    }

    private fun withinPlot(plot: ChartRect, a: ChartOffset, b: ChartOffset): Boolean =
        a.isFinite && b.isFinite &&
            maxOf(a.x, b.x) >= plot.left && minOf(a.x, b.x) <= plot.right &&
            maxOf(a.y, b.y) >= plot.top && minOf(a.y, b.y) <= plot.bottom

    // ---- interaction ---------------------------------------------------------

    /**
     * Event markers are selectable; rules and regions are not.
     *
     * A marker names something that happened and has a tooltip worth showing. A
     * threshold line is a reference the reader drew themselves — selecting it
     * would report a number they already chose, and would steal the tap from
     * the data underneath.
     */
    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        if (order != AnnotationOrder.Above) return null
        val radius = context.px(context.dimensions.annotationMarkerRadius) * MARKER_HIT_FACTOR

        annotations.forEach { resolved ->
            // Markers and call-outs both name a moment and both have a mark to
            // aim at. A rule and a region do not, so they are not targets.
            val (at, value) = when (val annotation = resolved.annotation) {
                is ChartAnnotation.EventMarker -> annotation.at to annotation.value
                is ChartAnnotation.Callout -> annotation.at to annotation.value
                else -> return@forEach
            }
            val position = anchorPosition(context, value, resolved.domainStart) ?: return@forEach
            if (abs(position.x - point.x) <= radius && abs(position.y - point.y) <= radius) {
                val annotation = resolved.annotation
                return ChartSelection(
                    seriesId = annotation.id,
                    seriesName = annotation.label.orEmpty(),
                    seriesIndex = 0,
                    pointIndex = 0,
                    x = resolved.domainStart ?: ChartX.Category(annotation.label.orEmpty()),
                    y = value ?: 0.0,
                    // The annotation itself, so a caller reads
                    // `selection.chartAnnotation` and acts on it — the same
                    // selection channel every other mark uses, rather than a
                    // parallel callback that would need its own clearing rules.
                    item = annotation,
                    position = position,
                )
            }
        }
        return null
    }

    /**
     * Annotations that mean something, and only those.
     *
     * A labelled threshold or event is information a reader who cannot see the
     * chart needs — "Target: 100,000" is the whole reason the line is there. An
     * unlabelled rule is decoration, and announcing it would pad the summary
     * with facts nobody stated.
     */
    override fun describe(): List<ChartLayerSummary> {
        val meaningful = annotations.mapNotNull { resolved ->
            val annotation = resolved.annotation
            val label = annotation.label?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ChartLayerEntry(
                label = label,
                value = when (annotation) {
                    is ChartAnnotation.HorizontalRule -> annotation.value
                    is ChartAnnotation.EventMarker -> annotation.value
                    is ChartAnnotation.Callout -> annotation.value
                    is ChartAnnotation.LabelBox -> annotation.value
                    else -> null
                },
                detail = when (annotation) {
                    is ChartAnnotation.HorizontalRule ->
                        "$label: ${valueFormatter.format(annotation.value)}"
                    is ChartAnnotation.ValueRange ->
                        "$label: ${valueFormatter.format(annotation.from)} to " +
                            valueFormatter.format(annotation.to)
                    else -> label
                },
            )
        }
        if (meaningful.isEmpty()) return emptyList()
        return listOf(
            ChartLayerSummary(
                seriesId = id,
                seriesName = "Annotations",
                pointCount = meaningful.size,
                entries = meaningful,
            ),
        )
    }

    private companion object {
        const val MARKER_HOLE_FRACTION = 0.4f

        /** Markers are small; fingers are not. */
        const val MARKER_HIT_FACTOR = 2.2f

        /** Half the arrowhead's width, as a fraction of its length. */
        const val ARROW_HALF_WIDTH = 0.5f
    }
}

/**
 * One marker shape.
 *
 * Shapes rather than colours distinguish one kind of mark from another, so a
 * reader who cannot tell two annotation colours apart can still tell a release
 * from an incident.
 */
private fun DrawScope.drawMarker(
    shape: AnnotationMarkerShape,
    centre: Offset,
    radius: Float,
    colour: androidx.compose.ui.graphics.Color,
) {
    when (shape) {
        AnnotationMarkerShape.Circle -> drawCircle(colour, radius, centre)

        AnnotationMarkerShape.Square -> drawRect(
            color = colour,
            topLeft = Offset(centre.x - radius, centre.y - radius),
            size = Size(radius * 2f, radius * 2f),
        )

        AnnotationMarkerShape.Diamond -> drawPath(
            androidx.compose.ui.graphics.Path().apply {
                moveTo(centre.x, centre.y - radius)
                lineTo(centre.x + radius, centre.y)
                lineTo(centre.x, centre.y + radius)
                lineTo(centre.x - radius, centre.y)
                close()
            },
            colour,
        )

        AnnotationMarkerShape.Triangle -> drawPath(
            androidx.compose.ui.graphics.Path().apply {
                moveTo(centre.x, centre.y - radius)
                lineTo(centre.x + radius, centre.y + radius)
                lineTo(centre.x - radius, centre.y + radius)
                close()
            },
            colour,
        )

        AnnotationMarkerShape.Cross -> {
            val width = radius * 0.45f
            drawLine(
                colour,
                Offset(centre.x - radius, centre.y - radius),
                Offset(centre.x + radius, centre.y + radius),
                strokeWidth = width,
            )
            drawLine(
                colour,
                Offset(centre.x + radius, centre.y - radius),
                Offset(centre.x - radius, centre.y + radius),
                strokeWidth = width,
            )
        }
    }
}

/** A filled region with its outline, the pair the layer always draws together. */
private fun io.devkit.chartkit.scene.ChartSceneBuilder.addRegion(
    rect: ChartRect,
    fill: androidx.compose.ui.graphics.Color,
    outline: androidx.compose.ui.graphics.Color,
    strokeWidth: Float,
) {
    if (rect.width <= 0f || rect.height <= 0f) return
    add(io.devkit.chartkit.scene.ChartSceneNode.Rect(rect, fill))
    add(
        io.devkit.chartkit.scene.ChartSceneNode.Rect(
            bounds = rect,
            color = outline.copy(alpha = outline.alpha * 0.6f),
            style = io.devkit.chartkit.scene.PaintStyle.Stroke,
            strokeWidth = strokeWidth,
        ),
    )
}

/** One marker shape as a scene primitive. */
private fun io.devkit.chartkit.scene.ChartSceneBuilder.addMarker(
    shape: AnnotationMarkerShape,
    centre: ChartOffset,
    radius: Float,
    colour: androidx.compose.ui.graphics.Color,
    strokeWidth: Float,
) {
    when (shape) {
        AnnotationMarkerShape.Circle ->
            add(io.devkit.chartkit.scene.ChartSceneNode.Circle(centre, radius, colour))

        AnnotationMarkerShape.Square -> add(
            io.devkit.chartkit.scene.ChartSceneNode.Rect(
                ChartRect(centre.x - radius, centre.y - radius, centre.x + radius, centre.y + radius),
                colour,
            ),
        )

        AnnotationMarkerShape.Diamond -> add(
            io.devkit.chartkit.scene.ChartSceneNode.Path(
                points = listOf(
                    ChartOffset(centre.x, centre.y - radius),
                    ChartOffset(centre.x + radius, centre.y),
                    ChartOffset(centre.x, centre.y + radius),
                    ChartOffset(centre.x - radius, centre.y),
                ),
                color = colour,
                style = io.devkit.chartkit.scene.PaintStyle.Fill,
                closed = true,
            ),
        )

        AnnotationMarkerShape.Triangle -> add(
            io.devkit.chartkit.scene.ChartSceneNode.Path(
                points = listOf(
                    ChartOffset(centre.x, centre.y - radius),
                    ChartOffset(centre.x + radius, centre.y + radius),
                    ChartOffset(centre.x - radius, centre.y + radius),
                ),
                color = colour,
                style = io.devkit.chartkit.scene.PaintStyle.Fill,
                closed = true,
            ),
        )

        AnnotationMarkerShape.Cross -> {
            add(
                io.devkit.chartkit.scene.ChartSceneNode.Line(
                    ChartOffset(centre.x - radius, centre.y - radius),
                    ChartOffset(centre.x + radius, centre.y + radius),
                    colour,
                    strokeWidth,
                ),
            )
            add(
                io.devkit.chartkit.scene.ChartSceneNode.Line(
                    ChartOffset(centre.x + radius, centre.y - radius),
                    ChartOffset(centre.x - radius, centre.y + radius),
                    colour,
                    strokeWidth,
                ),
            )
        }
    }
}

/** An arrowhead built from the segment's own direction. */
private fun io.devkit.chartkit.scene.ChartSceneBuilder.addArrowHead(
    from: ChartOffset,
    to: ChartOffset,
    head: Float,
    colour: androidx.compose.ui.graphics.Color,
) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = kotlin.math.sqrt(dx * dx + dy * dy)
    if (length <= 0f) return
    val ux = dx / length
    val uy = dy / length
    val baseX = to.x - ux * head
    val baseY = to.y - uy * head
    add(
        io.devkit.chartkit.scene.ChartSceneNode.Path(
            points = listOf(
                to,
                ChartOffset(baseX - uy * head * 0.5f, baseY + ux * head * 0.5f),
                ChartOffset(baseX + uy * head * 0.5f, baseY - ux * head * 0.5f),
            ),
            color = colour,
            style = io.devkit.chartkit.scene.PaintStyle.Fill,
            closed = true,
        ),
    )
}

/** An annotation's label, measured by the chart so the export lands where it drew. */
private fun io.devkit.chartkit.scene.ChartSceneBuilder.addLabel(
    text: String?,
    at: ChartOffset,
    context: ChartRenderContext,
) {
    val label = text?.takeIf { it.isNotBlank() } ?: return
    val style = context.typography.annotationLabel
    val layout = context.textMeasurer.measure(label, style, maxLines = 1)
    val fontSize = with(context.density) { style.fontSize.toPx() }
    add(
        io.devkit.chartkit.scene.ChartSceneNode.Text(
            text = label,
            position = ChartOffset(at.x, at.y + layout.firstBaseline - layout.size.height / 2f),
            color = context.colors.annotation.labelContent,
            fontSizePx = fontSize,
            anchor = io.devkit.chartkit.scene.TextAnchor.Middle,
        ),
    )
}
