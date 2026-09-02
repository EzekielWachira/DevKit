package io.devkit.netkit

import io.devkit.netkit.scenario.GlobalNetworkConfig
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.model.ScenarioId
import io.devkit.netkit.scenario.model.ScenarioMetadata
import io.devkit.netkit.scenario.model.ScenarioPack
import io.devkit.netkit.scenario.model.ScenarioPackId
import io.devkit.netkit.scenario.model.ScenarioSource
import io.devkit.netkit.scenario.persistence.InMemoryScenarioStorage
import io.devkit.netkit.scenario.persistence.JsonScenarioRepository
import io.devkit.netkit.scenario.persistence.ScenarioPersistenceError
import io.devkit.netkit.scenario.persistence.ScenarioStorage
import io.devkit.netkit.scenario.persistence.ScenarioWriteResult
import io.devkit.netkit.scenario.serialization.ScenarioSchema
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saved-scenario persistence.
 *
 * Every case here runs against [InMemoryScenarioStorage] rather than DataStore,
 * which is the point of the storage seam: the rules about what gets written,
 * what survives a restart and what happens to a corrupt document are all
 * testable without an emulator.
 */
class ScenarioRepositoryTest {

    private val storage = InMemoryScenarioStorage()

    private fun repository(from: ScenarioStorage = storage) = JsonScenarioRepository(from)

    private fun scenario(
        name: String = "Checkout failure",
        id: ScenarioId = ScenarioId.random(),
        packId: ScenarioPackId? = null,
    ) = NetworkScenario(
        id = id,
        name = name,
        description = "Reproduces the 503 from ticket QA-421",
        globalConfig = GlobalNetworkConfig(latencyMillis = 2_500),
        rules = listOf(Fixtures.rule(action = NetworkAction.ReturnResponse(503))),
        metadata = ScenarioMetadata(packId = packId),
    )

    @Test
    fun `a saved scenario is published and readable`() = runTest {
        val repository = repository()
        val saved = scenario()

        val result = repository.save(saved)

        assertTrue(result is ScenarioWriteResult.Success)
        assertEquals(1, repository.scenarios.value.size)
        assertEquals(saved.name, repository.get(saved.id)?.name)
    }

    @Test
    fun `saving the same id updates rather than duplicates`() = runTest {
        val repository = repository()
        val original = scenario()
        repository.save(original)

        repository.save(original.copy(name = "Renamed"))

        assertEquals(1, repository.scenarios.value.size)
        assertEquals("Renamed", repository.get(original.id)?.name)
    }

    @Test
    fun `saving stamps updatedAt`() = runTest {
        var now = 1_000L
        val repository = JsonScenarioRepository(storage, nowMillis = { now })
        val saved = repository.save(scenario()).scenarioOrNull!!

        now = 5_000L
        val updated = repository.save(saved.copy(name = "Later")).scenarioOrNull!!

        assertEquals(5_000L, updated.metadata.updatedAtMillis)
        assertEquals(saved.metadata.createdAtMillis, updated.metadata.createdAtMillis)
    }

    @Test
    fun `an invalid scenario is rejected and not written`() = runTest {
        val repository = repository()

        val result = repository.save(scenario(name = "  "))

        assertTrue(result is ScenarioWriteResult.Failure)
        assertTrue(
            (result as ScenarioWriteResult.Failure).error is ScenarioPersistenceError.Invalid,
        )
        assertTrue(repository.scenarios.value.isEmpty())
    }

    @Test
    fun `a built-in scenario cannot be overwritten`() = runTest {
        val repository = repository()
        val builtIn = scenario().copy(
            metadata = ScenarioMetadata(source = ScenarioSource.BUILT_IN),
        )
        // Force it into the store the only way a bad file could: through load.
        repository.save(builtIn)

        val result = repository.save(builtIn.copy(name = "Hijacked"))

        assertTrue(result is ScenarioWriteResult.Failure)
        assertTrue((result as ScenarioWriteResult.Failure).error is ScenarioPersistenceError.ReadOnly)
    }

    @Test
    fun `delete removes only the named scenario`() = runTest {
        val repository = repository()
        val first = repository.save(scenario("First")).scenarioOrNull!!
        val second = repository.save(scenario("Second")).scenarioOrNull!!

        repository.delete(first.id)

        assertEquals(listOf(second.id), repository.scenarios.value.map { it.id })
    }

    @Test
    fun `duplicate produces a new identity and fresh rule ids`() = runTest {
        val repository = repository()
        val original = repository.save(scenario()).scenarioOrNull!!

        val copy = repository.duplicate(original.id).scenarioOrNull!!

        assertNotEquals(original.id, copy.id)
        assertEquals("${original.name} Copy", copy.name)
        assertEquals(original.rules.size, copy.rules.size)
        // Sequence progress is keyed on rule id: a shared id would mean a shared
        // cursor between the original and its copy.
        assertTrue(copy.rules.map { it.id }.none { it in original.rules.map { r -> r.id } })
    }

    @Test
    fun `duplicating twice does not collide on the name`() = runTest {
        val repository = repository()
        val original = repository.save(scenario()).scenarioOrNull!!

        val first = repository.duplicate(original.id).scenarioOrNull!!
        val second = repository.duplicate(original.id).scenarioOrNull!!

        assertNotEquals(first.name, second.name)
    }

    @Test
    fun `duplicating an unknown id fails cleanly`() = runTest {
        val result = repository().duplicate(ScenarioId("missing"))

        assertTrue(result is ScenarioWriteResult.Failure)
        assertTrue((result as ScenarioWriteResult.Failure).error is ScenarioPersistenceError.NotFound)
    }

    @Test
    fun `scenarios survive recreating the repository over the same storage`() = runTest {
        val first = repository()
        val saved = first.save(scenario()).scenarioOrNull!!
        first.setActiveScenarioId(saved.id)

        val second = repository()
        second.load()

        assertEquals(1, second.scenarios.value.size)
        assertEquals(saved.name, second.get(saved.id)?.name)
        assertEquals(saved.id, second.activeScenarioId.value)
        assertEquals(
            GlobalNetworkMode.Normal,
            second.get(saved.id)?.globalConfig?.mode,
        )
        assertEquals(2_500L, second.get(saved.id)?.globalConfig?.latencyMillis)
    }

    @Test
    fun `the stored document carries a schema version`() = runTest {
        repository().save(scenario())

        val document = storage.read()

        assertNotNull(document)
        assertTrue(document!!.contains("\"schemaVersion\":${ScenarioSchema.CURRENT_VERSION}"))
    }

    @Test
    fun `a corrupt document leaves the repository empty and reports the failure`() = runTest {
        val corrupt = InMemoryScenarioStorage("{ this is not json")
        val repository = repository(corrupt)

        val error = repository.load()

        assertNotNull(error)
        assertTrue(repository.scenarios.value.isEmpty())
        assertTrue(repository.isLoaded.value)
        // The bad document is left alone rather than deleted: a NetKit parsing
        // bug must not cost a QA engineer their saved work.
        assertEquals("{ this is not json", corrupt.read())
    }

    @Test
    fun `a document from a newer schema is refused rather than half-read`() = runTest {
        val future = InMemoryScenarioStorage(
            """{"schemaVersion":99,"scenarios":[],"packs":[]}""",
        )
        val repository = repository(future)

        val error = repository.load()

        assertNotNull(error)
        assertTrue(error is ScenarioPersistenceError.StorageFailed)
        assertTrue(error!!.message.contains("99"))
    }

    @Test
    fun `one unreadable scenario does not cost the others`() = runTest {
        val mixed = InMemoryScenarioStorage(
            """
            {
              "schemaVersion": 1,
              "scenarios": [
                {"id":"good","name":"Good","rules":[]},
                {"id":"","name":"Broken","rules":[]}
              ],
              "packs": []
            }
            """.trimIndent(),
        )
        val repository = repository(mixed)

        repository.load()

        assertEquals(listOf("Good"), repository.scenarios.value.map { it.name })
    }

    @Test
    fun `pack membership round-trips`() = runTest {
        val repository = repository()
        val pack = ScenarioPack(name = "Checkout")
        repository.savePack(pack)
        val saved = repository.save(scenario(packId = pack.id)).scenarioOrNull!!

        val reloaded = repository()
        reloaded.load()

        assertEquals(pack.id, reloaded.get(saved.id)?.metadata?.packId)
        assertEquals("Checkout", reloaded.getPack(pack.id)?.name)
    }

    @Test
    fun `deleting a pack keeps its scenarios as loose scenarios`() = runTest {
        val repository = repository()
        val pack = ScenarioPack(name = "Checkout")
        repository.savePack(pack)
        val saved = repository.save(scenario(packId = pack.id)).scenarioOrNull!!

        repository.deletePack(pack.id)

        assertNull(repository.getPack(pack.id))
        assertEquals(1, repository.scenarios.value.size)
        assertNull(repository.get(saved.id)?.metadata?.packId)
    }

    @Test
    fun `setPack moves a scenario between packs`() = runTest {
        val repository = repository()
        val checkout = ScenarioPack(name = "Checkout")
        val bookings = ScenarioPack(name = "Bookings")
        repository.savePack(checkout)
        repository.savePack(bookings)
        val saved = repository.save(scenario(packId = checkout.id)).scenarioOrNull!!

        repository.setPack(saved.id, bookings.id)
        assertEquals(bookings.id, repository.get(saved.id)?.metadata?.packId)

        repository.setPack(saved.id, null)
        assertNull(repository.get(saved.id)?.metadata?.packId)
    }

    @Test
    fun `clear removes everything including the stored document`() = runTest {
        val repository = repository()
        repository.save(scenario())
        repository.savePack(ScenarioPack(name = "Checkout"))

        repository.clear()

        assertTrue(repository.scenarios.value.isEmpty())
        assertTrue(repository.packs.value.isEmpty())
        assertNull(storage.read())
    }

    @Test
    fun `deleting the active scenario clears the remembered activation`() = runTest {
        val repository = repository()
        val saved = repository.save(scenario()).scenarioOrNull!!
        repository.setActiveScenarioId(saved.id)

        repository.delete(saved.id)

        assertNull(repository.activeScenarioId.value)
    }
}
