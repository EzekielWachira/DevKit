package io.devkit.fillkit

import kotlin.random.Random

enum class ScenarioValidationMode { Lenient, Strict }

/** Context supplied to a form-scoped custom generator. */
data class FillGenerationContext(
    val locale: FillLocale,
    val random: Random,
)

fun interface FillGenerator<T : Any> {
    fun generate(context: FillGenerationContext): T
}

/** Behavior shared by one [FillKitHost]. */
data class FillKitConfig(
    val locale: FillLocale = FillLocale.System,
    val seed: Long? = null,
    val showTrigger: Boolean = true,
    val showFieldValues: Boolean = true,
    val scenarioValidationMode: ScenarioValidationMode = ScenarioValidationMode.Lenient,
    val scenarios: List<FillScenario> = emptyList(),
    val customGenerators: Map<String, FillGenerator<*>> = emptyMap(),
    val loggingEnabled: Boolean = true,
)
