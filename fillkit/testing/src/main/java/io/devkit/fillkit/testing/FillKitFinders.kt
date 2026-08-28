package io.devkit.fillkit.testing

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performSemanticsAction
import io.devkit.fillkit.FillKitFormSnapshot
import io.devkit.fillkit.FillKitSemantics
import io.devkit.fillkit.FillKitTestSemantics
import io.devkit.fillkit.FillType
import io.devkit.fillkit.displayName

/**
 * Finds a field by its FillKit ID rather than by label text, content description
 * or tree position, so renaming a label or reordering a form cannot break a test.
 *
 * The unmerged-tree lookup is handled here; callers never need to think about
 * where FillKit places its semantics.
 */
fun SemanticsNodeInteractionsProvider.onFillKitField(
    fieldId: String,
    formId: String? = null,
): SemanticsNodeInteraction = onNode(
    matcher = fillKitFieldMatcher(fieldId, formId),
    useUnmergedTree = true,
)

/** The control node for one [io.devkit.fillkit.FillKitHost]. */
fun SemanticsNodeInteractionsProvider.onFillKitForm(formId: String): SemanticsNodeInteraction = onNode(
    matcher = SemanticsMatcher.expectValue(FillKitTestSemantics.ControlFormId, formId),
    useUnmergedTree = true,
)

fun SemanticsNodeInteraction.assertRegistered(): SemanticsNodeInteraction =
    assert(SemanticsMatcher.keyIsDefined(FillKitSemantics.FormId)) { "field is not registered with a FillKit host" }

fun SemanticsNodeInteraction.assertFillKitType(type: FillType<*>): SemanticsNodeInteraction =
    assert(SemanticsMatcher.expectValue(FillKitSemantics.FieldType, type.displayName()))

fun SemanticsNodeInteraction.assertFillKitFormId(formId: String): SemanticsNodeInteraction =
    assert(SemanticsMatcher.expectValue(FillKitSemantics.FormId, formId))

fun SemanticsNodeInteraction.assertFillKitFieldId(fieldId: String): SemanticsNodeInteraction =
    assert(SemanticsMatcher.expectValue(FillKitSemantics.FieldId, fieldId))

/** Reads the live registration list from the form's control node. */
fun SemanticsNodeInteraction.assertRegisteredFieldCount(expected: Int): SemanticsNodeInteraction {
    val actual = fillKitSnapshot().fieldIds
    check(actual.size == expected) {
        "expected $expected registered FillKit fields but found ${actual.size}: ${actual.joinToString()}"
    }
    return this
}

fun SemanticsNodeInteraction.assertFillKitFieldRegistered(fieldId: String): SemanticsNodeInteraction {
    val snapshot = fillKitSnapshot()
    check(fieldId in snapshot.fieldIds) {
        "\"$fieldId\" is not registered in form \"${snapshot.formId}\"; registered: ${snapshot.fieldIds.joinToString()}"
    }
    return this
}

/** Convenience for asserting a field exists and is on screen. */
fun SemanticsNodeInteractionsProvider.assertFillKitFieldDisplayed(
    fieldId: String,
    formId: String? = null,
): SemanticsNodeInteraction = onFillKitField(fieldId, formId).assertIsDisplayed()

internal fun SemanticsNodeInteraction.fillKitSnapshot(): FillKitFormSnapshot {
    val out = mutableListOf<FillKitFormSnapshot>()
    performSemanticsAction(FillKitTestSemantics.Snapshot) { action -> action(out) }
    return out.firstOrNull() ?: error("the FillKit host did not return a snapshot")
}

private fun fillKitFieldMatcher(fieldId: String, formId: String?): SemanticsMatcher {
    val byField = SemanticsMatcher.expectValue(FillKitSemantics.FieldId, fieldId)
    return if (formId == null) byField else byField and SemanticsMatcher.expectValue(FillKitSemantics.FormId, formId)
}
