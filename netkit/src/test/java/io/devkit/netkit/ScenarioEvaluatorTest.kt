package io.devkit.netkit

import io.devkit.netkit.config.NetKitDefaults
import io.devkit.netkit.engine.DecisionOrigin
import io.devkit.netkit.engine.ScenarioDecision
import io.devkit.netkit.engine.ScenarioEvaluator
import io.devkit.netkit.scenario.EndpointMatcher
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.GlobalNetworkConfig
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.NetworkScenario
import io.devkit.netkit.scenario.RequestTarget
import io.devkit.netkit.scenario.TimeoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the full precedence table documented on [ScenarioEvaluator]. */
class ScenarioEvaluatorTest {

    private fun target(method: String = "GET", path: String = "/api/v1/bookings") = RequestTarget(
        method = method,
        scheme = "https",
        host = "api.example.com",
        port = 443,
        path = path,
    )

    private fun rule(
        path: String = "/api/v1/bookings",
        method: HttpMethod = HttpMethod.ANY,
        action: NetworkAction = NetworkAction.HttpError(500),
        enabled: Boolean = true,
        name: String? = null,
    ) = EndpointRule.forPath(path, method, action, name, enabled)

    @Test
    fun `no rules and normal global passes through`() {
        val decision = ScenarioEvaluator.evaluate(NetworkScenario(), target())

        assertTrue(decision is ScenarioDecision.PassThrough)
        assertEquals(DecisionOrigin.NONE, decision.origin)
        assertFalse(decision.isSimulated)
    }

    @Test
    fun `disabled netkit ignores every rule and the global mode`() {
        val scenario = NetworkScenario(
            enabled = false,
            global = GlobalNetworkConfig(mode = GlobalNetworkMode.Offline, latencyMillis = 5_000),
            rules = listOf(rule()),
        )

        val decision = ScenarioEvaluator.evaluate(scenario, target())

        assertTrue(decision is ScenarioDecision.PassThrough)
        assertEquals(DecisionOrigin.NONE, decision.origin)
    }

    @Test
    fun `exact path matches`() {
        val scenario = NetworkScenario(rules = listOf(rule(path = "/api/v1/bookings")))

        val decision = ScenarioEvaluator.evaluate(scenario, target(path = "/api/v1/bookings"))

        assertTrue(decision is ScenarioDecision.RespondWith)
        assertEquals(500, (decision as ScenarioDecision.RespondWith).statusCode)
        assertEquals(DecisionOrigin.ENDPOINT_RULE, decision.origin)
    }

    @Test
    fun `a different path does not match`() {
        val scenario = NetworkScenario(rules = listOf(rule(path = "/api/v1/bookings")))

        val decision = ScenarioEvaluator.evaluate(scenario, target(path = "/api/v1/profile"))

        assertTrue(decision is ScenarioDecision.PassThrough)
    }

    @Test
    fun `trailing slashes and missing leading slashes describe the same endpoint`() {
        val scenario = NetworkScenario(rules = listOf(rule(path = "api/v1/bookings/")))

        val decision = ScenarioEvaluator.evaluate(scenario, target(path = "/api/v1/bookings"))

        assertTrue(decision is ScenarioDecision.RespondWith)
    }

    @Test
    fun `method matching claims the right verb`() {
        val scenario = NetworkScenario(rules = listOf(rule(method = HttpMethod.POST)))

        val matched = ScenarioEvaluator.evaluate(scenario, target(method = "POST"))
        assertTrue(matched is ScenarioDecision.RespondWith)
    }

    @Test
    fun `a rule scoped to another verb does not match`() {
        val scenario = NetworkScenario(rules = listOf(rule(method = HttpMethod.POST)))

        val decision = ScenarioEvaluator.evaluate(scenario, target(method = "GET"))

        assertTrue(decision is ScenarioDecision.PassThrough)
    }

    @Test
    fun `method matching is case insensitive`() {
        val scenario = NetworkScenario(rules = listOf(rule(method = HttpMethod.DELETE)))

        val decision = ScenarioEvaluator.evaluate(scenario, target(method = "delete"))

        assertTrue(decision is ScenarioDecision.RespondWith)
    }

    @Test
    fun `ANY claims every verb`() {
        val scenario = NetworkScenario(rules = listOf(rule(method = HttpMethod.ANY)))

        listOf("GET", "POST", "PATCH", "OPTIONS").forEach { method ->
            assertTrue(
                "expected $method to match",
                ScenarioEvaluator.evaluate(scenario, target(method = method))
                    is ScenarioDecision.RespondWith,
            )
        }
    }

    @Test
    fun `a disabled rule is ignored and the global mode applies instead`() {
        val scenario = NetworkScenario(
            global = GlobalNetworkConfig(mode = GlobalNetworkMode.Offline),
            rules = listOf(rule(enabled = false)),
        )

        val decision = ScenarioEvaluator.evaluate(scenario, target())

        assertTrue(decision is ScenarioDecision.FailOffline)
        assertEquals(DecisionOrigin.GLOBAL, decision.origin)
    }

    @Test
    fun `the first matching rule wins`() {
        val scenario = NetworkScenario(
            rules = listOf(
                rule(action = NetworkAction.HttpError(503), name = "first"),
                rule(action = NetworkAction.HttpError(500), name = "second"),
            ),
        )

        val decision = ScenarioEvaluator.evaluate(scenario, target()) as ScenarioDecision.RespondWith

        assertEquals(503, decision.statusCode)
        assertEquals("first", decision.scenarioLabel)
    }

    @Test
    fun `an endpoint rule replaces the global mode rather than composing with it`() {
        val scenario = NetworkScenario(
            global = GlobalNetworkConfig(mode = GlobalNetworkMode.Offline, latencyMillis = 5_000),
            rules = listOf(rule(action = NetworkAction.Delay(100))),
        )

        val decision = ScenarioEvaluator.evaluate(scenario, target())

        assertTrue(decision is ScenarioDecision.Delay)
        assertEquals(100L, (decision as ScenarioDecision.Delay).delayMillis)
        assertEquals(DecisionOrigin.ENDPOINT_RULE, decision.origin)
    }

    @Test
    fun `a pass-through rule exempts an endpoint from global offline`() {
        val scenario = NetworkScenario(
            global = GlobalNetworkConfig(mode = GlobalNetworkMode.Offline),
            rules = listOf(rule(path = "/api/v1/profile", action = NetworkAction.PassThrough)),
        )

        val exempt = ScenarioEvaluator.evaluate(scenario, target(path = "/api/v1/profile"))
        val other = ScenarioEvaluator.evaluate(scenario, target(path = "/api/v1/bookings"))

        assertTrue(exempt is ScenarioDecision.PassThrough)
        assertEquals(DecisionOrigin.ENDPOINT_RULE, exempt.origin)
        assertTrue(other is ScenarioDecision.FailOffline)
    }

    @Test
    fun `global offline fails every unclaimed request`() {
        val scenario = NetworkScenario(global = GlobalNetworkConfig(mode = GlobalNetworkMode.Offline))

        val decision = ScenarioEvaluator.evaluate(scenario, target())

        assertTrue(decision is ScenarioDecision.FailOffline)
        assertTrue(decision.isSimulated)
    }

    @Test
    fun `endpoint offline fails only the claimed endpoint`() {
        val scenario = NetworkScenario(rules = listOf(rule(action = NetworkAction.Offline)))

        assertTrue(ScenarioEvaluator.evaluate(scenario, target()) is ScenarioDecision.FailOffline)
        assertTrue(
            ScenarioEvaluator.evaluate(scenario, target(path = "/other")) is ScenarioDecision.PassThrough,
        )
    }

    @Test
    fun `global timeout carries the configured type`() {
        val scenario = NetworkScenario(
            global = GlobalNetworkConfig(mode = GlobalNetworkMode.Timeout(TimeoutType.CONNECT)),
        )

        val decision = ScenarioEvaluator.evaluate(scenario, target()) as ScenarioDecision.FailTimeout

        assertEquals(TimeoutType.CONNECT, decision.type)
        assertEquals(DecisionOrigin.GLOBAL, decision.origin)
    }

    @Test
    fun `endpoint timeout carries the configured type`() {
        val scenario = NetworkScenario(
            rules = listOf(rule(action = NetworkAction.Timeout(TimeoutType.READ))),
        )

        val decision = ScenarioEvaluator.evaluate(scenario, target()) as ScenarioDecision.FailTimeout

        assertEquals(TimeoutType.READ, decision.type)
        assertEquals(DecisionOrigin.ENDPOINT_RULE, decision.origin)
    }

    @Test
    fun `global latency becomes a delay decision`() {
        val scenario = NetworkScenario(global = GlobalNetworkConfig(latencyMillis = 2_500))

        val decision = ScenarioEvaluator.evaluate(scenario, target()) as ScenarioDecision.Delay

        assertEquals(2_500L, decision.delayMillis)
        assertEquals(DecisionOrigin.GLOBAL, decision.origin)
        assertFalse(decision.isSimulated)
    }

    @Test
    fun `a zero-millisecond endpoint delay collapses to pass through`() {
        val scenario = NetworkScenario(rules = listOf(rule(action = NetworkAction.Delay(0))))

        val decision = ScenarioEvaluator.evaluate(scenario, target())

        assertTrue(decision is ScenarioDecision.PassThrough)
        assertEquals(DecisionOrigin.ENDPOINT_RULE, decision.origin)
    }

    @Test
    fun `an http error without a body gets the default envelope`() {
        val scenario = NetworkScenario(rules = listOf(rule(action = NetworkAction.HttpError(404))))

        val decision = ScenarioEvaluator.evaluate(scenario, target()) as ScenarioDecision.RespondWith

        assertEquals(NetKitDefaults.DEFAULT_ERROR_BODY, decision.body)
        assertEquals("application/json", decision.contentType)
    }

    @Test
    fun `a custom body and content type are carried through`() {
        val scenario = NetworkScenario(
            rules = listOf(
                rule(
                    action = NetworkAction.HttpError(
                        statusCode = 503,
                        body = """{"message":"Bookings service temporarily unavailable"}""",
                        contentType = "application/problem+json",
                        delayMillis = 250,
                    ),
                    name = "Bookings Failure",
                ),
            ),
        )

        val decision = ScenarioEvaluator.evaluate(scenario, target()) as ScenarioDecision.RespondWith

        assertEquals(503, decision.statusCode)
        assertEquals("""{"message":"Bookings service temporarily unavailable"}""", decision.body)
        assertEquals("application/problem+json", decision.contentType)
        assertEquals(250L, decision.delayMillis)
        assertEquals("Bookings Failure", decision.scenarioLabel)
        assertTrue(decision.isSimulated)
    }

    @Test
    fun `the responsible rule id travels with the decision`() {
        val configured = rule()
        val scenario = NetworkScenario(rules = listOf(configured))

        val decision = ScenarioEvaluator.evaluate(scenario, target())

        assertEquals(configured.id, decision.ruleId)
    }

    @Test
    fun `a pass-through decision from an idle scenario names no scenario`() {
        val decision = ScenarioEvaluator.evaluate(NetworkScenario(), target())

        assertNull(decision.scenarioLabel)
        assertNull(decision.ruleId)
    }

    @Test
    fun `idle is true only when nothing can affect a request`() {
        assertTrue(NetworkScenario().isIdle)
        assertTrue(NetworkScenario(enabled = false, rules = listOf(rule())).isIdle)
        assertTrue(NetworkScenario(rules = listOf(rule(enabled = false))).isIdle)
        assertFalse(NetworkScenario(rules = listOf(rule())).isIdle)
        assertFalse(NetworkScenario(global = GlobalNetworkConfig(latencyMillis = 1)).isIdle)
        assertFalse(
            NetworkScenario(global = GlobalNetworkConfig(mode = GlobalNetworkMode.Offline)).isIdle,
        )
    }

    @Test
    fun `a custom matcher participates in evaluation`() {
        val prefixMatcher = object : EndpointMatcher {
            override val label = "/api/v1/*"
            override fun matches(target: RequestTarget) = target.path.startsWith("/api/v1/")
        }
        val scenario = NetworkScenario(
            rules = listOf(
                EndpointRule(matcher = prefixMatcher, action = NetworkAction.HttpError(429)),
            ),
        )

        val matched = ScenarioEvaluator.evaluate(scenario, target(path = "/api/v1/anything"))
        val unmatched = ScenarioEvaluator.evaluate(scenario, target(path = "/api/v2/anything"))

        assertTrue(matched is ScenarioDecision.RespondWith)
        assertTrue(unmatched is ScenarioDecision.PassThrough)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an out-of-range status is rejected at construction`() {
        NetworkAction.HttpError(999)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative latency is rejected at construction`() {
        GlobalNetworkConfig(latencyMillis = -1)
    }
}
