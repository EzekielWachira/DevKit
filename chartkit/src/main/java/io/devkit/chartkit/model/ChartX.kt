package io.devkit.chartkit.model

/**
 * A domain value on the horizontal axis, in one of the three shapes an axis can
 * actually be built from.
 *
 * ChartKit's promise is that a developer charts their own model —
 * `x = { it.month }` where `month` is a `String`, a `Double` or a timestamp —
 * without first converting anything. Something has to turn those into a
 * position, and this is the one place it happens. Layers, scales and axes see
 * only [ChartX]; nothing downstream knows what the caller's field was.
 */
sealed interface ChartX {

    /** A continuous numeric position. */
    @JvmInline
    value class Numeric(val value: Double) : ChartX

    /** A discrete band, identified and labelled by [label]. */
    @JvmInline
    value class Category(val label: String) : ChartX

    /** An instant, as epoch milliseconds. See [io.devkit.chartkit.scale.TimeScale]. */
    @JvmInline
    value class Time(val epochMillis: Long) : ChartX

    companion object {
        operator fun invoke(value: Number): ChartX = Numeric(value.toDouble())
        operator fun invoke(value: String): ChartX = Category(value)

        /** An instant, as epoch milliseconds. */
        fun time(epochMillis: Long): ChartX = Time(epochMillis)
    }
}

/**
 * Turns whatever the caller's `x` lambda returned into a [ChartX].
 *
 * ### Why the input is `Any?`
 *
 * The alternative designs were each worse. Forcing `x: (T) -> ChartX` makes
 * every call site read `x = { ChartX(it.month) }`, which is the conversion step
 * this library exists not to require. Overloading `LineChart` on the lambda's
 * return type does not compile — `(T) -> String` and `(T) -> Number` erase to
 * the same JVM signature. Making the chart generic in `X` needs an implicit
 * resolver per `X`, which Kotlin has no mechanism to supply.
 *
 * So the lambda returns `Any?` and lands here. This is **type inspection, not
 * reflection**: a `when` over `is Number` / `is CharSequence`, no
 * `Class.forName`, no field lookup, no `kotlin-reflect`, nothing that costs a
 * class load or breaks under R8. Everything else in ChartKit's public surface
 * stays typed — `y` is `(T) -> Number`, series are `ChartSeries<T>`, and a
 * selection hands back the caller's own `T`.
 *
 * A custom domain type is handled by supplying a resolver rather than by
 * teaching ChartKit about the type:
 *
 * ```kotlin
 * val fiscalQuarters = ChartXResolver { value ->
 *     when (value) {
 *         is FiscalQuarter -> ChartX.Category(value.shortLabel)
 *         else -> null
 *     }
 * }
 * ```
 *
 * Returning `null` falls through to [ChartXResolver.Default].
 */
fun interface ChartXResolver {

    /** The [ChartX] for [value], or `null` to fall back to the default rules. */
    fun resolve(value: Any?): ChartX?

    companion object {

        /**
         * The built-in rules.
         *
         * | Input | Result |
         * | --- | --- |
         * | `Number` | [ChartX.Numeric] |
         * | `CharSequence` | [ChartX.Category] |
         * | `Boolean` | [ChartX.Category] (`"true"` / `"false"`) |
         * | `Enum` | [ChartX.Category], using `name` |
         * | `java.util.Date` | [ChartX.Time] |
         * | anything else | [ChartX.Category] of `toString()` |
         * | `null` | [ChartX.Category] of `""` |
         *
         * `java.time` is absent on purpose: `LocalDate` and `Instant` are API 26
         * and ChartKit's floor is 24, so naming them here would either raise the
         * floor or oblige consumers to enable desugaring. Consumers on those
         * types pass epoch millis — `date.toEpochDay() * 86_400_000L` — with a
         * time axis, and keep the time-zone decision where it belongs.
         *
         * The final fallback is a category rather than an exception. An
         * unrecognised type is a chart that still draws, labelled with whatever
         * `toString` gives, which is a better failure than a crash inside a
         * draw pass.
         */
        val Default: ChartXResolver = ChartXResolver { value ->
            when (value) {
                null -> ChartX.Category("")
                is Number -> ChartX.Numeric(value.toDouble())
                is CharSequence -> ChartX.Category(value.toString())
                is Boolean -> ChartX.Category(value.toString())
                is Enum<*> -> ChartX.Category(value.name)
                is java.util.Date -> ChartX.Time(value.time)
                else -> ChartX.Category(value.toString())
            }
        }

        /**
         * Reads [Number] values as epoch milliseconds instead of as plain
         * numbers, so a `List<Long>` of timestamps produces a time axis.
         */
        val Time: ChartXResolver = ChartXResolver { value ->
            when (value) {
                is Number -> ChartX.Time(value.toLong())
                is java.util.Date -> ChartX.Time(value.time)
                else -> null
            }
        }
    }
}

/** Applies this resolver, falling back to [ChartXResolver.Default]. */
internal fun ChartXResolver.resolveOrDefault(value: Any?): ChartX =
    resolve(value) ?: ChartXResolver.Default.resolve(value) ?: ChartX.Category(value.toString())
