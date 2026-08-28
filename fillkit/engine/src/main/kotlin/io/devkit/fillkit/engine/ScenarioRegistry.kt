package io.devkit.fillkit.engine

import io.devkit.fillkit.FillScenario

/** Immutable scenario lookup and composition with deterministic child-over-base overrides. */
class ScenarioRegistry(scenarios: List<FillScenario>) {
    private val byId: Map<String, FillScenario>

    init {
        val duplicate = scenarios.groupingBy(FillScenario::id).eachCount().entries.firstOrNull { it.value > 1 }?.key
        require(duplicate == null) { "duplicate scenario id: $duplicate" }
        byId = scenarios.associateBy(FillScenario::id)
        scenarios.forEach { resolve(it.id) }
    }

    fun find(id: String): FillScenario? = byId[id]?.let { resolve(it.id) }

    fun resolve(id: String): FillScenario = resolve(id, mutableListOf())

    private fun resolve(id: String, stack: MutableList<String>): FillScenario {
        val scenario = byId[id] ?: throw IllegalArgumentException("unknown scenario: $id")
        if (id in stack) {
            val cycle = (stack.dropWhile { it != id } + id).joinToString(" -> ")
            throw IllegalStateException("FillKit scenario include cycle detected: $cycle")
        }
        stack += id
        val bases = scenario.includes.map { resolve(it, stack) }
        stack.removeAt(stack.lastIndex)
        val values = linkedMapOf<String, io.devkit.fillkit.FillValue>()
        val generators = linkedMapOf<String, io.devkit.fillkit.FillScenarioGenerator>()
        var personaId: String? = null
        bases.forEach { base ->
            personaId = base.personaId ?: personaId
            base.values.forEach { (key, value) -> values[key] = value; generators.remove(key) }
            base.generators.forEach { (key, value) -> generators[key] = value; values.remove(key) }
        }
        personaId = scenario.personaId ?: personaId
        scenario.values.forEach { (key, value) -> values[key] = value; generators.remove(key) }
        scenario.generators.forEach { (key, value) -> generators[key] = value; values.remove(key) }
        return scenario.copy(values = values, generators = generators, includes = emptyList(), personaId = personaId)
    }
}
