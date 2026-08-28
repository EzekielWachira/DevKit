package io.devkit.fillkit

enum class FillCatalogIssueKind {
    MissingTargetForm,
    UnknownPersona,
    MissingLocalePack,
    UnknownGenerator,
    NoNavigator,
    UnreachableForm,
}

/** A concrete reason a scenario cannot (or might not) launch, never a silent failure. */
data class FillCatalogIssue(
    val kind: FillCatalogIssueKind,
    val message: String,
    val blocking: Boolean,
)

data class FillScenarioCatalogEntry(
    val scenario: FillScenario,
    val packId: String?,
    val packName: String?,
    val packVersion: String?,
    val issues: List<FillCatalogIssue> = emptyList(),
) {
    val id: String get() = scenario.id
    val name: String get() = scenario.name
    val targetForm: String? get() = scenario.targetForm
    val tags: Set<String> get() = scenario.tags
    val launchable: Boolean get() = issues.none(FillCatalogIssue::blocking)
    val blockingReason: String? get() = issues.firstOrNull(FillCatalogIssue::blocking)?.message

    fun launchRequest(
        formId: String? = targetForm,
        seed: Long? = null,
        generation: Int = 0,
        locale: String? = null,
        personaId: String? = scenario.personaId,
        personaPackId: String? = null,
        fingerprint: String? = null,
        source: FillActivationSource = FillActivationSource.QaLauncher,
    ): FillActivationRequest {
        val target = requireNotNull(formId ?: targetForm) {
            "scenario \"${scenario.id}\" has no target form; pass one explicitly"
        }
        return FillActivationRequest(
            formId = target,
            scenarioPackId = packId,
            scenarioId = scenario.id,
            personaPackId = personaPackId,
            personaId = personaId,
            locale = locale,
            seed = seed,
            generation = generation,
            configurationFingerprint = fingerprint,
            source = source,
        )
    }

    internal fun haystack(): String = scenario.searchText(packName)
}

/** Everything the catalog needs, expressed as plain data so it stays unit-testable. */
data class FillCatalogInput(
    val packs: List<FillKitPack> = emptyList(),
    val scenarioPacks: List<FillScenarioPack> = emptyList(),
    val scenarios: List<FillScenario> = emptyList(),
    val personaIds: Set<String> = emptySet(),
    val localeCodes: Set<String> = emptySet(),
    val generatorIds: Set<String> = emptySet(),
    val navigableForms: Set<String> = emptySet(),
    val hasNavigator: Boolean = false,
)

/**
 * The scenario list QA sees. Built from the same packs the developer panel and
 * Compose tests use, so nothing has a private scenario source.
 */
class FillScenarioCatalog private constructor(val entries: List<FillScenarioCatalogEntry>) {

    val forms: List<String> = entries.mapNotNull(FillScenarioCatalogEntry::targetForm).distinct().sorted()
    val tags: List<String> = entries.flatMap(FillScenarioCatalogEntry::tags).distinct().sorted()
    val packIds: List<String> = entries.mapNotNull(FillScenarioCatalogEntry::packId).distinct().sorted()

    fun find(scenarioId: String): FillScenarioCatalogEntry? = entries.firstOrNull { it.id == scenarioId }

    /** Local, allocation-light, matches name, id, form, tags, category and description. */
    fun search(
        query: String = "",
        form: String? = null,
        packId: String? = null,
        tag: String? = null,
        localeCompatibleWith: Set<String>? = null,
    ): List<FillScenarioCatalogEntry> {
        val terms = query.trim().lowercase().split(' ').filter(String::isNotEmpty)
        return entries.filter { entry ->
            (form == null || entry.targetForm == form) &&
                (packId == null || entry.packId == packId) &&
                (tag == null || tag in entry.tags) &&
                (localeCompatibleWith == null || entry.localeCompatible(localeCompatibleWith)) &&
                terms.all { term -> entry.haystack().contains(term) }
        }
    }

    private fun FillScenarioCatalogEntry.localeCompatible(available: Set<String>): Boolean =
        issues.none { it.kind == FillCatalogIssueKind.MissingLocalePack } || available.isNotEmpty()

    companion object {
        fun build(input: FillCatalogInput): FillScenarioCatalog {
            val packed = input.packs.flatMap { kit -> kit.scenarioPacks } + input.scenarioPacks
            val entries = buildList {
                packed.forEach { pack ->
                    pack.scenarios.forEach { scenario ->
                        add(entry(scenario, pack.id, pack.name, pack.version, input))
                    }
                }
                input.scenarios.forEach { scenario -> add(entry(scenario, null, null, null, input)) }
            }
            return FillScenarioCatalog(entries.distinctBy { "${it.packId}/${it.id}" })
        }

        private fun entry(
            scenario: FillScenario,
            packId: String?,
            packName: String?,
            packVersion: String?,
            input: FillCatalogInput,
        ): FillScenarioCatalogEntry {
            val issues = buildList {
                if (scenario.targetForm == null) {
                    add(
                        FillCatalogIssue(
                            FillCatalogIssueKind.MissingTargetForm,
                            "Scenario declares no target form; choose one before launching.",
                            blocking = false,
                        ),
                    )
                } else if (input.navigableForms.isNotEmpty() && scenario.targetForm !in input.navigableForms) {
                    add(
                        FillCatalogIssue(
                            FillCatalogIssueKind.UnreachableForm,
                            "The application navigator does not declare form \"${scenario.targetForm}\".",
                            blocking = true,
                        ),
                    )
                }
                if (!input.hasNavigator) {
                    add(
                        FillCatalogIssue(
                            FillCatalogIssueKind.NoNavigator,
                            "No FillKit navigation adapter is configured; the screen will not be opened for you.",
                            blocking = false,
                        ),
                    )
                }
                scenario.personaId?.let { personaId ->
                    if (input.personaIds.isNotEmpty() && personaId !in input.personaIds) {
                        add(
                            FillCatalogIssue(
                                FillCatalogIssueKind.UnknownPersona,
                                "Missing persona: $personaId",
                                blocking = true,
                            ),
                        )
                    }
                }
                scenario.generators.values
                    .filterIsInstance<FillScenarioGenerator.Registered>()
                    .map(FillScenarioGenerator.Registered::generatorId)
                    .distinct()
                    .forEach { generatorId ->
                        if (input.generatorIds.isNotEmpty() && generatorId !in input.generatorIds) {
                            add(
                                FillCatalogIssue(
                                    FillCatalogIssueKind.UnknownGenerator,
                                    "Missing generator pack entry: $generatorId",
                                    blocking = true,
                                ),
                            )
                        }
                    }
            }
            return FillScenarioCatalogEntry(scenario, packId, packName, packVersion, issues)
        }
    }
}
