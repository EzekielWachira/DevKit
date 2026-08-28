package io.devkit.fillkit

import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver

/** Debug-only metadata keys. Values are identifiers, never generated or user-entered data. */
object FillKitSemantics {
    val FieldId = SemanticsPropertyKey<String>("FillKitFieldId")
    val FieldType = SemanticsPropertyKey<String>("FillKitFieldType")
    val FieldLabel = SemanticsPropertyKey<String>("FillKitFieldLabel")
    val FieldGroup = SemanticsPropertyKey<String>("FillKitFieldGroup")
    val FormId = SemanticsPropertyKey<String>("FillKitFormId")
    val Source = SemanticsPropertyKey<String>("FillKitSource")
    val Confidence = SemanticsPropertyKey<String>("FillKitConfidence")
    val Target = SemanticsPropertyKey<String>("FillKitTarget")
}

internal var SemanticsPropertyReceiver.fillKitFieldId by FillKitSemantics.FieldId
internal var SemanticsPropertyReceiver.fillKitFieldType by FillKitSemantics.FieldType
internal var SemanticsPropertyReceiver.fillKitFieldLabel by FillKitSemantics.FieldLabel
internal var SemanticsPropertyReceiver.fillKitFieldGroup by FillKitSemantics.FieldGroup
internal var SemanticsPropertyReceiver.fillKitFormId by FillKitSemantics.FormId
internal var SemanticsPropertyReceiver.fillKitSource by FillKitSemantics.Source
internal var SemanticsPropertyReceiver.fillKitConfidence by FillKitSemantics.Confidence
internal var SemanticsPropertyReceiver.fillKitTarget by FillKitSemantics.Target
