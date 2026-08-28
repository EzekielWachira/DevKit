package io.devkit.fillkit.debug.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.devkit.fillkit.FillTypeSuggestion
import io.devkit.fillkit.SuggestionFillability
import io.devkit.fillkit.displayName
import io.devkit.fillkit.debug.runtime.FormRegistry
import io.devkit.fillkit.debug.runtime.StoredSuggestion

private val ListMaxHeight = 420.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FillKitRouteSheet(
    route: PanelRoute,
    registry: FormRegistry,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = SheetShape,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        dragHandle = { FillKitDragHandle() },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * MaxPanelHeightFraction)
                    .navigationBarsPadding()
                    .padding(start = SheetGutter, end = SheetGutter, bottom = 12.dp),
            ) {
                when (route) {
                    PanelRoute.Persona -> PersonaSheet(registry, onDismiss)
                    PanelRoute.Locale -> LocaleSheet(registry, onDismiss)
                    PanelRoute.Scenario -> ScenarioSheet(registry, onDismiss)
                    PanelRoute.Suggestions -> SuggestionSheet(registry)
                    PanelRoute.Reproduction -> ReproductionSheet(registry, onDismiss)
                    PanelRoute.Qa -> QaLauncherSheet(registry, onDismiss)
                }
            }
        }
    }
}

@Composable
internal fun RouteHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Persona ---------------------------------------------------------------

@Composable
private fun ColumnScope.PersonaSheet(registry: FormRegistry, onDismiss: () -> Unit) {
    var saveDialog by remember { mutableStateOf(false) }
    var personaName by remember { mutableStateOf("") }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    RouteHeader("Persona", "The identity every generated value is drawn from.")

    LazyColumn(
        modifier = Modifier.weight(1f, fill = false).heightIn(max = ListMaxHeight),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        item {
            OptionRow(
                title = "Random",
                subtitle = "New identity on every fill",
                selected = registry.isRandomPersona,
                onClick = { registry.selectRandomPersona(); onDismiss() },
            )
        }
        registry.personaGroups.forEach { group ->
            item { SectionLabel(group.name, Modifier.padding(top = 8.dp, bottom = 2.dp)) }
            items(group.values, key = { it.id }) { persona ->
                val saved = persona.metadata["source"] == "runtime"
                OptionRow(
                    title = persona.name,
                    subtitle = persona.displayValue().takeIf { it != persona.name },
                    selected = registry.activePersonaId == persona.id,
                    badge = persona.localeTagOrNull()?.substringAfter('-')?.uppercase(),
                    onClick = { registry.selectPersona(persona.id); onDismiss() },
                    trailing = if (saved) {
                        { GhostAction("✕", MaterialTheme.colorScheme.error) { registry.deleteRuntimePersona(persona.id) } }
                    } else {
                        null
                    },
                )
            }
        }
    }

    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(
            onClick = {
                personaName = if (registry.isRandomPersona) "" else "${registry.persona.name} copy"
                saveDialog = true
            },
            modifier = Modifier.weight(1f).height(46.dp),
            shape = ControlShape,
        ) {
            Text("Save current persona", fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        if (registry.savedRuntimePersonas.isNotEmpty()) {
            GhostAction("Delete all", MaterialTheme.colorScheme.error) { confirmDeleteAll = true }
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

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete saved personas?") },
            text = { Text("Removes every persona saved on this device. Personas defined in code stay.") },
            confirmButton = {
                TextButton(onClick = {
                    registry.deleteAllRuntimePersonas()
                    confirmDeleteAll = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }
}

// --- Locale ----------------------------------------------------------------

@Composable
private fun ColumnScope.LocaleSheet(registry: FormRegistry, onDismiss: () -> Unit) {
    RouteHeader("Locale", "Names, addresses and phone formats follow this region.")
    LazyColumn(
        modifier = Modifier.weight(1f, fill = false).heightIn(max = ListMaxHeight),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(registry.availableLocales, key = { it.code }) { option ->
            OptionRow(
                title = option.displayName,
                subtitle = option.country,
                selected = registry.localeTag == option.code,
                badge = option.code.uppercase(),
                onClick = { registry.changeLocale(option.code); onDismiss() },
            )
        }
    }
}

// --- Scenario --------------------------------------------------------------

@Composable
private fun ColumnScope.ScenarioSheet(registry: FormRegistry, onDismiss: () -> Unit) {
    RouteHeader("Scenarios", "Apply a preset set of values across the whole form.")
    LazyColumn(
        modifier = Modifier.weight(1f, fill = false).heightIn(max = ListMaxHeight),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        registry.scenarioGroups.forEach { group ->
            item { SectionLabel(group.name, Modifier.padding(top = 8.dp, bottom = 2.dp)) }
            items(group.values, key = { it.id }) { scenario ->
                val overrides = scenario.values.size + scenario.generators.size
                OptionRow(
                    title = scenario.name,
                    subtitle = "$overrides field${if (overrides == 1) "" else "s"} overridden",
                    selected = registry.activeScenarioId == scenario.id,
                    onClick = { registry.applyScenario(scenario.id); onDismiss() },
                )
            }
        }
    }
}

// --- Suggestions -----------------------------------------------------------

@Composable
private fun ColumnScope.SuggestionSheet(registry: FormRegistry) {
    val suggestions = registry.suggestions
    RouteHeader("Suggestions", "Fields FillKit detected but you have not registered yet.")
    if (suggestions.isEmpty()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = CardShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Text(
                "Nothing left to review.",
                modifier = Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.weight(1f, fill = false).heightIn(max = ListMaxHeight),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(suggestions, key = { System.identityHashCode(it.owner) }) { suggestion ->
            SuggestionCard(suggestion, registry)
        }
    }
}

@Composable
private fun SuggestionCard(suggestion: StoredSuggestion, registry: FormRegistry) {
    var selected by remember(suggestion.id) {
        mutableStateOf(suggestion.candidates.firstOrNull())
    }
    val current = selected ?: suggestion.candidates.firstOrNull()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    suggestion.label,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                current?.let { MiniBadge("${it.type.displayName()} · ${it.confidence}") }
            }
            Text(
                current?.reasons?.firstOrNull()?.description ?: "No confident match",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (suggestion.candidates.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestion.candidates.forEach { candidate ->
                        CandidateRow(candidate, candidate == current) { selected = candidate }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                GhostAction("Ignore", MaterialTheme.colorScheme.onSurfaceVariant) {
                    registry.ignoreSuggestion(suggestion.owner)
                }
                Button(
                    onClick = { registry.acceptSuggestion(suggestion.owner, current) },
                    enabled = current?.fillability == SuggestionFillability.Fillable,
                    shape = CircleShape,
                ) { Text("Add field", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun CandidateRow(candidate: FillTypeSuggestion, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ChipShape)
            .clickable(onClick = onClick)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            candidate.type.displayName(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        MiniBadge(candidate.confidence.name)
        if (candidate.fillability != SuggestionFillability.Fillable) {
            MiniBadge(
                candidate.fillability.name,
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
