package io.devkit.netkit.scenario.preset

import io.devkit.netkit.scenario.EndpointMatcher
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.SequenceCompletionBehavior
import io.devkit.netkit.scenario.SequenceStep
import io.devkit.netkit.scenario.condition.QueryParameterCondition
import io.devkit.netkit.scenario.condition.RuleCondition
import io.devkit.netkit.scenario.condition.StringMatch
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.model.ScenarioMetadata
import io.devkit.netkit.scenario.model.ScenarioPresetOrigin

/**
 * Presets for the pagination bugs that only appear on page two.
 *
 * ### Why these are query conditions rather than pagination logic
 *
 * There is no pagination code anywhere in NetKit's interceptor, and there is not
 * going to be. "Page 2 fails" is expressed as a query-parameter condition —
 * `page = 2` — which means NetKit needs to know nothing about your API beyond the
 * name of one parameter. Cursor pagination is the same mechanism with a different
 * parameter name and value.
 *
 * The alternative, a pagination model NetKit understands, would have to guess at
 * your envelope shape, your parameter names, whether you are zero- or one-indexed
 * and whether "no more pages" is a null cursor or an empty array. It would be
 * wrong for most APIs and subtly wrong for the rest.
 *
 * ### Why the fixtures are yours to write
 *
 * The presets that return a *body* — empty page, duplicate data, malformed
 * metadata — ask you for it. NetKit cannot invent a response that your parser
 * will accept, and a fixture that fails to parse tests your error path rather
 * than the pagination bug you were chasing. The defaults are plausible shapes to
 * edit, not shapes to trust.
 */
object PaginationScenarioPresets {

    private const val PATH = "path"
    private const val METHOD = "method"
    private const val PARAMETER = "parameter"
    private const val TARGET = "target"
    private const val STATUS = "status"
    private const val DELAY = "delayMillis"
    private const val BODY = "body"
    private const val ATTEMPTS = "attempts"

    /** A page envelope common enough to be a useful starting point. */
    private const val EMPTY_PAGE_BODY = """{"data":[],"next_page":null,"has_more":false}"""

    /** A page whose ids repeat the previous page — the duplicate-item bug. */
    private const val DUPLICATE_PAGE_BODY =
        """{"data":[{"id":"1","name":"First item"},{"id":"2","name":"Second item"}],""" +
            """"next_page":2,"has_more":true}"""

    /**
     * Pagination metadata that contradicts itself.
     *
     * `has_more` is true, the page is empty, and `next_page` points at the page
     * just fetched. Every one of those is a real bug seen in a real backend, and
     * together they are the reliable way to make a client loop forever.
     */
    private const val MALFORMED_PAGE_BODY =
        """{"data":[],"next_page":2,"next_cursor":"same-as-current","has_more":true}"""

    private val pathField = PresetField(
        key = PATH,
        label = "Endpoint",
        hint = "The path only, without the query string",
        default = "/api/v1/services",
    )

    private val methodField = PresetField(
        key = METHOD,
        label = "Method",
        default = HttpMethod.GET.name,
        kind = PresetFieldKind.CHOICE,
        options = listOf(HttpMethod.GET.name, HttpMethod.POST.name, HttpMethod.ANY.name),
    )

    private val parameterField = PresetField(
        key = PARAMETER,
        label = "Pagination parameter",
        hint = "page, cursor, offset — whatever your API calls it",
        default = "page",
        kind = PresetFieldKind.CHOICE,
        options = listOf("page", "cursor", "offset", "page_number", "after"),
    )

    private val targetField = PresetField(
        key = TARGET,
        label = "Target page or cursor",
        hint = "The exact value to match, e.g. 2 or an opaque cursor",
        default = "2",
    )

    private fun methodOf(configuration: PresetConfiguration): HttpMethod =
        HttpMethod.entries.firstOrNull {
            it.name == configuration.text(METHOD, HttpMethod.GET.name).uppercase()
        } ?: HttpMethod.GET

    /**
     * The condition that pins a rule to one page.
     *
     * An exact `equals` match on the configured parameter, which works
     * identically for `page=2` and for an opaque cursor value — the whole reason
     * cursor pagination needs no special support.
     */
    private fun pageCondition(configuration: PresetConfiguration): List<RuleCondition> = listOf(
        QueryParameterCondition(
            name = configuration.text(PARAMETER, "page"),
            match = StringMatch.EQUALS,
            value = configuration.text(TARGET, "2"),
        ),
    )

    private fun rule(
        configuration: PresetConfiguration,
        name: String,
        action: NetworkAction,
        conditions: List<RuleCondition> = pageCondition(configuration),
    ): EndpointRule = EndpointRule(
        name = name,
        method = methodOf(configuration),
        matcher = EndpointMatcher.ExactPath(configuration.text(PATH, "/api/v1/services")),
        conditions = conditions,
        action = action,
    )

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

    private fun validateEndpoint(configuration: PresetConfiguration): List<String> = buildList {
        val path = configuration.text(PATH)
        when {
            path.isEmpty() -> add("Enter the endpoint being paginated.")
            path.contains(' ') -> add("A path cannot contain spaces.")
            path.contains('?') -> add("Enter the path only — the parameter is configured below.")
        }
        if (configuration.text(PARAMETER).isEmpty()) {
            add("Enter the name of your pagination parameter.")
        }
        if (configuration.text(TARGET).isEmpty()) {
            add("Enter the page or cursor value to target.")
        }
    }

    // ---- presets -------------------------------------------------------------

    /**
     * One page fails while the rest work.
     *
     * The canonical infinite-scroll bug: the list loads, the user scrolls, page
     * two errors, and the app either shows a spinner forever or silently stops
     * paginating with no way to retry.
     */
    val pageFails: ScenarioPreset = object : ScenarioPreset {
        override val id: String = "pagination.page-fails"
        override val name: String = "A page fails"
        override val description: String =
            "One page returns an error while every other page loads normally."
        override val category: PresetCategory = PresetCategory.PAGINATION
        override val fields: List<PresetField> = listOf(
            pathField,
            methodField,
            parameterField,
            targetField,
            PresetField(
                key = STATUS,
                label = "Fails with",
                default = "500",
                kind = PresetFieldKind.CHOICE,
                options = listOf("500", "502", "503", "504", "429"),
            ),
        )

        override fun validate(configuration: PresetConfiguration): List<String> =
            validateEndpoint(configuration)

        override fun build(configuration: PresetConfiguration): NetworkScenario {
            val status = configuration.int(STATUS, 500)
            val target = configuration.text(TARGET, "2")
            val parameter = configuration.text(PARAMETER, "page")
            return scenario(
                preset = this,
                configuration = configuration,
                description = "$parameter=$target returns $status. Every other page reaches the " +
                    "real backend, so the failure is isolated to one scroll step.",
                rules = listOf(
                    rule(
                        configuration = configuration,
                        name = "$parameter=$target → $status",
                        action = NetworkAction.ReturnResponse(
                            statusCode = status,
                            body = """{"error":"Simulated pagination failure"}""",
                        ),
                    ),
                ),
            )
        }
    }

    /**
     * The first page comes back empty.
     *
     * Tests the empty state, which is usually built last and tested least — and
     * distinguishes it from the loading state, which is the bug: a screen that
     * spins forever on a legitimately empty result.
     */
    val emptyFirstPage: ScenarioPreset = object : ScenarioPreset {
        override val id: String = "pagination.empty-first-page"
        override val name: String = "Empty first page"
        override val description: String =
            "The list is empty from the start. Tests the empty state, not the loading state."
        override val category: PresetCategory = PresetCategory.PAGINATION
        override val fields: List<PresetField> = listOf(
            pathField,
            methodField,
            parameterField,
            PresetField(
                key = BODY,
                label = "Empty response body",
                hint = "Your API's shape for 'no results'",
                default = EMPTY_PAGE_BODY,
                kind = PresetFieldKind.BODY,
            ),
        )

        override fun validate(configuration: PresetConfiguration): List<String> = buildList {
            val path = configuration.text(PATH)
            if (path.isEmpty()) add("Enter the endpoint being paginated.")
            if (path.contains(' ')) add("A path cannot contain spaces.")
        }

        override fun build(configuration: PresetConfiguration): NetworkScenario = scenario(
            preset = this,
            configuration = configuration,
            description = "Every request to this endpoint returns an empty page, whatever the " +
                "pagination parameter says.",
            rules = listOf(
                rule(
                    configuration = configuration,
                    name = "Always empty",
                    // No page condition: an empty *first* page means the endpoint
                    // is empty however the client asks for it.
                    conditions = emptyList(),
                    action = NetworkAction.ReturnResponse(
                        statusCode = 200,
                        body = configuration.raw(BODY, EMPTY_PAGE_BODY),
                    ),
                ),
            ),
        )
    }

    /**
     * The first page loads, the next one is empty.
     *
     * The end-of-pagination case. What usually breaks is the footer: the loading
     * spinner never goes away, or the app keeps requesting the same empty page.
     */
    val emptyNextPage: ScenarioPreset = object : ScenarioPreset {
        override val id: String = "pagination.empty-next-page"
        override val name: String = "Empty next page"
        override val description: String =
            "The next page comes back with no items. Tests end-of-list handling."
        override val category: PresetCategory = PresetCategory.PAGINATION
        override val fields: List<PresetField> = listOf(
            pathField,
            methodField,
            parameterField,
            targetField,
            PresetField(
                key = BODY,
                label = "Empty page body",
                default = EMPTY_PAGE_BODY,
                kind = PresetFieldKind.BODY,
            ),
        )

        override fun validate(configuration: PresetConfiguration): List<String> =
            validateEndpoint(configuration)

        override fun build(configuration: PresetConfiguration): NetworkScenario {
            val target = configuration.text(TARGET, "2")
            val parameter = configuration.text(PARAMETER, "page")
            return scenario(
                preset = this,
                configuration = configuration,
                description = "$parameter=$target returns 200 with no items. Earlier pages come " +
                    "from the real backend. Check the footer spinner clears.",
                rules = listOf(
                    rule(
                        configuration = configuration,
                        name = "$parameter=$target → empty",
                        action = NetworkAction.ReturnResponse(
                            statusCode = 200,
                            body = configuration.raw(BODY, EMPTY_PAGE_BODY),
                        ),
                    ),
                ),
            )
        }
    }

    /**
     * The next page takes a long time.
     *
     * Widens the window in which every append-time bug lives: a duplicated
     * request because the scroll listener fired twice, a lost scroll position, a
     * loader that never appears because the request completed too fast to see.
     */
    val slowNextPage: ScenarioPreset = object : ScenarioPreset {
        override val id: String = "pagination.slow-next-page"
        override val name: String = "Slow next page"
        override val description: String =
            "One page takes seconds to load. Exposes duplicate requests and scroll jumps."
        override val category: PresetCategory = PresetCategory.PAGINATION
        override val fields: List<PresetField> = listOf(
            pathField,
            methodField,
            parameterField,
            targetField,
            PresetField(
                key = DELAY,
                label = "Delay (ms)",
                default = "5000",
                kind = PresetFieldKind.NUMBER,
            ),
        )

        override fun validate(configuration: PresetConfiguration): List<String> = buildList {
            addAll(validateEndpoint(configuration))
            if (configuration.number(DELAY, 5_000) < 0) add("The delay cannot be negative.")
        }

        override fun build(configuration: PresetConfiguration): NetworkScenario {
            val delay = configuration.number(DELAY, 5_000).coerceAtLeast(0)
            val target = configuration.text(TARGET, "2")
            val parameter = configuration.text(PARAMETER, "page")
            return scenario(
                preset = this,
                configuration = configuration,
                description = "$parameter=$target is delayed ${delay}ms and then reaches the " +
                    "real backend, so the data is genuine and only the timing is simulated.",
                rules = listOf(
                    rule(
                        configuration = configuration,
                        name = "$parameter=$target → delayed ${delay}ms",
                        // A delay rather than a synthetic response: the point is
                        // the timing, and real data makes the scroll behaviour
                        // afterwards worth watching.
                        action = NetworkAction.Delay(delay),
                    ),
                ),
            )
        }
    }

    /**
     * A page that fails a few times and then works.
     *
     * Reuses the response sequence from 0.2 rather than reimplementing retry
     * logic — a sequence *is* "behave differently on each successive request",
     * and pagination retry is that with a query condition on top.
     */
    val retrySucceeds: ScenarioPreset = object : ScenarioPreset {
        override val id: String = "pagination.retry-succeeds"
        override val name: String = "Page retry eventually succeeds"
        override val description: String =
            "A page fails a few times and then loads. Tests retry and the retry button."
        override val category: PresetCategory = PresetCategory.PAGINATION
        override val fields: List<PresetField> = listOf(
            pathField,
            methodField,
            parameterField,
            targetField,
            PresetField(
                key = ATTEMPTS,
                label = "Failures before success",
                default = "2",
                kind = PresetFieldKind.NUMBER,
            ),
            PresetField(
                key = STATUS,
                label = "Fails with",
                default = "500",
                kind = PresetFieldKind.CHOICE,
                options = listOf("500", "502", "503", "504"),
            ),
        )

        override fun validate(configuration: PresetConfiguration): List<String> = buildList {
            addAll(validateEndpoint(configuration))
            val attempts = configuration.number(ATTEMPTS, 2)
            if (attempts < 1) add("There must be at least one failure before success.")
            if (attempts > MAX_ATTEMPTS) {
                add("More than $MAX_ATTEMPTS failures is more than anyone will sit through.")
            }
        }

        override fun build(configuration: PresetConfiguration): NetworkScenario {
            val attempts = configuration.number(ATTEMPTS, 2).coerceIn(1, MAX_ATTEMPTS).toInt()
            val status = configuration.int(STATUS, 500)
            val target = configuration.text(TARGET, "2")
            val parameter = configuration.text(PARAMETER, "page")
            return scenario(
                preset = this,
                configuration = configuration,
                description = "$parameter=$target fails $attempts time" +
                    "${if (attempts == 1) "" else "s"} with $status, then passes through to the " +
                    "real backend.",
                rules = listOf(
                    rule(
                        configuration = configuration,
                        name = "$parameter=$target → retry $attempts×",
                        action = NetworkAction.Sequence(
                            steps = List(attempts) {
                                SequenceStep(
                                    NetworkAction.ReturnResponse(
                                        statusCode = status,
                                        body = """{"error":"Simulated pagination failure"}""",
                                    ),
                                )
                            } + SequenceStep(NetworkAction.PassThrough),
                            // Once it has succeeded it keeps succeeding: a
                            // scenario that started failing again after a
                            // successful retry would be testing something nobody
                            // asked about.
                            completion = SequenceCompletionBehavior.PASS_THROUGH,
                        ),
                    ),
                ),
            )
        }
    }

    /**
     * A page whose body is a fixture you supply — duplicated ids, or metadata
     * that contradicts itself.
     *
     * Described honestly as a **fixture** rather than as NetKit "duplicating your
     * data": NetKit does not parse your envelope and could not duplicate items in
     * a schema it has never seen. What it does is return the exact bytes you give
     * it for one page, which is enough to reproduce both the duplicate-item bug
     * and the infinite-loop-on-bad-metadata bug.
     */
    val fixturePage: ScenarioPreset = object : ScenarioPreset {
        override val id: String = "pagination.fixture-page"
        override val name: String = "Page data fixture"
        override val description: String =
            "Return a body you supply for one page — duplicate ids, or metadata that lies."
        override val category: PresetCategory = PresetCategory.PAGINATION
        override val fields: List<PresetField> = listOf(
            pathField,
            methodField,
            parameterField,
            targetField,
            PresetField(
                key = "fixture",
                label = "Fixture",
                default = DUPLICATE,
                kind = PresetFieldKind.CHOICE,
                options = listOf(DUPLICATE, MALFORMED, CUSTOM),
            ),
            PresetField(
                key = BODY,
                label = "Response body",
                hint = "Used when the fixture is \"$CUSTOM\"; edit the others freely too",
                default = DUPLICATE_PAGE_BODY,
                kind = PresetFieldKind.BODY,
            ),
        )

        override fun validate(configuration: PresetConfiguration): List<String> = buildList {
            addAll(validateEndpoint(configuration))
            if (configuration.text("fixture", DUPLICATE) == CUSTOM &&
                configuration.raw(BODY).isBlank()
            ) {
                add("Enter the response body to return.")
            }
        }

        override fun build(configuration: PresetConfiguration): NetworkScenario {
            val fixture = configuration.text("fixture", DUPLICATE)
            val body = when (fixture) {
                DUPLICATE -> configuration.raw(BODY, DUPLICATE_PAGE_BODY)
                MALFORMED -> configuration.raw(BODY, MALFORMED_PAGE_BODY)
                    .takeIf { it != DUPLICATE_PAGE_BODY } ?: MALFORMED_PAGE_BODY
                else -> configuration.raw(BODY, DUPLICATE_PAGE_BODY)
            }
            val target = configuration.text(TARGET, "2")
            val parameter = configuration.text(PARAMETER, "page")
            return scenario(
                preset = this,
                configuration = configuration,
                description = when (fixture) {
                    DUPLICATE -> "$parameter=$target returns a fixture whose ids repeat an " +
                        "earlier page. Check the list does not show duplicates."

                    MALFORMED -> "$parameter=$target returns metadata that contradicts itself: " +
                        "no items, but more pages. Check the app stops rather than looping."

                    else -> "$parameter=$target returns the body you supplied."
                },
                rules = listOf(
                    rule(
                        configuration = configuration,
                        name = "$parameter=$target → $fixture fixture",
                        action = NetworkAction.ReturnResponse(statusCode = 200, body = body),
                    ),
                ),
            )
        }
    }

    private const val DUPLICATE = "Duplicate data"
    private const val MALFORMED = "Malformed metadata"
    private const val CUSTOM = "Custom"

    /** Beyond this, a retry scenario stops being something a person can sit through. */
    private const val MAX_ATTEMPTS = 10L

    /** Every pagination preset, in the order the picker shows them. */
    val all: List<ScenarioPreset> = listOf(
        pageFails,
        slowNextPage,
        emptyNextPage,
        emptyFirstPage,
        retrySucceeds,
        fixturePage,
    )
}
