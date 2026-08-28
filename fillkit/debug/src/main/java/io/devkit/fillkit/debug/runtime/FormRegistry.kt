package io.devkit.fillkit.debug.runtime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import io.devkit.fillkit.FieldOverlayBehavior
import io.devkit.fillkit.FillCatalogInput
import io.devkit.fillkit.FillActivationRejection
import io.devkit.fillkit.FillActivationRequest
import io.devkit.fillkit.FillActivationResult
import io.devkit.fillkit.FillGenerator
import io.devkit.fillkit.FillKitConfig
import io.devkit.fillkit.FillKitCommand
import io.devkit.fillkit.FillKitFormSnapshot
import io.devkit.fillkit.FillKitPack
import io.devkit.fillkit.FillKitSeed
import io.devkit.fillkit.FillLocale
import io.devkit.fillkit.FillLocalePack
import io.devkit.fillkit.FillPersona
import io.devkit.fillkit.FillReproductionSpec
import io.devkit.fillkit.FillScenario
import io.devkit.fillkit.FillSeed
import io.devkit.fillkit.configurationFingerprint
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
    val overlay: FieldOverlayBehavior = FieldOverlayBehavior.Default,
) {
    val currentValue: Any? get() = target.currentValue
}

/** What the overlay knows about the field the developer is currently in. */
internal data class FocusedField(val field: StoredField, val bounds: Rect?)

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
    private val applicationPacks: List<FillKitPack> = emptyList(),
    initialSeed: FillSeed = config.seed?.let(::coerceSeed) ?: FillKitSeed.random(),
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

    /**
     * Per-field regeneration counters. They are deliberately outside the
     * reproduction spec: applying a seed resets them, so a reproduction is exact
     * while a single-field reroll stays a local exploration.
     */
    private val fieldNonces = mutableStateMapOf<String, Int>()

    /** Debug-only bookkeeping for the overlay; never application state. */
    private val lastGenerated = mutableStateMapOf<String, Any>()
    private val bounds = mutableStateMapOf<Any, Rect>()
    private var focusedOwner by mutableStateOf<Any?>(null)

    /** Digest of everything that can change generated data for a given seed. */
    val configurationFingerprint: String = config.configurationFingerprint(applicationPacks)

    var masterSeed by mutableStateOf(initialSeed)
        private set
    var generation by mutableStateOf(0)
        private set

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

    /** Non-zero per-field reroll counters, the shape a reproduction records. */
    val fieldGenerations: Map<String, Int> get() = fieldNonces.filterValues { it > 0 }.toSortedMap()

    /** The focused registered field, with its latest window bounds. */
    val focusedField: FocusedField?
        get() = focusedOwner?.let { owner ->
            registrations[owner]?.let { FocusedField(it, bounds[owner]) }
        }

    fun boundsOf(field: StoredField): Rect? = bounds[field.owner]

    fun generationOf(fieldId: String): Int = fieldNonces[fieldId] ?: 0

    /** True when the application value diverged from what FillKit last generated. */
    fun isModified(field: StoredField): Boolean {
        val current = field.currentValue ?: return false
        if (current is String && current.isEmpty()) return false
        val generated = lastGenerated[field.id] ?: return true
        return generated != current
    }

    /** Resolves what [fill] would write, without touching the field. */
    fun preview(field: StoredField): Any? = runCatching { resolveValue(field) }.getOrNull()

    /** True when the active scenario pins this field explicitly. */
    fun isScenarioValue(field: StoredField): Boolean {
        val scenario = activeScenarioId?.let(scenarioRegistry::find) ?: return false
        return field.id in scenario.values || field.id in scenario.generators
    }
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

    // --- Reproduction --------------------------------------------------------

    fun reproductionSpec() = FillReproductionSpec(
        formId = formId,
        seed = masterSeed.value,
        generation = generation,
        locale = localeTag,
        scenarioPackId = activeScenarioId?.let(::scenarioPackIdOf),
        scenarioId = activeScenarioId,
        personaPackId = activePersonaId?.let(::personaPackIdOf),
        personaId = activePersonaId,
        configurationFingerprint = configurationFingerprint,
        fieldGenerations = fieldGenerations,
    )

    /** Everything the QA catalog needs from this host's effective configuration. */
    fun catalogInput(navigableForms: Set<String>, hasNavigator: Boolean) = FillCatalogInput(
        packs = config.packs,
        scenarioPacks = config.scenarioPacks,
        scenarios = config.scenarios,
        personaIds = personas.mapTo(mutableSetOf(), FillPersona::id),
        localeCodes = availableLocales.mapTo(mutableSetOf()) { it.code },
        generatorIds = config.allGenerators().mapTo(mutableSetOf()) { it.first },
        navigableForms = navigableForms,
        hasNavigator = hasNavigator,
    )

    fun snapshot() = FillKitFormSnapshot(
        formId = formId,
        seed = masterSeed.value,
        generation = generation,
        localeTag = localeTag,
        scenarioId = activeScenarioId,
        personaId = activePersonaId,
        fieldIds = fields.map(StoredField::id),
        reproduction = reproductionSpec(),
    )

    /** Fresh master seed, generation reset to zero, form refilled. */
    fun newSeed(): FillSeed = FillKitSeed.random().also { applySeed(it) }

    fun applySeed(seed: FillSeed, generation: Int = 0, fill: Boolean = true) {
        masterSeed = seed
        this.generation = generation
        fieldNonces.clear()
        rebuildResolver()
        if (fill) fillAll()
    }

    // --- Activation ----------------------------------------------------------

    /**
     * The single path every entry point converges on: panel, QA launcher, deep
     * link and Compose tests all end up here with a [FillActivationRequest].
     */
    fun activate(request: FillActivationRequest): FillActivationResult {
        val warnings = mutableListOf<String>()
        request.configurationFingerprint?.let { recorded ->
            if (recorded != configurationFingerprint) {
                warnings += "Reproduction was recorded with configuration $recorded but this build is " +
                    "$configurationFingerprint; the same seed may produce different values."
            }
        }
        request.personaId?.let { id ->
            if (findPersona(id) == null) {
                return FillActivationResult.Rejected(FillActivationRejection.UnknownPersona, "unknown persona \"$id\"")
            }
        }
        request.scenarioId?.let { id ->
            if (scenarioRegistry.find(id) == null) {
                return FillActivationResult.Rejected(FillActivationRejection.UnknownScenario, "unknown scenario \"$id\"")
            }
        }
        request.locale?.let { tag ->
            if (availableLocales.none { it.code.equals(tag, ignoreCase = true) }) {
                warnings += "Missing locale pack \"$tag\"; FillKit fell back to the closest available locale."
            }
            localePack = localeRegistry.resolve(FillLocale.Code(tag))
        }
        fieldNonces.clear()
        request.fieldGenerations.forEach { (id, value) -> if (value > 0) fieldNonces[id] = value }
        request.seed?.let { masterSeed = coerceSeed(it) }
        generation = request.generation
        activePersonaId = request.personaId
        rebuildResolver()
        request.scenarioId?.let { id ->
            activeScenarioId = id
            scenarioRegistry.find(id)?.let { scenario ->
                scenario.personaId?.let { personaId -> if (findPersona(personaId) != null) activePersonaId = personaId }
                validateScenarioFields(scenario)
            }
        }
        if (request.fill) fillAll()
        val spec = reproductionSpec()
        return if (warnings.isEmpty()) {
            FillActivationResult.Applied(spec)
        } else {
            FillActivationResult.PartiallyApplied(spec, warnings)
        }
    }

    /** Programmatic command surface shared by the test bridge and the panel. */
    fun execute(command: FillKitCommand): Boolean {
        when (command) {
            FillKitCommand.FillAll -> fillAll()
            FillKitCommand.ClearAll -> clearAll()
            FillKitCommand.RegenerateAll -> regenerateAll()
            FillKitCommand.SelectRandomPersona -> selectRandomPersona()
            is FillKitCommand.Fill -> if (field(command.fieldId) == null) return false else fill(command.fieldId)
            is FillKitCommand.Clear -> if (field(command.fieldId) == null) return false else clear(command.fieldId)
            is FillKitCommand.SelectPersona -> {
                if (findPersona(command.personaId) == null) return false
                selectPersona(command.personaId)
            }
            is FillKitCommand.SetLocale -> changeLocale(command.localeTag)
            is FillKitCommand.SetSeed -> applySeed(coerceSeed(command.seed), command.generation)
            is FillKitCommand.ApplyScenario -> {
                if (scenarioRegistry.find(command.scenarioId) == null) return false
                applyScenario(command.scenarioId)
            }
        }
        return true
    }

    /**
     * Rerolls one field only. The counter is recorded in the reproduction so a
     * QA engineer who tapped "another" twice can still be reproduced exactly.
     */
    fun regenerate(fieldId: String) {
        val next = (fieldNonces[fieldId] ?: 0) + 1
        if (next > FillReproductionSpec.MAX_GENERATION) return problem("field \"$fieldId\" exhausted its reroll counter")
        fieldNonces[fieldId] = next
        fill(fieldId)
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
        bounds.remove(owner)
        if (focusedOwner === owner) focusedOwner = null
    }

    override fun setFieldFocus(owner: Any, focused: Boolean) {
        if (focused) {
            focusedOwner = owner
        } else if (focusedOwner === owner) {
            focusedOwner = null
        }
    }

    override fun setFieldBounds(owner: Any, bounds: Rect) {
        if (this.bounds[owner] != bounds) this.bounds[owner] = bounds
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
            field.target as FillTarget<Any>, field.generator, "ContentType", suggestion.confidence, field.overlay,
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

    /** Randomize keeps the master seed and advances the generation counter. */
    override fun regenerateAll() {
        activePersonaId = null
        generation += 1
        fieldNonces.clear()
        rebuildResolver()
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

    private fun rebuildResolver() {
        resolver = newResolver()
        generatedPersona = resolver.generatedPersona()
    }

    private fun scenarioPackIdOf(scenarioId: String): String? =
        config.allScenarioPacks().firstOrNull { pack -> pack.scenarios.any { it.id == scenarioId } }?.id

    private fun personaPackIdOf(personaId: String): String? =
        config.allPersonaPacks().firstOrNull { pack -> pack.personas.any { it.id == personaId } }?.id

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
        rebuildResolver()
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

    private fun newResolver() =
        FillValueResolver(localePack, config.allGenerators(), masterSeed, generation, formId)

    @Suppress("UNCHECKED_CAST")
    private fun resolveValue(field: StoredField): Any = resolver.resolve(
        FillResolutionRequest(
            fieldId = field.id,
            type = field.type as FillType<Any>,
            scenario = activeScenarioId?.let(scenarioRegistry::find),
            persona = activePersonaId?.let(::findPersona),
            fieldGenerator = field.generator as? FillGenerator<Any>,
            nonce = fieldNonces[field.id] ?: 0,
        ),
        generatedPersona,
    )

    private fun fillResolved(field: StoredField) {
        try {
            val value = resolveValue(field)
            lastGenerated[field.id] = value
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
    overlay = overlay,
)

private fun String.humanize(): String =
    replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('-', ' ').replaceFirstChar(Char::uppercase)

/** Keeps arbitrary configured longs inside the reproducible seed range. */
internal fun coerceSeed(value: Long): FillSeed =
    if (FillKitSeed.isValid(value)) FillSeed(value) else FillSeed(value.mod(FillKitSeed.MAX + 1))

private fun FillType<*>.supportsTextTarget(): Boolean = when (this) {
    FillType.DateOfBirth, FillType.Age, is FillType.Integer, is FillType.Decimal,
    is FillType.BooleanValue, is FillType.Date, is FillType.Unsupported -> false
    is FillType.Custom<*> -> valueClass == String::class
    else -> true
}
