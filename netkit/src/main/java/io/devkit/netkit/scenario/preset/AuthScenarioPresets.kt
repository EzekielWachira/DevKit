package io.devkit.netkit.scenario.preset

import io.devkit.netkit.scenario.EndpointMatcher
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.SequenceCompletionBehavior
import io.devkit.netkit.scenario.SequenceStep
import io.devkit.netkit.scenario.TimeoutType
import io.devkit.netkit.scenario.condition.RequestCountCondition
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.model.ScenarioMetadata
import io.devkit.netkit.scenario.model.ScenarioPresetOrigin

/**
 * Presets for the authentication edge cases every app gets wrong at least once.
 *
 * ### What NetKit can and cannot do here
 *
 * NetKit simulates **network behaviour**. It does not know where an app keeps its
 * access token, cannot expire one, and will not touch a token store. What it can
 * do is make the network behave the way an expired token makes it behave — a
 * `401` on a protected call, a refresh endpoint that succeeds, fails, or hangs —
 * which is exactly the input the app's own auth logic is supposed to handle.
 *
 * That is also why refresh responses are **configurable**: NetKit has no idea
 * what shape your refresh payload takes, and inventing one would produce a
 * scenario that exercises your error path rather than your success path. The
 * wizard asks; the default is a plausible OAuth-shaped body you will want to
 * replace.
 *
 * ### Everything here compiles to ordinary rules
 *
 * Each preset produces `EndpointRule`s with conditions and sequences the rule
 * editor can display and change. Nothing in the interceptor knows the word
 * "auth". Open a generated scenario and you will find rules, not magic.
 */
object AuthScenarioPresets {

    // ---- shared configuration ----------------------------------------------

    private const val PROTECTED = "protectedPaths"
    private const val REFRESH_PATH = "refreshPath"
    private const val REFRESH_METHOD = "refreshMethod"
    private const val COUNT = "count"
    private const val REFRESH_BODY = "refreshBody"
    private const val DELAY = "delayMillis"

    /**
     * A refresh response shaped like the OAuth one most backends return.
     *
     * A placeholder, and labelled as one in the wizard. The token value is
     * obviously fake on purpose: a default that looked like a real token would
     * eventually be mistaken for one.
     */
    private const val DEFAULT_REFRESH_BODY =
        """{"access_token":"netkit-simulated-token","refresh_token":""" +
            """"netkit-simulated-refresh","token_type":"Bearer","expires_in":3600}"""

    private val protectedPathsField = PresetField(
        key = PROTECTED,
        label = "Protected paths",
        hint = "Everything under this prefix is treated as needing a token",
        default = "/api/v1",
    )

    private val refreshPathField = PresetField(
        key = REFRESH_PATH,
        label = "Refresh endpoint",
        hint = "Excluded from the 401 rule automatically",
        default = "/api/v1/auth/refresh",
    )

    private val refreshMethodField = PresetField(
        key = REFRESH_METHOD,
        label = "Refresh method",
        default = HttpMethod.POST.name,
        kind = PresetFieldKind.CHOICE,
        options = listOf(HttpMethod.POST.name, HttpMethod.GET.name, HttpMethod.PUT.name),
    )

    private val refreshBodyField = PresetField(
        key = REFRESH_BODY,
        label = "Refresh response body",
        hint = "NetKit cannot guess your token payload — paste the shape your app expects",
        default = DEFAULT_REFRESH_BODY,
        kind = PresetFieldKind.BODY,
    )

    /**
     * The rule that makes protected calls fail with a `401`.
     *
     * Two things make this correct rather than merely plausible. First it matches
     * a **prefix**, so it covers every protected endpoint rather than the handful
     * someone remembered to list. Second it is preceded by a pass-through rule on
     * the refresh endpoint — because a 401 rule that also caught `/auth/refresh`
     * would make refresh impossible, and the resulting infinite logout loop is a
     * NetKit bug masquerading as an app bug.
     *
     * @param count how many protected calls get a 401; `0` means every one.
     */
    private fun protectedRules(
        configuration: PresetConfiguration,
        count: Long,
    ): List<EndpointRule> {
        val refreshPath = configuration.text(REFRESH_PATH, "/api/v1/auth/refresh")
        val protectedPrefix = configuration.text(PROTECTED, "/api/v1")
        return listOf(
            // Ordered first: the engine takes the first rule that claims a
            // request, so this is what keeps refresh reachable.
            EndpointRule(
                name = "Refresh endpoint (excluded from 401s)",
                method = HttpMethod.ANY,
                matcher = EndpointMatcher.ExactPath(refreshPath),
                action = NetworkAction.PassThrough,
            ),
            EndpointRule(
                name = if (count > 0) "Protected paths → 401 (first $count)" else "Protected paths → 401",
                method = HttpMethod.ANY,
                matcher = EndpointMatcher.PathPrefix(protectedPrefix),
                conditions = if (count > 0) {
                    listOf(RequestCountCondition.Range(1, count))
                } else {
                    emptyList()
                },
                action = NetworkAction.ReturnResponse(
                    statusCode = 401,
                    body = """{"error":"invalid_token","message":"The access token has expired"}""",
                ),
            ),
        )
    }

    private fun refreshMethodOf(configuration: PresetConfiguration): HttpMethod =
        HttpMethod.entries.firstOrNull {
            it.name == configuration.text(REFRESH_METHOD, HttpMethod.POST.name).uppercase()
        } ?: HttpMethod.POST

    private fun scenario(
        preset: ScenarioPreset,
        configuration: PresetConfiguration,
        description: String,
        rules: List<EndpointRule>,
    ): NetworkScenario = NetworkScenario(
        name = preset.name,
        description = description,
        rules = rules,
        preset = ScenarioPresetOrigin(preset.id, preset.name, configuration.values),
        metadata = ScenarioMetadata(),
    )

    /** Shared validation: both paths must be present and path-shaped. */
    private fun validatePaths(configuration: PresetConfiguration): List<String> = buildList {
        val protectedPrefix = configuration.text(PROTECTED)
        val refresh = configuration.text(REFRESH_PATH)
        if (protectedPrefix.isEmpty()) add("Enter the prefix your protected endpoints share.")
        if (protectedPrefix.contains(' ')) add("A path prefix cannot contain spaces.")
        if (refresh.isEmpty()) add("Enter your refresh endpoint.")
        if (refresh.contains(' ')) add("A path cannot contain spaces.")
        if (refresh.isNotEmpty() && protectedPrefix.isNotEmpty() &&
            !refresh.startsWith(protectedPrefix)
        ) {
            // Not an error: plenty of apps host refresh outside the API prefix.
            // Worth saying, because the excluding pass-through rule then does
            // nothing and someone will wonder why it is there.
            add(
                "Note: \"$refresh\" is outside \"$protectedPrefix\", so it would not have " +
                    "received a 401 anyway.",
            )
        }
    }

    // ---- presets -------------------------------------------------------------

    /**
     * The access token has expired: the next N protected calls return `401`.
     *
     * The starting point for every other auth scenario. On its own it answers
     * "does the app notice a 401 at all", which is a surprisingly common failure.
     */
    val tokenExpires: ScenarioPreset = object : ScenarioPreset {
        override val id: String = "auth.token-expires"
        override val name: String = "Access token expires"
        override val description: String =
            "Protected calls return 401. Refresh reaches the real backend."
        override val category: PresetCategory = PresetCategory.AUTHENTICATION
        override val fields: List<PresetField> = listOf(
            protectedPathsField,
            refreshPathField,
            PresetField(
                key = COUNT,
                label = "How many 401s",
                hint = "0 means every protected call keeps failing",
                default = "1",
                kind = PresetFieldKind.NUMBER,
            ),
        )

        override fun validate(configuration: PresetConfiguration): List<String> = buildList {
            addAll(validatePaths(configuration))
            if (configuration.number(COUNT, 1) < 0) add("The number of 401s cannot be negative.")
        }

        override fun build(configuration: PresetConfiguration): NetworkScenario {
            val count = configuration.number(COUNT, 1).coerceAtLeast(0)
            return scenario(
                preset = this,
                configuration = configuration,
                description = if (count > 0) {
                    "The first $count protected call${if (count == 1L) "" else "s"} return 401. " +
                        "Refresh is left alone so the app can recover."
                } else {
                    "Every protected call returns 401. Refresh is left alone."
                },
                rules = protectedRules(configuration, count),
            )
        }
    }

    /**
     * `401`, then a refresh that works, then normal service.
     *
     * The happy path, and worth testing precisely because it usually *looks*
     * fine: an app that silently drops the original request after refreshing
     * passes a casual look and loses data in production.
     */
    val refreshSucceeds: ScenarioPreset = object : ScenarioPreset {
        override val id: String = "auth.refresh-succeeds"
        override val name: String = "Refresh succeeds"
        override val description: String =
            "One 401, a successful refresh, then protected calls work again."
        override val category: PresetCategory = PresetCategory.AUTHENTICATION
        override val fields: List<PresetField> =
            listOf(protectedPathsField, refreshPathField, refreshMethodField, refreshBodyField)

        override fun validate(configuration: PresetConfiguration): List<String> =
            validatePaths(configuration)

        override fun build(configuration: PresetConfiguration): NetworkScenario = scenario(
            preset = this,
            configuration = configuration,
            description = "The first protected call returns 401; refresh returns 200 with the " +
                "configured payload; the retry passes through to the real backend.",
            rules = listOf(
                EndpointRule(
                    name = "Refresh succeeds",
                    method = refreshMethodOf(configuration),
                    matcher = EndpointMatcher.ExactPath(
                        configuration.text(REFRESH_PATH, "/api/v1/auth/refresh"),
                    ),
                    action = NetworkAction.ReturnResponse(
                        statusCode = 200,
                        body = configuration.raw(REFRESH_BODY, DEFAULT_REFRESH_BODY),
                    ),
                ),
            ) + protectedRules(configuration, count = 1).drop(1),
        )
    }

    /**
     * `401`, then a refresh that also fails — the session-expiry path.
     *
     * What this is really testing is whether the app logs out **once** and
     * cleanly. Apps that get this wrong show a login screen behind three stacked
     * error dialogs.
     */
    val refreshFails: ScenarioPreset = object : ScenarioPreset {
        override val id: String = "auth.refresh-fails"
        override val name: String = "Refresh fails"
        override val description: String =
            "The token expires and refresh is rejected. The session is over."
        override val category: PresetCategory = PresetCategory.AUTHENTICATION
        override val fields: List<PresetField> = listOf(
            protectedPathsField,
            refreshPathField,
            refreshMethodField,
            PresetField(
                key = "refreshStatus",
                label = "Refresh fails with",
                default = "401",
                kind = PresetFieldKind.CHOICE,
                options = listOf("401", "403", "500", "503"),
            ),
        )

        override fun validate(configuration: PresetConfiguration): List<String> =
            validatePaths(configuration)

        override fun build(configuration: PresetConfiguration): NetworkScenario {
            val status = configuration.int("refreshStatus", 401)
            return scenario(
                preset = this,
                configuration = configuration,
                description = "Protected calls return 401 and refresh returns $status. Check " +
                    "the app logs out exactly once and does not loop.",
                rules = listOf(
                    EndpointRule(
                        name = "Refresh rejected",
                        method = refreshMethodOf(configuration),
                        matcher = EndpointMatcher.ExactPath(
                            configuration.text(REFRESH_PATH, "/api/v1/auth/refresh"),
                        ),
                        action = NetworkAction.ReturnResponse(
                            statusCode = status,
                            body = """{"error":"invalid_grant","message":"Refresh token rejected"}""",
                        ),
                    ),
                ) + protectedRules(configuration, count = 0).drop(1),
            )
        }
    }

    /**
     * Refresh takes a very long time, or never answers.
     *
     * The single most productive auth scenario there is. A slow refresh is what
     * exposes duplicate refresh calls, duplicate logout events, and the UI
     * deadlock where every screen waits on a mutex the refresh never releases.
     */
    val refreshSlow: ScenarioPreset = object : ScenarioPreset {
        override val id: String = "auth.refresh-slow"
        override val name: String = "Refresh is slow"
        override val description: String =
            "Refresh takes seconds to answer. Exposes duplicate refreshes and UI deadlocks."
        override val category: PresetCategory = PresetCategory.AUTHENTICATION
        override val fields: List<PresetField> = listOf(
            protectedPathsField,
            refreshPathField,
            refreshMethodField,
            PresetField(
                key = DELAY,
                label = "Refresh delay (ms)",
                hint = "0 makes refresh time out instead of answering slowly",
                default = "5000",
                kind = PresetFieldKind.NUMBER,
            ),
            refreshBodyField,
        )

        override fun validate(configuration: PresetConfiguration): List<String> = buildList {
            addAll(validatePaths(configuration))
            if (configuration.number(DELAY, 5_000) < 0) add("The delay cannot be negative.")
        }

        override fun build(configuration: PresetConfiguration): NetworkScenario {
            val delay = configuration.number(DELAY, 5_000).coerceAtLeast(0)
            return scenario(
                preset = this,
                configuration = configuration,
                description = if (delay > 0) {
                    "Protected calls return 401; refresh answers after ${delay}ms. Fire several " +
                        "protected calls at once and count how many refreshes the app makes."
                } else {
                    "Protected calls return 401; refresh never answers."
                },
                rules = listOf(
                    EndpointRule(
                        name = if (delay > 0) "Refresh delayed ${delay}ms" else "Refresh times out",
                        method = refreshMethodOf(configuration),
                        matcher = EndpointMatcher.ExactPath(
                            configuration.text(REFRESH_PATH, "/api/v1/auth/refresh"),
                        ),
                        action = if (delay > 0) {
                            NetworkAction.ReturnResponse(
                                statusCode = 200,
                                body = configuration.raw(REFRESH_BODY, DEFAULT_REFRESH_BODY),
                                delayMillis = delay,
                            )
                        } else {
                            NetworkAction.Timeout(TimeoutType.READ)
                        },
                    ),
                ) + protectedRules(configuration, count = 0).drop(1),
            )
        }
    }

    /**
     * Every protected endpoint returns `401` at once.
     *
     * Built to catch one specific, extremely common bug: an app that performs
     * **one refresh per failed call**. Open a screen that fans out to four
     * endpoints and watch the history — four refreshes means the refresh is not
     * being coalesced, and on a real backend that is a rate-limit or a
     * revoked-token incident waiting to happen.
     *
     * Expressed as a prefix rule rather than a list of paths, because the bug is
     * about concurrency, not about which endpoints an app happens to have.
     */
    val concurrentUnauthorized: ScenarioPreset = object : ScenarioPreset {
        override val id: String = "auth.refresh-storm"
        override val name: String = "Concurrent 401 storm"
        override val description: String =
            "Every protected call fails at once. Does the app refresh once, or once per call?"
        override val category: PresetCategory = PresetCategory.AUTHENTICATION
        override val fields: List<PresetField> = listOf(
            protectedPathsField,
            refreshPathField,
            refreshMethodField,
            PresetField(
                key = DELAY,
                label = "Refresh delay (ms)",
                hint = "A slow refresh widens the window where duplicates happen",
                default = "2000",
                kind = PresetFieldKind.NUMBER,
            ),
            refreshBodyField,
        )

        override fun validate(configuration: PresetConfiguration): List<String> = buildList {
            addAll(validatePaths(configuration))
            if (configuration.number(DELAY, 2_000) < 0) add("The delay cannot be negative.")
        }

        override fun build(configuration: PresetConfiguration): NetworkScenario {
            val delay = configuration.number(DELAY, 2_000).coerceAtLeast(0)
            return scenario(
                preset = this,
                configuration = configuration,
                description = "All protected calls return 401 until the first refresh completes " +
                    "(${delay}ms). Count the refresh requests in history — there should be one.",
                rules = listOf(
                    EndpointRule(
                        name = "Refresh (slow, succeeds once)",
                        method = refreshMethodOf(configuration),
                        matcher = EndpointMatcher.ExactPath(
                            configuration.text(REFRESH_PATH, "/api/v1/auth/refresh"),
                        ),
                        action = NetworkAction.ReturnResponse(
                            statusCode = 200,
                            body = configuration.raw(REFRESH_BODY, DEFAULT_REFRESH_BODY),
                            delayMillis = delay,
                        ),
                    ),
                    EndpointRule(
                        name = "Protected paths → 401 until refreshed",
                        method = HttpMethod.ANY,
                        matcher = EndpointMatcher.PathPrefix(
                            configuration.text(PROTECTED, "/api/v1"),
                        ),
                        // A sequence rather than a plain 401: the first several
                        // calls fail, then traffic recovers. Without recovery the
                        // app can never get past the storm and the interesting
                        // part — what it did during it — is buried under an
                        // endless failure loop.
                        action = NetworkAction.Sequence(
                            steps = List(STORM_SIZE) {
                                SequenceStep(
                                    NetworkAction.ReturnResponse(
                                        statusCode = 401,
                                        body = """{"error":"invalid_token"}""",
                                    ),
                                )
                            } + SequenceStep(NetworkAction.PassThrough),
                            completion = SequenceCompletionBehavior.PASS_THROUGH,
                        ),
                    ),
                ),
            )
        }
    }

    /**
     * How many protected calls the storm scenario fails before recovering.
     *
     * Four: enough to represent a screen that fans out (profile, bookings,
     * notifications, services), few enough that the app reaches its recovered
     * state while someone is still watching.
     */
    private const val STORM_SIZE = 4

    /** Every auth preset, in the order the picker shows them. */
    val all: List<ScenarioPreset> = listOf(
        tokenExpires,
        refreshSucceeds,
        refreshFails,
        refreshSlow,
        concurrentUnauthorized,
    )
}
