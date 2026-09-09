package io.devkit.chartkit.gauge

import androidx.compose.runtime.Immutable

/**
 * One reading on a gauge.
 *
 * Several may share a dial — an actual against a target, a current against a
 * rolling average — which is why a needle is a list entry rather than a
 * parameter. The single-value `GaugeChart` overload builds a list of one.
 *
 * @param id stable across recompositions and data changes. What the animation
 *   matches on: identifying needles by list position means inserting a target
 *   needle above the current one animates the current needle to the target's
 *   value and back, which looks exactly like a data error.
 * @param label what this needle measures, for the legend and the screen
 *   reader. "Current speed", "Target" — the words are the caller's, because
 *   ChartKit cannot know which of two needles is the aspiration.
 * @param style `null` takes the theme's needle colour, or the series palette
 *   slot when the gauge has more than one needle.
 */
@Immutable
data class GaugeValue(
    val id: String,
    val value: Double,
    val label: String = id,
    val style: GaugeNeedleStyle? = null,
    /** The caller's own object, carried through to selection and tooltips. */
    val item: Any? = null,
) {
    init {
        require(id.isNotBlank()) {
            "A gauge needle needs a stable, non-blank id — it is what animation matches on " +
                "across value changes"
        }
    }
}

/** The outline a needle is drawn with. */
enum class GaugeNeedleShape {

    /** A straight stroke from the pivot to the tip. The lightest. */
    Line,

    /** A wedge, widest at the pivot. The classic instrument needle. */
    Triangle,

    /**
     * A tapered blade: wide at the pivot, narrow but not pointed at the tip.
     * The default — it reads as pointing without vanishing at small sizes.
     */
    Needle,

    /** A stroke ending in an arrowhead, for a target or limit marker. */
    Arrow,
}

/**
 * How a needle is drawn.
 *
 * Relative lengths throughout, not pixels: a needle specified at `0.85` of the
 * radius stays right at every size, and one specified at `120.dp` is wrong at
 * all but one.
 *
 * @param length the tip's distance from the pivot, as a fraction of the outer
 *   radius. Slightly under `1` by default so the tip sits inside the track
 *   rather than on it.
 * @param tail how far the needle extends *behind* the pivot, as the same
 *   fraction. Real instrument needles are counterweighted and show a stub;
 *   zero draws none.
 * @param baseWidth the needle's width at the pivot, in dp. `null` takes the
 *   theme's.
 * @param tipWidth the width at the tip, in dp. `null` derives it from the
 *   shape — a [GaugeNeedleShape.Triangle] tapers to nothing.
 * @param color `null` takes the theme's needle colour, or this needle's
 *   palette slot when the gauge has several.
 */
@Immutable
data class GaugeNeedleStyle(
    val shape: GaugeNeedleShape = GaugeNeedleShape.Needle,
    val length: Float = 0.86f,
    val tail: Float = 0.12f,
    val baseWidth: androidx.compose.ui.unit.Dp? = null,
    val tipWidth: androidx.compose.ui.unit.Dp? = null,
    val color: Int? = null,
) {
    init {
        require(length.isFinite() && length > 0f) {
            "Needle length is a fraction of the radius and must be positive, was $length"
        }
        require(tail.isFinite() && tail >= 0f) { "Needle tail cannot be negative, was $tail" }
    }

    companion object {

        /** A tapered blade with a short tail. */
        val Default: GaugeNeedleStyle = GaugeNeedleStyle()

        /** A thin stroke, for a secondary reading that must not dominate. */
        val Thin: GaugeNeedleStyle = GaugeNeedleStyle(
            shape = GaugeNeedleShape.Line,
            length = 0.9f,
            tail = 0f,
        )

        /** An arrow, for a target or limit that is not the main reading. */
        val Target: GaugeNeedleStyle = GaugeNeedleStyle(
            shape = GaugeNeedleShape.Arrow,
            length = 0.9f,
            tail = 0f,
        )
    }
}

/**
 * The hub at the centre of a dial.
 *
 * @param radius as a fraction of the outer radius, so it scales with the gauge.
 * @param color `null` takes the theme's needle colour — a pivot in a different
 *   colour from the needle it anchors reads as a separate mark.
 * @param strokeColor an optional ring around it, for a pivot on a dark face.
 */
@Immutable
data class GaugePivotStyle(
    val radius: Float = 0.06f,
    val color: Int? = null,
    val strokeColor: Int? = null,
    val strokeWidth: androidx.compose.ui.unit.Dp? = null,
) {
    init {
        require(radius.isFinite() && radius >= 0f) { "Pivot radius cannot be negative, was $radius" }
    }

    companion object {
        val Default: GaugePivotStyle = GaugePivotStyle()

        /** No hub — for a dial whose centre carries content instead. */
        val None: GaugePivotStyle = GaugePivotStyle(radius = 0f)
    }
}

/** The mark a [GaugeMarker] draws. */
enum class GaugeMarkerShape {
    Triangle,
    Dot,
    Line,
}

/**
 * A single value marked on the arc, without a needle.
 *
 * For the quantities a dial refers to but does not read: a target, a limit, a
 * previous measurement. Lighter than a needle by design — a second needle says
 * "another reading", and a marker says "a line on the dial".
 *
 * @param label the caller's words, announced alongside the value. ChartKit does
 *   not name it: a marker at 160 is a target on one gauge and a redline on the
 *   next.
 * @param position where the mark sits across the track, `0` inner and `1`
 *   outer. Above `1` puts it outside the arc.
 */
@Immutable
data class GaugeMarker(
    val value: Double,
    val label: String? = null,
    val shape: GaugeMarkerShape = GaugeMarkerShape.Triangle,
    val color: Int? = null,
    val position: Float = 1f,
    val size: androidx.compose.ui.unit.Dp? = null,
) {
    init {
        require(value.isFinite()) { "A gauge marker needs a finite value, was $value" }
    }
}

/**
 * The face behind the dial.
 *
 * Optional, and off by default: a gauge on a card usually wants the card's own
 * surface behind it, and a pane drawn anyway is a second background fighting
 * the first.
 *
 * @param fill the disc behind the arc. `null` draws none.
 * @param border a ring around the face. `null` draws none.
 * @param radius the face's radius as a fraction of the outer radius. Above `1`
 *   reaches past the track, which is what a bezel looks like.
 */
@Immutable
data class GaugePane(
    val fill: Int? = null,
    val border: Int? = null,
    val borderWidth: androidx.compose.ui.unit.Dp? = null,
    val radius: Float = 1f,
) {
    init {
        require(radius.isFinite() && radius > 0f) { "Pane radius must be positive, was $radius" }
    }

    companion object {
        /** No face. The default. */
        val None: GaugePane = GaugePane()

        /** The theme's own dial face. */
        val Themed: GaugePane = GaugePane(fill = THEMED)

        /** Sentinel for "use the theme's colour", distinguishable from a real one. */
        internal const val THEMED: Int = 1
    }
}

/**
 * How much of the dial is drawn at the size it was given.
 *
 * ### Detail, not content
 *
 * Every mode draws the arc, the bands, the needle and the value. What varies is
 * the *scale's* resolution — minor ticks, then label density. A gauge that
 * dropped a band or a needle to fit would be hiding data; one that drops every
 * other number is still a gauge, just a coarser one.
 */
enum class GaugeDetail {

    /** Everything, whatever the size. */
    Full,

    /** No minor ticks, and labels only where they fit. */
    Compact,

    /** Full where there is room; compact where there is not. The default. */
    Auto,
    ;

    /** Whether a dial of this radius, in pixels, draws at compact detail. */
    internal fun isCompact(radiusPx: Float, compactBelowPx: Float): Boolean = when (this) {
        Full -> false
        Compact -> true
        Auto -> radiusPx > 0f && radiusPx < compactBelowPx
    }
}

/** What a gauge does with pointer input. */
enum class GaugeInteraction {

    /** A display. The default — a speedometer is not a control. */
    None,

    /** A tap on the arc reads a value out of it. */
    Tap,

    /** A tap or a drag around the arc sets the value. Makes the gauge a knob. */
    Drag,
    ;

    internal val isInteractive: Boolean get() = this != None
    internal val allowsDrag: Boolean get() = this == Drag
}

/** Where a gauge's value readout sits. */
enum class GaugeValuePosition {

    /** In the middle of the dial. */
    Center,

    /**
     * Below the middle, clear of a needle's hub.
     *
     * What a semicircular speedometer wants: the centre of a half dial is on
     * the pivot, and a number printed there is under the needle.
     */
    BelowCenter,
}
