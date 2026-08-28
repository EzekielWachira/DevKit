package io.devkit.fillkit.debug.runtime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.devkit.fillkit.FillGenerator
import io.devkit.fillkit.FillKitConfig
import io.devkit.fillkit.FillLocale
import io.devkit.fillkit.FillLocalePack
import io.devkit.fillkit.FillPersona
import io.devkit.fillkit.FillScenario
import io.devkit.fillkit.FillTarget
import io.devkit.fillkit.FillTargetKind
import io.devkit.fillkit.FillType
import io.devkit.fillkit.FillTypeSuggestion
import io.devkit.fillkit.FieldSuggestionContext
import io.devkit.fillkit.FieldSuggestionMode
import io.devkit.fillkit.SuggestionConfidence
import io.devkit.fillkit.SuggestionFillability
import io.devkit.fillkit.ScenarioValidationMode
import io.devkit.fillkit.debug.persistence.RuntimePersonaPersistence
import io.devkit.fillkit.engine.FillResolutionRequest
import io.devkit.fillkit.engine.FillValueResolver
import io.devkit.fillkit.engine.FieldSuggestionEngine
import io.devkit.fillkit.engine.ScenarioRegistry
import io.devkit.fillkit.engine.locale.DefaultFillLocaleRegistry
import io.devkit.fillkit.runtime.FillKitCommands
import io.devkit.fillkit.runtime.FillKitField
import io.devkit.fillkit.runtime.FillKitRegistry
import io.devkit.fillkit.runtime.FillKitSuggestionCandidate
import io.devkit.fillkit.runtime.FillKitContentTypeField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

internal class StoredField(
    val owner: Any,
    val id: String,
    val label: String,
    val group: String?,
    val type: FillType<*>,
    val target: FillTarget<Any>,
    val generator: FillGenerator<*>?,
    val source: String = "Explicit",
    val confidence: SuggestionConfidence = SuggestionConfidence.Exact,
) {
    val currentValue: Any? get() = target.currentValue
}

internal data class StoredSuggestion(
    val owner: Any,
    val id: String,
    val label: String,
    val candidates: List<FillTypeSuggestion>,
    val target: FillTarget<String>?,
)

internal data class NamedGroup<T>(val name: String, val values: List<T>)

internal class FormRegistry(
    override val formId: String,
    private val initialLocaleTag: String,
    private val config: FillKitConfig,
    private val logger: (String) -> Unit,
    private val persistence: RuntimePersonaPersistence? = null,
) : FillKitRegistry, FillKitCommands {
    private val registrations = mutableStateMapOf<Any, StoredField>()
    private val registrationOrder = mutableStateListOf<Any>()
    private val suggestionRegistrations = mutableStateMapOf<Any, StoredSuggestion>()
    private val suggestionOrder = mutableStateListOf<Any>()
    private val ignoredSuggestions = mutableStateMapOf<Any, Boolean>()
    private val runtimePersonas = mutableStateListOf<FillPersona>()
    private val localeRegistry = DefaultFillLocaleRegistry(config.allLocalePacks())
    private val scenarioRegistry = ScenarioRegistry(config.allScenarios())
    private var persistenceScope: CoroutineScope? = null
    private var persistenceJob: Job? = null
    private var resolver: FillValueResolver
    private var generatedPersona: FillPersona

    var localePack by mutableStateOf(localeRegistry.resolve(FillLocale.Code(initialLocaleTag)))
        private set
    val localeTag: String get() = localePack.code

    var activePersonaId by mutableStateOf<String?>(null)
        private set
    var activeScenarioId by mutableStateOf<String?>(null)
        private set

    val persona: FillPersona get() = activePersonaId?.let(::findPersona) ?: generatedPersona
    val isRandomPersona: Boolean get() = activePersonaId == null
    val fields: List<StoredField> get() = registrationOrder.mapNotNull(registrations::get)
    val suggestions: List<StoredSuggestion> get() = suggestionOrder.mapNotNull(suggestionRegistrations::get)
        .filter { ignoredSuggestions[it.owner] != true && it.owner !in registrations }
    val availableLocales: List<FillLocalePack> get() = localeRegistry.availableLocales()
    val personas: List<FillPersona> get() = config.allPersonas() + runtimePersonas
    val savedRuntimePersonas: List<FillPersona> get() = runtimePersonas
    val personaGroups: List<NamedGroup<FillPersona>> get() = buildList {
        config.allPersonaPacks().forEach { add(NamedGroup(it.name, it.personas)) }
        if (runtimePersonas.isNotEmpty()) add(NamedGroup("Saved on this device", runtimePersonas.toList()))
    }
    val scenarioGroups: List<NamedGroup<FillScenario>> get() = buildList {
        config.allScenarioPacks().forEach { pack -> add(NamedGroup(pack.name, pack.scenarios)) }
        if (config.scenarios.isNotEmpty()) add(NamedGroup("Form", config.scenarios))
    }

    init {
        resolver = newResolver()
        generatedPersona = resolver.generatedPersona()
    }

    fun attachPersistence(scope: CoroutineScope) {
        persistenceScope = scope
        persistenceJob?.cancel()
        persistenceJob = persistence?.let { store ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                fun replaceFromStorage(loaded: List<FillPersona>) {
                    runtimePersonas.clear()
                    val codeIds = config.allPersonas().mapTo(mutableSetOf(), FillPersona::id)
                    loaded.filterNot { it.id in codeIds }.forEach(runtimePersonas::add)
                    if (activePersonaId != null && findPersona(activePersonaId!!) == null) activePersonaId = null
                }
                replaceFromStorage(store.personas.first())
                store.personas.drop(1).collectLatest(::replaceFromStorage)
            }
        }
    }

    fun detachPersistence() {
        persistenceJob?.cancel()
        persistenceJob = null
        persistenceScope = null
    }

    override fun <T : Any> register(owner: Any, field: FillKitField<T>) {
        val duplicate = registrations.values.firstOrNull { it.id == field.id && it.owner !== owner }
        if (duplicate != null) log("duplicate field ID \"${field.id}\" in form \"$formId\"")
        if (owner !in registrations) registrationOrder += owner
        registrations[owner] = field.erase(owner)
    }

    override fun <T : Any> update(owner: Any, field: FillKitField<T>) {
        if (owner !in registrations) register(owner, field) else registrations[owner] = field.erase(owner)
    }

    override fun unregister(owner: Any) {
        registrations.remove(owner)
        registrationOrder.remove(owner)
    }

    override fun registerContentType(owner: Any, field: FillKitContentTypeField) {
        registrations.remove(owner)
        val context = FieldSuggestionContext(field.id, field.label)
        val suggestion = field.mapper?.suggest(field.contentType, context)
            ?: config.allContentTypeMappers().firstNotNullOfOrNull { it.suggest(field.contentType, context) }
            ?: return log("no ContentType mapping for field \"${field.id}\"")
        val suggestedType = suggestion.type
        if (suggestedType is FillType.Unsupported) {
            return log("field \"${field.id}\" was detected but ${suggestedType.category} generation is unsupported")
        }
        if (!suggestedType.supportsTextTarget()) {
            return log("ContentType mapping for field \"${field.id}\" does not produce text")
        }
        @Suppress("UNCHECKED_CAST")
        val type = suggestedType as FillType<String>
        if (owner !in registrationOrder) registrationOrder += owner
        @Suppress("UNCHECKED_CAST")
        registrations[owner] = StoredField(
            owner, field.id, field.label ?: field.id.humanize(), field.group, type,
            field.target as FillTarget<Any>, field.generator, "ContentType", suggestion.confidence,
        )
    }

    override fun updateContentType(owner: Any, field: FillKitContentTypeField) = registerContentType(owner, field)

    override fun registerSuggestion(owner: Any, candidate: FillKitSuggestionCandidate) {
        if (!config.semanticDiscovery || config.suggestionMode == FieldSuggestionMode.Disabled) return
        if (owner !in suggestionRegistrations) suggestionOrder += owner
        val stored = candidate.toStored(owner)
        suggestionRegistrations[owner] = stored
        if (config.suggestionMode == FieldSuggestionMode.AutoRegisterExact) {
            val exact = stored.candidates.firstOrNull {
                it.confidence == SuggestionConfidence.Exact && it.fillability == SuggestionFillability.Fillable
            }
            if (exact != null) acceptSuggestion(owner, exact)
        }
    }

    override fun updateSuggestion(owner: Any, candidate: FillKitSuggestionCandidate) {
        if (owner !in suggestionRegistrations) return registerSuggestion(owner, candidate)
        val previousType = registrations[owner]?.type
        val stored = candidate.toStored(owner)
        suggestionRegistrations[owner] = stored
        val selected = stored.candidates.firstOrNull { it.type == previousType }
        when {
            selected != null -> acceptSuggestion(owner, selected)
            config.suggestionMode == FieldSuggestionMode.AutoRegisterExact -> {
                registrations.remove(owner)
                registrationOrder.remove(owner)
                stored.candidates.firstOrNull {
                    it.confidence == SuggestionConfidence.Exact && it.fillability == SuggestionFillability.Fillable
                }?.let { acceptSuggestion(owner, it) }
            }
        }
    }

    override fun unregisterSuggestion(owner: Any) {
        suggestionRegistrations.remove(owner)
        suggestionOrder.remove(owner)
        ignoredSuggestions.remove(owner)
        if (registrations[owner]?.source == "Suggestion") unregister(owner)
    }

    fun acceptSuggestion(owner: Any, suggestion: FillTypeSuggestion? = null) {
        val stored = suggestionRegistrations[owner] ?: return
        val selected = suggestion ?: stored.candidates.firstOrNull() ?: return
        val target = stored.target ?: return log("suggestion \"${stored.id}\" is detection-only")
        if (selected.fillability != SuggestionFillability.Fillable || !selected.type.supportsTextTarget()) {
            return log("suggestion \"${stored.id}\" is unsupported for its fill target")
        }
        @Suppress("UNCHECKED_CAST")
        val type = selected.type as FillType<String>
        if (owner !in registrations) registrationOrder += owner
        @Suppress("UNCHECKED_CAST")
        registrations[owner] = StoredField(
            owner, stored.id, stored.label, "Suggested", type,
            target as FillTarget<Any>, null, "Suggestion", selected.confidence,
        )
    }

    fun ignoreSuggestion(owner: Any) { ignoredSuggestions[owner] = true }

    override fun fillAll() = fields.forEach(::fillResolved)

    override fun regenerateAll() {
        activePersonaId = null
        generatedPersona = resolver.generatedPersona()
        fillAll()
    }

    override fun clearAll() = fields.forEach(::clear)
    override fun fill(fieldId: String) { field(fieldId)?.let(::fillResolved) }
    override fun clear(fieldId: String) { field(fieldId)?.let(::clear) }

    override fun applyScenario(scenarioId: String) {
        val scenario = runCatching { scenarioRegistry.resolve(scenarioId) }.getOrElse {
            problem(it.message ?: "cannot resolve scenario $scenarioId")
            return
        }
        activeScenarioId = scenario.id
        scenario.personaId?.let { id ->
            if (findPersona(id) == null) problem("scenario \"${scenario.id}\" references unknown persona \"$id\"")
            else activePersonaId = id
        }
        validateScenarioFields(scenario)
        fillAll()
    }

    override fun selectRandomPersona() {
        activePersonaId = null
        generatedPersona = resolver.generatedPersona()
        fillAll()
    }

    override fun selectPersona(personaId: String) {
        if (findPersona(personaId) == null) {
            problem("unknown persona \"$personaId\"")
            return
        }
        activePersonaId = personaId
        fillAll()
    }

    fun changeLocale(tag: String) {
        localePack = localeRegistry.resolve(FillLocale.Code(tag))
        resolver = newResolver()
        generatedPersona = resolver.generatedPersona()
        if (isRandomPersona) fillAll()
    }

    override fun changeLocale(locale: FillLocale) {
        when (locale) {
            FillLocale.System -> changeLocale(initialLocaleTag)
            is FillLocale.Code -> changeLocale(locale.value)
        }
    }

    fun saveCurrentPersona(name: String) {
        require(name.isNotBlank()) { "persona name cannot be blank" }
        val store = persistence ?: return problem("runtime persona persistence is unavailable")
        val scope = persistenceScope ?: return problem("runtime persona persistence is not attached")
        val saved = persona.copy(
            id = "runtime-${UUID.randomUUID()}",
            name = name.trim(),
            metadata = persona.metadata + ("source" to "runtime"),
        )
        runtimePersonas.removeAll { it.id == saved.id }
        runtimePersonas += saved
        activePersonaId = saved.id
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.save(saved)
        }
    }

    fun deleteRuntimePersona(id: String) {
        val store = persistence ?: return
        val scope = persistenceScope ?: return
        runtimePersonas.removeAll { it.id == id }
        if (activePersonaId == id) activePersonaId = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.delete(id)
        }
    }

    fun deleteAllRuntimePersonas() {
        val store = persistence ?: return
        val scope = persistenceScope ?: return
        val runtimeIds = runtimePersonas.mapTo(mutableSetOf(), FillPersona::id)
        runtimePersonas.clear()
        if (activePersonaId in runtimeIds) activePersonaId = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.clear()
        }
    }

    private fun newResolver() = FillValueResolver(localePack, config.allGenerators(), config.seed)

    private fun fillResolved(field: StoredField) {
        try {
            @Suppress("UNCHECKED_CAST")
            val value = resolver.resolve(
                FillResolutionRequest(
                    fieldId = field.id,
                    type = field.type as FillType<Any>,
                    scenario = activeScenarioId?.let(scenarioRegistry::find),
                    persona = activePersonaId?.let(::findPersona),
                    fieldGenerator = field.generator as? FillGenerator<Any>,
                ),
                generatedPersona,
            )
            field.target.fill(value)
        } catch (error: Exception) {
            problem("cannot fill field \"${field.id}\": ${error.message}")
        }
    }

    private fun validateScenarioFields(scenario: FillScenario) {
        (scenario.values.keys + scenario.generators.keys).forEach { id ->
            if (field(id) == null) problem("scenario \"${scenario.id}\" references unknown field \"$id\"")
        }
    }

    private fun clear(field: StoredField) {
        val cleared = field.target.clear()
        if (!cleared && field.currentValue is String) field.target.fill("")
        else if (!cleared && field.currentValue !is String && field.target.kind == FillTargetKind.Callback) {
            log("field \"${field.id}\" has no clear behavior")
        }
    }

    private fun findPersona(id: String): FillPersona? = personas.firstOrNull { it.id == id }
    private fun field(id: String): StoredField? = registrations.values.firstOrNull { it.id == id }

    private fun problem(message: String) {
        val diagnostic = "FillKit configuration error: $message"
        if (config.scenarioValidationMode == ScenarioValidationMode.Strict) error(diagnostic) else log(diagnostic)
    }

    private fun log(message: String) {
        if (config.loggingEnabled) logger(message)
    }

    private fun FillKitSuggestionCandidate.toStored(owner: Any): StoredSuggestion {
        val context = FieldSuggestionContext(metadata.id, metadata.label, metadata.testTag)
        val mapped = contentType?.let { type ->
            config.allContentTypeMappers().firstNotNullOfOrNull { it.suggest(type, context) }
        }
        val inferred = FieldSuggestionEngine(config.allSuggestionRulePacks()).suggest(metadata)
        val candidates = listOfNotNull(mapped).plus(inferred).map { suggestion ->
            suggestion.copy(fillability = when {
                suggestion.type is FillType.Unsupported -> SuggestionFillability.Unsupported
                target == null -> SuggestionFillability.DetectionOnly
                !suggestion.type.supportsTextTarget() -> SuggestionFillability.Unsupported
                else -> SuggestionFillability.Fillable
            })
        }.distinctBy { it.type }.sortedByDescending { it.confidence.ordinal.let { ordinal -> 4 - ordinal } }
        val id = metadata.id ?: metadata.testTag ?: "suggested-${System.identityHashCode(owner)}"
        return StoredSuggestion(owner, id, metadata.label ?: id.humanize(), candidates, target)
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> FillKitField<T>.erase(owner: Any) = StoredField(
    owner = owner,
    id = id,
    label = label ?: id.humanize(),
    group = group,
    type = type,
    target = target as FillTarget<Any>,
    generator = generator,
)

private fun String.humanize(): String =
    replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('-', ' ').replaceFirstChar(Char::uppercase)

private fun FillType<*>.supportsTextTarget(): Boolean = when (this) {
    FillType.DateOfBirth, FillType.Age, is FillType.Integer, is FillType.Decimal,
    is FillType.BooleanValue, is FillType.Date, is FillType.Unsupported -> false
    is FillType.Custom<*> -> valueClass == String::class
    else -> true
}
