package io.devkit.fillkit

/** Selects the locale dataset used for synthetic values. */
sealed interface FillLocale {
    /** Resolve the device locale when the debug host starts. */
    data object System : FillLocale

    /** A BCP-47 locale tag such as `en-KE`. */
    data class Code(val value: String) : FillLocale {
        init {
            require(value.isNotBlank()) { "FillKit locale code cannot be blank" }
        }
    }
}
