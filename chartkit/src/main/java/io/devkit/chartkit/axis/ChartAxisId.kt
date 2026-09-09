package io.devkit.chartkit.axis

/**
 * The stable name of one axis within a chart.
 *
 * ### Why a name and not an index
 *
 * A layer has to say which scale measures it, and the obvious encoding —
 * `axisIndex = 1` — is wrong for a reason that only shows up later: the index
 * means "the second axis I happened to declare". Insert an axis above it,
 * reorder a `when`, make one axis conditional on a feature flag, and every
 * layer below silently rebinds to a different quantity. Nothing fails; the
 * chart just starts measuring revenue against the conversion scale.
 *
 * A name cannot drift. `ChartAxisId("temperature")` refers to the temperature
 * axis in every configuration the chart can be in, and a layer naming an axis
 * that does not exist is a configuration error the chart reports by name
 * rather than a quiet rebinding.
 *
 * ```kotlin
 * val Rainfall = ChartAxisId("rainfall")
 * val Temperature = ChartAxisId("temperature")
 * ```
 *
 * A value class, so the identity costs nothing at runtime: it is a `String`
 * after compilation, and the type only exists to stop a series id, a category
 * label and an axis name being interchangeable at a call site that takes all
 * three.
 */
@JvmInline
value class ChartAxisId(val value: String) {

    init {
        require(value.isNotBlank()) { "An axis id must not be blank" }
    }

    override fun toString(): String = value

    companion object {

        /**
         * The domain axis every chart has.
         *
         * A `LineChart` that never mentions an axis id still has one; it is
         * this. Which is what lets a simple chart and a six-axis combo chart be
         * the same engine — the simple chart is the case where the registry has
         * one entry per dimension and nobody had to say so.
         */
        val DefaultX: ChartAxisId = ChartAxisId("default-x")

        /** The value axis every chart has. */
        val DefaultY: ChartAxisId = ChartAxisId("default-y")

        /**
         * The second value axis, for charts declared through
         * `secondaryValueAxis` rather than through the axis DSL.
         *
         * [ValueAxisBinding.Secondary] resolves to this, which is how the older
         * two-axis API and the registry are the same mechanism rather than two.
         */
        val SecondaryY: ChartAxisId = ChartAxisId("secondary-y")
    }
}

/** The axis id a legacy [ValueAxisBinding] names. */
internal fun ValueAxisBinding.axisId(): ChartAxisId = when (this) {
    ValueAxisBinding.Primary -> ChartAxisId.DefaultY
    ValueAxisBinding.Secondary -> ChartAxisId.SecondaryY
}

/**
 * A chart whose axes cannot be resolved.
 *
 * Thrown rather than absorbed. A layer bound to an axis that does not exist has
 * no correct fallback: measuring it against the primary axis would draw a
 * conversion rate against a revenue scale and look like data. See
 * [io.devkit.chartkit.axis.AxisRegistry] for the checks.
 */
class ChartAxisException(message: String) : IllegalArgumentException(message)
