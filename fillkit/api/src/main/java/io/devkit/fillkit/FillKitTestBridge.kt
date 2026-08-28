package io.devkit.fillkit

import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.SemanticsPropertyKey

/** A programmatic FillKit operation a test can request from a live host. */
sealed interface FillKitCommand {
    data object FillAll : FillKitCommand
    data object ClearAll : FillKitCommand
    data object RegenerateAll : FillKitCommand
    data object SelectRandomPersona : FillKitCommand
    data class Fill(val fieldId: String) : FillKitCommand
    data class Clear(val fieldId: String) : FillKitCommand
    data class SelectPersona(val personaId: String) : FillKitCommand
    data class SetLocale(val localeTag: String) : FillKitCommand
    data class SetSeed(val seed: Long, val generation: Int = 0) : FillKitCommand
    data class ApplyScenario(val scenarioId: String) : FillKitCommand
}

/** Read-only view of a live host, used by tests and by the reproduction report. */
data class FillKitFormSnapshot(
    val formId: String,
    val seed: Long,
    val generation: Int,
    val localeTag: String,
    val scenarioId: String?,
    val personaId: String?,
    val fieldIds: List<String>,
    val reproduction: FillReproductionSpec,
) {
    val reproductionToken: String? get() = FillReproductionTokenCodec.encodeOrNull(reproduction)
}

/**
 * Debug/test control surface published as Compose semantics by the debug runtime.
 *
 * Only `fillkit-debug` ever applies these keys, so a release build's semantics tree
 * contains no FillKit control actions. `fillkit-testing` drives them through
 * `performSemanticsAction`, which keeps tests off the runtime internals.
 */
object FillKitTestSemantics {
    val ControlFormId = SemanticsPropertyKey<String>("FillKitControlFormId")

    /** Results travel through an out-parameter because semantics actions return Boolean. */
    val Activate = SemanticsPropertyKey<
        AccessibilityAction<(FillActivationRequest, MutableList<FillActivationResult>) -> Boolean>,
        >("FillKitActivate")

    val Command = SemanticsPropertyKey<AccessibilityAction<(FillKitCommand) -> Boolean>>("FillKitCommand")

    val Snapshot = SemanticsPropertyKey<
        AccessibilityAction<(MutableList<FillKitFormSnapshot>) -> Boolean>,
        >("FillKitSnapshot")
}
