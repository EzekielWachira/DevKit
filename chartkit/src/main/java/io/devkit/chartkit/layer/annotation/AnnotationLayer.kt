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
import io.devkit.chartkit.annotation.AnnotationOrder
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

        scope.drawCircle(colour, radius, Offset(position.x, position.y))
        // A hole in the middle, so the marker reads as a mark rather than as a
        // data point of the series it sits over.
        scope.drawCircle(
            context.colors.annotation.labelContent,
            radius * MARKER_HOLE_FRACTION,
            Offset(position.x, position.y),
        )
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
    ): ChartOffset? {
        val coordinates = context.cartesian
        val plot = coordinates.plotArea
        val at = domain?.let(positionOfDomain) ?: return null
        if (!at.isFinite()) return null
        // No value: pinned to the top of the plot, where an event that has a
        // date but no magnitude belongs.
        val valuePosition = annotation.value?.let { coordinates.positionOfValue(it) }
            ?: coordinates.valueOf(ChartOffset(plot.left, plot.top))
        if (!valuePosition.isFinite()) return null
        val point = coordinates.pointAt(at, valuePosition)
        return if (plot.contains(point)) point else null
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
            val annotation = resolved.annotation as? ChartAnnotation.EventMarker ?: return@forEach
            val position = markerPosition(context, annotation, resolved.domainStart) ?: return@forEach
            if (abs(position.x - point.x) <= radius && abs(position.y - point.y) <= radius) {
                return ChartSelection(
                    seriesId = annotation.id,
                    seriesName = annotation.label.orEmpty(),
                    seriesIndex = 0,
                    pointIndex = 0,
                    x = resolved.domainStart ?: ChartX.Category(annotation.label.orEmpty()),
                    y = annotation.value ?: 0.0,
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
    }
}
