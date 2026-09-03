package io.devkit.netkit.scenario.serialization

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The on-disk and on-the-wire shape of a scenario.
 *
 * Deliberately **not** the domain model. Domain types carry behaviour, defaults,
 * `require` blocks and value classes that are all good for a running scenario
 * and bad for a format that has to survive schema changes; these types carry
 * nothing but data and explicit `@SerialName`s. A mapper sits between them
 * ([ScenarioStorageMapper]), which is what makes a future migration a change to
 * one file rather than a change to the model everything else depends on.
 *
 * ```text
 * NetworkScenario  ←── ScenarioStorageMapper ──→  StoredScenario  ──→ JSON
 * ```
 */
@Serializable
internal data class StoredScenario(
    val id: String,
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val global: StoredGlobal? = null,
    val rules: List<StoredRule> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val source: String = "created",
    val packId: String? = null,
    /** Schema 2. Absent in every schema-1 file, which is what the default is for. */
    val chaos: StoredChaos? = null,
    /** Schema 2. Provenance only; the rules remain the source of truth. */
    val preset: StoredPresetOrigin? = null,
)

/** Schema 2. Which preset generated a scenario. Never consulted at runtime. */
@Serializable
internal data class StoredPresetOrigin(
    val id: String,
    val name: String,
    val configuration: Map<String, String> = emptyMap(),
)

/** Schema 2. Chaos mode as written down. */
@Serializable
internal data class StoredChaos(
    val enabled: Boolean = false,
    val failureProbability: Double = 0.0,
    val minLatencyMillis: Long = 0,
    val maxLatencyMillis: Long = 0,
    val failures: List<StoredWeightedOutcome> = emptyList(),
    val hosts: List<String> = emptyList(),
    val pathPrefixes: List<String> = emptyList(),
    val methods: List<String> = emptyList(),
    val excludedPathPrefixes: List<String> = emptyList(),
)

/** Schema 2. One weighted branch of a random choice. */
@Serializable
internal data class StoredWeightedOutcome(
    val weight: Int,
    val action: StoredAction,
)

/**
 * Schema 2. A condition on a rule.
 *
 * Polymorphic like [StoredMatcher] and [StoredAction], so a 0.4 condition kind is
 * a new subtype rather than a migration. An unknown kind is a hard error on
 * import: a condition silently dropped would widen a rule from "page 2 only" to
 * "every page", and a scenario that reproduces the wrong bug is worse than one
 * that refuses to load.
 */
@Serializable
internal sealed interface StoredCondition {

    @Serializable
    @SerialName("requestCount")
    data class RequestCount(
        /** `exactly`, `atLeast`, `range` or `every`. */
        val kind: String,
        val from: Long = 1,
        val to: Long = 1,
        val interval: Long = 1,
    ) : StoredCondition

    @Serializable
    @SerialName("header")
    data class Header(
        val name: String,
        val match: String = "exists",
        val value: String = "",
    ) : StoredCondition

    @Serializable
    @SerialName("query")
    data class Query(
        val name: String,
        val match: String = "equals",
        val value: String = "",
    ) : StoredCondition

    @Serializable
    @SerialName("body")
    data class Body(
        val text: String,
        val jsonField: String? = null,
    ) : StoredCondition

    @Serializable
    @SerialName("previousResult")
    data class PreviousResult(
        val requirement: String? = null,
        val ruleId: String? = null,
        val ruleLabel: String? = null,
        val minimumHits: Long = 1,
    ) : StoredCondition

    @Serializable
    @SerialName("allOf")
    data class AllOf(val conditions: List<StoredCondition>) : StoredCondition

    @Serializable
    @SerialName("anyOf")
    data class AnyOf(val conditions: List<StoredCondition>) : StoredCondition
}

/** Schema 2. One event of an exported reproduction trace. */
@Serializable
internal data class StoredTraceEvent(
    val index: Long,
    val type: String,
    val method: String? = null,
    val path: String? = null,
    val rule: String? = null,
    val detail: String? = null,
    val reason: String? = null,
)

/**
 * Schema 2. The run metadata a reproduction carries.
 *
 * Counters and identity only. Nothing here is derived from request or response
 * content, which is what makes a reproduction safe to attach without review.
 */
@Serializable
internal data class StoredRun(
    val runId: String,
    val seed: Long,
    val startedAt: String? = null,
    val scenarioName: String? = null,
    val evaluationCount: Long = 0,
    val simulatedCount: Long = 0,
)

@Serializable
internal data class StoredPack(
    val id: String,
    val name: String,
    val description: String? = null,
    val source: String = "created",
)

@Serializable
internal data class StoredGlobal(
    val mode: String = "normal",
    /** Only meaningful when [mode] is `timeout`. */
    val timeoutType: String? = null,
    val latencyMillis: Long = 0,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class StoredRule(
    val id: String,
    val name: String? = null,
    val enabled: Boolean = true,
    val method: String = "ANY",
    val matcher: StoredMatcher,
    val action: StoredAction,
    /**
     * Schema 2. Absent in a schema-1 file, meaning "no conditions".
     *
     * Omitted from the file when empty — the rest of the document writes its
     * defaults, but a stray `"conditions": []` on every rule would make every
     * scenario in a repository show a diff on the upgrade to 0.3 while nothing
     * about its behaviour had changed.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val conditions: List<StoredCondition> = emptyList(),
    /**
     * Schema 2. Absent in a schema-1 file, meaning "always fires" — which is
     * exactly what a schema-1 rule did, so an old scenario needs no rewriting.
     */
    val probability: Double? = null,
)

/**
 * How a rule claims a request.
 *
 * Polymorphic from the start even though 0.2 ships one kind, because prefix,
 * regex and host matchers are the obvious next additions and a non-polymorphic
 * format would need a migration to accept them.
 */
@Serializable
internal sealed interface StoredMatcher {

    @Serializable
    @SerialName("exactPath")
    data class ExactPath(val path: String) : StoredMatcher

    /** Schema 2. Every path under a prefix. */
    @Serializable
    @SerialName("pathPrefix")
    data class PathPrefix(val prefix: String) : StoredMatcher
}

@Serializable
internal data class StoredHeader(val name: String, val value: String)

/**
 * What a rule does.
 *
 * The discriminator is the JSON field `type`, so an unknown action from a newer
 * NetKit fails with a clear "unknown action type" rather than silently
 * deserialising into something harmless.
 */
@Serializable
internal sealed interface StoredAction {

    @Serializable
    @SerialName("passThrough")
    data object PassThrough : StoredAction

    @Serializable
    @SerialName("delay")
    data class Delay(val delayMillis: Long) : StoredAction

    @Serializable
    @SerialName("respond")
    data class Respond(
        val statusCode: Int,
        val body: String? = null,
        val contentType: String = "application/json",
        val headers: List<StoredHeader> = emptyList(),
        val delayMillis: Long = 0,
    ) : StoredAction

    @Serializable
    @SerialName("malformed")
    data class Malformed(
        /** A built-in kind, or `custom` when [customBody] is set. */
        val kind: String,
        val statusCode: Int = 200,
        val delayMillis: Long = 0,
        val customLabel: String? = null,
        val customBody: String? = null,
        val customContentType: String? = null,
    ) : StoredAction

    @Serializable
    @SerialName("offline")
    data object Offline : StoredAction

    @Serializable
    @SerialName("timeout")
    data class Timeout(val timeoutType: String) : StoredAction

    @Serializable
    @SerialName("sequence")
    data class Sequence(
        val steps: List<StoredAction>,
        val completion: String = "repeatLast",
    ) : StoredAction

    /** Schema 2. Fail the way a dropped connection does. */
    @Serializable
    @SerialName("disconnect")
    data object Disconnect : StoredAction

    /** Schema 2. Delay by a value drawn from a range. */
    @Serializable
    @SerialName("randomDelay")
    data class RandomDelay(
        val minMillis: Long,
        val maxMillis: Long,
    ) : StoredAction

    /** Schema 2. Choose one of several behaviours by relative weight. */
    @Serializable
    @SerialName("weighted")
    data class Weighted(val outcomes: List<StoredWeightedOutcome>) : StoredAction
}

/**
 * The document [io.devkit.netkit.scenario.persistence.ScenarioStorage] holds.
 *
 * Everything NetKit persists lives in one versioned envelope so a migration only
 * ever has to look at one number.
 */
@Serializable
internal data class StoredScenarioDocument(
    val schemaVersion: Int = ScenarioSchema.CURRENT_VERSION,
    val scenarios: List<StoredScenario> = emptyList(),
    val packs: List<StoredPack> = emptyList(),
    val activeScenarioId: String? = null,
)

/**
 * The envelope of an exported `.netkit.json`.
 *
 * `format` exists so a file that is merely *valid JSON* is still rejected: an
 * exported Postman collection or a random API response must not be read as a
 * scenario just because its shape happens to fit.
 */
@Serializable
internal data class StoredExportEnvelope(
    val format: String = ScenarioSchema.FORMAT,
    val schemaVersion: Int = ScenarioSchema.CURRENT_VERSION,
    val type: String,
    val exportedAt: String? = null,
    val generator: String? = null,
    val scenario: StoredScenario? = null,
    val pack: StoredPack? = null,
    /**
     * Nullable rather than defaulting to an empty list so a single-scenario
     * export does not carry a stray `"scenarios": []`. An exported file is read
     * by people as often as by NetKit.
     */
    val scenarios: List<StoredScenario>? = null,
    /** Schema 2. Present only in a reproduction export. */
    val run: StoredRun? = null,
    /** Schema 2. Present only in a reproduction export that carries a trace. */
    val trace: List<StoredTraceEvent>? = null,
)

/** Constants that define the NetKit scenario format. */
object ScenarioSchema {

    /** The value of the `format` field in every exported file. */
    const val FORMAT: String = "netkit"

    /**
     * The schema version this NetKit reads and writes.
     *
     * ```text
     * 1 → NetKit 0.1 and 0.2
     * 2 → NetKit 0.3: conditions, probability, weighted outcomes, chaos,
     *     random delay, disconnect, preset provenance, reproductions
     * ```
     */
    const val CURRENT_VERSION: Int = 2

    /**
     * The oldest schema version this NetKit can migrate forward from.
     *
     * Still 1: every scenario a QA team saved or exported under 0.1 or 0.2
     * imports into 0.3 unchanged. Every field version 2 added is optional with a
     * neutral default, so the migration has no data to rewrite — see
     * [ScenarioMigration1To2].
     */
    const val MIN_SUPPORTED_VERSION: Int = 1

    /** `type` for a single-scenario export. */
    const val TYPE_SCENARIO: String = "scenario"

    /** `type` for a whole-pack export. */
    const val TYPE_PACK: String = "scenario-pack"

    /**
     * `type` for a reproduction: a scenario plus the seed and trace of one run.
     *
     * A distinct `type` rather than a distinct file extension, so that a file
     * renamed on its way through a chat client or a ticket attachment is still
     * identified correctly. NetKit never decides what a file is from its name.
     */
    const val TYPE_REPRODUCTION: String = "reproduction"
}
