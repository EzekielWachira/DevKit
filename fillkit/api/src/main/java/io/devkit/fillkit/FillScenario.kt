package io.devkit.fillkit

/** A scenario generator declaration; explicit values remain independently serializable. */
sealed interface FillScenarioGenerator {
    data class Registered(val generatorId: String) : FillScenarioGenerator
    data class Type(val type: FillType<*>) : FillScenarioGenerator
    data class Inline(val generator: FillGenerator<*>) : FillScenarioGenerator
}

/**
 * A named workflow with composable values, generators, and an optional persona.
 *
 * [targetForm] and the surrounding metadata let the QA launcher list, search and
 * route a scenario without a second scenario system.
 */
data class FillScenario(
    val id: String,
    val name: String,
    val values: Map<String, FillValue> = emptyMap(),
    val generators: Map<String, FillScenarioGenerator> = emptyMap(),
    val includes: List<String> = emptyList(),
    val personaId: String? = null,
    val targetForm: String? = null,
    val description: String? = null,
    val category: String? = null,
    val tags: Set<String> = emptySet(),
) {
    init {
        require(id.isNotBlank()) { "scenario id cannot be blank" }
        require(name.isNotBlank()) { "scenario name cannot be blank" }
        require(values.keys.intersect(generators.keys).isEmpty()) {
            "scenario field cannot declare both a value and generator"
        }
        require(targetForm == null || targetForm.isNotBlank()) { "scenario targetForm cannot be blank" }
        require(tags.none(String::isBlank)) { "scenario tags cannot be blank" }
    }

    /** Lowercased haystack used by QA launcher search. */
    fun searchText(packName: String? = null): String =
        listOfNotNull(name, id, targetForm, category, description, packName)
            .plus(tags)
            .joinToString(" ")
            .lowercase()
}

fun fillScenario(
    id: String,
    name: String,
    targetForm: String? = null,
    description: String? = null,
    category: String? = null,
    tags: Set<String> = emptySet(),
    block: FillScenarioBuilder.() -> Unit,
): FillScenario = FillScenarioBuilder().apply(block).build(id, name, targetForm, description, category, tags)

class FillScenarioBuilder internal constructor() {
    private val values = linkedMapOf<String, FillValue>()
    private val generators = linkedMapOf<String, FillScenarioGenerator>()
    private val includes = mutableListOf<String>()
    private var personaId: String? = null

    fun text(fieldId: String, value: String) = put(fieldId, FillValue.Text(value))
    fun integer(fieldId: String, value: Int) = put(fieldId, FillValue.Integer(value))
    fun decimal(fieldId: String, value: Double) = put(fieldId, FillValue.Decimal(value))
    fun boolean(fieldId: String, value: Boolean) = put(fieldId, FillValue.BooleanValue(value))
    fun date(fieldId: String, value: FillDate) = put(fieldId, FillValue.DateValue(value))
    fun value(fieldId: String, value: Any) = put(fieldId, FillValue.of(value))

    fun include(scenario: FillScenario) = include(scenario.id)
    fun include(scenarioId: String) {
        require(scenarioId.isNotBlank()) { "included scenario id cannot be blank" }
        includes += scenarioId
    }

    fun persona(id: String) {
        require(id.isNotBlank()) { "scenario persona id cannot be blank" }
        personaId = id
    }

    fun generated(field: String, generatorId: String) = generator(field, FillScenarioGenerator.Registered(generatorId))
    fun <T : Any> generated(field: String, type: FillType<T>) = generator(field, FillScenarioGenerator.Type(type))
    fun <T : Any> custom(field: String, generator: FillGenerator<T>) = generator(field, FillScenarioGenerator.Inline(generator))

    private fun generator(field: String, value: FillScenarioGenerator) {
        require(field.isNotBlank()) { "scenario field id cannot be blank" }
        require(field !in values && generators.put(field, value) == null) { "duplicate scenario field id: $field" }
    }

    private fun put(fieldId: String, value: FillValue) {
        require(fieldId.isNotBlank()) { "scenario field id cannot be blank" }
        require(fieldId !in generators && values.put(fieldId, value) == null) { "duplicate scenario field id: $fieldId" }
    }

    internal fun build(
        id: String,
        name: String,
        targetForm: String? = null,
        description: String? = null,
        category: String? = null,
        tags: Set<String> = emptySet(),
    ) = FillScenario(
        id, name, values.toMap(), generators.toMap(), includes.toList(), personaId,
        targetForm, description, category, tags,
    )
}

data class FillScenarioPack(
    override val id: String,
    override val name: String,
    val scenarios: List<FillScenario>,
    override val version: String? = null,
) : FillVersionedPack {
    init {
        require(id.isNotBlank()) { "scenario pack id cannot be blank" }
        require(name.isNotBlank()) { "scenario pack name cannot be blank" }
        requireUniqueIds("scenario", scenarios.map(FillScenario::id))
    }
}

fun scenarioPack(
    id: String,
    name: String,
    version: String? = null,
    block: FillScenarioPackBuilder.() -> Unit,
): FillScenarioPack = FillScenarioPackBuilder().apply(block).build(id, name, version)

class FillScenarioPackBuilder internal constructor() {
    private val scenarios = mutableListOf<FillScenario>()
    fun scenario(value: FillScenario) { scenarios += value }
    fun scenario(
        id: String,
        name: String,
        targetForm: String? = null,
        description: String? = null,
        category: String? = null,
        tags: Set<String> = emptySet(),
        block: FillScenarioBuilder.() -> Unit,
    ) {
        scenarios += fillScenario(id, name, targetForm, description, category, tags, block)
    }
    internal fun build(id: String, name: String, version: String? = null) =
        FillScenarioPack(id, name, scenarios.toList(), version)
}

/** Stable generator ID used when a scenario requests a built-in type. */
fun FillType<*>.generatorId(): String = when (this) {
    FillType.FirstName -> "first-name"
    FillType.LastName -> "last-name"
    FillType.FullName -> "full-name"
    FillType.MiddleName -> "middle-name"
    FillType.NamePrefix -> "name-prefix"
    FillType.NameSuffix -> "name-suffix"
    FillType.Username -> "username"
    FillType.DateOfBirth -> "date-of-birth"
    FillType.Age -> "age"
    FillType.Email -> "email"
    is FillType.PhoneNumber -> "phone"
    FillType.PhoneCountryCode -> "phone-country-code"
    FillType.StreetAddress -> "street-address"
    FillType.City -> "city"
    FillType.Region -> "region"
    FillType.Country -> "country"
    FillType.PostalCode -> "postal-code"
    FillType.CompanyName -> "company-name"
    FillType.JobTitle -> "job-title"
    FillType.Website -> "website"
    FillType.Url -> "url"
    is FillType.OtpCode -> "otp-code"
    is FillType.Unsupported -> "unsupported:${category}"
    is FillType.Password -> "password"
    is FillType.Text -> "text"
    is FillType.Integer -> "integer"
    is FillType.Decimal -> "decimal"
    is FillType.BooleanValue -> "boolean"
    is FillType.Date -> "date"
    is FillType.Selection -> "selection"
    is FillType.Custom<*> -> key
}
