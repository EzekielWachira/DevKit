package io.devkit.netkit

import io.devkit.netkit.masking.DefaultSensitiveDataMasker
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.ResponseHeader
import io.devkit.netkit.scenario.SequenceStep
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.model.ScenarioPack
import io.devkit.netkit.scenario.serialization.JsonScenarioSerializer
import io.devkit.netkit.scenario.serialization.ScenarioImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What must never appear in an exported `.netkit.json`.
 *
 * An exported scenario is attached to bug tickets, pasted into chat and
 * occasionally committed to a repository. It is authored data, so it *should*
 * hold no secrets — but a custom response header is free text, and "paste the
 * real Set-Cookie so the app behaves" is exactly the shortcut someone takes at
 * six in the evening. These tests are the backstop for that moment.
 */
class ExportSecurityTest {

    private val serializer = JsonScenarioSerializer()

    private val secret = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.super-secret"

    private fun scenarioWith(vararg headers: ResponseHeader) = NetworkScenario(
        name = "Leaky",
        rules = listOf(
            Fixtures.rule(
                action = NetworkAction.ReturnResponse(
                    statusCode = 200,
                    headers = headers.toList(),
                ),
            ),
        ),
    )

    @Test
    fun `an authorization header is stripped on export`() {
        val export = serializer.exportScenario(
            scenarioWith(ResponseHeader("Authorization", secret)),
        )

        assertFalse(export.content.contains(secret))
        assertFalse(export.content.contains("Authorization"))
        assertEquals(1, export.warnings.size)
        assertTrue(export.warnings.single().contains("Authorization"))
    }

    @Test
    fun `credential headers are matched case-insensitively`() {
        DefaultSensitiveDataMasker.DEFAULT_HEADER_NAMES.forEach { name ->
            listOf(name, name.lowercase(), name.uppercase()).forEach { variant ->
                val export = serializer.exportScenario(
                    scenarioWith(ResponseHeader(variant, secret)),
                )
                assertFalse(
                    "expected '$variant' to be stripped",
                    export.content.contains(secret),
                )
            }
        }
    }

    @Test
    fun `harmless headers are kept`() {
        val export = serializer.exportScenario(
            scenarioWith(
                ResponseHeader("Retry-After", "60"),
                ResponseHeader("X-RateLimit-Remaining", "0"),
                ResponseHeader("Content-Language", "fr"),
            ),
        )

        assertTrue(export.content.contains("Retry-After"))
        assertTrue(export.content.contains("X-RateLimit-Remaining"))
        assertTrue(export.content.contains("Content-Language"))
        assertTrue(export.warnings.isEmpty())
    }

    @Test
    fun `a credential inside a sequence step is stripped too`() {
        val scenario = NetworkScenario(
            name = "Sequenced leak",
            rules = listOf(
                Fixtures.rule(
                    action = NetworkAction.Sequence(
                        listOf(
                            SequenceStep(NetworkAction.ReturnResponse(500)),
                            SequenceStep(
                                NetworkAction.ReturnResponse(
                                    statusCode = 200,
                                    headers = listOf(
                                        ResponseHeader("Set-Cookie", "session=abc123"),
                                        ResponseHeader("Retry-After", "1"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val export = serializer.exportScenario(scenario)

        assertFalse(export.content.contains("session=abc123"))
        assertTrue(export.content.contains("Retry-After"))
        assertEquals(1, export.warnings.size)
    }

    @Test
    fun `a pack export strips credentials from every scenario`() {
        val pack = ScenarioPack(name = "Checkout")
        val scenarios = listOf(
            scenarioWith(ResponseHeader("X-Api-Key", "k-123")),
            scenarioWith(ResponseHeader("Cookie", "session=xyz")),
        )

        val export = serializer.exportPack(pack, scenarios)

        assertFalse(export.content.contains("k-123"))
        assertFalse(export.content.contains("session=xyz"))
        assertEquals(2, export.warnings.size)
    }

    @Test
    fun `stripping does not mutate the scenario in memory`() {
        val scenario = scenarioWith(ResponseHeader("Authorization", secret))

        serializer.exportScenario(scenario)

        val action = scenario.rules.single().action as NetworkAction.ReturnResponse
        assertEquals(1, action.headers.size)
        assertEquals(secret, action.headers.single().value)
    }

    @Test
    fun `an export contains no history, replay or device data`() {
        val export = serializer.exportScenario(
            NetworkScenario(
                name = "Clean",
                rules = listOf(Fixtures.rule(action = NetworkAction.ReturnResponse(503))),
            ),
        )

        listOf(
            "requestHeaders",
            "responseHeaders",
            "history",
            "records",
            "replay",
            "snapshot",
            "deviceId",
            "androidId",
        ).forEach { forbidden ->
            assertFalse(
                "an export must not contain '$forbidden'",
                export.content.contains(forbidden, ignoreCase = true),
            )
        }
    }

    @Test
    fun `a stripped export still imports cleanly`() {
        val export = serializer.exportScenario(
            scenarioWith(
                ResponseHeader("Authorization", secret),
                ResponseHeader("Retry-After", "60"),
            ),
        )

        val result = serializer.import(export.content)

        assertTrue(result is ScenarioImportResult.Scenario)
        val action = (result as ScenarioImportResult.Scenario)
            .scenario.rules.single().action as NetworkAction.ReturnResponse
        assertEquals(listOf(ResponseHeader("Retry-After", "60")), action.headers)
    }
}
