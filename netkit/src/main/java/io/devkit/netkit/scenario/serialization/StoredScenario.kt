package io.devkit.netkit.scenario.serialization

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

@Serializable
internal data class StoredRule(
    val id: String,
    val name: String? = null,
    val enabled: Boolean = true,
    val method: String = "ANY",
    val matcher: StoredMatcher,
    val action: StoredAction,
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
)

/** Constants that define the NetKit scenario format. */
object ScenarioSchema {

    /** The value of the `format` field in every exported file. */
    const val FORMAT: String = "netkit"

    /** The schema version this NetKit reads and writes. */
    const val CURRENT_VERSION: Int = 1

    /** The oldest schema version this NetKit can migrate forward from. */
    const val MIN_SUPPORTED_VERSION: Int = 1

    /** `type` for a single-scenario export. */
    const val TYPE_SCENARIO: String = "scenario"

    /** `type` for a whole-pack export. */
    const val TYPE_PACK: String = "scenario-pack"
}
