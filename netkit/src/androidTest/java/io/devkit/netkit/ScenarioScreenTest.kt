package io.devkit.netkit

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.devkit.netkit.config.NetKitConfig
import io.devkit.netkit.history.InMemoryNetworkHistoryStore
import io.devkit.netkit.history.NetworkOutcome
import io.devkit.netkit.history.NetworkRecord
import io.devkit.netkit.replay.ReplayEligibility
import io.devkit.netkit.replay.ReplayOverride
import io.devkit.netkit.replay.ReplayResult
import io.devkit.netkit.replay.ReplaySnapshotStore
import io.devkit.netkit.replay.ReplayUnavailableReason
import io.devkit.netkit.replay.RequestReplayer
import io.devkit.netkit.scenario.MalformedResponseType
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.SequenceStep
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.pack.scenarioPack
import io.devkit.netkit.scenario.persistence.InMemoryScenarioStorage
import io.devkit.netkit.scenario.persistence.JsonScenarioRepository
import io.devkit.netkit.scenario.runtime.ScenarioManager
import io.devkit.netkit.scenario.serialization.JsonScenarioSerializer
import io.devkit.netkit.state.DefaultNetKitController
import io.devkit.netkit.state.NetKitController
import io.devkit.netkit.ui.NetKitScreen
import io.devkit.netkit.ui.NetKitTestTags
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose coverage of the 0.2 scenario surfaces.
 *
 * The assertions are on controller state rather than on pixels, for the same
 * reason as the 0.1 suite: the point is that a tap reaches the runtime. What is
 * asserted on screen is limited to the things a person must be able to *see* —
 * that a scenario is active, that a replay of a `POST` warns first — because
 * those are contracts, not styling.
 */
@RunWith(AndroidJUnit4::class)
class ScenarioScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val history = InMemoryNetworkHistoryStore(NetKitConfig().maxHistoryEntries)
    private val serializer = JsonScenarioSerializer()
    private val repository = JsonScenarioRepository(InMemoryScenarioStorage())

    private val demoPack = scenarioPack("Demo API") {
        scenario("Server error") {
            get("/api/v1/bookings") { respond(500) }
        }
        scenario("Retry eventually succeeds") {
            get("/api/v1/bookings") {
                sequence {
                    respond(500)
                    respond(500)
                    respond(200)
                }
            }
        }
    }

    private val manager = ScenarioManager(
        repository = repository,
        serializer = serializer,
        scope = scope,
        builtInPacks = listOf(demoPack),
    )

    private val controller: NetKitController = DefaultNetKitController(
        historyStore = history,
        manager = manager,
        replayer = FakeReplayer,
        replaySnapshots = ReplaySnapshotStore(),
        scope = scope,
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun setContent() {
        compose.setContent {
            MaterialTheme {
                NetKitScreen(controller = controller, onClose = {})
            }
        }
    }

    private fun openScenarios() {
        compose.onNodeWithTag(NetKitTestTags.TAB_SCENARIOS).performClick()
    }

    private fun save(scenario: NetworkScenario): NetworkScenario =
        runBlocking { manager.save(scenario) }.scenarioOrNull!!

    private fun record(id: Long = 1, method: String = "GET") = NetworkRecord(
        id = id,
        startedAtMillis = System.currentTimeMillis(),
        durationMillis = 12,
        method = method,
        scheme = "https",
        host = "api.example.com",
        path = "/api/v1/checkout",
        url = "https://api.example.com/api/v1/checkout",
        outcome = NetworkOutcome.Completed(200, "OK"),
    )

    // ---- listing -----------------------------------------------------------

    @Test
    fun scenarioListRenders() {
        setContent()

        openScenarios()

        compose.onNodeWithTag(NetKitTestTags.SCENARIO_LIST).assertIsDisplayed()
        compose.onNodeWithText("Server error").assertIsDisplayed()
        compose.onNodeWithText("Demo API").assertIsDisplayed()
    }

    @Test
    fun savedScenarioAppearsInTheList() {
        val saved = save(NetworkScenario(name = "Checkout failure"))
        setContent()

        openScenarios()

        compose.onNodeWithTag(NetKitTestTags.SCENARIO_ROW_PREFIX + saved.id.value)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun searchNarrowsTheList() {
        save(NetworkScenario(name = "Checkout failure"))
        save(NetworkScenario(name = "Slow dashboard"))
        setContent()

        openScenarios()
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_SEARCH).performTextInput("Slow")

        compose.onNodeWithText("Slow dashboard").assertIsDisplayed()
        compose.onNodeWithText("Checkout failure").assertIsNotDisplayed()
    }

    // ---- create, activate, duplicate, delete -------------------------------

    @Test
    fun scenarioCanBeCreated() {
        setContent()

        openScenarios()
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_NEW).performScrollTo().performClick()
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_EDITOR).assertIsDisplayed()
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_EDITOR_NAME)
            .performTextInput("Checkout failure")
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_EDITOR_SAVE).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(
            "Checkout failure",
            manager.scenarios.value.first { !it.metadata.isReadOnly }.name,
        )
    }

    @Test
    fun aScenarioWithoutANameCannotBeSaved() {
        setContent()

        openScenarios()
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_NEW).performScrollTo().performClick()
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_EDITOR_SAVE).performScrollTo().performClick()
        compose.waitForIdle()

        assertTrue(manager.scenarios.value.none { !it.metadata.isReadOnly })
    }

    @Test
    fun ruleCanBeAddedToAScenarioWithTheSharedEditor() {
        setContent()

        openScenarios()
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_NEW).performScrollTo().performClick()
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_EDITOR_NAME).performTextInput("Bookings")
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_EDITOR_ADD_RULE)
            .performScrollTo()
            .performClick()

        // The very same sheet the console uses for temporary overrides.
        compose.onNodeWithTag(NetKitTestTags.EDITOR).assertIsDisplayed()
        compose.onNodeWithTag(NetKitTestTags.EDITOR_PATH).performTextInput("/api/v1/bookings")
        compose.onNodeWithTag(NetKitTestTags.EDITOR_SAVE).performScrollTo().performClick()
        compose.waitForIdle()

        // The rule lands in the draft before anything is persisted.
        compose.onNodeWithText("/api/v1/bookings").assertIsDisplayed()

        compose.onNodeWithTag(NetKitTestTags.SCENARIO_EDITOR_SAVE).performScrollTo().performClick()
        compose.waitForIdle()

        val saved = manager.scenarios.value.first { !it.metadata.isReadOnly }
        assertEquals("/api/v1/bookings", saved.rules.single().matcher.label)
    }

    @Test
    fun scenarioCanBeActivatedAndDeactivated() {
        val saved = save(NetworkScenario(name = "Checkout failure", rules = listOf(rule())))
        setContent()

        openScenarios()
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_ROW_PREFIX + saved.id.value)
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_DETAIL_ACTIVATE)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        assertEquals(saved.id, manager.activeScenarioId.value)
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_ACTIVE_CARD).assertIsDisplayed()

        compose.onNodeWithTag(NetKitTestTags.SCENARIO_DEACTIVATE).performScrollTo().performClick()
        compose.waitForIdle()

        assertNull(manager.activeScenarioId.value)
    }

    @Test
    fun activeScenarioIndicatorAppearsOnTheConsole() {
        val saved = save(NetworkScenario(name = "Checkout failure", rules = listOf(rule())))
        runBlocking { manager.activate(saved.id) }
        setContent()

        compose.onNodeWithTag(NetKitTestTags.SCENARIO_ACTIVE_CARD)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("ACTIVE").assertIsDisplayed()
        compose.onNodeWithText("Checkout failure").assertIsDisplayed()
    }

    @Test
    fun scenarioCanBeDuplicated() {
        val saved = save(NetworkScenario(name = "Checkout failure", rules = listOf(rule())))
        setContent()

        openScenarios()
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_ROW_PREFIX + saved.id.value)
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_DETAIL_DUPLICATE)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        assertEquals(2, manager.scenarios.value.count { !it.metadata.isReadOnly })
    }

    @Test
    fun scenarioDeletionAsksFirst() {
        val saved = save(NetworkScenario(name = "Checkout failure"))
        setContent()

        openScenarios()
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_ROW_PREFIX + saved.id.value)
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_DETAIL_DELETE)
            .performScrollTo()
            .performClick()

        compose.onNodeWithTag(NetKitTestTags.CONFIRM_DIALOG).assertIsDisplayed()
        assertEquals(1, manager.scenarios.value.count { !it.metadata.isReadOnly })

        compose.onNodeWithTag(NetKitTestTags.CONFIRM_ACCEPT).performClick()
        compose.waitForIdle()

        assertTrue(manager.scenarios.value.none { !it.metadata.isReadOnly })
    }

    @Test
    fun aBuiltInScenarioHidesEditAndDelete() {
        setContent()

        openScenarios()
        compose.onNodeWithTag(
            NetKitTestTags.SCENARIO_ROW_PREFIX + demoPack.scenarios.first().id.value,
        ).performScrollTo().performClick()

        compose.onNodeWithTag(NetKitTestTags.SCENARIO_DETAIL_DUPLICATE)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_DETAIL_EDIT).assertDoesNotExist()
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_DETAIL_DELETE).assertDoesNotExist()
    }

    // ---- sequences ---------------------------------------------------------

    @Test
    fun sequenceEditorAddsAndRemovesSteps() {
        setContent()

        compose.onNodeWithTag(NetKitTestTags.ADD_RULE).performScrollTo().performClick()
        compose.onNodeWithTag(NetKitTestTags.EDITOR_PATH).performTextInput("/api/v1/bookings")
        compose.onNodeWithTag(NetKitTestTags.EDITOR_BEHAVIOR_PREFIX + "SEQUENCE")
            .performScrollTo()
            .performClick()

        compose.onNodeWithTag(NetKitTestTags.SEQUENCE_EDITOR).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(NetKitTestTags.SEQUENCE_STEP_PREFIX + "0").assertIsDisplayed()

        compose.onNodeWithTag(NetKitTestTags.SEQUENCE_ADD_STEP).performScrollTo().performClick()
        compose.onNodeWithTag(NetKitTestTags.SEQUENCE_STEP_PREFIX + "1")
            .performScrollTo()
            .assertIsDisplayed()

        compose.onNodeWithTag(NetKitTestTags.SEQUENCE_ADD_STEP).performScrollTo().performClick()
        compose.onNodeWithTag(NetKitTestTags.EDITOR_SAVE).performScrollTo().performClick()
        compose.waitForIdle()

        val action = controller.state.value.rules.single().action as NetworkAction.Sequence
        assertEquals(3, action.steps.size)
    }

    @Test
    fun sequenceProgressIsShownForTheActiveScenario() {
        val retry = demoPack.scenarios[1]
        runBlocking { manager.activate(retry.id) }
        manager.executionState.advance(
            retry.rules.single().id,
            3,
            io.devkit.netkit.scenario.SequenceCompletionBehavior.REPEAT_LAST,
        )
        setContent()

        compose.onNodeWithText("sequence 1 / 3", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun sequenceProgressCanBeResetFromTheScenarioDetail() {
        val retry = demoPack.scenarios[1]
        val ruleId = retry.rules.single().id
        runBlocking { manager.activate(retry.id) }
        manager.executionState.advance(
            ruleId,
            3,
            io.devkit.netkit.scenario.SequenceCompletionBehavior.REPEAT_LAST,
        )
        setContent()

        openScenarios()
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_ROW_PREFIX + retry.id.value)
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag(NetKitTestTags.RULE_SEQUENCE_RESET_PREFIX + ruleId)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        assertEquals(0, manager.executionState.peek(ruleId, 3).completed)
    }

    // ---- malformed ---------------------------------------------------------

    @Test
    fun malformedBehaviorCanBeChosen() {
        setContent()

        compose.onNodeWithTag(NetKitTestTags.ADD_RULE).performScrollTo().performClick()
        compose.onNodeWithTag(NetKitTestTags.EDITOR_PATH).performTextInput("/api/v1/bookings")
        compose.onNodeWithTag(NetKitTestTags.EDITOR_BEHAVIOR_PREFIX + "MALFORMED")
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag(
            NetKitTestTags.EDITOR_MALFORMED_PREFIX + MalformedResponseType.HtmlInsteadOfJson.label,
        ).performScrollTo().performClick()
        compose.onNodeWithTag(NetKitTestTags.EDITOR_SAVE).performScrollTo().performClick()
        compose.waitForIdle()

        val action = controller.state.value.rules.single().action as NetworkAction.Malformed
        assertEquals(MalformedResponseType.HtmlInsteadOfJson, action.type)
    }

    @Test
    fun customResponseHeadersCanBeAdded() {
        setContent()

        compose.onNodeWithTag(NetKitTestTags.ADD_RULE).performScrollTo().performClick()
        compose.onNodeWithTag(NetKitTestTags.EDITOR_PATH).performTextInput("/api/v1/bookings")
        compose.onNodeWithTag(NetKitTestTags.EDITOR_HEADER_ADD).performScrollTo().performClick()
        compose.onNodeWithTag(NetKitTestTags.EDITOR_HEADER_NAME_PREFIX + "0")
            .performScrollTo()
            .performTextInput("Retry-After")
        compose.onNodeWithTag(NetKitTestTags.EDITOR_HEADER_VALUE_PREFIX + "0")
            .performTextInput("60")
        compose.onNodeWithTag(NetKitTestTags.EDITOR_SAVE).performScrollTo().performClick()
        compose.waitForIdle()

        val action = controller.state.value.rules.single().action as NetworkAction.ReturnResponse
        assertEquals("Retry-After", action.headers.single().name)
        assertEquals("60", action.headers.single().value)
    }

    // ---- replay ------------------------------------------------------------

    @Test
    fun replayOpensFromTheHistoryDetail() {
        history.record(record(id = 1))
        setContent()

        compose.onNodeWithTag(NetKitTestTags.TAB_HISTORY).performClick()
        compose.onNodeWithTag(NetKitTestTags.HISTORY_ROW_PREFIX + "1").performClick()
        compose.onNodeWithTag(NetKitTestTags.DETAIL_REPLAY).performScrollTo().performClick()

        compose.onNodeWithTag(NetKitTestTags.REPLAY_SHEET).assertIsDisplayed()
    }

    @Test
    fun replayingASideEffectfulMethodWarnsFirst() {
        history.record(record(id = 2, method = "POST"))
        setContent()

        compose.onNodeWithTag(NetKitTestTags.TAB_HISTORY).performClick()
        compose.onNodeWithTag(NetKitTestTags.HISTORY_ROW_PREFIX + "2").performClick()
        compose.onNodeWithTag(NetKitTestTags.DETAIL_REPLAY).performScrollTo().performClick()

        // The warning is a contract, not styling: a QA engineer must not be able
        // to write to a real backend without being told.
        compose.onNodeWithText("This request may create or modify real backend data.")
            .assertIsDisplayed()
        compose.onNodeWithText("Replay POST").assertIsDisplayed()
        assertFalse(FakeReplayer.replayed)
    }

    @Test
    fun cancellingAReplaySendsNothing() {
        history.record(record(id = 3, method = "DELETE"))
        setContent()

        compose.onNodeWithTag(NetKitTestTags.TAB_HISTORY).performClick()
        compose.onNodeWithTag(NetKitTestTags.HISTORY_ROW_PREFIX + "3").performClick()
        compose.onNodeWithTag(NetKitTestTags.DETAIL_REPLAY).performScrollTo().performClick()
        compose.onNodeWithTag(NetKitTestTags.REPLAY_CANCEL).performScrollTo().performClick()
        compose.waitForIdle()

        assertFalse(FakeReplayer.replayed)
    }

    // ---- history filters ---------------------------------------------------

    @Test
    fun historyFiltersNarrowTheList() {
        history.record(record(id = 1))
        history.record(
            record(id = 2).copy(
                source = io.devkit.netkit.history.NetworkRecordSource.SIMULATED,
            ),
        )
        setContent()

        compose.onNodeWithTag(NetKitTestTags.TAB_HISTORY).performClick()
        compose.onNodeWithTag(NetKitTestTags.HISTORY_FILTER_PREFIX + "SIMULATED").performClick()

        compose.onNodeWithTag(NetKitTestTags.HISTORY_ROW_PREFIX + "2").assertIsDisplayed()
        compose.onNodeWithTag(NetKitTestTags.HISTORY_ROW_PREFIX + "1").assertDoesNotExist()
    }

    // ---- save current setup ------------------------------------------------

    @Test
    fun currentSetupCanBeSavedAsAScenario() {
        controller.setGlobalLatency(2_500)
        controller.addRule(rule())
        setContent()

        openScenarios()
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_SAVE_CURRENT).performScrollTo().performClick()
        compose.onNodeWithText("Name").performTextInput("Captured")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        val saved = manager.scenarios.value.first { !it.metadata.isReadOnly }
        assertEquals("Captured", saved.name)
        assertEquals(2_500L, saved.globalConfig?.latencyMillis)
        assertNotNull(saved.rules.singleOrNull())
        assertTrue(controller.state.value.rules.isEmpty())
    }

    private fun rule() = io.devkit.netkit.scenario.EndpointRule.forPath(
        path = "/api/v1/bookings",
        action = NetworkAction.ReturnResponse(500),
    )
}

/**
 * A replayer that records whether it was asked to send, and never does.
 *
 * The console's replay tests are about the *confirmation flow*; actually
 * sending is covered by `RequestReplayTest` against MockWebServer.
 */
private object FakeReplayer : RequestReplayer {

    var replayed: Boolean = false
        private set

    override fun eligibility(recordId: Long): ReplayEligibility =
        ReplayEligibility.Eligible(fakeSnapshot(recordId))

    override suspend fun replay(
        recordId: Long,
        override: ReplayOverride,
        bypassNetKit: Boolean,
    ): ReplayResult {
        replayed = true
        return ReplayResult.Unavailable(ReplayUnavailableReason.NO_SNAPSHOT)
    }

    private fun fakeSnapshot(recordId: Long) = io.devkit.netkit.replay.ReplaySnapshot(
        recordId = recordId,
        request = okhttp3.Request.Builder()
            .url("https://api.example.com/api/v1/checkout")
            .apply {
                when (recordId) {
                    1L -> get()
                    3L -> delete()
                    else -> post(
                        "{}".toRequestBody("application/json".toMediaType()),
                    )
                }
            }
            .build(),
        hasReplaceableBody = false,
    )
}
