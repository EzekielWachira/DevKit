package io.devkit.fillkit

import kotlin.reflect.KClass

/** Describes a value FillKit may generate for a registered form field. */
sealed interface FillType<T : Any> {
    data object FirstName : FillType<String>
    data object LastName : FillType<String>
    data object FullName : FillType<String>
    data object MiddleName : FillType<String>
    data object NamePrefix : FillType<String>
    data object NameSuffix : FillType<String>
    data object Username : FillType<String>
    data object DateOfBirth : FillType<FillDate>
    data object Age : FillType<Int>
    data object Email : FillType<String>
    data class PhoneNumber(
        val countryCode: String? = null,
        val format: FillPhoneNumberFormat = FillPhoneNumberFormat.International,
    ) : FillType<String>
    data object PhoneCountryCode : FillType<String>
    data object StreetAddress : FillType<String>
    data object City : FillType<String>
    data object Region : FillType<String>
    data object Country : FillType<String>
    data object PostalCode : FillType<String>
    data object CompanyName : FillType<String>
    data object JobTitle : FillType<String>
    data object Website : FillType<String>
    data object Url : FillType<String>

    data class OtpCode(
        val length: Int = 6,
        val numericOnly: Boolean = true,
    ) : FillType<String> {
        init {
            require(length in 4..8) { "OTP length must be in 4..8" }
        }
    }

    /** Recognized semantic category for which FillKit intentionally provides no generator. */
    data class Unsupported(val category: String) : FillType<String> {
        init { require(category.isNotBlank()) { "unsupported category cannot be blank" } }
    }

    data class Password(
        val minLength: Int = 12,
        val maxLength: Int = minLength,
        val uppercase: Boolean = true,
        val lowercase: Boolean = true,
        val digits: Boolean = true,
        val specialCharacters: Boolean = true,
    ) : FillType<String> {
        init {
            require(minLength > 0 && maxLength >= minLength) {
                "password length must satisfy 0 < minLength <= maxLength"
            }
            val categories = listOf(uppercase, lowercase, digits, specialCharacters).count { it }
            require(categories > 0) { "password must enable at least one character category" }
            require(minLength >= categories) {
                "password minLength must fit all required character categories"
            }
        }
    }

    data class Text(
        val minLength: Int = 8,
        val maxLength: Int = 24,
    ) : FillType<String> {
        init {
            require(minLength >= 0 && maxLength >= minLength) {
                "text length must satisfy 0 <= minLength <= maxLength"
            }
        }
    }

    data class Integer(val range: IntRange = 0..100) : FillType<Int> {
        init {
            require(!range.isEmpty()) { "integer range cannot be empty" }
        }
    }

    data class Decimal(
        val range: ClosedFloatingPointRange<Double> = 0.0..100.0,
        val decimalPlaces: Int = 2,
    ) : FillType<Double> {
        init {
            require(range.start.isFinite() && range.endInclusive.isFinite()) {
                "decimal range must be finite"
            }
            require(range.start <= range.endInclusive) { "decimal range cannot be empty" }
            require(decimalPlaces in 0..8) { "decimalPlaces must be in 0..8" }
        }
    }

    data class BooleanValue(val probabilityTrue: Float = 0.5f) : FillType<Boolean> {
        init {
            require(probabilityTrue in 0f..1f) { "probabilityTrue must be in 0..1" }
        }
    }

    data class Date(
        val min: FillDate = FillDate(1970, 1, 1),
        val max: FillDate = FillDate(2030, 12, 31),
    ) : FillType<FillDate> {
        init {
            require(min <= max) { "date range cannot be empty" }
        }
    }

    /**
     * A formatted money string. Currency follows the locale's region or an
     * explicit code, never the language.
     */
    data class CurrencyAmount(
        val currencyCode: String? = null,
        val range: ClosedFloatingPointRange<Double> = 1.0..10_000.0,
        val decimalPlaces: Int = 2,
    ) : FillType<String> {
        init {
            require(currencyCode == null || currencyCode.length == 3) {
                "currency must be a 3-letter ISO 4217 code"
            }
            require(range.start <= range.endInclusive) { "currency range cannot be empty" }
            require(decimalPlaces in 0..4) { "currency decimalPlaces must be in 0..4" }
        }
    }

    data class Selection(val options: List<String>) : FillType<String> {
        init {
            require(options.isNotEmpty()) { "selection options cannot be empty" }
            require(options.none(String::isBlank)) { "selection options cannot be blank" }
        }
    }

    /** Resolves a form-scoped generator registered under [key]. */
    data class Custom<T : Any>(
        val key: String,
        val valueClass: KClass<T>,
    ) : FillType<T> {
        init {
            require(key.isNotBlank()) { "custom generator key cannot be blank" }
        }
    }
}
