package io.devkit.netkit

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
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
import io.devkit.netkit.replay.ReplayEligibility
import io.devkit.netkit.replay.ReplayOverride
import io.devkit.netkit.replay.ReplayResult
import io.devkit.netkit.replay.ReplaySnapshotStore
import io.devkit.netkit.replay.ReplayUnavailableReason
import io.devkit.netkit.replay.RequestReplayer
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.chaos.ChaosPresets
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.persistence.InMemoryScenarioStorage
import io.devkit.netkit.scenario.persistence.JsonScenarioRepository
import io.devkit.netkit.scenario.preset.AuthScenarioPresets
import io.devkit.netkit.scenario.preset.PaginationScenarioPresets
import io.devkit.netkit.scenario.runtime.ScenarioManager
import io.devkit.netkit.scenario.serialization.JsonScenarioSerializer
import io.devkit.netkit.state.DefaultNetKitController
import io.devkit.netkit.state.NetKitController
import io.devkit.netkit.ui.NetKitScreen
import io.devkit.netkit.ui.NetKitTestTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose coverage of the 0.3 surfaces.
 *
 * Assertions are on **controller state** rather than on pixels, following the
 * 0.1 and 0.2 suites: the contract being tested is that a tap reaches the
 * runtime, not that a chip is a particular shade. The few on-screen assertions
 * are for things a person genuinely must be able to see — the seed above all,
 * because a seed nobody can read is a seed nobody can put in a ticket.
 */
@RunWith(AndroidJUnit4::class)
class NetKit03ScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val history = InMemoryNetworkHistoryStore(NetKitConfig().maxHistoryEntries)
    private val repository = JsonScenarioRepository(InMemoryScenarioStorage())

    private val manager = ScenarioManager(
        repository = repository,
        serializer = JsonScenarioSerializer(),
        scope = scope,
    )

    private val controller: NetKitController = DefaultNetKitController(
        historyStore = history,
        manager = manager,
        replayer = FakeReplayer03,
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

    private fun openChaos() = compose.onNodeWithTag(NetKitTestTags.TAB_CHAOS).performClick()

    private fun openRun() = compose.onNodeWithTag(NetKitTestTags.TAB_RUN).performClick()

    private fun openScenarios() =
        compose.onNodeWithTag(NetKitTestTags.TAB_SCENARIOS).performClick()

    // ---- chaos ---------------------------------------------------------------

    @Test
    fun chaosScreenRenders() {
        setContent()
        openChaos()

        compose.onNodeWithTag(NetKitTestTags.CHAOS_SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(NetKitTestTags.CHAOS_ENABLED).assertIsDisplayed()
    }

    @Test
    fun chaosCanBeEnabledAndDisabled() {
        setContent()
        openChaos()

        compose.onNodeWithTag(NetKitTestTags.CHAOS_ENABLED).performClick()
        compose.waitForIdle()
        assertTrue(controller.state.value.configuration.chaos.enabled)

        compose.onNodeWithTag(NetKitTestTags.CHAOS_ENABLED).performClick()
        compose.waitForIdle()
        assertFalse(controller.state.value.configuration.chaos.enabled)
    }

    /** Turning chaos on with no scenario active must still give you a seed. */
    @Test
    fun enablingChaosStartsARun() {
        setContent()
        openChaos()

        compose.onNodeWithTag(NetKitTestTags.CHAOS_ENABLED).performClick()
        compose.waitForIdle()

        assertNotNull("chaos should have started a run", controller.runs.current.value)
        assertNotNull(controller.runs.seed)
    }

    @Test
    fun chaosPresetFillsInTheEditor() {
        setContent()
        openChaos()

        val preset = ChaosPresets.poorMobileNetwork
        compose.onNodeWithTag(NetKitTestTags.CHAOS_PRESET_PREFIX + preset.id)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        val chaos = controller.state.value.configuration.chaos
        assertEquals(preset.config.failureProbability, chaos.failureProbability)
        assertEquals(preset.config.latency, chaos.latency)
        assertTrue(chaos.enabled)
    }

    @Test
    fun chaosLatencyRangeCanBeEdited() {
        setContent()
        openChaos()
        compose.onNodeWithTag(NetKitTestTags.CHAOS_ENABLED).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(NetKitTestTags.CHAOS_LATENCY_MAX)
            .performScrollTo()
            .performTextClearance()
        compose.onNodeWithTag(NetKitTestTags.CHAOS_LATENCY_MAX).performTextInput("4000")
        compose.waitForIdle()

        assertEquals(4_000L, controller.state.value.configuration.chaos.latency.maxMillis)
    }

    /**
     * An inverted range must be impossible to produce, not merely flagged: the
     * editor clamps the other end rather than letting a scenario be saved that
     * the validator would refuse.
     */
    @Test
    fun chaosLatencyRangeCannotBeInverted() {
        setContent()
        openChaos()
        compose.onNodeWithTag(NetKitTestTags.CHAOS_ENABLED).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(NetKitTestTags.CHAOS_LATENCY_MIN)
            .performScrollTo()
            .performTextClearance()
        compose.onNodeWithTag(NetKitTestTags.CHAOS_LATENCY_MIN).performTextInput("9000")
        compose.waitForIdle()

        val latency = controller.state.value.configuration.chaos.latency
        assertTrue("range should never invert", latency.maxMillis >= latency.minMillis)
    }

    @Test
    fun chaosExclusionsCanBeEntered() {
        setContent()
        openChaos()
        compose.onNodeWithTag(NetKitTestTags.CHAOS_ENABLED).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(NetKitTestTags.CHAOS_EXCLUSIONS)
            .performScrollTo()
            .performTextInput("/auth/refresh, /analytics")
        compose.waitForIdle()

        val exclusions = controller.state.value.configuration.chaos.exclusions.prefixes
        assertTrue(exclusions.contains("/auth/refresh"))
        assertTrue(exclusions.contains("/analytics"))
    }

    // ---- run -----------------------------------------------------------------

    @Test
    fun runScreenSaysSoWhenNothingIsRunning() {
        setContent()
        openRun()

        compose.onNodeWithTag(NetKitTestTags.RUN_SCREEN).assertIsDisplayed()
        compose.onNodeWithText("No run in progress").assertIsDisplayed()
    }

    @Test
    fun theSeedIsDisplayed() {
        activateChaos()
        setContent()
        openRun()

        val seed = controller.runs.seed!!
        compose.onNodeWithTag(NetKitTestTags.RUN_SEED).assertIsDisplayed()
        compose.onNodeWithText("Seed $seed").assertIsDisplayed()
    }

    @Test
    fun restartingWithTheSameSeedKeepsIt() {
        activateChaos()
        setContent()
        openRun()
        val before = controller.runs.current.value!!

        compose.onNodeWithTag(NetKitTestTags.RUN_RESTART_SAME).performScrollTo().performClick()
        compose.waitForIdle()

        val after = controller.runs.current.value!!
        assertEquals(before.seed, after.seed)
        assertNotEquals(before.id, after.id)
    }

    @Test
    fun generatingANewSeedChangesIt() {
        activateChaos()
        setContent()
        openRun()
        val before = controller.runs.seed!!

        compose.onNodeWithTag(NetKitTestTags.RUN_RESTART_NEW).performScrollTo().performClick()
        compose.waitForIdle()

        assertNotEquals(before, controller.runs.seed)
    }

    /** The developer's half of the workflow: paste a seed from a ticket. */
    @Test
    fun aSeedFromATicketCanBeApplied() {
        activateChaos()
        setContent()
        openRun()

        compose.onNodeWithTag(NetKitTestTags.RUN_SEED_FIELD)
            .performScrollTo()
            .performTextInput("843921773")
        compose.onNodeWithTag(NetKitTestTags.RUN_SEED_APPLY).performClick()
        compose.waitForIdle()

        assertEquals(843_921_773L, controller.runs.seed)
    }

    @Test
    fun copyingTheReproductionShowsAConfirmation() {
        activateChaos()
        setContent()
        openRun()

        compose.onNodeWithTag(NetKitTestTags.RUN_COPY_REPRODUCTION)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Reproduction copied").assertIsDisplayed()
    }

    @Test
    fun theTimelineCanBeTurnedOff() {
        activateChaos()
        setContent()
        openRun()

        compose.onNodeWithTag(NetKitTestTags.RUN_TIMELINE_TOGGLE).performScrollTo().performClick()
        compose.waitForIdle()

        assertFalse(controller.runs.isTimelineEnabled)
    }

    @Test
    fun runStatisticsAreShown() {
        activateChaos()
        setContent()
        openRun()

        compose.onNodeWithTag(NetKitTestTags.RUN_STATS).performScrollTo().assertIsDisplayed()
    }

    // ---- rule editor ----------------------------------------------------------

    @Test
    fun advancedRuleOptionsAreHiddenUntilAskedFor() {
        setContent()
        compose.onNodeWithTag(NetKitTestTags.ADD_RULE).performScrollTo().performClick()
        compose.waitForIdle()

        // The simple 0.1 workflow must stay simple: no probability slider until
        // someone asks for one.
        compose.onNodeWithTag(NetKitTestTags.EDITOR_PROBABILITY).assertDoesNotExistNow()
        compose.onNodeWithTag(NetKitTestTags.EDITOR_ADVANCED_TOGGLE)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun theProbabilitySliderAppearsWhenAdvancedIsOpened() {
        setContent()
        compose.onNodeWithTag(NetKitTestTags.ADD_RULE).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(NetKitTestTags.EDITOR_ADVANCED_TOGGLE)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(NetKitTestTags.EDITOR_PROBABILITY)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aConditionCanBeAddedAndRemoved() {
        setContent()
        compose.onNodeWithTag(NetKitTestTags.ADD_RULE).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(NetKitTestTags.EDITOR_ADVANCED_TOGGLE)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(NetKitTestTags.CONDITION_ADD_PREFIX + "QUERY")
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(NetKitTestTags.CONDITION_ROW_PREFIX + "0")
            .performScrollTo()
            .assertIsDisplayed()

        compose.onNodeWithTag(NetKitTestTags.CONDITION_DELETE_PREFIX + "0").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(NetKitTestTags.CONDITION_ROW_PREFIX + "0").assertDoesNotExistNow()
    }

    @Test
    fun aConditionalRuleReachesTheRuntime() {
        setContent()
        compose.onNodeWithTag(NetKitTestTags.ADD_RULE).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(NetKitTestTags.EDITOR_PATH).performTextInput("/api/v1/services")
        compose.onNodeWithTag(NetKitTestTags.EDITOR_ADVANCED_TOGGLE)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(NetKitTestTags.CONDITION_ADD_PREFIX + "QUERY")
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(NetKitTestTags.EDITOR_SAVE).performScrollTo().performClick()
        compose.waitForIdle()

        val rule = controller.state.value.rules.single()
        assertEquals(1, rule.conditions.size)
        assertTrue(rule.isConditional)
    }

    @Test
    fun aWeightedOutcomeCanBeAdded() {
        setContent()
        compose.onNodeWithTag(NetKitTestTags.ADD_RULE).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(NetKitTestTags.EDITOR_BEHAVIOR_PREFIX + "RANDOM")
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(NetKitTestTags.OUTCOME_ADD).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(NetKitTestTags.OUTCOME_ROW_PREFIX + "0")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aLatencyRangeCanBeEntered() {
        setContent()
        compose.onNodeWithTag(NetKitTestTags.ADD_RULE).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(NetKitTestTags.EDITOR_PATH).performTextInput("/api/v1/feed")
        compose.onNodeWithTag(NetKitTestTags.EDITOR_BEHAVIOR_PREFIX + "LATENCY_RANGE")
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(NetKitTestTags.EDITOR_LATENCY_MIN).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(NetKitTestTags.EDITOR_SAVE).performScrollTo().performClick()
        compose.waitForIdle()

        assertTrue(
            controller.state.value.rules.single().action is NetworkAction.RandomDelay,
        )
    }

    // ---- presets --------------------------------------------------------------

    @Test
    fun thePresetPickerListsAuthAndPaginationTemplates() {
        setContent()
        openScenarios()

        compose.onNodeWithTag(NetKitTestTags.SCENARIO_FROM_PRESET)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(NetKitTestTags.PRESET_PICKER).assertIsDisplayed()
        compose.onNodeWithTag(
            NetKitTestTags.PRESET_ROW_PREFIX + AuthScenarioPresets.tokenExpires.id,
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(
            NetKitTestTags.PRESET_ROW_PREFIX + PaginationScenarioPresets.pageFails.id,
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun selectingAnAuthPresetCreatesAnEditableScenario() {
        setContent()
        openScenarios()
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_FROM_PRESET)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(
            NetKitTestTags.PRESET_ROW_PREFIX + AuthScenarioPresets.tokenExpires.id,
        ).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(NetKitTestTags.PRESET_CREATE).performScrollTo().performClick()
        compose.waitForIdle()

        val saved = savedScenarios().single()
        assertEquals(AuthScenarioPresets.tokenExpires.name, saved.name)
        assertTrue("the preset must produce real rules", saved.rules.isNotEmpty())
        // Editable afterwards, which is the whole architectural claim.
        assertFalse(saved.metadata.isReadOnly)
    }

    @Test
    fun selectingAPaginationPresetCreatesAConditionalRule() {
        setContent()
        openScenarios()
        compose.onNodeWithTag(NetKitTestTags.SCENARIO_FROM_PRESET)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(
            NetKitTestTags.PRESET_ROW_PREFIX + PaginationScenarioPresets.pageFails.id,
        ).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(NetKitTestTags.PRESET_CREATE).performScrollTo().performClick()
        compose.waitForIdle()

        val saved = savedScenarios().single()
        assertTrue(saved.rules.single().conditions.isNotEmpty())
        assertNotNull("provenance should be recorded", saved.preset)
    }

    // ---- helpers ----------------------------------------------------------------

    private fun activateChaos() {
        controller.setChaos(ChaosPresets.poorMobileNetwork.config)
    }

    private fun savedScenarios(): List<NetworkScenario> = runBlocking {
        controller.scenarios.scenarios.value
    }

    /**
     * `assertDoesNotExist` reads oddly at a call site that has just scrolled; a
     * named alias keeps the intent obvious.
     */
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistNow() =
        assertDoesNotExist()
}

/** A replayer that never sends, for a suite that does not exercise replay. */
private object FakeReplayer03 : RequestReplayer {
    override fun eligibility(recordId: Long): ReplayEligibility =
        ReplayEligibility.Unavailable(ReplayUnavailableReason.DISABLED)

    override suspend fun replay(
        recordId: Long,
        override: ReplayOverride,
        bypassNetKit: Boolean,
    ): ReplayResult = ReplayResult.Unavailable(ReplayUnavailableReason.DISABLED)
}
