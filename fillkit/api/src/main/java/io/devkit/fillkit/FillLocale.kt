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

    /**
     * A region only, such as `KE`. FillKit picks an appropriate language pack
     * for that country, which keeps locale and country from being conflated.
     */
    data class Country(val code: String) : FillLocale {
        init {
            require(code.isNotBlank()) { "FillKit country code cannot be blank" }
        }

        val normalized: String get() = code.trim().uppercase()
    }
}
