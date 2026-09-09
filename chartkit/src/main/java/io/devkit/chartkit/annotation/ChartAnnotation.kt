package io.devkit.chartkit.annotation

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** Whether an annotation is drawn under the data or over it. */
enum class AnnotationOrder {

    /**
     * Behind the data. The default for regions and ranges.
     *
     * A shaded target zone drawn over the line hides the values the reader
     * shaded it to compare against.
     */
    Behind,

    /**
     * In front of the data. The default for rules and markers.
     *
     * A threshold line has to be visible where it crosses the series, which is
     * exactly the place it would be hidden if it were drawn first.
     */
    Above,
}

/**
 * The shape of an event marker or a callout's anchor.
 *
 * Shapes rather than icons. An icon is a drawable, a tint, a density and a
 * content description — four things a canvas-drawn mark would have to be told
 * about, and four things that go wrong differently on each. A small vocabulary
 * of shapes covers what a marker is for, distinguishes marks *without relying
 * on colour*, and costs one path each.
 */
enum class AnnotationMarkerShape {
    Circle,
    Diamond,
    Square,
    /** Points upward; the usual "something happened here" mark. */
    Triangle,
    /** A cross, for a mark that should not read as a data point. */
    Cross,
}

/** Which way a callout's label sits from the point it names. */
enum class CalloutDirection {
    Up,
    Down,
    Start,
    End,
}

/** Where an annotation's label sits along the rule or region it names. */
enum class AnnotationLabelPlacement {
    Start,
    Center,
    End,
    None,
}

/**
 * How an annotation is drawn.
 *
 * Every field is nullable and falls back to the theme, so an annotation that
 * only needs to exist is `horizontalRule(100_000.0, label = "Target")` and one
 * that needs to look particular can say so without a theme.
 *
 * @param color the rule or region colour. `null` takes
 *   [io.devkit.chartkit.theme.ChartAnnotationColors].
 * @param lineWidth `null` takes the theme's annotation line width.
 * @param dashed a dashed rule reads as a reference line rather than as data,
 *   which is what a threshold is. On by default for rules.
 * @param labelPlacement where the label sits, or [AnnotationLabelPlacement.None]
 *   to draw the annotation unlabelled.
 */
@Immutable
data class AnnotationStyle(
    val color: Color? = null,
    val lineWidth: Dp? = null,
    val dashed: Boolean = true,
    val labelPlacement: AnnotationLabelPlacement = AnnotationLabelPlacement.End,
) {
    companion object {
        /** A dashed rule with its label at the far end. */
        val Rule: AnnotationStyle = AnnotationStyle()

        /** A solid rule, for something that is not a threshold. */
        val Solid: AnnotationStyle = AnnotationStyle(dashed = false)

        /** A filled region with its label at the start of the band. */
        val Region: AnnotationStyle = AnnotationStyle(
            dashed = false,
            labelPlacement = AnnotationLabelPlacement.Start,
        )

        /** A marker with its label beside it. */
        val Marker: AnnotationStyle = AnnotationStyle(
            dashed = false,
            labelPlacement = AnnotationLabelPlacement.End,
        )
    }
}

/**
 * A reference mark drawn in a chart's own coordinate space.
 *
 * ### Why a layer and not a chart parameter
 *
 * A target line, a launch date and a safe operating band are the same marks on
 * a line chart, a bar chart, a scatter and a candlestick chart. Written as a
 * parameter of `LineChart` they would exist on exactly one chart type; written
 * as annotations on the coordinate system they exist on all of them, and a
 * combined chart gets them once rather than once per layer.
 *
 * ### Domain positions
 *
 * Anything positioned along the domain takes `Any?` and is resolved by the
 * chart's own [io.devkit.chartkit.model.ChartXResolver] — the same mechanism
 * the data uses. So `verticalRule(at = "Mar")` works on a category chart and
 * `verticalRule(at = campaignStartMillis)` on a time chart, with no conversion
 * and no separate annotation type per axis kind.
 *
 * @property id stable within a chart; used for keying and for hit testing.
 * @property label the text drawn beside the annotation, and read out by a
 *   screen reader when the annotation is a meaningful threshold.
 * @property order whether it is drawn under or over the data.
 * @property extendsDomain whether the annotation's own position is included
 *   when the axes choose their intervals. On by default, because a target line
 *   above every observed value is invisible otherwise — and a reader who cannot
 *   see the target cannot see the gap to it. Turn it off for a marker that
 *   should not stretch the axis.
 */
@Immutable
sealed interface ChartAnnotation {

    val id: String
    val label: String?
    val order: AnnotationOrder
    val style: AnnotationStyle
    val extendsDomain: Boolean

    /**
     * Which value axis this annotation's value is stated in.
     *
     * `null` means the chart's primary axis, which is right for every
     * single-axis chart and is why almost no annotation names one. On a chart
     * with several value axes it is not optional information: a rule at `30`
     * means 30°C on the temperature axis and 30mm on the rainfall one, and a
     * chart that guessed would draw the line in the wrong place and look
     * entirely plausible doing it.
     *
     * ```kotlin
     * horizontalRule(value = 30.0, label = "Heat threshold", valueAxis = TemperatureAxis)
     * ```
     *
     * Vertical and domain-range annotations ignore it: they are positioned on
     * the shared domain axis, which is shared.
     */
    val valueAxis: io.devkit.chartkit.axis.ChartAxisId? get() = null

    /** A rule across the plot at a value — a target, a limit, an average. */
    @Immutable
    data class HorizontalRule(
        val value: Double,
        override val label: String? = null,
        override val id: String = "h-rule-$value",
        override val order: AnnotationOrder = AnnotationOrder.Above,
        override val style: AnnotationStyle = AnnotationStyle.Rule,
        override val extendsDomain: Boolean = true,
        override val valueAxis: io.devkit.chartkit.axis.ChartAxisId? = null,
    ) : ChartAnnotation

    /** A rule down the plot at a domain position — a release, an incident. */
    @Immutable
    data class VerticalRule(
        val at: Any?,
        override val label: String? = null,
        override val id: String = "v-rule-$at",
        override val order: AnnotationOrder = AnnotationOrder.Above,
        override val style: AnnotationStyle = AnnotationStyle.Rule,
        override val extendsDomain: Boolean = false,
    ) : ChartAnnotation

    /** A band between two values — an acceptable range, a confidence interval. */
    @Immutable
    data class ValueRange(
        val from: Double,
        val to: Double,
        override val label: String? = null,
        override val id: String = "v-range-$from-$to",
        override val order: AnnotationOrder = AnnotationOrder.Behind,
        override val style: AnnotationStyle = AnnotationStyle.Region,
        override val extendsDomain: Boolean = true,
        override val valueAxis: io.devkit.chartkit.axis.ChartAxisId? = null,
    ) : ChartAnnotation

    /** A band between two domain positions — a campaign, an outage, a quarter. */
    @Immutable
    data class DomainRange(
        val from: Any?,
        val to: Any?,
        override val label: String? = null,
        override val id: String = "d-range-$from-$to",
        override val order: AnnotationOrder = AnnotationOrder.Behind,
        override val style: AnnotationStyle = AnnotationStyle.Region,
        override val extendsDomain: Boolean = false,
    ) : ChartAnnotation

    /** A rectangle bounded on both axes — a target zone, a safe operating box. */
    @Immutable
    data class Region(
        val domainFrom: Any?,
        val domainTo: Any?,
        val valueFrom: Double,
        val valueTo: Double,
        override val label: String? = null,
        override val id: String = "region-$domainFrom-$domainTo-$valueFrom-$valueTo",
        override val order: AnnotationOrder = AnnotationOrder.Behind,
        override val style: AnnotationStyle = AnnotationStyle.Region,
        override val extendsDomain: Boolean = true,
        override val valueAxis: io.devkit.chartkit.axis.ChartAxisId? = null,
    ) : ChartAnnotation

    /**
     * A point marked on the plot — a product launch, a deploy, a record.
     *
     * @param value where on the value axis it sits. `null` pins it to the top
     *   of the plot, which is where an event that has a date but no magnitude
     *   belongs.
     */
    @Immutable
    data class EventMarker(
        val at: Any?,
        val value: Double? = null,
        override val label: String? = null,
        override val id: String = "event-$at",
        override val order: AnnotationOrder = AnnotationOrder.Above,
        override val style: AnnotationStyle = AnnotationStyle.Marker,
        override val extendsDomain: Boolean = false,
        val shape: AnnotationMarkerShape = AnnotationMarkerShape.Circle,
        override val valueAxis: io.devkit.chartkit.axis.ChartAxisId? = null,
    ) : ChartAnnotation

    /**
     * A labelled call-out pointing at one place on the plot.
     *
     * ```text
     *         ┌─────────────┐
     *         │ App launch  │
     *         └──────┬──────┘
     *                ↓
     * ───────────────●──────────
     * ```
     *
     * The difference from an [EventMarker] is the **connector**: a call-out's
     * label sits away from the point it names, so it can be read where the plot
     * is busy, and the line is what ties the two together. A marker's label sits
     * beside it and is lost as soon as anything else is nearby.
     *
     * @param direction which way the label sits from the anchor.
     * @param connectorLength how far away. `null` takes the theme's.
     */
    @Immutable
    data class Callout(
        val at: Any?,
        val value: Double? = null,
        override val label: String? = null,
        val direction: CalloutDirection = CalloutDirection.Up,
        val connectorLength: Dp? = null,
        val shape: AnnotationMarkerShape = AnnotationMarkerShape.Circle,
        override val id: String = "callout-$at",
        override val order: AnnotationOrder = AnnotationOrder.Above,
        override val style: AnnotationStyle = AnnotationStyle.Marker,
        override val extendsDomain: Boolean = false,
        override val valueAxis: io.devkit.chartkit.axis.ChartAxisId? = null,
    ) : ChartAnnotation

    /**
     * An arrow between two points on the plot.
     *
     * For pointing at a relationship the chart does not otherwise draw — "this
     * peak caused that drop" — where a rule or a region would say the wrong
     * thing. Both ends are in the chart's own coordinates, so the arrow follows
     * the data through a zoom.
     */
    @Immutable
    data class Arrow(
        val fromAt: Any?,
        val fromValue: Double,
        val toAt: Any?,
        val toValue: Double,
        override val label: String? = null,
        override val id: String = "arrow-$fromAt-$toAt",
        override val order: AnnotationOrder = AnnotationOrder.Above,
        override val style: AnnotationStyle = AnnotationStyle.Solid,
        override val extendsDomain: Boolean = false,
        override val valueAxis: io.devkit.chartkit.axis.ChartAxisId? = null,
    ) : ChartAnnotation

    /**
     * A label with no mark, placed at a point on the plot.
     *
     * The quietest annotation there is: a caption, a units note, a "no data
     * after here". Drawn as a chip so it stays legible over the data.
     */
    @Immutable
    data class LabelBox(
        val at: Any?,
        val value: Double? = null,
        override val label: String? = null,
        override val id: String = "label-$at-$value",
        override val order: AnnotationOrder = AnnotationOrder.Above,
        override val style: AnnotationStyle = AnnotationStyle(
            dashed = false,
            labelPlacement = AnnotationLabelPlacement.Center,
        ),
        override val extendsDomain: Boolean = false,
        override val valueAxis: io.devkit.chartkit.axis.ChartAxisId? = null,
    ) : ChartAnnotation
}

// ---- builders ---------------------------------------------------------------
//
// Free functions rather than only constructors, so a list of annotations reads
// as a description of the chart rather than as a list of type names.

/** A rule across the plot at [value]. */
fun horizontalRule(
    value: Double,
    label: String? = null,
    style: AnnotationStyle = AnnotationStyle.Rule,
    order: AnnotationOrder = AnnotationOrder.Above,
    extendsDomain: Boolean = true,
    id: String = "h-rule-$value",
    valueAxis: io.devkit.chartkit.axis.ChartAxisId? = null,
): ChartAnnotation =
    ChartAnnotation.HorizontalRule(value, label, id, order, style, extendsDomain, valueAxis)

/** A rule down the plot at domain position [at]. */
fun verticalRule(
    at: Any?,
    label: String? = null,
    style: AnnotationStyle = AnnotationStyle.Rule,
    order: AnnotationOrder = AnnotationOrder.Above,
    extendsDomain: Boolean = false,
    id: String = "v-rule-$at",
): ChartAnnotation = ChartAnnotation.VerticalRule(at, label, id, order, style, extendsDomain)

/** A band between two values on the value axis. */
fun valueRange(
    from: Double,
    to: Double,
    label: String? = null,
    style: AnnotationStyle = AnnotationStyle.Region,
    order: AnnotationOrder = AnnotationOrder.Behind,
    extendsDomain: Boolean = true,
    id: String = "v-range-$from-$to",
    valueAxis: io.devkit.chartkit.axis.ChartAxisId? = null,
): ChartAnnotation =
    ChartAnnotation.ValueRange(from, to, label, id, order, style, extendsDomain, valueAxis)

/** A band between two positions on the domain axis. */
fun domainRange(
    from: Any?,
    to: Any?,
    label: String? = null,
    style: AnnotationStyle = AnnotationStyle.Region,
    order: AnnotationOrder = AnnotationOrder.Behind,
    extendsDomain: Boolean = false,
    id: String = "d-range-$from-$to",
): ChartAnnotation = ChartAnnotation.DomainRange(from, to, label, id, order, style, extendsDomain)

/** A rectangle bounded on both axes. */
@Suppress("LongParameterList")
fun region(
    domainFrom: Any?,
    domainTo: Any?,
    valueFrom: Double,
    valueTo: Double,
    label: String? = null,
    style: AnnotationStyle = AnnotationStyle.Region,
    order: AnnotationOrder = AnnotationOrder.Behind,
    extendsDomain: Boolean = true,
    id: String = "region-$domainFrom-$domainTo-$valueFrom-$valueTo",
    valueAxis: io.devkit.chartkit.axis.ChartAxisId? = null,
): ChartAnnotation = ChartAnnotation.Region(
    domainFrom, domainTo, valueFrom, valueTo, label, id, order, style, extendsDomain, valueAxis,
)

/**
 * A labelled call-out pointing at a place on the plot.
 *
 * @param value where on the value axis the anchor sits. `null` pins it to the
 *   top of the plot.
 */
@Suppress("LongParameterList")
fun callout(
    at: Any?,
    value: Double? = null,
    label: String? = null,
    direction: CalloutDirection = CalloutDirection.Up,
    connectorLength: Dp? = null,
    shape: AnnotationMarkerShape = AnnotationMarkerShape.Circle,
    style: AnnotationStyle = AnnotationStyle.Marker,
    order: AnnotationOrder = AnnotationOrder.Above,
    extendsDomain: Boolean = false,
    id: String = "callout-$at",
    valueAxis: io.devkit.chartkit.axis.ChartAxisId? = null,
): ChartAnnotation = ChartAnnotation.Callout(
    at, value, label, direction, connectorLength, shape, id, order, style, extendsDomain, valueAxis,
)

/** An arrow between two points on the plot. */
@Suppress("LongParameterList")
fun arrow(
    fromAt: Any?,
    fromValue: Double,
    toAt: Any?,
    toValue: Double,
    label: String? = null,
    style: AnnotationStyle = AnnotationStyle.Solid,
    order: AnnotationOrder = AnnotationOrder.Above,
    extendsDomain: Boolean = false,
    id: String = "arrow-$fromAt-$toAt",
    valueAxis: io.devkit.chartkit.axis.ChartAxisId? = null,
): ChartAnnotation = ChartAnnotation.Arrow(
    fromAt, fromValue, toAt, toValue, label, id, order, style, extendsDomain, valueAxis,
)

/** A bare label placed at a point on the plot. */
fun labelBox(
    at: Any?,
    value: Double? = null,
    label: String? = null,
    style: AnnotationStyle = AnnotationStyle(
        dashed = false,
        labelPlacement = AnnotationLabelPlacement.Center,
    ),
    order: AnnotationOrder = AnnotationOrder.Above,
    extendsDomain: Boolean = false,
    id: String = "label-$at-$value",
    valueAxis: io.devkit.chartkit.axis.ChartAxisId? = null,
): ChartAnnotation =
    ChartAnnotation.LabelBox(at, value, label, id, order, style, extendsDomain, valueAxis)

/**
 * A shaded band between two values — a tolerance, an SLA, a safe range.
 *
 * The same annotation as [valueRange]; the name exists because "threshold band"
 * is what the thing is called when it marks an acceptable interval rather than
 * a measured one, and a call site reads better for saying so.
 */
fun thresholdBand(
    from: Double,
    to: Double,
    label: String? = null,
    style: AnnotationStyle = AnnotationStyle.Region,
    order: AnnotationOrder = AnnotationOrder.Behind,
    extendsDomain: Boolean = true,
    id: String = "threshold-$from-$to",
    valueAxis: io.devkit.chartkit.axis.ChartAxisId? = null,
): ChartAnnotation =
    ChartAnnotation.ValueRange(from, to, label, id, order, style, extendsDomain, valueAxis)

/** A point marked on the plot at domain position [at]. */
fun eventMarker(
    at: Any?,
    value: Double? = null,
    label: String? = null,
    style: AnnotationStyle = AnnotationStyle.Marker,
    order: AnnotationOrder = AnnotationOrder.Above,
    extendsDomain: Boolean = false,
    id: String = "event-$at",
    shape: AnnotationMarkerShape = AnnotationMarkerShape.Circle,
    valueAxis: io.devkit.chartkit.axis.ChartAxisId? = null,
): ChartAnnotation =
    ChartAnnotation.EventMarker(at, value, label, id, order, style, extendsDomain, shape, valueAxis)

/**
 * The annotation this selection came from, or `null` for a data selection.
 *
 * ```kotlin
 * LineChart(
 *     data = revenue, x = { it.month }, y = { it.amount },
 *     annotations = listOf(eventMarker(at = "Mar", label = "Launch")),
 *     onSelectionChanged = { selection ->
 *         selection?.chartAnnotation?.let { openReleaseNotes(it.id) }
 *     },
 * )
 * ```
 *
 * Annotations arrive through the **same** selection channel as the data rather
 * than through a callback of their own. One channel means one set of clearing
 * rules, one tooltip, one live region and one thing for a caller to observe —
 * and it means a marker and a data point cannot both be "selected" at once,
 * which is a state nothing on screen could represent.
 */
val io.devkit.chartkit.model.ChartSelection<*>.chartAnnotation: ChartAnnotation?
    get() = item as? ChartAnnotation
