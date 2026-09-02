package io.devkit.netkit

import io.devkit.netkit.config.NetKitLimits
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.SequenceStep
import io.devkit.netkit.scenario.model.DefaultScenarioValidator
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.model.ScenarioPack
import io.devkit.netkit.scenario.model.ScenarioSource
import io.devkit.netkit.scenario.model.ValidationResult
import io.devkit.netkit.scenario.runtime.ImportCollisionPolicy
import io.devkit.netkit.scenario.runtime.ScenarioImportOutcome
import io.devkit.netkit.scenario.serialization.JsonScenarioSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Committing an import: collisions, packs and the guarantee that a preview
 * changes nothing.
 *
 * The rule that carries the most weight is that an import can never silently
 * destroy work already on the device. A `.netkit.json` arrives from someone
 * else's phone and may carry the same id as a scenario the recipient spent an
 * afternoon on.
 */
class ScenarioImportTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val serializer = JsonScenarioSerializer()
    private val manager = Fixtures.manager(scope)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun exported(scenario: NetworkScenario) = serializer.exportScenario(scenario).content

    @Test
    fun `previewing does not save anything`() = runTest {
        val result = manager.preview(exported(Fixtures.scenario("Checkout")))

        assertTrue(result.failureReason == null)
        assertTrue(manager.scenarios.value.isEmpty())
    }

    @Test
    fun `committing a previewed scenario saves it`() = runTest {
        val scenario = Fixtures.scenario("Checkout", Fixtures.rule())
        val result = manager.preview(exported(scenario))

        val outcome = manager.commitImport(result)

        assertTrue(outcome is ScenarioImportOutcome.Imported)
        assertEquals(1, manager.scenarios.value.size)
        assertEquals("Checkout", manager.scenarios.value.single().name)
        assertEquals(
            ScenarioSource.IMPORTED,
            manager.scenarios.value.single().metadata.source,
        )
    }

    @Test
    fun `committing a rejected file imports nothing`() = runTest {
        val outcome = manager.commitImport(manager.preview("not a netkit file"))

        assertTrue(outcome is ScenarioImportOutcome.Rejected)
        assertTrue(manager.scenarios.value.isEmpty())
    }

    @Test
    fun `an id collision imports a copy rather than overwriting`() = runTest {
        val mine = manager.save(Fixtures.scenario("Checkout Failure")).scenarioOrNull!!
        val theirs = mine.copy(
            name = "Checkout Failure",
            rules = listOf(Fixtures.rule(action = NetworkAction.ReturnResponse(418))),
        )

        val outcome = manager.commitImport(
            manager.preview(exported(theirs)),
        ) as ScenarioImportOutcome.Imported

        assertEquals(2, manager.scenarios.value.size)
        // The original is untouched.
        assertEquals(mine.rules, manager.scenarios.value.single { it.id == mine.id }.rules)
        assertEquals(listOf("Checkout Failure"), outcome.renamed)
        val copy = manager.scenarios.value.single { it.id != mine.id }
        assertEquals("Checkout Failure (Imported)", copy.name)
        assertNotEquals(mine.id, copy.id)
    }

    @Test
    fun `a second collision gets a distinct name`() = runTest {
        val mine = manager.save(Fixtures.scenario("Checkout Failure")).scenarioOrNull!!

        manager.commitImport(manager.preview(exported(mine)))
        manager.commitImport(manager.preview(exported(mine)))

        val names = manager.scenarios.value.map { it.name }.sorted()
        assertEquals(3, names.size)
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `replace-existing overwrites deliberately`() = runTest {
        val mine = manager.save(
            Fixtures.scenario("Checkout", Fixtures.rule(action = NetworkAction.ReturnResponse(500))),
        ).scenarioOrNull!!
        val theirs = mine.copy(
            rules = listOf(Fixtures.rule(action = NetworkAction.ReturnResponse(418))),
        )

        val outcome = manager.commitImport(
            manager.preview(exported(theirs)),
            ImportCollisionPolicy.REPLACE_EXISTING,
        ) as ScenarioImportOutcome.Imported

        assertEquals(1, manager.scenarios.value.size)
        assertEquals(1, outcome.replaced)
        val action = manager.scenarios.value.single().rules.single().action
        assertEquals(418, (action as NetworkAction.ReturnResponse).statusCode)
    }

    @Test
    fun `an imported copy gets fresh rule ids`() = runTest {
        val mine = manager.save(Fixtures.scenario("Checkout", Fixtures.rule())).scenarioOrNull!!

        manager.commitImport(manager.preview(exported(mine)))

        val copy = manager.scenarios.value.single { it.id != mine.id }
        // A shared rule id would mean the copy shares a sequence cursor with the
        // original the moment either is activated.
        assertNotEquals(mine.rules.single().id, copy.rules.single().id)
    }

    @Test
    fun `importing a pack brings its scenarios and membership`() = runTest {
        val pack = ScenarioPack(name = "Checkout")
        val scenarios = listOf(
            Fixtures.scenario("Declined", Fixtures.rule()),
            Fixtures.scenario("Timeout", Fixtures.rule()),
        )
        val content = serializer.exportPack(pack, scenarios).content

        val outcome = manager.commitImport(manager.preview(content)) as ScenarioImportOutcome.Imported

        assertEquals(2, outcome.scenarios.size)
        assertEquals(pack.id, outcome.pack?.id)
        assertEquals(1, manager.packs.value.size)
        assertEquals(2, manager.packContents.value.single().scenarios.size)
        assertTrue(manager.looseScenarios.value.isEmpty())
    }

    @Test
    fun `importing a pack handles a collision on one of its scenarios`() = runTest {
        val pack = ScenarioPack(name = "Checkout")
        val mine = manager.save(Fixtures.scenario("Declined")).scenarioOrNull!!
        val content = serializer.exportPack(
            pack,
            listOf(mine, Fixtures.scenario("Timeout")),
        ).content

        val outcome = manager.commitImport(manager.preview(content)) as ScenarioImportOutcome.Imported

        assertEquals(3, manager.scenarios.value.size)
        assertEquals(listOf("Declined"), outcome.renamed)
    }

    @Test
    fun `an imported scenario can be activated straight away`() = runTest {
        val outcome = manager.commitImport(
            manager.preview(exported(Fixtures.scenario("Checkout", Fixtures.rule()))),
        ) as ScenarioImportOutcome.Imported

        assertTrue(manager.activate(outcome.scenarios.single().id))
        assertEquals("Checkout", manager.activeSnapshot.value?.name)
    }

    // ---- validation --------------------------------------------------------

    @Test
    fun `the validator accepts an ordinary scenario`() {
        val result = DefaultScenarioValidator().validate(
            Fixtures.scenario("Checkout", Fixtures.rule()),
        )

        assertTrue(result.isValid)
    }

    @Test
    fun `the validator accepts a scenario with no rules`() {
        // Starting a scenario empty is how you start one at all.
        assertTrue(DefaultScenarioValidator().validate(Fixtures.scenario("Empty")).isValid)
    }

    @Test
    fun `the validator rejects a blank name`() {
        val result = DefaultScenarioValidator().validate(Fixtures.scenario("   "))

        assertFalse(result.isValid)
        assertEquals("name", result.errors.single().field)
    }

    @Test
    fun `the validator rejects an oversized body`() {
        val result = DefaultScenarioValidator().validate(
            Fixtures.scenario(
                "Huge",
                Fixtures.rule(
                    action = NetworkAction.ReturnResponse(
                        statusCode = 200,
                        body = "x".repeat(NetKitLimits.MAX_BODY_BYTES + 1),
                    ),
                ),
            ),
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.single().message.contains("KB"))
    }

    @Test
    fun `the validator rejects duplicate rule ids`() {
        val rule = Fixtures.rule()
        val result = DefaultScenarioValidator().validate(
            Fixtures.scenario("Twins", rule, rule.copy(matcher = rule.matcher)),
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.message.contains("unique") })
    }

    @Test
    fun `the validator reaches inside a sequence`() {
        val result = DefaultScenarioValidator().validate(
            Fixtures.scenario(
                "Bad step",
                Fixtures.rule(
                    action = NetworkAction.Sequence(
                        listOf(
                            SequenceStep(NetworkAction.ReturnResponse(500)),
                            SequenceStep(
                                NetworkAction.ReturnResponse(
                                    statusCode = 200,
                                    body = "x".repeat(NetKitLimits.MAX_BODY_BYTES + 1),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.single().field.contains("steps[1]"))
    }

    @Test
    fun `the validator reports every problem at once`() {
        val result = DefaultScenarioValidator().validate(
            NetworkScenario(
                name = "",
                description = "d".repeat(NetKitLimits.MAX_DESCRIPTION_LENGTH + 1),
                rules = emptyList(),
            ),
        )

        assertTrue(result is ValidationResult.Invalid)
        assertEquals(2, result.errors.size)
    }

    @Test
    fun `a pack with too many scenarios is rejected`() {
        val scenarios = List(NetKitLimits.MAX_SCENARIOS_PER_PACK + 1) {
            Fixtures.scenario("Scenario $it")
        }

        val result = DefaultScenarioValidator().validate(ScenarioPack(name = "Big"), scenarios)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.field == "scenarios" })
    }
}
