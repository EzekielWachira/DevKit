package io.devkit.fillkit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.devkit.fillkit.runtime.FillKitCommands

/** Programmatic controls for a single [FillKitHost]. */
class FillKitController internal constructor() {
    private var commands: FillKitCommands? = null

    fun fillAll() = commands?.fillAll() ?: Unit
    fun regenerateAll() = commands?.regenerateAll() ?: Unit
    fun clearAll() = commands?.clearAll() ?: Unit
    fun fill(fieldId: String) = commands?.fill(fieldId) ?: Unit
    fun clear(fieldId: String) = commands?.clear(fieldId) ?: Unit
    fun applyScenario(scenarioId: String) = commands?.applyScenario(scenarioId) ?: Unit
    fun selectPersona(personaId: String) = commands?.selectPersona(personaId) ?: Unit
    fun selectRandomPersona() = commands?.selectRandomPersona() ?: Unit
    fun changeLocale(locale: FillLocale) = commands?.changeLocale(locale) ?: Unit

    internal fun bind(value: FillKitCommands?) {
        commands = value
    }
}

/** Creates a controller that is harmless when the debug runtime is absent. */
@Composable
fun rememberFillKitController(): FillKitController = remember { FillKitController() }
