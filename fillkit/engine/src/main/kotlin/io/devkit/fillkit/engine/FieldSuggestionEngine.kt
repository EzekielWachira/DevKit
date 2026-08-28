package io.devkit.fillkit.engine

import io.devkit.fillkit.FieldMetadata
import io.devkit.fillkit.FillContentHint
import io.devkit.fillkit.FillSuggestionRulePack
import io.devkit.fillkit.FillType
import io.devkit.fillkit.FillTypeSuggestion
import io.devkit.fillkit.SuggestionConfidence
import io.devkit.fillkit.SuggestionReason
import io.devkit.fillkit.SuggestionSource

/** Deterministic, platform-neutral field inference. No Compose tree access or reflection. */
class FieldSuggestionEngine(private val rulePacks: List<FillSuggestionRulePack> = emptyList()) {
    fun suggest(metadata: FieldMetadata): List<FillTypeSuggestion> {
        metadata.explicitFillType?.let {
            return listOf(suggestion(it, SuggestionConfidence.Exact, SuggestionSource.Explicit, "explicit FillType"))
        }

        val results = buildList {
            metadata.contentHint?.let { hint -> fromContentHint(hint)?.let(::add) }
            rulePacks.flatMapTo(this) { pack ->
                pack.rules.flatMap { rule -> rule.suggest(metadata) }.map { candidate ->
                    candidate.copy(reasons = candidate.reasons.map { reason ->
                        val source = reason.source
                        if (source is SuggestionSource.CustomRule) reason.copy(
                            source = SuggestionSource.CustomRule("${pack.id}:${source.id}"),
                        ) else reason
                    })
                }
            }
            heuristicText(metadata).forEach(::add)
            currentValue(metadata.currentText)?.let(::add)
        }
        return results
            .groupBy { it.type }
            .map { (_, values) -> values.maxBy { it.confidence.rank }.copy(reasons = values.flatMap { it.reasons }.distinct()) }
            .sortedWith(compareByDescending<FillTypeSuggestion> { it.confidence.rank }.thenBy { it.type.toString() })
    }

    private fun fromContentHint(hint: FillContentHint): FillTypeSuggestion? {
        val type = when (hint) {
            FillContentHint.Email -> FillType.Email
            FillContentHint.Username -> FillType.Username
            FillContentHint.Password -> FillType.Password()
            FillContentHint.PostalAddress, FillContentHint.Street -> FillType.StreetAddress
            FillContentHint.PostalCode -> FillType.PostalCode
            FillContentHint.Country -> FillType.Country
            FillContentHint.Region -> FillType.Region
            FillContentHint.City -> FillType.City
            FillContentHint.FullName -> FillType.FullName
            FillContentHint.FirstName -> FillType.FirstName
            FillContentHint.LastName -> FillType.LastName
            FillContentHint.MiddleName -> FillType.MiddleName
            FillContentHint.NamePrefix -> FillType.NamePrefix
            FillContentHint.NameSuffix -> FillType.NameSuffix
            FillContentHint.Phone -> FillType.PhoneNumber()
            FillContentHint.PhoneCountryCode -> FillType.PhoneCountryCode
            FillContentHint.Gender -> FillType.Selection(listOf("Female", "Male", "Non-binary", "Prefer not to say"))
            FillContentHint.BirthDate -> FillType.DateOfBirth
            FillContentHint.BirthDay -> FillType.Integer(1..31)
            FillContentHint.BirthMonth -> FillType.Integer(1..12)
            FillContentHint.BirthYear -> FillType.Integer(1900..2025)
            FillContentHint.Otp -> FillType.OtpCode()
            FillContentHint.PaymentCardNumber, FillContentHint.PaymentSecurityCode, FillContentHint.PaymentExpiration ->
                FillType.Unsupported("payment data")
            FillContentHint.Unknown -> return null
        }
        return suggestion(type, SuggestionConfidence.Exact, SuggestionSource.ExplicitContentType(hint.name), "exact content type hint")
    }

    private fun heuristicText(metadata: FieldMetadata): List<FillTypeSuggestion> {
        val sources = listOfNotNull(
            metadata.label?.let { it to SuggestionSource.SemanticLabel },
            metadata.testTag?.let { it to SuggestionSource.TestTag },
        ) + metadata.semanticHints.map { it to SuggestionSource.SemanticLabel }
        return sources.flatMap { (raw, source) ->
            val text = raw.lowercase().replace(Regex("[_-]+"), " ")
            buildList {
                fun match(regex: Regex, type: FillType<*>, label: String, confidence: SuggestionConfidence = SuggestionConfidence.High) {
                    if (regex.containsMatchIn(text)) add(suggestion(type, confidence, source, "$label matched \"$raw\""))
                }
                match(Regex("\\b(e ?mail|email address)\\b"), FillType.Email, "email")
                match(Regex("\\b(first|given) name\\b"), FillType.FirstName, "first name")
                match(Regex("\\b(last name|surname|family name)\\b"), FillType.LastName, "last name")
                match(Regex("\\b(full name|your name)\\b"), FillType.FullName, "full name")
                match(Regex("\\b(phone|mobile|telephone)\\b"), FillType.PhoneNumber(), "phone")
                match(Regex("\\b(city|town)\\b"), FillType.City, "city")
                match(Regex("\\b(postcode|postal code|zip)\\b"), FillType.PostalCode, "postal code")
                match(Regex("\\bpassword\\b"), FillType.Password(), "password")
                match(Regex("\\b(date of birth|dob|birthday)\\b"), FillType.DateOfBirth, "date of birth")
                match(Regex("\\b(description|bio|notes?)\\b"), FillType.Text(40, 160), "long text", SuggestionConfidence.Medium)
                if (Regex("^\\s*code\\s*$").matches(text)) {
                    add(suggestion(FillType.OtpCode(), SuggestionConfidence.Low, source, "ambiguous code label"))
                    add(suggestion(FillType.PostalCode, SuggestionConfidence.Low, source, "ambiguous code label"))
                    add(suggestion(FillType.Text(), SuggestionConfidence.Low, source, "ambiguous code label"))
                }
            }
        }
    }

    private fun currentValue(value: String?): FillTypeSuggestion? = when {
        value.isNullOrBlank() -> null
        Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(value) ->
            suggestion(FillType.Email, SuggestionConfidence.Medium, SuggestionSource.CurrentValue, "current value resembles an email")
        Regex("^\\+?[0-9 ()-]{7,}$").matches(value) ->
            suggestion(FillType.PhoneNumber(), SuggestionConfidence.Medium, SuggestionSource.CurrentValue, "current value resembles a phone number")
        else -> null
    }

    private fun suggestion(type: FillType<*>, confidence: SuggestionConfidence, source: SuggestionSource, reason: String) =
        FillTypeSuggestion(type, confidence, listOf(SuggestionReason(source, reason)))
}

private val SuggestionConfidence.rank: Int get() = when (this) {
    SuggestionConfidence.Exact -> 4
    SuggestionConfidence.High -> 3
    SuggestionConfidence.Medium -> 2
    SuggestionConfidence.Low -> 1
}
