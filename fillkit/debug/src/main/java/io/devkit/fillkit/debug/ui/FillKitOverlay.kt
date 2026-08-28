package io.devkit.fillkit.debug.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.devkit.fillkit.FillKitConfig
import io.devkit.fillkit.FillLocale
import io.devkit.fillkit.FillPersona
import io.devkit.fillkit.FillValue
import io.devkit.fillkit.debug.runtime.FormRegistry
import io.devkit.fillkit.debug.runtime.StoredField

private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private val CardShape = RoundedCornerShape(20.dp)
private val ControlShape = RoundedCornerShape(14.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FillKitOverlay(registry: FormRegistry, config: FillKitConfig) {
    var open by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (config.showTrigger) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            contentAlignment = Alignment.BottomEnd,
        ) {
            FillKitTrigger(onClick = { open = true })
        }
    }

    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = sheetState,
            shape = SheetShape,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            dragHandle = { PremiumDragHandle() },
        ) {
            FillKitPanel(
                registry = registry,
                config = config,
                onFillAll = {
                    registry.fillAll()
                    open = false
                },
            )
        }
    }
}

@Composable
private fun FillKitTrigger(onClick: () -> Unit) {
    val gradient = Brush.linearGradient(
        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
    )
    Surface(
        modifier = Modifier
            .size(54.dp)
            .shadow(12.dp, CircleShape)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        shape = CircleShape,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier.background(gradient),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "⚡",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun PremiumDragHandle() {
    Box(
        modifier = Modifier
            .padding(top = 12.dp, bottom = 8.dp)
            .size(width = 40.dp, height = 4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)),
    )
}

@Composable
private fun FillKitPanel(
    registry: FormRegistry,
    config: FillKitConfig,
    onFillAll: () -> Unit,
) {
    val fields = registry.fields
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxHeight(0.92f)
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
    ) {
        PanelHeader(registry.formId, fields.size)
        PrimaryFillButton(fieldCount = fields.size, onClick = onFillAll)
        SecondaryActions(
            onRandomize = registry::regenerateAll,
            onClear = registry::clearAll,
        )
        PersonaCard(registry)
        ConfigurationSection(registry)
        FieldsHeader(fields.size)

        if (fields.isEmpty()) {
            EmptyFieldsCard()
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(fields, key = { System.identityHashCode(it.owner) }) { field ->
                    FieldCard(field, config.showFieldValues, registry)
                }
                item { Spacer(Modifier.height(4.dp)) }
            }
        }
    }
}

@Composable
private fun PanelHeader(formId: String, fieldCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text("⚡", style = MaterialTheme.typography.titleMedium)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "FillKit",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                formId.humanize(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                text = fieldCount.toString(),
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PrimaryFillButton(fieldCount: Int, onClick: () -> Unit) {
    val enabled = fieldCount > 0
    val colors = if (enabled) {
        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    } else {
        listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .shadow(if (enabled) 6.dp else 0.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(colors))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                "Fill All",
                color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "  →",
                color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun SecondaryActions(onRandomize: () -> Unit, onClear: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        FilledTonalButton(
            onClick = onRandomize,
            modifier = Modifier.weight(1f).height(46.dp),
            shape = ControlShape,
        ) {
            Text("↻  Randomize", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
            onClick = onClear,
            modifier = Modifier.weight(1f).height(46.dp),
            shape = ControlShape,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text("Clear", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PersonaCard(registry: FormRegistry) {
    var saveDialog by remember { mutableStateOf(false) }
    var personaName by remember { mutableStateOf("") }
    val persona = registry.persona
    val title = persona.displayValue()
    val runtimeSaved = persona.metadata["source"] == "runtime"
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "ACTIVE PERSONA",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LocaleBadge((persona.locale as? FillLocale.Code)?.value ?: registry.localeTag)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            if (runtimeSaved) {
                TextButton(onClick = { registry.deleteRuntimePersona(persona.id) }) { Text("Delete") }
            }
            TextButton(onClick = {
                personaName = if (registry.isRandomPersona) "" else "${persona.name} copy"
                saveDialog = true
            }) { Text("Save Persona") }
        }
    }

    if (saveDialog) {
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text("Save persona") },
            text = {
                OutlinedTextField(
                    value = personaName,
                    onValueChange = { personaName = it },
                    label = { Text("Persona name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = personaName.isNotBlank(),
                    onClick = { registry.saveCurrentPersona(personaName); saveDialog = false },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LocaleBadge(localeTag: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            localeTag.substringAfter('-').uppercase(),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ConfigurationSection(registry: FormRegistry) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Persona")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            SelectionPill("Random", registry.isRandomPersona, registry::selectRandomPersona)
            registry.personas.forEach { persona ->
                SelectionPill(
                    label = persona.name,
                    selected = registry.activePersonaId == persona.id,
                    onClick = { registry.selectPersona(persona.id) },
                )
            }
        }
        if (registry.savedRuntimePersonas.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "Delete all saved personas",
                    modifier = Modifier.clip(CircleShape).clickable { registry.deleteAllRuntimePersonas() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SectionLabel("Locale")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            registry.availableLocales.forEach { option ->
                SelectionPill(
                    label = option.displayName,
                    selected = registry.localeTag == option.code,
                    onClick = { registry.changeLocale(option.code) },
                )
            }
        }

        if (registry.scenarioGroups.isNotEmpty()) {
            SectionLabel("Scenarios")
            registry.scenarioGroups.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        group.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        group.values.forEach { scenario ->
                            SelectionPill(
                                label = scenario.name,
                                selected = registry.activeScenarioId == scenario.id,
                                onClick = { registry.applyScenario(scenario.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(label: String) {
    Text(
        label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun SelectionPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = if (selected) "✓  $label" else label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun FieldsHeader(fieldCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Fields", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "$fieldCount registered",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyFieldsCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("No fields yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Registered fields will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FieldCard(field: StoredField, showValue: Boolean, registry: FormRegistry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    if (field.group != null) {
                        Text(
                            field.group.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        field.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                FilledTonalButton(
                    onClick = { registry.fill(field.id) },
                    shape = CircleShape,
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Text("↻  Fill", fontWeight = FontWeight.SemiBold)
                }
            }

            if (showValue) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        field.currentValue?.toString()?.ifEmpty { "Empty" } ?: "Empty",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Clear",
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(role = Role.Button) { registry.clear(field.id) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                Text(
                    "Clear",
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { registry.clear(field.id) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun FillPersona.displayValue(): String {
    val fullName = (values["fullName"] as? FillValue.Text)?.value
    if (!fullName.isNullOrBlank()) return fullName
    val first = (values["firstName"] as? FillValue.Text)?.value.orEmpty()
    val last = (values["lastName"] as? FillValue.Text)?.value.orEmpty()
    return "$first $last".trim().ifBlank { name }
}

private fun String.humanize(): String = replace('-', ' ').replaceFirstChar(Char::uppercase)
