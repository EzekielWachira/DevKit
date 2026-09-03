package io.devkit.netkit

import io.devkit.netkit.engine.ScenarioDecision
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.condition.AllOf
import io.devkit.netkit.scenario.condition.AnyOf
import io.devkit.netkit.scenario.condition.BodyCondition
import io.devkit.netkit.scenario.condition.HeaderCondition
import io.devkit.netkit.scenario.condition.PreviousResult
import io.devkit.netkit.scenario.condition.PreviousResultCondition
import io.devkit.netkit.scenario.condition.QueryParameterCondition
import io.devkit.netkit.scenario.condition.RequestBodyPeek
import io.devkit.netkit.scenario.condition.RequestCountCondition
import io.devkit.netkit.scenario.condition.RuleCondition
import io.devkit.netkit.scenario.condition.StringMatch
import io.devkit.netkit.scenario.runtime.ActiveNetworkConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule conditions, through the real engine.
 *
 * Conditions are deterministic by construction, so every assertion here is an
 * exact one about which requests a rule claimed.
 */
class RuleConditionTest {

    // ---- request count -------------------------------------------------------

    @Test
    fun `only the first request`() {
        val statuses = harness(RequestCountCondition.Exactly(1)).statuses(4)
        assertEquals(listOf(500, null, null, null), statuses)
    }

    @Test
    fun `a specific request index`() {
        val statuses = harness(RequestCountCondition.Exactly(3)).statuses(5)
        assertEquals(listOf(null, null, 500, null, null), statuses)
    }

    @Test
    fun `every request from the third onwards`() {
        val statuses = harness(RequestCountCondition.AtLeast(3)).statuses(5)
        assertEquals(listOf(null, null, 500, 500, 500), statuses)
    }

    @Test
    fun `a range of requests`() {
        val statuses = harness(RequestCountCondition.Range(3, 5)).statuses(6)
        assertEquals(listOf(null, null, 500, 500, 500, null), statuses)
    }

    @Test
    fun `every fourth request`() {
        val statuses = harness(RequestCountCondition.Every(4)).statuses(9)
        assertEquals(listOf(null, null, null, 500, null, null, null, 500, null), statuses)
    }

    /**
     * The counter is per rule, so traffic to an unrelated endpoint must not
     * advance it. Without this, "fail the first booking request" would fail
     * whichever request happened to be first overall.
     */
    @Test
    fun `unrelated traffic does not advance a rule's counter`() {
        val harness = harness(RequestCountCondition.Exactly(2))

        harness.decide(path = "/api/v1/profile")
        harness.decide(path = "/api/v1/profile")
        assertTrue(harness.decide() is ScenarioDecision.PassThrough)
        assertTrue(harness.decide() is ScenarioDecision.RespondWith)
    }

    /**
     * Each rule counts **the requests that reached it**, not the requests the
     * endpoint received.
     *
     * The consequence is worth knowing, because it surprises people: a second
     * rule below a `first request only` rule does not see request 1 at all, so
     * *its* first request is the endpoint's second. Pairing `Exactly(1)` with
     * `AtLeast(2)` therefore leaves a gap rather than partitioning the traffic.
     */
    @Test
    fun `a rule counts only the requests that reached it`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(
                    Fixtures.rule(action = NetworkAction.ReturnResponse(401))
                        .copy(id = "first", conditions = listOf(RequestCountCondition.Exactly(1))),
                    Fixtures.rule(action = NetworkAction.ReturnResponse(200))
                        .copy(id = "rest", conditions = listOf(RequestCountCondition.AtLeast(2))),
                ),
            ),
        )

        // Request 2 fell through both rules: the second rule was on its own first
        // request, which AtLeast(2) does not accept.
        assertEquals(listOf(401, null, 200, 200), harness.statuses(4))
    }

    /**
     * The way to express "the first one fails, the rest work": leave the second
     * rule unconditional and let ordering do the partitioning.
     */
    @Test
    fun `an unconditional rule below a counted one catches everything else`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(
                    Fixtures.rule(action = NetworkAction.ReturnResponse(401))
                        .copy(id = "first", conditions = listOf(RequestCountCondition.Exactly(1))),
                    Fixtures.rule(action = NetworkAction.ReturnResponse(200)).copy(id = "rest"),
                ),
            ),
        )

        assertEquals(listOf(401, 200, 200, 200), harness.statuses(4))
    }

    @Test
    fun `an invalid request index is rejected at construction`() {
        listOf(
            { RequestCountCondition.Exactly(0) },
            { RequestCountCondition.AtLeast(0) },
            { RequestCountCondition.Every(0) },
            { RequestCountCondition.Range(3, 1) },
        ).forEach { build ->
            try {
                build()
                error("Expected an invalid request-count condition to be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotBlank())
            }
        }
    }

    // ---- query ---------------------------------------------------------------

    @Test
    fun `a query parameter must equal a value`() {
        val harness = harness(QueryParameterCondition("page", StringMatch.EQUALS, "2"))

        assertTrue(harness.decide(query = "page=1") is ScenarioDecision.PassThrough)
        assertTrue(harness.decide(query = "page=2") is ScenarioDecision.RespondWith)
        assertTrue(harness.decide(query = "page=3") is ScenarioDecision.PassThrough)
    }

    @Test
    fun `a query parameter must exist`() {
        val harness = harness(QueryParameterCondition("cursor", StringMatch.EXISTS))

        assertTrue(harness.decide(query = null) is ScenarioDecision.PassThrough)
        assertTrue(harness.decide(query = "cursor=abc") is ScenarioDecision.RespondWith)
        // A bare flag is present with an empty value, so it exists.
        assertTrue(harness.decide(query = "cursor") is ScenarioDecision.RespondWith)
    }

    @Test
    fun `a query parameter must be missing`() {
        val harness = harness(QueryParameterCondition("page", StringMatch.MISSING))

        assertTrue(harness.decide(query = null) is ScenarioDecision.RespondWith)
        assertTrue(harness.decide(query = "page=2") is ScenarioDecision.PassThrough)
    }

    @Test
    fun `a query value is percent-decoded before comparison`() {
        val harness = harness(QueryParameterCondition("q", StringMatch.EQUALS, "two words"))

        assertTrue(harness.decide(query = "q=two%20words") is ScenarioDecision.RespondWith)
        assertTrue(harness.decide(query = "q=two+words") is ScenarioDecision.RespondWith)
    }

    @Test
    fun `a cursor value works exactly like a page number`() {
        val harness = harness(QueryParameterCondition("cursor", StringMatch.EQUALS, "eyJpZCI6MX0"))

        assertTrue(harness.decide(query = "cursor=eyJpZCI6MX0") is ScenarioDecision.RespondWith)
        assertTrue(harness.decide(query = "cursor=other") is ScenarioDecision.PassThrough)
    }

    // ---- header --------------------------------------------------------------

    @Test
    fun `a header must exist`() {
        val harness = harness(HeaderCondition("Authorization", StringMatch.EXISTS))

        assertTrue(harness.decide() is ScenarioDecision.PassThrough)
        assertTrue(
            harness.decide(headers = listOf("Authorization" to "Bearer x"))
                is ScenarioDecision.RespondWith,
        )
    }

    @Test
    fun `a header must be missing`() {
        val harness = harness(HeaderCondition("Authorization", StringMatch.MISSING))

        assertTrue(harness.decide() is ScenarioDecision.RespondWith)
        assertTrue(
            harness.decide(headers = listOf("Authorization" to "Bearer x"))
                is ScenarioDecision.PassThrough,
        )
    }

    @Test
    fun `a header must equal a value, case-insensitively in the name`() {
        val harness = harness(HeaderCondition("X-App-Version", StringMatch.EQUALS, "4.2"))

        assertTrue(
            harness.decide(headers = listOf("x-app-version" to "4.2"))
                is ScenarioDecision.RespondWith,
        )
        assertTrue(
            harness.decide(headers = listOf("X-App-Version" to "4.1"))
                is ScenarioDecision.PassThrough,
        )
    }

    @Test
    fun `a header must contain a value`() {
        val harness = harness(HeaderCondition("User-Agent", StringMatch.CONTAINS, "okhttp"))

        assertTrue(
            harness.decide(headers = listOf("User-Agent" to "MyApp/1.0 okhttp/5.0"))
                is ScenarioDecision.RespondWith,
        )
        assertTrue(
            harness.decide(headers = listOf("User-Agent" to "curl/8"))
                is ScenarioDecision.PassThrough,
        )
    }

    @Test
    fun `a header condition needs a value when it compares one`() {
        try {
            HeaderCondition("X-Thing", StringMatch.EQUALS, "")
            error("Expected a valueless equality condition to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("needs a value"))
        }
    }

    // ---- body ----------------------------------------------------------------

    @Test
    fun `a body condition matches text when the body is readable`() {
        val harness = harness(BodyCondition("coupon"))

        assertTrue(
            harness.decide(body = RequestBodyPeek.Text("""{"coupon":"SAVE10"}"""))
                is ScenarioDecision.RespondWith,
        )
        assertTrue(
            harness.decide(body = RequestBodyPeek.Text("""{"total":100}"""))
                is ScenarioDecision.PassThrough,
        )
    }

    @Test
    fun `a body condition can target a JSON field`() {
        val harness = harness(BodyCondition("SAVE10", jsonField = "coupon"))

        assertTrue(
            harness.decide(body = RequestBodyPeek.Text("""{"coupon":"SAVE10","total":1}"""))
                is ScenarioDecision.RespondWith,
        )
        // The value appears, but under a different field: a field-scoped
        // condition must not degrade into a substring search.
        assertTrue(
            harness.decide(body = RequestBodyPeek.Text("""{"note":"SAVE10"}"""))
                is ScenarioDecision.PassThrough,
        )
    }

    @Test
    fun `a JSON field condition reads a numeric value`() {
        val harness = harness(BodyCondition("42", jsonField = "quantity"))

        assertTrue(
            harness.decide(body = RequestBodyPeek.Text("""{"quantity":42}"""))
                is ScenarioDecision.RespondWith,
        )
    }

    /**
     * The safety property. A body NetKit will not read must produce a non-match,
     * never an exception and never a consumed stream.
     */
    @Test
    fun `an unavailable body never matches`() {
        val harness = harness(BodyCondition("coupon"))

        RequestBodyPeek.Unavailable.Reason.entries.forEach { reason ->
            assertTrue(
                "body unavailable ($reason) should not match",
                harness.decide(body = RequestBodyPeek.Unavailable(reason))
                    is ScenarioDecision.PassThrough,
            )
        }
        assertTrue(harness.decide(body = RequestBodyPeek.Absent) is ScenarioDecision.PassThrough)
    }

    @Test
    fun `an unreadable body is reported in the timeline as unavailable`() {
        val harness = harness(BodyCondition("coupon"))

        harness.decide(
            body = RequestBodyPeek.Unavailable(RequestBodyPeek.Unavailable.Reason.TOO_LARGE),
        )

        val skipped = harness.timeline.last()
        assertTrue(skipped.detail!!.contains("body too large to inspect"))
    }

    // ---- previous result -----------------------------------------------------

    @Test
    fun `a rule can wait for an earlier simulated failure`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(
                    Fixtures.rule(path = "/api/v1/profile", action = NetworkAction.ReturnResponse(500))
                        .copy(id = "breaks-first"),
                    Fixtures.rule(action = NetworkAction.ReturnResponse(503)).copy(
                        id = "follows",
                        conditions = listOf(
                            PreviousResultCondition(PreviousResult.AFTER_SIMULATED_FAILURE),
                        ),
                    ),
                ),
            ),
        )

        // Nothing has failed yet.
        assertTrue(harness.decide() is ScenarioDecision.PassThrough)
        // Now something has.
        harness.decide(path = "/api/v1/profile")
        assertTrue(harness.decide() is ScenarioDecision.RespondWith)
    }

    @Test
    fun `a rule can require that another rule has fired`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(
                    Fixtures.rule(path = "/api/v1/profile", action = NetworkAction.Delay(1))
                        .copy(id = "watched"),
                    Fixtures.rule(action = NetworkAction.ReturnResponse(503)).copy(
                        id = "dependent",
                        conditions = listOf(
                            PreviousResultCondition(ruleId = "watched", minimumHits = 2),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(harness.decide() is ScenarioDecision.PassThrough)
        harness.decide(path = "/api/v1/profile")
        assertTrue(harness.decide() is ScenarioDecision.PassThrough)
        harness.decide(path = "/api/v1/profile")
        assertTrue(harness.decide() is ScenarioDecision.RespondWith)
    }

    @Test
    fun `a previous-result condition needs something to check`() {
        try {
            PreviousResultCondition()
            error("Expected an empty previous-result condition to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("requirement"))
        }
    }

    // ---- composition ---------------------------------------------------------

    @Test
    fun `multiple conditions all have to hold`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(
                    Fixtures.rule(action = NetworkAction.ReturnResponse(500)).copy(
                        id = "narrow",
                        conditions = listOf(
                            QueryParameterCondition("page", StringMatch.EQUALS, "2"),
                            RequestCountCondition.Exactly(1),
                        ),
                    ),
                ),
            ),
        )

        // Right page, but this is the rule's second reach.
        assertTrue(harness.decide(query = "page=1") is ScenarioDecision.PassThrough)
        assertTrue(harness.decide(query = "page=2") is ScenarioDecision.PassThrough)
    }

    @Test
    fun `multiple conditions match when all of them hold`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(
                    Fixtures.rule(action = NetworkAction.ReturnResponse(500)).copy(
                        id = "narrow",
                        conditions = listOf(
                            QueryParameterCondition("page", StringMatch.EQUALS, "2"),
                            RequestCountCondition.Exactly(1),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(harness.decide(query = "page=2") is ScenarioDecision.RespondWith)
    }

    @Test
    fun `an anyOf group matches when one entry holds`() {
        val harness = harness(
            AnyOf(
                listOf(
                    QueryParameterCondition("page", StringMatch.EQUALS, "2"),
                    QueryParameterCondition("page", StringMatch.EQUALS, "3"),
                ),
            ),
        )

        assertTrue(harness.decide(query = "page=1") is ScenarioDecision.PassThrough)
        assertTrue(harness.decide(query = "page=2") is ScenarioDecision.RespondWith)
        assertTrue(harness.decide(query = "page=3") is ScenarioDecision.RespondWith)
    }

    @Test
    fun `an allOf group matches when every entry holds`() {
        val condition = AllOf(
            listOf(
                QueryParameterCondition("page", StringMatch.EQUALS, "2"),
                QueryParameterCondition("sort", StringMatch.EXISTS),
            ),
        )

        assertTrue(harness(condition).decide(query = "page=2") is ScenarioDecision.PassThrough)
        assertTrue(
            harness(condition).decide(query = "page=2&sort=asc") is ScenarioDecision.RespondWith,
        )
    }

    @Test
    fun `an empty anyOf never matches and an empty allOf always does`() {
        assertFalse(AnyOf(emptyList()).matches(context()))
        assertTrue(AllOf(emptyList()).matches(context()))
    }

    // ---- diagnostics ---------------------------------------------------------

    @Test
    fun `a condition failure is distinguishable from a probability failure`() {
        val harness = harness(QueryParameterCondition("page", StringMatch.EQUALS, "2"))
        harness.decide(query = "page=1")

        val stats = harness.statisticsFor("conditional")!!
        assertEquals(1, stats.evaluated)
        assertEquals(1, stats.matched)
        assertEquals(0, stats.conditionPassed)
        assertEquals(0, stats.executed)
        assertTrue(stats.diagnosis!!.contains("condition ruled every one out"))
    }

    private fun harness(condition: RuleCondition) = EngineHarness(
        ActiveNetworkConfiguration(
            rules = listOf(
                Fixtures.rule(action = NetworkAction.ReturnResponse(500))
                    .copy(id = "conditional", conditions = listOf(condition)),
            ),
        ),
    )

    /** A minimal context, for conditions that look at nothing about the request. */
    private fun context() = io.devkit.netkit.scenario.condition.ConditionContext(
        target = io.devkit.netkit.scenario.RequestTarget(
            method = "GET",
            scheme = "https",
            host = "api.example.com",
            port = 443,
            path = "/api/v1/bookings",
        ),
        ruleHitIndex = 1,
        evaluationIndex = 1,
        hasSimulatedFailure = false,
        executionsOf = { 0 },
        bodySource = { RequestBodyPeek.Absent },
        maxBodyBytes = 1_024,
    )
}
