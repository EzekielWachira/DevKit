package io.devkit.netkit

import io.devkit.netkit.scenario.EndpointMatcher
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.MalformedResponseType
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.ResponseHeader
import io.devkit.netkit.scenario.SequenceCompletionBehavior
import io.devkit.netkit.scenario.SequenceStep
import io.devkit.netkit.scenario.RequestTarget
import io.devkit.netkit.scenario.TimeoutType
import io.devkit.netkit.ui.scenarios.RuleBehavior
import io.devkit.netkit.ui.scenarios.RuleEditorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule editor's validation lives in a plain value class, so the rules a QA
 * engineer runs into are testable without launching Compose.
 */
class RuleEditorStateTest {

    @Test
    fun `a blank path is rejected`() {
        val form = RuleEditorState.new().copy(pathText = "   ")

        assertNotNull(form.pathError)
        assertFalse(form.isValid)
        assertNull(form.toRule())
    }

    @Test
    fun `a path with a query string is rejected`() {
        val form = RuleEditorState.new().copy(pathText = "/api/v1/bookings?page=2")

        assertNotNull(form.pathError)
    }

    @Test
    fun `a path with spaces is rejected`() {
        val form = RuleEditorState.new().copy(pathText = "/api/v1/my bookings")

        assertNotNull(form.pathError)
    }

    @Test
    fun `a valid http error form builds a rule`() {
        val form = RuleEditorState.new().copy(
            method = HttpMethod.GET,
            pathText = "/api/v1/bookings",
            behavior = RuleBehavior.RESPONSE,
            statusText = "500",
            bodyText = """{"message":"boom"}""",
            name = "Bookings Failure",
        )

        val rule = form.toRule()!!

        assertEquals(HttpMethod.GET, rule.method)
        assertEquals("/api/v1/bookings", rule.matcher.label)
        assertEquals("Bookings Failure", rule.name)
        val action = rule.action as NetworkAction.ReturnResponse
        assertEquals(500, action.statusCode)
        assertEquals("""{"message":"boom"}""", action.body)
    }

    @Test
    fun `a non-numeric status is rejected`() {
        val form = RuleEditorState.new().copy(pathText = "/x", statusText = "five hundred")

        assertNotNull(form.statusError)
        assertNull(form.toRule())
    }

    @Test
    fun `an out-of-range status is rejected`() {
        listOf("0", "99", "600", "-1").forEach { status ->
            val form = RuleEditorState.new().copy(pathText = "/x", statusText = status)
            assertNotNull("status $status should be rejected", form.statusError)
        }
    }

    @Test
    fun `a negative delay is rejected`() {
        val form = RuleEditorState.new().copy(
            pathText = "/x",
            behavior = RuleBehavior.DELAY,
            delayText = "-5",
        )

        assertNotNull(form.delayError)
        assertNull(form.toRule())
    }

    @Test
    fun `an empty delay means no delay`() {
        val form = RuleEditorState.new().copy(
            pathText = "/x",
            behavior = RuleBehavior.DELAY,
            delayText = "",
        )

        assertNull(form.delayError)
        assertEquals(NetworkAction.Delay(0), form.toRule()?.action)
    }

    @Test
    fun `status validation only applies to the http error behavior`() {
        val form = RuleEditorState.new().copy(
            pathText = "/x",
            behavior = RuleBehavior.OFFLINE,
            statusText = "not a number",
        )

        assertNull(form.statusError)
        assertEquals(NetworkAction.Offline, form.toRule()?.action)
    }

    @Test
    fun `a blank body means the default envelope`() {
        val form = RuleEditorState.new().copy(pathText = "/x", bodyText = "   ")

        assertNull((form.toRule()?.action as NetworkAction.ReturnResponse).body)
    }

    @Test
    fun `the timeout behavior carries the selected type`() {
        val form = RuleEditorState.new().copy(
            pathText = "/x",
            behavior = RuleBehavior.TIMEOUT,
            timeoutType = TimeoutType.CONNECT,
        )

        assertEquals(NetworkAction.Timeout(TimeoutType.CONNECT), form.toRule()?.action)
    }

    @Test
    fun `editing an existing rule keeps its id`() {
        val original = EndpointRule.forPath(
            path = "/api/v1/bookings",
            method = HttpMethod.POST,
            action = NetworkAction.Delay(750),
            name = "Slow checkout",
        )

        val edited = RuleEditorState.from(original).copy(name = "Renamed").toRule()!!

        assertEquals(original.id, edited.id)
        assertEquals(HttpMethod.POST, edited.method)
        assertEquals("Renamed", edited.name)
        assertEquals(NetworkAction.Delay(750), edited.action)
    }

    @Test
    fun `round-tripping every behavior preserves the action`() {
        val actions = listOf(
            NetworkAction.PassThrough,
            NetworkAction.Delay(1_500),
            NetworkAction.ReturnResponse(
                statusCode = 422,
                body = """{"a":1}""",
                contentType = "application/json",
                delayMillis = 100,
            ),
            NetworkAction.ReturnResponse(
                statusCode = 429,
                headers = listOf(ResponseHeader("Retry-After", "60")),
            ),
            NetworkAction.Malformed(MalformedResponseType.TruncatedJson),
            NetworkAction.Sequence(
                steps = listOf(
                    SequenceStep(NetworkAction.ReturnResponse(500)),
                    SequenceStep(NetworkAction.Timeout(TimeoutType.READ)),
                    SequenceStep(NetworkAction.ReturnResponse(200, """{"ok":true}""")),
                ),
                completion = SequenceCompletionBehavior.PASS_THROUGH,
            ),
            NetworkAction.Offline,
            NetworkAction.Timeout(TimeoutType.READ),
        )

        actions.forEach { action ->
            val rule = EndpointRule.forPath("/api/v1/x", HttpMethod.PUT, action)
            assertEquals(action, RuleEditorState.from(rule).toRule()?.action)
        }
    }

    @Test
    fun `a custom matcher survives an edit instead of being downgraded`() {
        val custom = object : EndpointMatcher {
            override val label = "/api/v1/*"
            override fun matches(target: RequestTarget) = target.path.startsWith("/api/v1/")
        }
        val original = EndpointRule(matcher = custom, action = NetworkAction.Offline)

        val form = RuleEditorState.from(original)
        val edited = form.copy(name = "Prefix rule").toRule()!!

        assertNull(form.pathError)
        assertTrue(edited.matcher === custom)
    }

    @Test
    fun `the disabled toggle is carried into the built rule`() {
        val form = RuleEditorState.new().copy(pathText = "/x", enabled = false)

        assertFalse(form.toRule()!!.enabled)
    }
}
