package io.devkit.netkit.scenario.pack

import io.devkit.netkit.scenario.EndpointMatcher
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.GlobalNetworkConfig
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.MalformedResponseType
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.ResponseHeader
import io.devkit.netkit.scenario.SequenceCompletionBehavior
import io.devkit.netkit.scenario.SequenceStep
import io.devkit.netkit.scenario.TimeoutType
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.model.ScenarioId
import io.devkit.netkit.scenario.model.ScenarioMetadata
import io.devkit.netkit.scenario.model.ScenarioPack
import io.devkit.netkit.scenario.model.ScenarioPackId
import io.devkit.netkit.scenario.model.ScenarioSource
import io.devkit.netkit.scenario.model.netKitSlug

/**
 * A pack declared in application code, together with its scenarios.
 *
 * Built-in packs are **code**, not data: they are re-declared on every launch
 * and never written to the scenario store. That is deliberate — persisting them
 * would leave a QA engineer looking at a stale copy of a scenario the app no
 * longer defines, with no way to tell the two apart. Users activate them, or
 * duplicate them into an ordinary editable scenario.
 */
data class BuiltInScenarioPack(
    val pack: ScenarioPack,
    val scenarios: List<NetworkScenario>,
)

/** Marks the scenario-pack DSL so nested builders cannot leak into each other. */
@DslMarker
annotation class ScenarioPackDsl

/**
 * Declares a pack of scenarios from application code.
 *
 * ```kotlin
 * val checkout = scenarioPack("Checkout") {
 *     description("Everything that can go wrong at payment time")
 *
 *     scenario("Gateway unavailable") {
 *         post("/api/v1/checkout") { respond(503) }
 *     }
 *
 *     scenario("Retry eventually succeeds") {
 *         post("/api/v1/checkout") {
 *             sequence {
 *                 respond(500)
 *                 respond(500)
 *                 respond(200, body = """{"orderId":"o-1"}""")
 *             }
 *         }
 *     }
 * }
 *
 * NetKit.create(NetKitConfig(builtInPacks = listOf(checkout)))
 * ```
 *
 * Ids are derived from [name] and each scenario's name, so they stay stable
 * across app builds and an activated built-in scenario survives a restart.
 *
 * @param name what the console groups these scenarios under.
 * @param key an explicit id fragment, when two packs share a display name.
 */
fun scenarioPack(
    name: String,
    key: String = name,
    build: ScenarioPackBuilder.() -> Unit,
): BuiltInScenarioPack = ScenarioPackBuilder(name, key).apply(build).build()

/** Builds one [BuiltInScenarioPack]. See [scenarioPack]. */
@ScenarioPackDsl
class ScenarioPackBuilder internal constructor(
    private val name: String,
    private val key: String,
) {
    private var description: String? = null
    private val scenarios = mutableListOf<NetworkScenario>()

    /** A sentence explaining what the pack covers. */
    fun description(text: String) {
        description = text
    }

    /** Adds one scenario. */
    fun scenario(
        name: String,
        key: String = name,
        build: ScenarioBuilder.() -> Unit,
    ) {
        scenarios += ScenarioBuilder(name, this.key, key).apply(build).build()
    }

    /** Adds an already-built scenario, re-homed into this pack. */
    fun scenario(scenario: NetworkScenario) {
        scenarios += scenario.copy(
            metadata = scenario.metadata.copy(
                source = ScenarioSource.BUILT_IN,
                packId = ScenarioPackId.builtIn(key),
            ),
        )
    }

    internal fun build(): BuiltInScenarioPack {
        val packId = ScenarioPackId.builtIn(key)
        return BuiltInScenarioPack(
            pack = ScenarioPack(
                id = packId,
                name = name,
                description = description,
                source = ScenarioSource.BUILT_IN,
            ),
            scenarios = scenarios.map {
                it.copy(metadata = it.metadata.copy(packId = packId))
            },
        )
    }
}

/** Builds one scenario inside a [scenarioPack]. */
@ScenarioPackDsl
class ScenarioBuilder internal constructor(
    private val name: String,
    private val packKey: String,
    private val key: String,
) {
    private var description: String? = null
    private var global: GlobalNetworkConfig? = null
    private val rules = mutableListOf<EndpointRule>()

    /** A sentence explaining what this scenario reproduces. */
    fun description(text: String) {
        description = text
    }

    /**
     * Pins the global network while this scenario is active.
     *
     * Leave it unset for a rules-only scenario, so the console's own offline and
     * latency switches keep working alongside it.
     */
    fun global(mode: GlobalNetworkMode = GlobalNetworkMode.Normal, latencyMillis: Long = 0) {
        global = GlobalNetworkConfig(mode, latencyMillis)
    }

    /** Shorthand for `global(latencyMillis = ...)`. */
    fun latency(millis: Long) = global(GlobalNetworkMode.Normal, millis)

    /** Shorthand for a scenario that takes the whole app offline. */
    fun offline() = global(GlobalNetworkMode.Offline)

    fun get(path: String, build: RuleBuilder.() -> Unit) = rule(HttpMethod.GET, path, build)
    fun post(path: String, build: RuleBuilder.() -> Unit) = rule(HttpMethod.POST, path, build)
    fun put(path: String, build: RuleBuilder.() -> Unit) = rule(HttpMethod.PUT, path, build)
    fun patch(path: String, build: RuleBuilder.() -> Unit) = rule(HttpMethod.PATCH, path, build)
    fun delete(path: String, build: RuleBuilder.() -> Unit) = rule(HttpMethod.DELETE, path, build)

    /** A rule matching any method on [path]. */
    fun any(path: String, build: RuleBuilder.() -> Unit) = rule(HttpMethod.ANY, path, build)

    /** A rule scoped to [method] on [path]. */
    fun rule(method: HttpMethod, path: String, build: RuleBuilder.() -> Unit) {
        val builder = RuleBuilder().apply(build)
        rules += EndpointRule(
            id = ruleId(method, path, rules.size),
            name = builder.name,
            enabled = builder.enabled,
            method = method,
            matcher = EndpointMatcher.ExactPath(path),
            action = builder.build(),
        )
    }

    /**
     * A stable, readable rule id.
     *
     * Sequence progress is keyed on rule id, so a built-in rule that changed id
     * on every launch would silently reset its own cursor.
     */
    private fun ruleId(method: HttpMethod, path: String, index: Int): String =
        "builtin:${packKey.netKitSlug()}/${key.netKitSlug()}/$index/" +
            "${method.name.lowercase()}-${path.netKitSlug()}"

    internal fun build(): NetworkScenario = NetworkScenario(
        id = ScenarioId.builtIn(packKey, key),
        name = name,
        description = description,
        globalConfig = global,
        rules = rules.toList(),
        metadata = ScenarioMetadata(
            source = ScenarioSource.BUILT_IN,
            packId = ScenarioPackId.builtIn(packKey),
        ),
    )
}

/** Builds one endpoint rule's behaviour. Exactly one behaviour may be set. */
@ScenarioPackDsl
class RuleBuilder internal constructor() {

    internal var name: String? = null
    internal var enabled: Boolean = true
    private var action: NetworkAction? = null

    /** An optional label, shown in history as the responsible rule. */
    fun name(text: String) {
        name = text
    }

    /** Adds the rule but leaves it switched off until someone enables it. */
    fun disabled() {
        enabled = false
    }

    /** Let this endpoint through even while the scenario changes everything else. */
    fun passThrough() = set(NetworkAction.PassThrough)

    /** Delay, then reach the real server. */
    fun delay(millis: Long) = set(NetworkAction.Delay(millis))

    /** Return a simulated response. Any status in `100..599`. */
    fun respond(
        statusCode: Int,
        body: String? = null,
        contentType: String = NetworkAction.ReturnResponse.DEFAULT_CONTENT_TYPE,
        headers: List<ResponseHeader> = emptyList(),
        delayMillis: Long = 0,
    ) = set(
        NetworkAction.ReturnResponse(statusCode, body, contentType, headers, delayMillis),
    )

    /** Return a deliberately unparseable response. */
    fun malformed(
        type: MalformedResponseType,
        statusCode: Int = 200,
        delayMillis: Long = 0,
    ) = set(NetworkAction.Malformed(type, statusCode, delayMillis))

    /** Fail as a device with no connectivity would. */
    fun offline() = set(NetworkAction.Offline)

    /** Fail with a simulated socket timeout. */
    fun timeout(type: TimeoutType = TimeoutType.READ) = set(NetworkAction.Timeout(type))

    /**
     * Behave differently on each successive request.
     *
     * ```kotlin
     * sequence {
     *     respond(500)
     *     respond(500)
     *     respond(200)
     * }
     * ```
     */
    fun sequence(
        completion: SequenceCompletionBehavior = SequenceCompletionBehavior.REPEAT_LAST,
        build: SequenceBuilder.() -> Unit,
    ) = set(NetworkAction.Sequence(SequenceBuilder().apply(build).build(), completion))

    private fun set(next: NetworkAction) {
        check(action == null) {
            "A NetKit rule has one behaviour; '${action?.label}' was already set."
        }
        action = next
    }

    internal fun build(): NetworkAction = checkNotNull(action) {
        "A NetKit rule needs a behaviour — call respond(), timeout(), sequence(), and so on."
    }
}

/** Builds the ordered steps of a response sequence. */
@ScenarioPackDsl
class SequenceBuilder internal constructor() {

    private val steps = mutableListOf<SequenceStep>()

    fun passThrough() = add(NetworkAction.PassThrough)

    fun delay(millis: Long) = add(NetworkAction.Delay(millis))

    fun respond(
        statusCode: Int,
        body: String? = null,
        contentType: String = NetworkAction.ReturnResponse.DEFAULT_CONTENT_TYPE,
        headers: List<ResponseHeader> = emptyList(),
        delayMillis: Long = 0,
    ) = add(NetworkAction.ReturnResponse(statusCode, body, contentType, headers, delayMillis))

    fun malformed(
        type: MalformedResponseType,
        statusCode: Int = 200,
        delayMillis: Long = 0,
    ) = add(NetworkAction.Malformed(type, statusCode, delayMillis))

    fun offline() = add(NetworkAction.Offline)

    fun timeout(type: TimeoutType = TimeoutType.READ) = add(NetworkAction.Timeout(type))

    private fun add(action: NetworkAction) {
        steps += SequenceStep(action)
    }

    internal fun build(): List<SequenceStep> = steps.toList()
}
