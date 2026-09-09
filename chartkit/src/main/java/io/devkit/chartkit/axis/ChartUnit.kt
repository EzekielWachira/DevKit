package io.devkit.chartkit.axis

/**
 * What an axis measures in.
 *
 * ### Deliberately not a units framework
 *
 * There is no dimensional analysis here, no conversion, no `Quantity<Length>`.
 * A charting library that knew millimetres convert to metres would still not
 * know whether a reader wants them to, and the machinery would earn its keep on
 * roughly one chart in a thousand.
 *
 * What a unit is for here is narrower and worth having:
 *
 * - it labels the axis and the tooltip consistently — `82 mm`, not `82` beside
 *   an axis titled "Rainfall" and `82.0` in a tooltip;
 * - it gives a screen reader something to say — "82 millimetres" reads, "82 mm"
 *   spelled out letter by letter does not;
 * - it lets ChartKit notice that a series declaring `°C` has been bound to an
 *   axis declaring `USD`, which is a real mistake that is otherwise invisible
 *   until somebody reads the chart.
 *
 * @property symbol what is written after a number, or `null` for a bare count.
 * @property spokenName how a screen reader should say it, singular or plural as
 *   the unit's own convention prefers. Falls back to [symbol].
 */
sealed interface ChartUnit {

    val symbol: String?
    val spokenName: String? get() = symbol

    /** Formats [text] — an already-formatted number — with this unit. */
    fun label(text: String): String = symbol?.let { "$text $it" } ?: text

    /** Announces [text] with the unit spelled out. */
    fun spoken(text: String): String = spokenName?.let { "$text $it" } ?: text

    /** No unit stated. The default, and never reported as a mismatch. */
    data object None : ChartUnit {
        override val symbol: String? get() = null
        override fun label(text: String): String = text
        override fun spoken(text: String): String = text
    }

    /** A proportion out of a hundred. */
    data object Percent : ChartUnit {
        override val symbol: String get() = "%"
        override val spokenName: String get() = "percent"
        // No space: "45%" is how a percentage is written in every locale
        // ChartKit formats for, and "45 %" reads as a typo.
        override fun label(text: String): String = "$text%"
    }

    /** Money, named by its ISO 4217 code so two currencies never look alike. */
    data class Currency(val code: String) : ChartUnit {
        override val symbol: String get() = code
        override val spokenName: String get() = code
    }

    /** A dimensionless tally — orders, users, visits. */
    data object Count : ChartUnit {
        override val symbol: String? get() = null
        override fun label(text: String): String = text
        override fun spoken(text: String): String = text
    }

    /** An elapsed time, in whatever the axis formats it as. */
    data class Duration(override val symbol: String = "s", override val spokenName: String = "seconds") : ChartUnit

    /** Anything else. */
    data class Custom(
        override val symbol: String,
        override val spokenName: String = symbol,
    ) : ChartUnit

    companion object {

        /**
         * Whether two units can describe the same axis.
         *
         * [None] is compatible with everything: a series that did not state a
         * unit has made no claim to contradict. Two stated units are compatible
         * only when they are the same, which is the whole check — see
         * [ChartUnit] for why there is no conversion.
         */
        fun compatible(a: ChartUnit, b: ChartUnit): Boolean =
            a == None || b == None || a == b
    }
}
