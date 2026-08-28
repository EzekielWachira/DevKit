package io.devkit.fillkit

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.autofill.FillableData
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentType
import io.devkit.fillkit.runtime.FillKitField
import io.devkit.fillkit.runtime.FillKitContentTypeField
import io.devkit.fillkit.runtime.FillKitRegistry
import io.devkit.fillkit.runtime.FillKitSuggestionCandidate
import io.devkit.fillkit.runtime.LocalFillKitRegistry

/** Registers this UI element with the nearest [FillKitHost]. */
fun <T : Any> Modifier.fillKit(
    id: String,
    type: FillType<T>,
    value: T?,
    onFill: (T) -> Unit,
    label: String? = null,
    group: String? = null,
    onClear: (() -> Unit)? = null,
    generator: FillGenerator<T>? = null,
    overlay: FieldOverlayBehavior = FieldOverlayBehavior.Default,
): Modifier = fillKitTarget(
    id, type, CallbackFillTarget(value, onFill, onClear), label, group, generator, null, overlay,
)

/** State-based Compose overload. Fill places the cursor at the end; clear resets the state. */
fun Modifier.fillKit(
    id: String,
    type: FillType<String>,
    state: TextFieldState,
    label: String? = null,
    group: String? = null,
    generator: FillGenerator<String>? = null,
    normalize: (String) -> String = { it },
    contentType: ContentType? = null,
    overlay: FieldOverlayBehavior = FieldOverlayBehavior.Default,
): Modifier = fillKitTarget(
    id, type, TextFieldStateFillTarget(state, normalize), label, group, generator, contentType, overlay,
)

/** ContentType-assisted state registration resolved by the host's scoped mapping chain. */
fun Modifier.fillKit(
    id: String,
    state: TextFieldState,
    contentType: ContentType,
    label: String? = null,
    group: String? = null,
    mapper: ContentTypeMapper? = null,
    generator: FillGenerator<String>? = null,
    normalize: (String) -> String = { it },
    overlay: FieldOverlayBehavior = FieldOverlayBehavior.Default,
): Modifier {
    require(id.isNotBlank()) { "FillKit field id cannot be blank" }
    return this then FillKitContentTypeElement(
        FillKitContentTypeField(
            id, label, group, contentType, TextFieldStateFillTarget(state, normalize), mapper, generator, overlay,
        ),
    )
}

/** Public-semantics target for custom fields that expose an OnFillData action. */
fun Modifier.fillKitSemantics(
    id: String,
    type: FillType<String>,
    currentText: String? = null,
    onFillData: (FillableData) -> Boolean,
    label: String? = null,
    group: String? = null,
    generator: FillGenerator<String>? = null,
    contentType: ContentType? = null,
    overlay: FieldOverlayBehavior = FieldOverlayBehavior.Default,
): Modifier = fillKitTarget(
    id, type, SemanticsFillTarget(currentText, onFillData), label, group, generator, contentType, overlay,
)

private fun <T : Any> Modifier.fillKitTarget(
    id: String,
    type: FillType<T>,
    target: FillTarget<T>,
    label: String?,
    group: String?,
    generator: FillGenerator<T>?,
    contentType: ContentType?,
    overlay: FieldOverlayBehavior,
): Modifier {
    require(id.isNotBlank()) { "FillKit field id cannot be blank" }
    return this then FillKitElement(id, label, group, type, target, generator, contentType, overlay)
}

/**
 * Opts a field into public-API suggestion discovery without explicitly choosing a [FillType].
 * A target makes the suggestion fillable after acceptance; omitting it is detection-only.
 */
fun Modifier.fillKitSuggestion(
    id: String? = null,
    label: String? = null,
    contentType: ContentType? = null,
    testTag: String? = null,
    currentText: String? = null,
    semanticHints: Set<String> = emptySet(),
): Modifier = this then FillKitSuggestionElement(
    FieldMetadata(
        id, label, contentType?.let(BuiltInContentTypeMapper::hint),
        testTag = testTag, currentText = currentText, semanticHints = semanticHints,
    ),
    target = null,
    contentType = contentType,
)

fun Modifier.fillKitSuggestion(
    state: TextFieldState,
    id: String? = null,
    label: String? = null,
    contentType: ContentType? = null,
    testTag: String? = null,
    semanticHints: Set<String> = emptySet(),
    normalize: (String) -> String = { it },
): Modifier = this then FillKitSuggestionElement(
    FieldMetadata(
        id, label, contentType?.let(BuiltInContentTypeMapper::hint),
        testTag = testTag, currentText = state.text.toString(), semanticHints = semanticHints,
    ),
    target = TextFieldStateFillTarget(state, normalize),
    contentType = contentType,
)

private data class FillKitElement<T : Any>(
    val id: String,
    val label: String?,
    val group: String?,
    val type: FillType<T>,
    val target: FillTarget<T>,
    val generator: FillGenerator<T>?,
    val contentType: ContentType?,
    val overlay: FieldOverlayBehavior,
) : ModifierNodeElement<FillKitNode<T>>() {
    override fun create() = FillKitNode(id, label, group, type, target, generator, contentType, overlay)
    override fun update(node: FillKitNode<T>) =
        node.update(id, label, group, type, target, generator, contentType, overlay)
}

private class FillKitNode<T : Any>(
    private var id: String,
    private var label: String?,
    private var group: String?,
    private var type: FillType<T>,
    private var target: FillTarget<T>,
    private var generator: FillGenerator<T>?,
    private var declaredContentType: ContentType?,
    private var overlay: FieldOverlayBehavior,
) : Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    ObserverModifierNode,
    SemanticsModifierNode,
    FocusEventModifierNode,
    GlobalPositionAwareModifierNode {
    private var registry: FillKitRegistry? = null
    private var focused = false
    override val isImportantForBounds: Boolean get() = false

    /** Ordinary Compose focus events from the field below this modifier. */
    override fun onFocusEvent(focusState: FocusState) {
        val next = focusState.hasFocus
        if (next == focused) return
        focused = next
        registry?.setFieldFocus(this, next)
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (!coordinates.isAttached) return
        registry?.setFieldBounds(this, coordinates.boundsInWindow())
    }

    override fun onAttach() = onObservedReadsChanged()
    override fun onObservedReadsChanged() {
        observeReads {
            val latest = currentValueOf(LocalFillKitRegistry)
            if (latest !== registry) {
                registry?.unregister(this)
                registry = latest
                registry?.register(this, field())
                invalidateSemantics()
            }
        }
    }
    override fun onDetach() {
        if (focused) registry?.setFieldFocus(this, false)
        focused = false
        registry?.unregister(this)
        registry = null
    }

    fun update(
        id: String, label: String?, group: String?, type: FillType<T>, target: FillTarget<T>,
        generator: FillGenerator<T>?, contentType: ContentType?, overlay: FieldOverlayBehavior,
    ) {
        this.id = id; this.label = label; this.group = group; this.type = type
        this.target = target; this.generator = generator; this.declaredContentType = contentType
        this.overlay = overlay
        registry?.update(this, field())
        invalidateSemantics()
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        declaredContentType?.let { contentType = it }
        val activeRegistry = registry ?: return
        fillKitFieldId = id
        fillKitFieldType = type.displayName()
        fillKitFieldLabel = label ?: id
        group?.let { fillKitFieldGroup = it }
        fillKitFormId = activeRegistry.formId
        fillKitSource = "Explicit"
        fillKitConfidence = SuggestionConfidence.Exact.name
        fillKitTarget = target.kind.name
    }

    private fun field() = FillKitField(id, label, group, type, target, generator, overlay)
}

private data class FillKitSuggestionElement(
    val metadata: FieldMetadata,
    val target: FillTarget<String>?,
    val contentType: ContentType?,
) : ModifierNodeElement<FillKitSuggestionNode>() {
    override fun create() = FillKitSuggestionNode(metadata, target, contentType)
    override fun update(node: FillKitSuggestionNode) = node.update(metadata, target, contentType)
}

private data class FillKitContentTypeElement(
    val field: FillKitContentTypeField,
) : ModifierNodeElement<FillKitContentTypeNode>() {
    override fun create() = FillKitContentTypeNode(field)
    override fun update(node: FillKitContentTypeNode) = node.update(field)
}

private class FillKitContentTypeNode(
    private var field: FillKitContentTypeField,
) : Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    ObserverModifierNode,
    SemanticsModifierNode,
    FocusEventModifierNode,
    GlobalPositionAwareModifierNode {
    private var registry: FillKitRegistry? = null
    private var focused = false
    override val isImportantForBounds: Boolean get() = false

    override fun onFocusEvent(focusState: FocusState) {
        val next = focusState.hasFocus
        if (next == focused) return
        focused = next
        registry?.setFieldFocus(this, next)
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (!coordinates.isAttached) return
        registry?.setFieldBounds(this, coordinates.boundsInWindow())
    }

    override fun onAttach() = onObservedReadsChanged()
    override fun onObservedReadsChanged() {
        observeReads {
            val latest = currentValueOf(LocalFillKitRegistry)
            if (latest !== registry) {
                registry?.unregister(this)
                registry = latest
                registry?.registerContentType(this, field)
                invalidateSemantics()
            }
        }
    }
    override fun onDetach() {
        if (focused) registry?.setFieldFocus(this, false)
        focused = false
        registry?.unregister(this)
        registry = null
    }
    fun update(field: FillKitContentTypeField) {
        this.field = field
        registry?.updateContentType(this, field)
        invalidateSemantics()
    }
    override fun SemanticsPropertyReceiver.applySemantics() {
        contentType = field.contentType
        val activeRegistry = registry ?: return
        fillKitFieldId = field.id
        fillKitFieldLabel = field.label ?: field.id
        fillKitFieldType = BuiltInContentTypeMapper.suggest(field.contentType, FieldSuggestionContext())
            ?.type?.displayName() ?: "ContentType"
        field.group?.let { fillKitFieldGroup = it }
        fillKitFormId = activeRegistry.formId
        fillKitSource = "ContentType"
        fillKitConfidence = SuggestionConfidence.Exact.name
        fillKitTarget = field.target.kind.name
    }
}

private class FillKitSuggestionNode(
    private var metadata: FieldMetadata,
    private var target: FillTarget<String>?,
    private var declaredContentType: ContentType?,
) : Modifier.Node(), CompositionLocalConsumerModifierNode, ObserverModifierNode, SemanticsModifierNode {
    private var registry: FillKitRegistry? = null
    override val isImportantForBounds: Boolean get() = false

    override fun onAttach() = onObservedReadsChanged()
    override fun onObservedReadsChanged() {
        observeReads {
            val latest = currentValueOf(LocalFillKitRegistry)
            if (latest !== registry) {
                registry?.unregisterSuggestion(this)
                registry = latest
                registry?.registerSuggestion(this, candidate())
                invalidateSemantics()
            }
        }
    }
    override fun onDetach() { registry?.unregisterSuggestion(this); registry = null }
    fun update(metadata: FieldMetadata, target: FillTarget<String>?, contentType: ContentType?) {
        this.metadata = metadata; this.target = target; this.declaredContentType = contentType
        registry?.updateSuggestion(this, candidate())
        invalidateSemantics()
    }
    override fun SemanticsPropertyReceiver.applySemantics() {
        declaredContentType?.let { contentType = it }
        val activeRegistry = registry ?: return
        fillKitFieldId = metadata.id ?: metadata.testTag ?: "suggested-${hashCode()}"
        fillKitFieldLabel = metadata.label ?: metadata.id ?: "Suggested field"
        fillKitFormId = activeRegistry.formId
        fillKitSource = "Suggestion"
        fillKitTarget = target?.kind?.name ?: SuggestionFillability.DetectionOnly.name
    }
    private fun candidate() = FillKitSuggestionCandidate(metadata, target, declaredContentType)
}
