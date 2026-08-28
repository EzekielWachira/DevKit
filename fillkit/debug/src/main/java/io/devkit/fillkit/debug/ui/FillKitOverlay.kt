package io.devkit.fillkit.debug.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.devkit.fillkit.FillKitConfig
import io.devkit.fillkit.debug.runtime.FormRegistry
import io.devkit.fillkit.debug.runtime.StoredField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FillKitOverlay(registry: FormRegistry, config: FillKitConfig) {
    var open by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (config.showTrigger) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomEnd,
        ) {
            FloatingActionButton(
                onClick = { open = true },
                modifier = Modifier.size(48.dp),
            ) {
                Text("⚡", style = MaterialTheme.typography.titleLarge)
            }
        }
    }

    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = sheetState,
        ) {
            FillKitPanel(registry, config)
        }
    }
}

@Composable
private fun FillKitPanel(registry: FormRegistry, config: FillKitConfig) {
    val fields = registry.fields
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxHeight(0.9f)
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
    ) {
        Text("FillKit", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "${registry.formId.humanize()} · ${fields.size} registered ${if (fields.size == 1) "field" else "fields"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = registry::fillAll, modifier = Modifier.fillMaxWidth()) { Text("Fill All") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = registry::regenerateAll, modifier = Modifier.weight(1f)) {
                Text("Randomize")
            }
            OutlinedButton(onClick = registry::clearAll, modifier = Modifier.weight(1f)) { Text("Clear") }
        }

        Text("Persona", style = MaterialTheme.typography.labelLarge)
        Text(registry.persona.fullName, style = MaterialTheme.typography.titleMedium)

        Text("Locale", style = MaterialTheme.typography.labelLarge)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            listOf("en-KE" to "Kenya", "en-US" to "United States", "en-GB" to "United Kingdom").forEach { (tag, name) ->
                val selected = registry.localeTag == tag
                if (selected) {
                    FilledTonalButton(onClick = { registry.changeLocale(tag) }) { Text(name) }
                } else {
                    OutlinedButton(onClick = { registry.changeLocale(tag) }) { Text(name) }
                }
            }
        }

        if (config.scenarios.isNotEmpty()) {
            Text("Scenarios", style = MaterialTheme.typography.labelLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                config.scenarios.forEach { scenario ->
                    OutlinedButton(onClick = { registry.applyScenario(scenario.id) }) { Text(scenario.name) }
                }
            }
        }

        HorizontalDivider()
        Text("Fields", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (fields.isEmpty()) {
            Text("No fields are currently registered.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(fields, key = { System.identityHashCode(it.owner) }) { field ->
                    FieldRow(field, config.showFieldValues, registry)
                }
            }
        }
    }
}

@Composable
private fun FieldRow(field: StoredField, showValue: Boolean, registry: FormRegistry) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        if (field.group != null) {
            Text(field.group, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(field.label, style = MaterialTheme.typography.bodyLarge)
                if (showValue) {
                    Text(
                        field.currentValue?.toString()?.ifEmpty { "Empty" } ?: "Empty",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            OutlinedButton(onClick = { registry.fill(field.id) }) { Text("Fill") }
            Spacer(Modifier.size(4.dp))
            OutlinedButton(onClick = { registry.clear(field.id) }) { Text("Clear") }
        }
    }
}

private fun String.humanize(): String = replace('-', ' ').replaceFirstChar(Char::uppercase)
