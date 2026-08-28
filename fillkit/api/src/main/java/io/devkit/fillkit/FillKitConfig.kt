package io.devkit.fillkit

enum class ScenarioValidationMode { Lenient, Strict }

/** A reusable collection spanning all four FillKit 0.2 systems. */
data class FillKitPack(
    override val id: String,
    override val name: String,
    val localePacks: List<FillLocalePack> = emptyList(),
    val personaPacks: List<FillPersonaPack> = emptyList(),
    val scenarioPacks: List<FillScenarioPack> = emptyList(),
    val generatorPacks: List<FillGeneratorPack> = emptyList(),
    val contentTypeMappers: List<ContentTypeMapper> = emptyList(),
    val suggestionRulePacks: List<FillSuggestionRulePack> = emptyList(),
    override val version: String? = null,
) : FillVersionedPack {
    init {
        require(id.isNotBlank()) { "FillKit pack id cannot be blank" }
        require(name.isNotBlank()) { "FillKit pack name cannot be blank" }
    }
}

fun fillKitPack(
    id: String,
    name: String,
    version: String? = null,
    block: FillKitPackBuilder.() -> Unit,
): FillKitPack = FillKitPackBuilder().apply(block).build(id, name, version)

/** Convenience for the common `version = 3` style; stored as a stable string. */
fun fillKitPack(
    id: String,
    name: String,
    version: Int,
    block: FillKitPackBuilder.() -> Unit,
): FillKitPack = fillKitPack(id, name, version.toString(), block)

class FillKitPackBuilder internal constructor() {
    private val locales = mutableListOf<FillLocalePack>()
    private val personas = mutableListOf<FillPersonaPack>()
    private val scenarios = mutableListOf<FillScenarioPack>()
    private val generators = mutableListOf<FillGeneratorPack>()
    private val contentTypes = mutableListOf<ContentTypeMapper>()
    private val suggestions = mutableListOf<FillSuggestionRulePack>()

    fun locale(value: FillLocalePack) { locales += value }
    fun personas(value: FillPersonaPack) { personas += value }
    fun scenarios(value: FillScenarioPack) { scenarios += value }
    fun generators(value: FillGeneratorPack) { generators += value }
    fun contentTypes(value: ContentTypeMapper) { contentTypes += value }
    fun suggestions(value: FillSuggestionRulePack) { suggestions += value }

    internal fun build(id: String, name: String, version: String? = null) =
        FillKitPack(
            id, name, locales.toList(), personas.toList(), scenarios.toList(), generators.toList(),
            contentTypes.toList(), suggestions.toList(), version,
        )
}

/** Behavior and reusable test data scoped to one [FillKitHost]. */
data class FillKitConfig(
    val locale: FillLocale = FillLocale.System,
    val seed: Long? = null,
    val localePacks: List<FillLocalePack> = emptyList(),
    val personaPacks: List<FillPersonaPack> = emptyList(),
    val scenarioPacks: List<FillScenarioPack> = emptyList(),
    val generatorPacks: List<FillGeneratorPack> = emptyList(),
    val generators: List<FillGenerator<*>> = emptyList(),
    /** Compatibility shortcut for form-local scenarios. */
    val scenarios: List<FillScenario> = emptyList(),
    /** Compatibility shortcut; the map key explicitly overrides the generator's own ID. */
    val customGenerators: Map<String, FillGenerator<*>> = emptyMap(),
    /** Local mappers override packed mappings; the built-in Compose mapper is the final fallback. */
    val contentTypeMappers: List<ContentTypeMapper> = emptyList(),
    val suggestionRulePacks: List<FillSuggestionRulePack> = emptyList(),
    val packs: List<FillKitPack> = emptyList(),
    val suggestionMode: FieldSuggestionMode = FieldSuggestionMode.Suggest,
    /** Enables public-API opt-in discovery through Modifier.fillKitSuggestion. */
    val semanticDiscovery: Boolean = true,
    val showTrigger: Boolean = true,
    val showFieldValues: Boolean = true,
    val scenarioValidationMode: ScenarioValidationMode = ScenarioValidationMode.Lenient,
    val loggingEnabled: Boolean = true,
) {
    init {
        requireUniqueIds("FillKit pack", packs.map(FillKitPack::id))
        requireUniqueIds("packed locale", packs.flatMap(FillKitPack::localePacks).map(FillLocalePack::code))
        requireUniqueIds("local locale", localePacks.map(FillLocalePack::code))
        requireUniqueIds("persona", allPersonas().map(FillPersona::id))
        requireUniqueIds("packed scenario", allScenarioPacks().flatMap(FillScenarioPack::scenarios).map(FillScenario::id))
        requireUniqueIds("local scenario", scenarios.map(FillScenario::id))
        requireUniqueIds("packed generator", allGeneratorPacks().flatMap(FillGeneratorPack::generators).map(FillGenerator<*>::id))
        requireUniqueIds("local generator", generators.map(FillGenerator<*>::id))
        requireUniqueIds("suggestion rule pack", allSuggestionRulePacks().map(FillSuggestionRulePack::id))
    }

    fun allLocalePacks(): List<FillLocalePack> =
        overrideBy(packs.flatMap(FillKitPack::localePacks), localePacks, FillLocalePack::code)
    fun allPersonaPacks(): List<FillPersonaPack> = packs.flatMap(FillKitPack::personaPacks) + personaPacks
    fun allPersonas(): List<FillPersona> = allPersonaPacks().flatMap(FillPersonaPack::personas)
    fun allScenarioPacks(): List<FillScenarioPack> = packs.flatMap(FillKitPack::scenarioPacks) + scenarioPacks
    fun allScenarios(): List<FillScenario> =
        overrideBy(allScenarioPacks().flatMap(FillScenarioPack::scenarios), scenarios, FillScenario::id)
    fun allGeneratorPacks(): List<FillGeneratorPack> = packs.flatMap(FillKitPack::generatorPacks) + generatorPacks
    fun allGenerators(): List<Pair<String, FillGenerator<*>>> =
        allGeneratorPacks().flatMap(FillGeneratorPack::generators).map { it.id to it } +
            generators.map { it.id to it } + customGenerators.toList()
    fun allContentTypeMappers(): List<ContentTypeMapper> =
        contentTypeMappers.asReversed() + packs.flatMap(FillKitPack::contentTypeMappers).asReversed() + BuiltInContentTypeMapper
    fun allSuggestionRulePacks(): List<FillSuggestionRulePack> =
        packs.flatMap(FillKitPack::suggestionRulePacks) + suggestionRulePacks
}

private fun <T, K> overrideBy(base: List<T>, overrides: List<T>, key: (T) -> K): List<T> =
    LinkedHashMap<K, T>().apply {
        base.forEach { put(key(it), it) }
        overrides.forEach { put(key(it), it) }
    }.values.toList()
