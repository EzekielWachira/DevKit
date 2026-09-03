package io.devkit.netkit.scenario.pack

import io.devkit.netkit.scenario.EndpointMatcher
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.GlobalNetworkConfig
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.LatencyRange
import io.devkit.netkit.scenario.MalformedResponseType
import io.devkit.netkit.scenario.Probability
import io.devkit.netkit.scenario.WeightedOutcome
import io.devkit.netkit.scenario.chaos.ChaosConfig
import io.devkit.netkit.scenario.chaos.ChaosExclusions
import io.devkit.netkit.scenario.chaos.ChaosPreset
import io.devkit.netkit.scenario.chaos.ChaosScope
import io.devkit.netkit.scenario.condition.BodyCondition
import io.devkit.netkit.scenario.condition.HeaderCondition
import io.devkit.netkit.scenario.condition.QueryParameterCondition
import io.devkit.netkit.scenario.condition.RequestCountCondition
import io.devkit.netkit.scenario.condition.RuleCondition
import io.devkit.netkit.scenario.condition.StringMatch
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
    private var chaos: ChaosConfig? = null
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

    /**
     * Adds application-layer instability across this scenario.
     *
     * ```kotlin
     * scenario("Poor network") {
     *     chaos {
     *         failureRate(0.15)
     *         latency(500, 3_000)
     *         exclude("/api/v1/auth/refresh")
     *         outcomes {
     *             http(500, weight = 3)
     *             http(503, weight = 3)
     *             timeout(weight = 2)
     *             disconnect(weight = 2)
     *         }
     *     }
     * }
     * ```
     *
     * Every draw comes from the run's seed, so the same seed reproduces the same
     * failures. See [io.devkit.netkit.scenario.chaos.ChaosConfig].
     */
    fun chaos(build: ChaosBuilder.() -> Unit) {
        chaos = ChaosBuilder().apply(build).build()
    }

    /** Applies a chaos preset, optionally adjusted. */
    fun chaos(preset: ChaosPreset, adjust: (ChaosConfig) -> ChaosConfig = { it }) {
        chaos = adjust(preset.config)
    }

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
            conditions = builder.conditions.toList(),
            probability = builder.probability,
            action = builder.build(),
        )
    }

    /**
     * A rule matching every path under [prefix].
     *
     * ```kotlin
     * prefix("/api/v1") { respond(401) }
     * ```
     */
    fun prefix(
        prefix: String,
        method: HttpMethod = HttpMethod.ANY,
        build: RuleBuilder.() -> Unit,
    ) {
        val builder = RuleBuilder().apply(build)
        rules += EndpointRule(
            id = ruleId(method, prefix, rules.size),
            name = builder.name,
            enabled = builder.enabled,
            method = method,
            matcher = EndpointMatcher.PathPrefix(prefix),
            conditions = builder.conditions.toList(),
            probability = builder.probability,
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
        chaos = chaos,
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
    internal var probability: Probability = Probability.ALWAYS
    internal val conditions = mutableListOf<RuleCondition>()
    private var action: NetworkAction? = null

    /** An optional label, shown in history as the responsible rule. */
    fun name(text: String) {
        name = text
    }

    /** Adds the rule but leaves it switched off until someone enables it. */
    fun disabled() {
        enabled = false
    }

    // ---- conditions and probability ----------------------------------------

    /**
     * The chance this rule acts once its conditions have passed.
     *
     * ```kotlin
     * get("/api/v1/bookings") {
     *     probability(0.3)
     *     respond(500)
     * }
     * ```
     *
     * A rule that fails its draw does not end evaluation — the next rule is
     * tried — so a 30% failure above a plain delay gives 30% failures and 70%
     * slow requests.
     */
    fun probability(chance: Double) {
        probability = Probability(chance)
    }

    /** `probabilityPercent(30)` reads better than `probability(0.3)` at some call sites. */
    fun probabilityPercent(percent: Int) {
        probability = Probability.ofPercent(percent)
    }

    /** Adds a condition. All conditions on a rule must hold. */
    fun where(condition: RuleCondition) {
        conditions += condition
    }

    /** Only the first request that reaches this rule. */
    fun firstRequestOnly() = where(RequestCountCondition.Exactly(1))

    /** Only the [index]-th request that reaches this rule. */
    fun onRequest(index: Long) = where(RequestCountCondition.Exactly(index))

    /** Requests [from] to [to] inclusive. */
    fun onRequests(from: Long, to: Long) = where(RequestCountCondition.Range(from, to))

    /** Every [interval]-th request. */
    fun everyNthRequest(interval: Long) = where(RequestCountCondition.Every(interval))

    /** A query parameter must equal [value]. */
    fun whereQuery(name: String, value: String) =
        where(QueryParameterCondition(name, StringMatch.EQUALS, value))

    /** A query parameter must be present, whatever its value. */
    fun whereQueryExists(name: String) =
        where(QueryParameterCondition(name, StringMatch.EXISTS))

    /**
     * A request header must be present.
     *
     * Prefer this to matching a header's literal value: `Authorization exists`
     * says "this is an authenticated call" without storing a credential in the
     * scenario. Use [whereHeader] only for non-secret headers such as an app
     * version.
     */
    fun whereHeaderExists(name: String) = where(HeaderCondition(name, StringMatch.EXISTS))

    /** A request header must equal [value]. */
    fun whereHeader(name: String, value: String) =
        where(HeaderCondition(name, StringMatch.EQUALS, value))

    /**
     * The request body must contain [text].
     *
     * Only evaluated for bodies NetKit can read safely — see [BodyCondition].
     * When it cannot, the condition does not match and the rule does not fire.
     */
    fun whereBodyContains(text: String) = where(BodyCondition(text))

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

    /** Fail as a dropped connection does, with an `IOException`. */
    fun disconnect() = set(NetworkAction.Disconnect)

    /** Fail with a simulated socket timeout. */
    fun timeout(type: TimeoutType = TimeoutType.READ) = set(NetworkAction.Timeout(type))

    /**
     * Delay by a value drawn from a range, then reach the real server.
     *
     * Deterministic for a given seed and evaluation index. A range whose ends
     * agree behaves exactly like [delay].
     */
    fun delayBetween(minMillis: Long, maxMillis: Long) =
        set(NetworkAction.RandomDelay(LatencyRange(minMillis, maxMillis)))

    /**
     * Choose one of several behaviours at random, by relative weight.
     *
     * ```kotlin
     * post("/api/v1/checkout") {
     *     outcomes {
     *         passThrough(weight = 60)
     *         http(500, weight = 15)
     *         http(503, weight = 10)
     *         timeout(weight = 10)
     *         disconnect(weight = 5)
     *     }
     * }
     * ```
     *
     * Weights are relative and normalised, so they need not sum to 100.
     */
    fun outcomes(build: OutcomesBuilder.() -> Unit) =
        set(NetworkAction.Weighted(OutcomesBuilder().apply(build).build()))

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

    fun disconnect() = add(NetworkAction.Disconnect)

    fun timeout(type: TimeoutType = TimeoutType.READ) = add(NetworkAction.Timeout(type))

    private fun add(action: NetworkAction) {
        steps += SequenceStep(action)
    }

    internal fun build(): List<SequenceStep> = steps.toList()
}

/**
 * Builds a set of weighted outcomes, for [RuleBuilder.outcomes] and
 * [ChaosBuilder.outcomes].
 *
 * One builder for both, so a rule's random branch and a chaos failure mix are
 * written the same way and cannot drift apart.
 */
@ScenarioPackDsl
class OutcomesBuilder internal constructor() {

    private val outcomes = mutableListOf<WeightedOutcome>()

    /** The request reaches the real server. Not valid inside chaos. */
    fun passThrough(weight: Int = 1) = add(weight, NetworkAction.PassThrough)

    /** A simulated HTTP response. */
    fun http(
        statusCode: Int,
        weight: Int = 1,
        body: String? = null,
        delayMillis: Long = 0,
    ) = add(weight, NetworkAction.ReturnResponse(statusCode, body, delayMillis = delayMillis))

    /** A simulated socket timeout. */
    fun timeout(weight: Int = 1, type: TimeoutType = TimeoutType.READ) =
        add(weight, NetworkAction.Timeout(type))

    /** A simulated dropped connection. */
    fun disconnect(weight: Int = 1) = add(weight, NetworkAction.Disconnect)

    /** A simulated loss of connectivity. */
    fun offline(weight: Int = 1) = add(weight, NetworkAction.Offline)

    /** A fixed delay, then the real server. */
    fun delay(millis: Long, weight: Int = 1) = add(weight, NetworkAction.Delay(millis))

    /** A deliberately unparseable response. */
    fun malformed(
        type: MalformedResponseType,
        weight: Int = 1,
        statusCode: Int = 200,
    ) = add(weight, NetworkAction.Malformed(type, statusCode))

    private fun add(weight: Int, action: NetworkAction) {
        outcomes += WeightedOutcome(weight, action)
    }

    internal fun build(): List<WeightedOutcome> {
        check(outcomes.isNotEmpty()) {
            "NetKit weighted outcomes need at least one entry — call http(), timeout(), and so on."
        }
        return outcomes.toList()
    }
}

/**
 * Builds a scenario's chaos configuration. See [ScenarioBuilder.chaos].
 *
 * Defaults are the safe ones: chaos is enabled (you asked for it by opening this
 * block), the failure mix is NetKit's standard one, and the scope is everything.
 * The setting most worth changing is [exclude] — see
 * [io.devkit.netkit.scenario.chaos.ChaosExclusions] for why.
 */
@ScenarioPackDsl
class ChaosBuilder internal constructor() {

    private var failureProbability: Probability = Probability(0.1)
    private var latency: LatencyRange = LatencyRange.NONE
    private var failures: List<WeightedOutcome> = ChaosConfig.defaultFailures
    private var hosts: List<String> = emptyList()
    private var prefixes: List<String> = emptyList()
    private var methods: List<HttpMethod> = emptyList()
    private val exclusions = mutableListOf<String>()

    /** The chance an in-scope request fails outright, `0.0`–`1.0`. */
    fun failureRate(chance: Double) {
        failureProbability = Probability(chance)
    }

    /** `failurePercent(15)` reads better than `failureRate(0.15)` at some call sites. */
    fun failurePercent(percent: Int) {
        failureProbability = Probability.ofPercent(percent)
    }

    /** Delay applied to in-scope requests that do not fail. */
    fun latency(minMillis: Long, maxMillis: Long = minMillis) {
        latency = LatencyRange(minMillis, maxMillis)
    }

    /** The failures to choose between. Replaces the default mix. */
    fun outcomes(build: OutcomesBuilder.() -> Unit) {
        failures = OutcomesBuilder().apply(build).build()
    }

    /** Restrict chaos to these hosts. Empty — the default — means every host. */
    fun hosts(vararg host: String) {
        hosts = host.toList()
    }

    /** Restrict chaos to paths under these prefixes. Empty means every path. */
    fun paths(vararg prefix: String) {
        prefixes = prefix.toList()
    }

    /** Restrict chaos to these verbs. Empty means every verb. */
    fun methods(vararg method: HttpMethod) {
        methods = method.toList()
    }

    /**
     * Endpoints chaos must never touch, whatever the scope says.
     *
     * Reach for this before turning the failure rate up. Chaos across a whole app
     * also breaks its token refresh and its crash reporter, and the logout loop
     * that follows looks exactly like the bug you were hunting.
     */
    fun exclude(vararg pathPrefix: String) {
        exclusions += pathPrefix
    }

    /** Excludes the endpoints almost every app wants spared. */
    fun excludeRecommended() {
        exclusions += ChaosExclusions.recommended
    }

    internal fun build(): ChaosConfig = ChaosConfig(
        enabled = true,
        failureProbability = failureProbability,
        latency = latency,
        failures = failures,
        scope = ChaosScope(hosts = hosts, pathPrefixes = prefixes, methods = methods),
        exclusions = ChaosExclusions(exclusions.toList()),
    )
}
