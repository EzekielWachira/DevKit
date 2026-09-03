package io.devkit.netkit.scenario.chaos

import io.devkit.netkit.config.NetKitLimits
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.LatencyRange
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.Probability
import io.devkit.netkit.scenario.RequestTarget
import io.devkit.netkit.scenario.TimeoutType
import io.devkit.netkit.scenario.WeightedOutcome

/**
 * Which requests chaos is allowed to touch.
 *
 * Scope is deliberately a small, closed set of the things a QA engineer actually
 * knows at configuration time — a host, a path prefix, a method. It is *not* a
 * general matcher: chaos is a blunt instrument by design, and an endpoint that
 * needs precise treatment should have an endpoint rule instead.
 *
 * An empty scope matches everything, which is the useful default for "make the
 * whole app flaky".
 *
 * @param hosts hostnames chaos applies to; empty means every host.
 * @param pathPrefixes path prefixes chaos applies to, e.g. `/api/v1`; empty means
 *   every path. A trailing `*` is accepted and ignored, because people type it.
 * @param methods verbs chaos applies to; empty means every verb.
 */
data class ChaosScope(
    val hosts: List<String> = emptyList(),
    val pathPrefixes: List<String> = emptyList(),
    val methods: List<HttpMethod> = emptyList(),
) {
    private val normalizedHosts: List<String> =
        hosts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private val normalizedPrefixes: List<String> =
        pathPrefixes.mapNotNull(::normalizePrefix)

    private val effectiveMethods: List<HttpMethod> =
        methods.filter { it != HttpMethod.ANY }

    /** True when this scope places no restriction at all. */
    val isEverything: Boolean
        get() = normalizedHosts.isEmpty() &&
            normalizedPrefixes.isEmpty() &&
            effectiveMethods.isEmpty()

    /** `api.example.com · /api/v1 · GET` — what the chaos screen shows. */
    val label: String
        get() = if (isEverything) {
            "All requests"
        } else {
            buildList {
                if (normalizedHosts.isNotEmpty()) add(normalizedHosts.joinToString(", "))
                if (normalizedPrefixes.isNotEmpty()) add(normalizedPrefixes.joinToString(", "))
                if (effectiveMethods.isNotEmpty()) {
                    add(effectiveMethods.joinToString(", ") { it.label })
                }
            }.joinToString(" · ")
        }

    /** True when [target] falls inside this scope. */
    fun matches(target: RequestTarget): Boolean {
        if (normalizedHosts.isNotEmpty() && target.host.lowercase() !in normalizedHosts) {
            return false
        }
        if (normalizedPrefixes.isNotEmpty() && !normalizedPrefixes.matchesPath(target.path)) {
            return false
        }
        if (effectiveMethods.isNotEmpty() && effectiveMethods.none { it.matches(target.method) }) {
            return false
        }
        return true
    }

    companion object {
        /** Every request on every host. */
        val Everything: ChaosScope = ChaosScope()

        /**
         * Normalises a user-typed prefix: adds the leading `/`, strips a trailing
         * `*` and a trailing `/`, and drops it entirely when it is blank.
         */
        internal fun normalizePrefix(raw: String): String? {
            var value = raw.trim()
            if (value.isEmpty()) return null
            if (value.endsWith("*")) value = value.dropLast(1)
            if (value.isEmpty() || value == "/") return null
            if (!value.startsWith("/")) value = "/$value"
            if (value.length > 1 && value.endsWith("/")) value = value.dropLast(1)
            return value
        }
    }
}

/**
 * True when [path] falls under any of these normalised prefixes.
 *
 * Matches on **path segment boundaries**, exactly as
 * [io.devkit.netkit.scenario.EndpointMatcher.PathPrefix] does: `/api/v1` claims
 * `/api/v1` and `/api/v1/bookings` but not `/api/v10/bookings`. A raw
 * `startsWith` would silently pull a neighbouring API version into a scenario's
 * blast radius, and the resulting bug report would name the wrong endpoint.
 *
 * Shared by scope and exclusions so the two cannot drift into disagreeing about
 * what a prefix means — which, for a pair where one overrides the other, would be
 * a particularly unpleasant kind of wrong.
 */
internal fun List<String>.matchesPath(path: String): Boolean {
    for (index in indices) {
        val prefix = this[index]
        if (!path.startsWith(prefix)) continue
        if (path.length == prefix.length || path[prefix.length] == '/') return true
    }
    return false
}

/**
 * Endpoints chaos must leave alone.
 *
 * The most important control on the chaos screen, and the one most likely to be
 * skipped: turning on 15% failures across an app also breaks its token refresh,
 * its crash reporter and its analytics, and the resulting logout loop looks
 * exactly like the bug you were hunting. Exclusions exist so a scenario can
 * destabilise the thing under test without destabilising the machinery you need
 * in order to observe it.
 *
 * An exclusion beats the scope: a path that is both in [ChaosScope.pathPrefixes]
 * and excluded is excluded.
 *
 * @param pathPrefixes path prefixes chaos never touches.
 */
data class ChaosExclusions(
    val pathPrefixes: List<String> = emptyList(),
) {
    private val normalized: List<String> = pathPrefixes.mapNotNull(ChaosScope::normalizePrefix)

    val isEmpty: Boolean get() = normalized.isEmpty()

    /** The normalised prefixes, for display and export. */
    val prefixes: List<String> get() = normalized

    val label: String get() = if (isEmpty) "None" else normalized.joinToString(", ")

    /** True when [target] must be left alone. */
    fun excludes(target: RequestTarget): Boolean = normalized.matchesPath(target.path)

    companion object {
        /** Nothing is excluded. */
        val None: ChaosExclusions = ChaosExclusions()

        /**
         * The endpoints almost every app wants excluded.
         *
         * Offered as a one-tap default in the chaos editor rather than applied
         * automatically: NetKit guessing at an app's auth path and silently
         * sparing it would be worse than asking.
         */
        val recommended: List<String> = listOf(
            "/auth/refresh",
            "/oauth/token",
            "/analytics",
            "/crash-reports",
            "/telemetry",
        )
    }
}

/**
 * Application-layer network instability, applied across a scope rather than one
 * endpoint at a time.
 *
 * ### What this is and is not
 *
 * NetKit operates inside an OkHttp interceptor. Chaos mode makes requests slow,
 * fail with a status, time out, or throw the exception a dropped connection
 * produces. It does **not** drop packets, degrade the radio, congest TCP or
 * interfere with DNS, and nothing in NetKit's vocabulary claims otherwise — the
 * terms used throughout are *request failure rate*, *simulated timeout*,
 * *simulated disconnect* and *latency*. A scenario that reproduces under chaos
 * has reproduced under a realistic application-layer approximation, which is
 * usually what a client bug needs; a genuinely transport-level problem needs a
 * network-level tool.
 *
 * ### Determinism
 *
 * Every draw chaos makes — whether a request fails, which failure it gets, how
 * long it is delayed — comes from the run's seeded stream keyed on the evaluation
 * index. The same seed and the same request ordering therefore produce the same
 * failures. See [io.devkit.netkit.scenario.random.RunRandomSource].
 *
 * ```kotlin
 * ChaosConfig(
 *     enabled = true,
 *     failureProbability = Probability(0.15),
 *     latency = LatencyRange(500, 3_000),
 *     failures = listOf(
 *         WeightedOutcome(3, NetworkAction.ReturnResponse(500)),
 *         WeightedOutcome(3, NetworkAction.ReturnResponse(503)),
 *         WeightedOutcome(2, NetworkAction.Timeout(TimeoutType.READ)),
 *         WeightedOutcome(2, NetworkAction.Disconnect),
 *     ),
 *     exclusions = ChaosExclusions(listOf("/auth/refresh")),
 * )
 * ```
 *
 * @param enabled the master switch. A disabled config keeps its settings so
 *   turning chaos back on does not mean re-entering them.
 * @param failureProbability the chance an in-scope request fails outright.
 * @param latency delay applied to in-scope requests that do **not** fail.
 *   [LatencyRange.NONE] adds nothing.
 * @param failures the failures to choose between, by relative weight. Must not be
 *   empty when [failureProbability] can fire, or chaos would have nothing to do.
 * @param scope which requests chaos may touch.
 * @param exclusions which requests it must not, whatever the scope says.
 */
data class ChaosConfig(
    val enabled: Boolean = false,
    val failureProbability: Probability = Probability(0.1),
    val latency: LatencyRange = LatencyRange.NONE,
    val failures: List<WeightedOutcome> = defaultFailures,
    val scope: ChaosScope = ChaosScope.Everything,
    val exclusions: ChaosExclusions = ChaosExclusions.None,
) {
    init {
        require(failures.size <= NetKitLimits.MAX_WEIGHTED_OUTCOMES) {
            "NetKit chaos cannot have more than ${NetKitLimits.MAX_WEIGHTED_OUTCOMES} " +
                "failure outcomes (was ${failures.size})"
        }
        require(failures.none { it.action is NetworkAction.PassThrough }) {
            "NetKit chaos failure outcomes cannot include pass-through — the failure rate " +
                "already decides how often a request is spared"
        }
    }

    /**
     * True when this configuration cannot change any request, so the engine can
     * skip it without drawing from the random stream.
     */
    val isIdle: Boolean
        get() = !enabled ||
            (latency.isZero && (failureProbability.isNever || failures.isEmpty()))

    /** True when this configuration can fail a request. */
    val canFail: Boolean get() = enabled && !failureProbability.isNever && failures.isNotEmpty()

    /** `15% · 500–3000ms` — the one-line summary shown on the console. */
    val summary: String
        get() = buildList {
            if (canFail) add("${failureProbability.percentLabel} failures")
            if (!latency.isZero) add(latency.label)
            if (isEmpty()) add("no effect")
        }.joinToString(" · ")

    /** True when [target] is in scope and not excluded. */
    fun applies(target: RequestTarget): Boolean =
        enabled && scope.matches(target) && !exclusions.excludes(target)

    companion object {

        /**
         * The failure mix chaos starts with: mostly server errors, with a timeout
         * and a disconnect in the mix because those are the two clients handle
         * worst and the two a `500`-only scenario never exercises.
         */
        val defaultFailures: List<WeightedOutcome> = listOf(
            WeightedOutcome(3, NetworkAction.ReturnResponse(500)),
            WeightedOutcome(3, NetworkAction.ReturnResponse(503)),
            WeightedOutcome(2, NetworkAction.Timeout(TimeoutType.READ)),
            WeightedOutcome(2, NetworkAction.Disconnect),
        )

        /** Chaos configured but switched off. */
        val Disabled: ChaosConfig = ChaosConfig()
    }
}

/**
 * Starting points for chaos, so nobody has to guess at a failure rate.
 *
 * These are **presets, not policy**: selecting one fills the chaos editor in and
 * the values stay editable. None of them is applied automatically, and NetKit
 * ships no globally-enabled chaos — a library that made an app flaky by existing
 * would deserve everything that followed.
 */
object ChaosPresets {

    /** Barely noticeable: catches ordering bugs without making the app unusable. */
    val mildInstability: ChaosPreset = ChaosPreset(
        id = "mild",
        name = "Mild instability",
        description = "A little latency and the occasional failure. Safe to leave on.",
        config = ChaosConfig(
            enabled = true,
            failureProbability = Probability(0.02),
            latency = LatencyRange(200, 800),
            failures = listOf(
                WeightedOutcome(2, NetworkAction.ReturnResponse(500)),
                WeightedOutcome(1, NetworkAction.ReturnResponse(503)),
            ),
        ),
    )

    /** A bad cell connection: the one most client bugs actually hide behind. */
    val poorMobileNetwork: ChaosPreset = ChaosPreset(
        id = "poor-mobile",
        name = "Poor mobile network",
        description = "Slow, and fails often enough to exercise every retry path.",
        config = ChaosConfig(
            enabled = true,
            failureProbability = Probability(0.08),
            latency = LatencyRange(800, 3_000),
            failures = listOf(
                WeightedOutcome(3, NetworkAction.Timeout(TimeoutType.READ)),
                WeightedOutcome(3, NetworkAction.ReturnResponse(503)),
                WeightedOutcome(2, NetworkAction.Disconnect),
            ),
        ),
    )

    /** Aggressive. For deliberately hunting a race, not for a normal session. */
    val veryUnstable: ChaosPreset = ChaosPreset(
        id = "very-unstable",
        name = "Very unstable",
        description = "One request in four fails. Expect the app to struggle.",
        config = ChaosConfig(
            enabled = true,
            failureProbability = Probability(0.25),
            latency = LatencyRange(500, 5_000),
            failures = ChaosConfig.defaultFailures,
        ),
    )

    /** Every preset, in the order the chaos screen offers them. */
    val all: List<ChaosPreset> = listOf(mildInstability, poorMobileNetwork, veryUnstable)

    /** The preset with [id], or `null`. */
    fun byId(id: String): ChaosPreset? = all.firstOrNull { it.id == id }
}

/** A named starting configuration for chaos mode. */
data class ChaosPreset(
    val id: String,
    val name: String,
    val description: String,
    val config: ChaosConfig,
)
