package io.devkit.fillkit

import androidx.compose.ui.autofill.ContentType

enum class SuggestionConfidence { Exact, High, Medium, Low }
enum class FieldSuggestionMode { Disabled, Suggest, AutoRegisterExact }
enum class SuggestionFillability { Fillable, DetectionOnly, Unsupported }

sealed interface SuggestionSource {
    data object Explicit : SuggestionSource
    data class ExplicitContentType(val name: String) : SuggestionSource
    data object SemanticLabel : SuggestionSource
    data object TestTag : SuggestionSource
    data object CurrentValue : SuggestionSource

    /** A label recognised through the active locale pack's alias table. */
    data class LocaleAlias(val localeCode: String) : SuggestionSource
    data class CustomRule(val id: String) : SuggestionSource
}

data class SuggestionReason(
    val source: SuggestionSource,
    val description: String,
)

data class FillTypeSuggestion(
    val type: FillType<*>,
    val confidence: SuggestionConfidence,
    val reasons: List<SuggestionReason>,
    val fillability: SuggestionFillability = SuggestionFillability.DetectionOnly,
)

/** Platform-neutral content vocabulary passed into the pure suggestion engine. */
enum class FillContentHint {
    Email, Username, Password, PostalAddress, PostalCode, Country, Region, City, Street,
    FullName, FirstName, LastName, MiddleName, NamePrefix, NameSuffix, Phone, PhoneCountryCode,
    Gender, BirthDate, BirthDay, BirthMonth, BirthYear, Otp, PaymentCardNumber,
    PaymentSecurityCode, PaymentExpiration, Unknown,
}

data class FieldMetadata(
    val id: String? = null,
    val label: String? = null,
    val contentHint: FillContentHint? = null,
    val contentTypeName: String? = null,
    val testTag: String? = null,
    val currentText: String? = null,
    val semanticHints: Set<String> = emptySet(),
    val explicitFillType: FillType<*>? = null,
)

fun interface FillSuggestionRule {
    fun suggest(metadata: FieldMetadata): List<FillTypeSuggestion>
}

data class FillSuggestionRulePack(
    val id: String,
    val name: String,
    val rules: List<FillSuggestionRule>,
) {
    init {
        require(id.isNotBlank()) { "suggestion rule pack id cannot be blank" }
        require(name.isNotBlank()) { "suggestion rule pack name cannot be blank" }
    }
}

fun suggestionRules(
    id: String,
    name: String,
    block: FillSuggestionRulesBuilder.() -> Unit,
): FillSuggestionRulePack = FillSuggestionRulesBuilder().apply(block).build(id, name)

class FillSuggestionRulesBuilder internal constructor() {
    private val rules = mutableListOf<FillSuggestionRule>()

    fun labelContains(
        text: String,
        type: FillType<*>,
        confidence: SuggestionConfidence = SuggestionConfidence.High,
    ) {
        require(text.isNotBlank()) { "suggestion label text cannot be blank" }
        val normalized = text.trim().lowercase()
        rules += FillSuggestionRule { metadata ->
            if (metadata.label.orEmpty().lowercase().contains(normalized)) {
                listOf(
                    FillTypeSuggestion(
                        type = type,
                        confidence = confidence,
                        reasons = listOf(
                            SuggestionReason(
                                SuggestionSource.CustomRule("label:$normalized"),
                                "label contains \"$text\"",
                            ),
                        ),
                    ),
                )
            } else emptyList()
        }
    }

    fun rule(value: FillSuggestionRule) { rules += value }
    internal fun build(id: String, name: String) = FillSuggestionRulePack(id, name, rules.toList())
}

data class FieldSuggestionContext(
    val id: String? = null,
    val label: String? = null,
    val testTag: String? = null,
)

fun interface ContentTypeMapper {
    fun suggest(contentType: ContentType, context: FieldSuggestionContext): FillTypeSuggestion?
}

fun contentTypeMappings(block: ContentTypeMappingsBuilder.() -> Unit): ContentTypeMapper =
    ContentTypeMappingsBuilder().apply(block).build()

class ContentTypeMappingsBuilder internal constructor() {
    private val mappings = mutableListOf<Pair<ContentType, FillType<*>>>()
    fun map(contentType: ContentType, fillType: FillType<*>) { mappings += contentType to fillType }
    internal fun build(): ContentTypeMapper = ContentTypeMapper { contentType, _ ->
        mappings.lastOrNull { it.first === contentType || it.first == contentType }?.second?.let { type ->
            FillTypeSuggestion(
                type,
                SuggestionConfidence.Exact,
                listOf(
                    SuggestionReason(
                        SuggestionSource.CustomRule("content-type-mapping"),
                        "application ContentType mapping",
                    ),
                ),
                if (type is FillType.Unsupported) SuggestionFillability.Unsupported else SuggestionFillability.DetectionOnly,
            )
        }
    }
}

/** Public Compose 1.10 ContentType adapter; no internal hint extraction or reflection is used. */
object BuiltInContentTypeMapper : ContentTypeMapper {
    override fun suggest(contentType: ContentType, context: FieldSuggestionContext): FillTypeSuggestion? {
        val mapping = mapping(contentType) ?: return null
        val (name, hint, type) = mapping
        return FillTypeSuggestion(
            type = type,
            confidence = SuggestionConfidence.Exact,
            reasons = listOf(
                SuggestionReason(
                    SuggestionSource.ExplicitContentType(name),
                    "ContentType.$name maps exactly to ${type.displayName()}",
                ),
            ),
            fillability = if (type is FillType.Unsupported) SuggestionFillability.Unsupported else SuggestionFillability.DetectionOnly,
        )
    }

    fun hint(contentType: ContentType): FillContentHint = mapping(contentType)?.second ?: FillContentHint.Unknown

    private fun mapping(contentType: ContentType): Triple<String, FillContentHint, FillType<*>>? = when {
        contentType === ContentType.EmailAddress -> Triple("EmailAddress", FillContentHint.Email, FillType.Email)
        contentType === ContentType.Username || contentType === ContentType.NewUsername ->
            Triple(if (contentType === ContentType.Username) "Username" else "NewUsername", FillContentHint.Username, FillType.Username)
        contentType === ContentType.Password || contentType === ContentType.NewPassword ->
            Triple(if (contentType === ContentType.Password) "Password" else "NewPassword", FillContentHint.Password, FillType.Password())
        contentType === ContentType.PersonFirstName -> Triple("PersonFirstName", FillContentHint.FirstName, FillType.FirstName)
        contentType === ContentType.PersonLastName -> Triple("PersonLastName", FillContentHint.LastName, FillType.LastName)
        contentType === ContentType.PersonFullName -> Triple("PersonFullName", FillContentHint.FullName, FillType.FullName)
        contentType === ContentType.PersonMiddleName || contentType === ContentType.PersonMiddleInitial ->
            Triple("PersonMiddleName", FillContentHint.MiddleName, FillType.MiddleName)
        contentType === ContentType.PersonNamePrefix -> Triple("PersonNamePrefix", FillContentHint.NamePrefix, FillType.NamePrefix)
        contentType === ContentType.PersonNameSuffix -> Triple("PersonNameSuffix", FillContentHint.NameSuffix, FillType.NameSuffix)
        contentType === ContentType.PhoneNumber || contentType === ContentType.PhoneNumberNational ||
            contentType === ContentType.PhoneNumberDevice -> Triple("PhoneNumber", FillContentHint.Phone, FillType.PhoneNumber())
        contentType === ContentType.PhoneCountryCode -> Triple("PhoneCountryCode", FillContentHint.PhoneCountryCode, FillType.PhoneCountryCode)
        contentType === ContentType.PostalAddress || contentType === ContentType.AddressStreet ||
            contentType === ContentType.AddressAuxiliaryDetails -> Triple("PostalAddress", FillContentHint.PostalAddress, FillType.StreetAddress)
        contentType === ContentType.PostalCode || contentType === ContentType.PostalCodeExtended ->
            Triple("PostalCode", FillContentHint.PostalCode, FillType.PostalCode)
        contentType === ContentType.AddressCountry -> Triple("AddressCountry", FillContentHint.Country, FillType.Country)
        contentType === ContentType.AddressRegion -> Triple("AddressRegion", FillContentHint.Region, FillType.Region)
        contentType === ContentType.AddressLocality -> Triple("AddressLocality", FillContentHint.City, FillType.City)
        contentType === ContentType.Gender -> Triple(
            "Gender", FillContentHint.Gender,
            FillType.Selection(listOf("Female", "Male", "Non-binary", "Prefer not to say")),
        )
        contentType === ContentType.BirthDateFull -> Triple("BirthDateFull", FillContentHint.BirthDate, FillType.DateOfBirth)
        contentType === ContentType.BirthDateDay -> Triple("BirthDateDay", FillContentHint.BirthDay, FillType.Integer(1..31))
        contentType === ContentType.BirthDateMonth -> Triple("BirthDateMonth", FillContentHint.BirthMonth, FillType.Integer(1..12))
        contentType === ContentType.BirthDateYear -> Triple("BirthDateYear", FillContentHint.BirthYear, FillType.Integer(1900..2025))
        contentType === ContentType.SmsOtpCode -> Triple("SmsOtpCode", FillContentHint.Otp, FillType.OtpCode())
        contentType === ContentType.CreditCardNumber -> payment("CreditCardNumber", FillContentHint.PaymentCardNumber)
        contentType === ContentType.CreditCardSecurityCode -> payment("CreditCardSecurityCode", FillContentHint.PaymentSecurityCode)
        contentType === ContentType.CreditCardExpirationDate || contentType === ContentType.CreditCardExpirationDay ||
            contentType === ContentType.CreditCardExpirationMonth || contentType === ContentType.CreditCardExpirationYear ->
            payment("CreditCardExpiration", FillContentHint.PaymentExpiration)
        else -> null
    }

    private fun payment(name: String, hint: FillContentHint) =
        Triple(name, hint, FillType.Unsupported("payment data ($name)"))
}

fun FillType<*>.displayName(): String = when (this) {
    is FillType.Custom<*> -> "Custom($key)"
    is FillType.Unsupported -> "Unsupported($category)"
    else -> this::class.simpleName ?: "FillType"
}
