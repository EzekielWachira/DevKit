package io.devkit.fillkit

/** A scenario value that can be validated against a registered [FillType]. */
sealed interface FillValue {
    val raw: Any

    data class Text(val value: String) : FillValue { override val raw: Any = value }
    data class Integer(val value: Int) : FillValue { override val raw: Any = value }
    data class Decimal(val value: Double) : FillValue { override val raw: Any = value }
    data class BooleanValue(val value: Boolean) : FillValue { override val raw: Any = value }
    data class DateValue(val value: FillDate) : FillValue { override val raw: Any = value }

    companion object {
        /** Converts the value types supported by FillKit's stable data models. */
        fun of(value: Any): FillValue = when (value) {
            is String -> Text(value)
            is Int -> Integer(value)
            is Double -> Decimal(value)
            is Float -> Decimal(value.toDouble())
            is Boolean -> BooleanValue(value)
            is FillDate -> DateValue(value)
            else -> throw IllegalArgumentException("unsupported FillValue type: ${value::class.simpleName}")
        }
    }
}
