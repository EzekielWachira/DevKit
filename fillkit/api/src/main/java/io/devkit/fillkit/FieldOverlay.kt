package io.devkit.fillkit

import androidx.compose.ui.semantics.SemanticsPropertyKey

/** When the contextual field overlay is shown. */
enum class FieldOverlayMode {
    Disabled,

    /** Only the field that currently has focus. The default; anything else clutters the app. */
    FocusedField,

    /** Every registered field at once. Useful for screenshots of small forms, noisy otherwise. */
    AlwaysForRegisteredFields,
}

/** Where the overlay renders relative to the field and the IME. */
enum class FieldOverlayPlacement {
    /** FillKit decides from the field bounds and the live IME inset. */
    Auto,

    /** Prefer an anchored popover near the field. */
    Field,

    /** Always use a keyboard-accessory bar above the IME. */
    AboveIme,
}

/** Per-field opt out, for the rare field where a suggestion is noise. */
enum class FieldOverlayBehavior { Default, Enabled, Disabled }

/** How much a field type can usefully offer in a one-line overlay. */
enum class FieldOverlayCapability {
    /** Show the generated value and fill on tap. */
    PreviewAndFill,

    /** Actions are useful but a preview is not (booleans, toggles). */
    ActionOnly,

    /** Nothing useful in a bar; the inspector still works. */
    InspectorOnly,

    /** FillKit cannot generate this at all. */
    Unsupported,
}

/**
 * Configuration for the contextual field overlay.
 *
 * Deliberately small: it answers whether, where and how much, and nothing
 * cosmetic.
 */
data class FieldOverlayConfig(
    val enabled: Boolean = true,
    val mode: FieldOverlayMode = FieldOverlayMode.FocusedField,
    val placement: FieldOverlayPlacement = FieldOverlayPlacement.Auto,
    val showPreview: Boolean = true,
    /** Generated passwords stay masked even though the data is synthetic. */
    val maskSensitivePreview: Boolean = true,
) {
    val active: Boolean get() = enabled && mode != FieldOverlayMode.Disabled
}

/** Which overlay control a test is reaching for. */
enum class FillKitOverlayAction { Fill, Regenerate, Clear, Inspect }

/**
 * Overlay-only semantics, kept separate from the application field's semantics
 * so a test can tell FillKit's accessory apart from the form itself. Applied
 * exclusively by `fillkit-debug`.
 */
object FillKitOverlaySemantics {
    val FieldId = SemanticsPropertyKey<String>("FillKitOverlayFieldId")
    val Preview = SemanticsPropertyKey<String>("FillKitOverlayPreview")
    val Placement = SemanticsPropertyKey<String>("FillKitOverlayPlacement")
    val Action = SemanticsPropertyKey<String>("FillKitOverlayAction")
    val Modified = SemanticsPropertyKey<Boolean>("FillKitOverlayModified")
}

/** A field type's overlay capability. Booleans and unsupported types opt out of previews. */
fun FillType<*>.overlayCapability(): FieldOverlayCapability = when (this) {
    is FillType.Unsupported -> FieldOverlayCapability.Unsupported
    is FillType.BooleanValue -> FieldOverlayCapability.ActionOnly
    FillType.DateOfBirth, is FillType.Date, FillType.Age -> FieldOverlayCapability.PreviewAndFill
    else -> FieldOverlayCapability.PreviewAndFill
}

/** True when a generated preview should be masked in the compact bar. */
fun FillType<*>.isSensitivePreview(): Boolean = this is FillType.Password
