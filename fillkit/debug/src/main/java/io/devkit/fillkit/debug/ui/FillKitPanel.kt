package io.devkit.fillkit.debug.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.devkit.fillkit.FillKitConfig
import io.devkit.fillkit.FillLocale
import io.devkit.fillkit.FillPersona
import io.devkit.fillkit.FillValue
import io.devkit.fillkit.displayName
import io.devkit.fillkit.debug.runtime.FormRegistry
import io.devkit.fillkit.debug.runtime.StoredField

/**
 * Main FillKit surface. Everything above the divider is pinned so "Fill All"
 * stays reachable; only the field list scrolls. Persona, locale, scenario and
 * suggestion pickers live in their own sheets, reached through the context row.
 */
@Composable
internal fun FillKitPanel(
    registry: FormRegistry,
    config: FillKitConfig,
    onFillAll: () -> Unit,
    onOpenRoute: (PanelRoute) -> Unit,
) {
    val fields = registry.fields
    val gutter = Modifier.padding(horizontal = SheetGutter)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Never let the panel grow into a full-screen surface — a strip of
                // the form it is filling always stays visible above it.
                .heightIn(max = maxHeight * MaxPanelHeightFraction)
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
        ) {
            PanelHeader(registry.formId, fields.size, gutter, onOpenRoute)
            Spacer(Modifier.height(16.dp))
            PrimaryActions(
                enabled = fields.isNotEmpty(),
                modifier = gutter,
                onFillAll = onFillAll,
                onRandomize = registry::regenerateAll,
                onClear = registry::clearAll,
            )
            Spacer(Modifier.height(14.dp))
            ContextRow(registry, onOpenRoute)
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(12.dp))
            Box(gutter) { SectionHeader("Fields", "${fields.size} registered") }

            if (fields.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Box(gutter) { EmptyFieldsCard() }
            } else {
                val listState = rememberLazyListState()
                LazyColumn(
                    // fill = false keeps the sheet as short as its content until the
                    // list outgrows the cap, then the list — not the sheet — scrolls.
                    state = listState,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(min = 120.dp)
                        .fadingEdges(listState),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(
                        start = SheetGutter,
                        end = SheetGutter,
                        top = 12.dp,
                        bottom = 12.dp,
                    ),
                ) {
                    items(fields, key = { System.identityHashCode(it.owner) }) { field ->
                        FieldCard(field, config.showFieldValues, registry)
                    }
                }
            }
        }
    }
}

/**
 * Softens the list only on the edges it can actually scroll past, so a short
 * list that fits stays crisp.
 */
private fun Modifier.fadingEdges(state: LazyListState): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = 20.dp.toPx()
        if (state.canScrollBackward) {
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black),
                    startY = 0f,
                    endY = fade,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (state.canScrollForward) {
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.Black, Color.Transparent),
                    startY = size.height - fade,
                    endY = size.height,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

@Composable
private fun PanelHeader(
    formId: String,
    fieldCount: Int,
    modifier: Modifier = Modifier,
    onOpenRoute: (PanelRoute) -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(ControlShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("⚡", style = MaterialTheme.typography.titleMedium)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "FillKit",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                formId.humanizeId(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HeaderAction("QA") { onOpenRoute(PanelRoute.Qa) }
        MiniBadge("$fieldCount")
    }
}

/** Compact header entry point; keeps the scrolling context row free for state. */
@Composable
private fun HeaderAction(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PrimaryActions(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onFillAll: () -> Unit,
    onRandomize: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FillAllButton(enabled, Modifier.weight(1f), onFillAll)
        // Icon-only so all three actions share one row and the field list keeps
        // the height the stacked layout used to spend on buttons.
        SquareAction("↻", "Randomize every field", enabled, onClick = onRandomize)
        SquareAction("✕", "Clear every field", enabled, destructive = true, onClick = onClear)
    }
}

@Composable
private fun FillAllButton(enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = if (enabled) {
        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    } else {
        listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .height(ActionHeight)
            .shadow(if (enabled) 6.dp else 0.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(colors))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Fill All",
                color = contentColor,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text("→", color = contentColor, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SquareAction(
    glyph: String,
    description: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Box(
        modifier = Modifier
            .size(ActionHeight)
            .clip(ControlShape)
            .background(
                if (destructive) {
                    Color.Transparent
                } else {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (enabled) 1f else 0.4f)
                },
            )
            .then(
                if (destructive) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), ControlShape)
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = tint, style = MaterialTheme.typography.titleLarge)
    }
}

/** One scrollable line of entry points into the secondary settings sheets. */
@Composable
private fun ContextRow(registry: FormRegistry, onOpenRoute: (PanelRoute) -> Unit) {
    val persona = registry.persona
    val suggestions = registry.suggestions
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = SheetGutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ContextChip(
            glyph = "#",
            label = "Seed",
            value = registry.masterSeed.value.toString(),
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
            onClick = { onOpenRoute(PanelRoute.Reproduction) },
        )
        if (suggestions.isNotEmpty()) {
            ContextChip(
                glyph = "!",
                label = "Suggestions",
                value = "${suggestions.size} detected",
                container = MaterialTheme.colorScheme.tertiary,
                content = MaterialTheme.colorScheme.onTertiary,
                highlighted = true,
                onClick = { onOpenRoute(PanelRoute.Suggestions) },
            )
        }
        ContextChip(
            glyph = if (registry.isRandomPersona) "?" else persona.displayValue().take(1).uppercase(),
            label = "Persona",
            value = if (registry.isRandomPersona) "Random" else persona.displayValue(),
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = { onOpenRoute(PanelRoute.Persona) },
        )
        ContextChip(
            glyph = registry.localeTag.substringAfter('-').take(2).uppercase(),
            label = "Locale",
            value = registry.localePack.displayName,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = { onOpenRoute(PanelRoute.Locale) },
        )
        if (registry.scenarioGroups.isNotEmpty()) {
            val active = registry.activeScenarioId
                ?.let { id -> registry.scenarioGroups.flatMap { it.values }.firstOrNull { it.id == id } }
            ContextChip(
                glyph = "▶",
                label = "Scenario",
                value = active?.name ?: "None",
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = { onOpenRoute(PanelRoute.Scenario) },
            )
        }
    }
}

@Composable
private fun ContextChip(
    glyph: String,
    label: String,
    value: String,
    container: Color,
    content: Color,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clip(ChipShape)
            .clickable(role = Role.Button, onClick = onClick),
        shape = ChipShape,
        color = if (highlighted) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            GlyphTile(glyph, container, content, size = 30, shape = CircleShape)
            Column {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    value,
                    modifier = Modifier.widthIn(max = 150.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "›",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyFieldsCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("No fields yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Attach Modifier.fillKit to a field and it shows up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FieldCard(field: StoredField, showValue: Boolean, registry: FormRegistry) {
    val category = field.type.category()
    val meta = listOfNotNull(
        field.type.displayName(),
        field.source,
        field.confidence.name,
        field.target.kind.name,
        field.generator?.id,
    ).joinToString(" · ")
    val value = field.currentValue?.toString().orEmpty()
    val filled = value.isNotEmpty()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = FieldCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlyphTile(
                glyph = field.type.glyph(),
                container = category.container(),
                content = category.onContainer(),
                size = 30,
                shape = RoundedCornerShape(10.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        field.label,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (field.group != null) {
                        Text(
                            field.group.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showValue && filled) {
                    Text(
                        value,
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .clip(ValueShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            FieldAction("↻", "Fill ${field.label}") { registry.fill(field.id) }
            if (filled || !showValue) {
                FieldAction("✕", "Clear ${field.label}", destructive = true) { registry.clear(field.id) }
            }
        }
    }
}

/** Icon-sized per-field control; keeps each row close to two lines tall. */
@Composable
private fun FieldAction(
    glyph: String,
    description: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(
                if (destructive) {
                    Color.Transparent
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.bodyMedium,
            color = if (destructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
        )
    }
}

internal fun FillPersona.displayValue(): String {
    val fullName = (values["fullName"] as? FillValue.Text)?.value
    if (!fullName.isNullOrBlank()) return fullName
    val first = (values["firstName"] as? FillValue.Text)?.value.orEmpty()
    val last = (values["lastName"] as? FillValue.Text)?.value.orEmpty()
    return "$first $last".trim().ifBlank { name }
}

internal fun FillPersona.localeTagOrNull(): String? = (locale as? FillLocale.Code)?.value
