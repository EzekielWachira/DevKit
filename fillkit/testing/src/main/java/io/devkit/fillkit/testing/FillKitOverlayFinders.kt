package io.devkit.fillkit.testing

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performClick
import io.devkit.fillkit.FillKitOverlayAction
import io.devkit.fillkit.FillKitOverlaySemantics

/**
 * The contextual overlay's own semantics, kept apart from the application
 * field's. These helpers exist mainly for testing FillKit itself; application
 * tests should prefer the programmatic driver, which is less brittle.
 */
fun SemanticsNodeInteractionsProvider.onFillKitFieldOverlay(fieldId: String): SemanticsNodeInteraction = onNode(
    matcher = SemanticsMatcher.expectValue(FillKitOverlaySemantics.FieldId, fieldId) and
        SemanticsMatcher.keyIsDefined(FillKitOverlaySemantics.Preview),
    useUnmergedTree = true,
)

fun SemanticsNodeInteractionsProvider.onFillKitOverlayAction(
    fieldId: String,
    action: FillKitOverlayAction,
): SemanticsNodeInteraction = onNode(
    matcher = SemanticsMatcher.expectValue(FillKitOverlaySemantics.FieldId, fieldId) and
        SemanticsMatcher.expectValue(FillKitOverlaySemantics.Action, action.name.lowercase()),
    useUnmergedTree = true,
)

/** Taps the suggestion itself, which is how a developer fills the field. */
fun SemanticsNodeInteraction.performFill(): SemanticsNodeInteraction = performClick()

fun SemanticsNodeInteraction.assertOverlayPreview(expected: String): SemanticsNodeInteraction =
    assert(SemanticsMatcher.expectValue(FillKitOverlaySemantics.Preview, expected))

fun SemanticsNodeInteraction.assertOverlayPreviewMatches(
    description: String = "matches predicate",
    predicate: (String) -> Boolean,
): SemanticsNodeInteraction = assert(
    SemanticsMatcher("overlay preview $description") { node ->
        node.config.getOrNull(FillKitOverlaySemantics.Preview)?.let(predicate) == true
    },
)

fun SemanticsNodeInteraction.assertOverlayModified(expected: Boolean = true): SemanticsNodeInteraction =
    assert(SemanticsMatcher.expectValue(FillKitOverlaySemantics.Modified, expected))

fun SemanticsNodeInteraction.assertOverlayPlacement(expected: String): SemanticsNodeInteraction =
    assert(SemanticsMatcher.expectValue(FillKitOverlaySemantics.Placement, expected))

/** The placement the positioner chose, for tests that assert IME behaviour. */
fun SemanticsNodeInteractionsProvider.fillKitOverlayPlacement(fieldId: String): String? = onNode(
    matcher = SemanticsMatcher.expectValue(FillKitOverlaySemantics.FieldId, fieldId) and
        SemanticsMatcher.keyIsDefined(FillKitOverlaySemantics.Placement),
    useUnmergedTree = true,
).fetchSemanticsNode().config.getOrNull(FillKitOverlaySemantics.Placement)
