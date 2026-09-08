package io.devkit.chartkit.layer.comparison

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.devkit.chartkit.coordinate.CartesianCoordinates
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartMath
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
import io.devkit.chartkit.transform.WaterfallStep
import io.devkit.chartkit.transform.WaterfallStepKind

/**
 * A running total, bar by bar.
 *
 * ```text
 *        ┌──┐
 *  ┌───┐ │  │╌╌┌──┐        ┌────┐
 *  │   │╌┘  │  │  │╌╌┌──┐╌╌│    │
 *  └───┘    └──┘  └──┘  └──┘    │
 *  Start   Rev   Cost  Tax  Total
 * ```
 *
 * ### Built on the Cartesian engine
 *
 * The axes, the grid, the category scale, the viewport, the tooltip, the
 * crosshair and the annotations are the ones every bar chart uses. What is here
 * is the bar's *anchor* — an increase starts where the previous bar ended,
 * while a subtotal starts at zero — and the connectors between them.
 *
 * ### Colour by role, not by sign
 *
 * Increases, decreases, subtotals and totals take four semantic theme colours,
 * so an application whose convention runs the other way swaps two values rather
 * than forking a layer. Nothing here knows that "up" is green.
 */
internal class WaterfallLayer(
    override val id: String,
    private val steps: List<WaterfallStep>,
    private val seriesId: String,
    private val seriesName: String,
    private val showConnectors: Boolean,
    private val cornerRadius: androidx.compose.ui.unit.Dp?,
    private val valueFormatter: ChartValueFormatter,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val coordinates = context.cartesian
        val categories = coordinates.categories ?: return
        if (coordinates.plotArea.isEmpty || steps.isEmpty()) return

        val reveal = context.reveal.coerceIn(0f, 1f)
        val band = categories.innerBandWidth
        val colours = context.colors.comparison
        val radius = context.px(cornerRadius ?: context.dimensions.barCornerRadius)

        steps.forEachIndexed { index, step ->
            val rect = rectFor(coordinates, categories.positionAt(index), band, step, reveal)
            if (rect.isEmpty) return@forEachIndexed
            val colour = when (step.kind) {
                WaterfallStepKind.Increase -> colours.increase
                WaterfallStepKind.Decrease -> colours.decrease
                WaterfallStepKind.Subtotal -> colours.subtotal
                WaterfallStepKind.Total -> colours.total
            }
            val effectiveRadius = minOf(radius, minOf(rect.width, rect.height) / 2f)
            scope.drawRoundRect(
                color = colour,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, rect.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                    effectiveRadius,
                    effectiveRadius,
                ),
            )

            if (isSelected(context, index)) {
                scope.drawRect(
                    color = context.colors.selectionHighlight,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                )
                scope.drawRect(
                    color = context.colors.selectionGuide,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    style = Stroke(width = context.px(context.dimensions.selectionGuideWidth) * 2f),
                )
            }
        }

        if (showConnectors && reveal >= 1f) drawConnectors(scope, context, categories, band)
    }

    /**
     * The dashes joining one bar's end to the next bar's start.
     *
     * What turns a row of floating bars into a running total: without them the
     * reader has to infer that the third bar starts where the second finished,
     * which is the one thing the chart exists to say.
     */
    private fun drawConnectors(
        scope: DrawScope,
        context: ChartRenderContext,
        categories: io.devkit.chartkit.scale.CategoryScale,
        band: Float,
    ) {
        val coordinates = context.cartesian
        val width = context.px(context.dimensions.waterfallConnectorWidth)
        val effect = PathEffect.dashPathEffect(floatArrayOf(width * 4f, width * 3f))

        steps.zipWithNext().forEachIndexed { index, (current, next) ->
            // A connector runs to where the *next* bar begins. For an anchored
            // step that is zero, not the running total, so no connector is
            // drawn — a line to a bar that starts from the axis would be
            // asserting a continuation that is not there.
            if (next.kind.isAnchored) return@forEachIndexed
            val value = if (current.kind.isAnchored) current.runningTotal else current.end
            val position = coordinates.positionOfValue(value)
            if (!position.isFinite()) return@forEachIndexed
            val from = coordinates.pointAt(categories.positionAt(index) + band / 2f, position)
            val to = coordinates.pointAt(categories.positionAt(index + 1) - band / 2f, position)
            scope.drawLine(
                color = context.colors.comparison.connector,
                start = Offset(from.x, from.y),
                end = Offset(to.x, to.y),
                strokeWidth = width,
                pathEffect = effect,
            )
        }
    }

    /**
     * One bar's rectangle.
     *
     * The reveal grows it from its own start rather than from the axis, because
     * a waterfall's bars do not begin at the axis — animating them from there
     * would show every bar sliding through positions the data never had.
     */
    private fun rectFor(
        coordinates: CartesianCoordinates,
        centre: Float,
        band: Float,
        step: WaterfallStep,
        reveal: Float,
    ): ChartRect {
        val startPosition = coordinates.positionOfValue(step.start)
        val endPosition = coordinates.positionOfValue(step.end)
        if (!startPosition.isFinite() || !endPosition.isFinite()) return ChartRect.Zero
        val grown = ChartMath.lerp(startPosition, endPosition, reveal)
        // A zero-magnitude step still draws a hairline, so a bar that changed
        // nothing is visibly present rather than missing.
        val low = minOf(startPosition, grown)
        val high = maxOf(startPosition, grown)
        val a = coordinates.pointAt(centre - band / 2f, low)
        val b = coordinates.pointAt(centre + band / 2f, maxOf(high, low + MIN_BAR_EXTENT))
        return ChartRect(a.x, a.y, b.x, b.y).normalized
    }

    private fun isSelected(context: ChartRenderContext, index: Int): Boolean =
        context.selection?.let { it.seriesId == seriesId && it.pointIndex == index } == true

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val coordinates = context.cartesian
        val categories = coordinates.categories ?: return null
        val index = categories.indexAt(coordinates.domainOf(point))
        if (index < 0) return null
        val step = steps.getOrNull(index) ?: return null
        val rect = rectFor(coordinates, categories.positionAt(index), categories.innerBandWidth, step, 1f)
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = index,
            x = ChartX.Category(step.label),
            // The *running total* is what a waterfall bar reports: "revenue
            // +40" is the delta, but "we are at 140" is the reading.
            y = step.runningTotal,
            item = step.item,
            position = ChartOffset(rect.centerX, minOf(rect.top, rect.bottom)),
        )
    }

    override fun describe(): List<ChartLayerSummary> = listOf(
        ChartLayerSummary(
            seriesId = seriesId,
            seriesName = seriesName,
            pointCount = steps.size,
            entries = steps.map { step ->
                ChartLayerEntry(
                    label = step.label,
                    value = step.runningTotal,
                    detail = describeStep(step, valueFormatter),
                )
            },
        ),
    )

    override fun describeSelection(
        selection: AnyChartSelection,
        formatter: ChartValueFormatter,
    ): String? {
        val step = steps.getOrNull(selection.pointIndex) ?: return null
        return describeStep(step, formatter)
    }

    /** The step, its change and where the running total stands after it. */
    private fun describeStep(step: WaterfallStep, formatter: ChartValueFormatter): String =
        when (step.kind) {
            WaterfallStepKind.Subtotal, WaterfallStepKind.Total ->
                "${step.label}: ${formatter.format(step.runningTotal)}"

            WaterfallStepKind.Increase ->
                "${step.label}: up ${formatter.format(kotlin.math.abs(step.delta))}, " +
                    "running total ${formatter.format(step.runningTotal)}"

            WaterfallStepKind.Decrease ->
                "${step.label}: down ${formatter.format(kotlin.math.abs(step.delta))}, " +
                    "running total ${formatter.format(step.runningTotal)}"
        }

    private companion object {
        /** A step of zero is still a bar the reader can see and tap. */
        const val MIN_BAR_EXTENT = 1.5f
    }
}
