package io.devkit.netkit

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.devkit.netkit.config.NetKitConfig
import io.devkit.netkit.history.InMemoryNetworkHistoryStore
import io.devkit.netkit.history.NetworkOutcome
import io.devkit.netkit.history.NetworkRecord
import io.devkit.netkit.history.NetworkRecordSource
import io.devkit.netkit.masking.MaskedHeader
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.state.DefaultNetKitController
import io.devkit.netkit.state.NetKitController
import io.devkit.netkit.ui.NetKitScreen
import io.devkit.netkit.ui.NetKitTestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose coverage of the console.
 *
 * The assertions are on controller state rather than on pixels: the point of
 * these tests is that a tap reaches the runtime, which is the contract the rest
 * of NetKit depends on.
 */
@RunWith(AndroidJUnit4::class)
class NetKitScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val history = InMemoryNetworkHistoryStore(NetKitConfig().maxHistoryEntries)
    private val controller: NetKitController = DefaultNetKitController(history)

    private fun setContent() {
        compose.setContent {
            MaterialTheme {
                NetKitScreen(controller = controller, onClose = {})
            }
        }
    }

    private fun record(
        id: Long = 1,
        path: String = "/api/v1/bookings",
        simulated: Boolean = false,
    ) = NetworkRecord(
        id = id,
        startedAtMillis = System.currentTimeMillis(),
        durationMillis = 182,
        method = "GET",
        scheme = "https",
        host = "api.example.com",
        path = path,
        url = "https://api.example.com$path",
        requestHeaders = listOf(MaskedHeader("Authorization", "Bearer ••••••••", masked = true)),
        outcome = NetworkOutcome.Completed(if (simulated) 500 else 200, "OK"),
        source = if (simulated) NetworkRecordSource.SIMULATED else NetworkRecordSource.REAL,
        scenarioLabel = if (simulated) "Bookings Failure" else null,
    )

    @Test
    fun screenRenders() {
        setContent()

        compose.onNodeWithTag(NetKitTestTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithText("NetKit").assertIsDisplayed()
        compose.onNodeWithTag(NetKitTestTags.ENABLED_SWITCH).assertIsOn()
    }

    @Test
    fun masterSwitchDisablesTheRuntime() {
        setContent()

        compose.onNodeWithTag(NetKitTestTags.ENABLED_SWITCH).performClick()

        compose.onNodeWithTag(NetKitTestTags.ENABLED_SWITCH).assertIsOff()
        assertFalse(controller.state.value.enabled)
    }

    @Test
    fun offlineModeUpdatesState() {
        setContent()

        compose.onNodeWithTag(NetKitTestTags.GLOBAL_MODE_PREFIX + "Offline").performClick()

        assertEquals(GlobalNetworkMode.Offline, controller.state.value.global.mode)
        assertTrue(controller.state.value.isSimulating)
    }

    @Test
    fun timeoutModeUpdatesState() {
        setContent()

        compose.onNodeWithTag(NetKitTestTags.GLOBAL_MODE_PREFIX + "Read timeout").performClick()

        assertTrue(controller.state.value.global.mode is GlobalNetworkMode.Timeout)
    }

    @Test
    fun latencyPresetUpdatesState() {
        setContent()

        compose.onNodeWithTag(NetKitTestTags.LATENCY_PRESET_PREFIX + "2500").performClick()

        assertEquals(2_500L, controller.state.value.global.latencyMillis)
    }

    @Test
    fun customLatencyUpdatesState() {
        setContent()

        compose.onNodeWithTag(NetKitTestTags.LATENCY_PRESET_PREFIX + "custom").performClick()
        compose.onNodeWithTag(NetKitTestTags.LATENCY_CUSTOM_FIELD).performTextClearance()
        compose.onNodeWithTag(NetKitTestTags.LATENCY_CUSTOM_FIELD).performTextInput("1750")

        assertEquals(1_750L, controller.state.value.global.latencyMillis)
    }

    @Test
    fun endpointRuleCanBeCreated() {
        setContent()

        compose.onNodeWithTag(NetKitTestTags.ADD_RULE).performScrollTo().performClick()
        compose.onNodeWithTag(NetKitTestTags.EDITOR).assertIsDisplayed()
        compose.onNodeWithTag(NetKitTestTags.EDITOR_METHOD_PREFIX + "GET").performClick()
        compose.onNodeWithTag(NetKitTestTags.EDITOR_PATH).performTextInput("/api/v1/bookings")
        compose.onNodeWithTag(NetKitTestTags.EDITOR_SAVE).performScrollTo().performClick()

        val rule = controller.state.value.rules.single()
        assertEquals(HttpMethod.GET, rule.method)
        assertEquals("/api/v1/bookings", rule.matcher.label)
        assertEquals(500, (rule.action as NetworkAction.HttpError).statusCode)
    }

    @Test
    fun invalidPathBlocksSaving() {
        setContent()

        compose.onNodeWithTag(NetKitTestTags.ADD_RULE).performScrollTo().performClick()
        compose.onNodeWithTag(NetKitTestTags.EDITOR_SAVE).performScrollTo().performClick()

        assertTrue("an empty path must not create a rule", controller.state.value.rules.isEmpty())
    }

    @Test
    fun ruleCanBeToggled() {
        val rule = EndpointRule.forPath("/api/v1/bookings", action = NetworkAction.HttpError(500))
        controller.addRule(rule)
        setContent()

        compose.onNodeWithTag(NetKitTestTags.RULE_TOGGLE_PREFIX + rule.id).performScrollTo().performClick()
        assertFalse(controller.state.value.rules.single().enabled)

        compose.onNodeWithTag(NetKitTestTags.RULE_TOGGLE_PREFIX + rule.id).performClick()
        assertTrue(controller.state.value.rules.single().enabled)
    }

    @Test
    fun ruleCanBeDeleted() {
        val rule = EndpointRule.forPath("/api/v1/bookings", action = NetworkAction.HttpError(500))
        controller.addRule(rule)
        setContent()

        compose.onNodeWithTag(NetKitTestTags.RULE_ROW_PREFIX + rule.id).performScrollTo().performClick()
        compose.onNodeWithTag(NetKitTestTags.EDITOR_DELETE).performScrollTo().performClick()

        assertTrue(controller.state.value.rules.isEmpty())
    }

    @Test
    fun historyRendersAndMarksSimulatedRequests() {
        history.record(record(id = 1))
        history.record(record(id = 2, path = "/api/v1/checkout", simulated = true))
        setContent()

        compose.onNodeWithTag(NetKitTestTags.TAB_HISTORY).performClick()

        compose.onNodeWithTag(NetKitTestTags.HISTORY_ROW_PREFIX + "1").assertIsDisplayed()
        compose.onNodeWithTag(NetKitTestTags.HISTORY_ROW_PREFIX + "2").assertIsDisplayed()
        compose.onNodeWithText("SIMULATED").assertIsDisplayed()
    }

    @Test
    fun requestDetailsOpenWithMaskedHeaders() {
        history.record(record(id = 7))
        setContent()

        compose.onNodeWithTag(NetKitTestTags.TAB_HISTORY).performClick()
        compose.onNodeWithTag(NetKitTestTags.HISTORY_ROW_PREFIX + "7").performClick()

        compose.onNodeWithTag(NetKitTestTags.DETAIL).assertIsDisplayed()
        compose.onNodeWithText("Bearer ••••••••").assertIsDisplayed()
        compose.onNodeWithTag(NetKitTestTags.DETAIL_COPY).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun clearHistoryEmptiesTheList() {
        history.record(record())
        setContent()

        compose.onNodeWithTag(NetKitTestTags.HISTORY_CLEAR).performScrollTo().performClick()

        assertTrue(controller.history.value.isEmpty())
    }

    @Test
    fun resetRestoresNormalNetworking() {
        controller.addRule(EndpointRule.forPath("/api/v1/bookings", action = NetworkAction.Offline))
        controller.setOffline(true)
        controller.setGlobalLatency(5_000)
        setContent()

        compose.onNodeWithTag(NetKitTestTags.RESET).performScrollTo().performClick()

        val state = controller.state.value
        assertTrue(state.rules.isEmpty())
        assertEquals(GlobalNetworkMode.Normal, state.global.mode)
        assertEquals(0L, state.global.latencyMillis)
        assertFalse(state.isSimulating)
    }
}
