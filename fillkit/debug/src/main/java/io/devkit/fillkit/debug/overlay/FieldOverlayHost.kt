package io.devkit.fillkit.debug.overlay

import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.devkit.fillkit.FieldOverlayBehavior
import io.devkit.fillkit.FieldOverlayCapability
import io.devkit.fillkit.FieldOverlayConfig
import io.devkit.fillkit.FieldOverlayMode
import io.devkit.fillkit.FillKitOverlayAction
import io.devkit.fillkit.FillKitOverlaySemantics
import io.devkit.fillkit.debug.runtime.FormRegistry
import io.devkit.fillkit.debug.runtime.StoredField
import io.devkit.fillkit.displayName
import io.devkit.fillkit.isSensitivePreview
import io.devkit.fillkit.overlayCapability

/**
 * One overlay host per [io.devkit.fillkit.FillKitHost].
 *
 * Only one field has text focus at a time, so a single host renders the
 * contextual accessory instead of every field carrying its own popup. It draws
 * inside the host's own Box rather than a `Popup`, which keeps it in the same
 * window as the form — the reason tapping a suggestion cannot take focus from
 * the text field or dismiss the keyboard.
 */
@Composable
internal fun BoxScope.FieldOverlayHost(registry: FormRegistry, config: FieldOverlayConfig) {
    if (!config.active) return

    var hostBounds by remember { mutableStateOf(Rect.Zero) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    var inspected by remember { mutableStateOf<String?>(null) }

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val windowImeInset = WindowInsets.ime.getBottom(density)

    val focused = registry.focusedField
    val field = focused?.field?.takeIf { it.overlayEnabled(config) }
    val fieldBounds = focused?.bounds
    val hostSize = IntSize(hostBounds.width.toInt(), hostBounds.height.toInt())
    // The IME inset is measured against the window, so only the part of it that
    // actually overlaps this host counts. A small host sitting above the
    // keyboard is not obscured at all.
    val imeInset = if (windowImeInset <= 0) {
        0
    } else {
        (hostBounds.bottom - (windowHeight - windowImeInset)).coerceAtLeast(0f).toInt()
    }
    val relativeBounds = fieldBounds?.translate(-hostBounds.left, -hostBounds.top)
    val visible = field != null && relativeBounds != null &&
        FieldOverlayPositioner.isFieldVisible(relativeBounds, hostSize, imeInset)

    // Held across the exit animation so the bar fades out instead of vanishing
    // the instant a field leaves composition.
    var rendered by remember { mutableStateOf<StoredField?>(null) }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    if (visible) {
        rendered = field
        bounds = fieldBounds
    }

    Box(
        modifier = Modifier
            .matchParentSize()
            .onGloballyPositioned { hostBounds = it.boundsInWindow() },
    ) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            val target = rendered
            val anchor = bounds
            if (target == null || anchor == null) return@AnimatedVisibility

            val layout = FieldOverlayPositioner.place(
                field = anchor.translate(-hostBounds.left, -hostBounds.top),
                container = hostSize,
                overlay = overlaySize,
                imeInset = imeInset,
                requested = config.placement,
                layoutDirection = layoutDirection,
            )
            val offset by animateIntOffsetAsState(IntOffset(layout.x, layout.y), label = "fillkit-overlay")

            FieldOverlayBar(
                field = target,
                registry = registry,
                config = config,
                placement = layout.placement,
                fillWidth = layout.fillWidth,
                modifier = Modifier
                    .offset { offset }
                    .then(
                        with(density) {
                            if (layout.fillWidth) {
                                Modifier.width(layout.maxWidth.toDp())
                            } else {
                                Modifier.widthIn(max = layout.maxWidth.toDp())
                            }
                        },
                    )
                    .onSizeChanged { overlaySize = it },
                onInspect = { inspected = target.id },
            )
        }
    }

    inspected?.let { fieldId ->
        registry.fields.firstOrNull { it.id == fieldId }?.let { target ->
            FieldInspectorSheet(target, registry) { inspected = null }
        } ?: run { inspected = null }
    }
}

@Composable
private fun FieldOverlayBar(
    field: StoredField,
    registry: FormRegistry,
    config: FieldOverlayConfig,
    placement: ResolvedOverlayPlacement,
    fillWidth: Boolean,
    modifier: Modifier = Modifier,
    onInspect: () -> Unit,
) {
    val capability = field.type.overlayCapability()
    // Resolution is pure, so recomputing per composition keeps the preview in
    // step with seed, persona, scenario and locale changes without a stale key.
    val preview = if (config.showPreview) registry.preview(field) else null
    val text = preview?.toString().orEmpty()
    val display = when {
        capability == FieldOverlayCapability.Unsupported -> "Not generated by FillKit"
        text.isEmpty() -> field.type.displayName()
        config.maskSensitivePreview && field.type.isSensitivePreview() -> "•".repeat(text.length.coerceAtMost(16))
        else -> text
    }
    val modified = registry.isModified(field)
    val fillable = capability == FieldOverlayCapability.PreviewAndFill ||
        capability == FieldOverlayCapability.ActionOnly

    Surface(
        modifier = modifier
            .shadow(8.dp, RoundedCornerShape(14.dp))
            .semantics {
                this[FillKitOverlaySemantics.FieldId] = field.id
                this[FillKitOverlaySemantics.Placement] = placement.name
            },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("⚡", style = MaterialTheme.typography.labelLarge)
            if (registry.isScenarioValue(field)) OverlayTag("SCENARIO")
            if (modified) OverlayTag("MODIFIED")

            // The value itself is the fill affordance: one tap, no extra button.
            Text(
                text = display,
                modifier = Modifier
                    // An anchored bar hugs its content and truncates long values;
                    // only the keyboard accessory spans the container.
                    .then(if (fillWidth) Modifier.weight(1f) else Modifier.widthIn(max = 240.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .fillKitOverlayTap(
                        enabled = fillable,
                        description = if (modified) "Replace ${field.label}" else "Fill ${field.label}",
                    ) { registry.fill(field.id) }
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .semantics {
                        this[FillKitOverlaySemantics.FieldId] = field.id
                        this[FillKitOverlaySemantics.Preview] = display
                        this[FillKitOverlaySemantics.Modified] = modified
                        this[FillKitOverlaySemantics.Action] = FillKitOverlayAction.Fill.name.lowercase()
                    },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (fillable) {
                OverlayIcon("↻", FillKitOverlayAction.Regenerate, field, "Generate another ${field.label}") {
                    registry.regenerate(field.id)
                }
            }
            OverlayIcon("⋯", FillKitOverlayAction.Inspect, field, "Inspect ${field.label}", onInspect)
        }
    }
}

@Composable
private fun OverlayTag(label: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.18f)) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}

@Composable
private fun OverlayIcon(
    glyph: String,
    action: FillKitOverlayAction,
    field: StoredField,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.14f))
            .fillKitOverlayTap(enabled = true, description = description, onClick = onClick)
            .semantics {
                this[FillKitOverlaySemantics.FieldId] = field.id
                this[FillKitOverlaySemantics.Action] = action.name.lowercase()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.inverseOnSurface)
    }
}

/**
 * Taps without focus. `Modifier.clickable` makes a node focusable, which would
 * pull focus off the text field and close the keyboard before the fill runs;
 * a raw pointer handler plus a semantics click keeps the field focused and
 * still leaves the control reachable from tests and accessibility services.
 */
private fun Modifier.fillKitOverlayTap(
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
): Modifier = if (!enabled) {
    this
} else {
    pointerInput(description, onClick) { detectTapGestures { onClick() } }
        .semantics {
            role = Role.Button
            contentDescription = description
            onClick(label = description) { onClick(); true }
        }
}

private fun StoredField.overlayEnabled(config: FieldOverlayConfig): Boolean = when (overlay) {
    FieldOverlayBehavior.Disabled -> false
    FieldOverlayBehavior.Enabled -> true
    FieldOverlayBehavior.Default -> config.mode != FieldOverlayMode.Disabled
}
