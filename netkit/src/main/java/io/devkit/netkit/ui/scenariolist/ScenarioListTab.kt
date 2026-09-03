package io.devkit.netkit.ui.scenariolist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.model.ScenarioId
import io.devkit.netkit.scenario.model.ScenarioPackContents
import io.devkit.netkit.scenario.runtime.SequenceProgress
import io.devkit.netkit.ui.NetKitFormat
import io.devkit.netkit.ui.NetKitTestTags
import io.devkit.netkit.ui.components.NetKitBadge
import io.devkit.netkit.ui.components.NetKitCard
import io.devkit.netkit.ui.components.NetKitEmptyState
import io.devkit.netkit.ui.components.NetKitGutter
import io.devkit.netkit.ui.components.NetKitMonoStyle
import io.devkit.netkit.ui.components.NetKitSectionLabel

/**
 * Saved scenarios: what is active, what is available, and how to get more.
 *
 * The active scenario sits at the top and is never more than one tap from being
 * switched off, because the single most expensive thing that can happen to a QA
 * session is not realising a scenario is still on.
 *
 * @param search the current filter text; empty shows everything.
 */
@Composable
internal fun ScenarioListTab(
    scenarios: List<NetworkScenario>,
    packs: List<ScenarioPackContents>,
    activeId: ScenarioId?,
    sequenceProgress: Map<String, SequenceProgress>,
    search: String,
    onSearchChange: (String) -> Unit,
    onOpen: (NetworkScenario) -> Unit,
    onToggleActive: (NetworkScenario) -> Unit,
    onNew: () -> Unit,
    onNewFromPreset: () -> Unit,
    onImport: () -> Unit,
    onSaveCurrentSetup: () -> Unit,
    canSaveCurrentSetup: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
) {
    val query = search.trim()
    val matching = scenarios.filter { it.matches(query) }
    val active = scenarios.firstOrNull { it.id == activeId }
    val packed = packs.map { contents ->
        contents.copy(scenarios = contents.scenarios.filter { it.matches(query) })
    }.filter { it.scenarios.isNotEmpty() }
    val loose = matching.filter { it.metadata.packId == null && it.id != activeId }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag(NetKitTestTags.SCENARIO_LIST),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "active") {
            Column(
                modifier = Modifier.padding(horizontal = NetKitGutter),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NetKitSectionLabel("Active scenario")
                if (active == null) {
                    Text(
                        text = "None. Activate a scenario below, or use the console tab for " +
                            "one-off overrides.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ActiveScenarioCard(
                        scenario = active,
                        sequenceProgress = sequenceProgress,
                        onOpen = { onOpen(active) },
                        onDeactivate = { onToggleActive(active) },
                    )
                }
            }
        }

        item(key = "search") {
            OutlinedTextField(
                value = search,
                onValueChange = onSearchChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NetKitGutter)
                    .testTag(NetKitTestTags.SCENARIO_SEARCH),
                label = { Text("Search scenarios") },
                placeholder = { Text("name, description or path") },
                singleLine = true,
            )
        }

        if (loose.isNotEmpty()) {
            item(key = "saved-header") {
                NetKitSectionLabel(
                    label = "Saved",
                    trailing = "${loose.size}",
                    modifier = Modifier.padding(horizontal = NetKitGutter),
                )
            }
            items(loose, key = { it.id.value }) { scenario ->
                ScenarioRow(
                    scenario = scenario,
                    isActive = false,
                    onClick = { onOpen(scenario) },
                    modifier = Modifier.padding(horizontal = NetKitGutter),
                )
            }
        }

        packed.forEach { contents ->
            item(key = "pack-${contents.pack.id.value}") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NetKitGutter, vertical = 4.dp)
                        .testTag(NetKitTestTags.SCENARIO_PACK_ROW_PREFIX + contents.pack.id.value),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = contents.pack.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (contents.pack.isReadOnly) NetKitBadge(text = "BUILT-IN")
                    Text(
                        text = contents.summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(contents.scenarios, key = { it.id.value }) { scenario ->
                ScenarioRow(
                    scenario = scenario,
                    isActive = scenario.id == activeId,
                    onClick = { onOpen(scenario) },
                    modifier = Modifier.padding(horizontal = NetKitGutter),
                )
            }
        }

        if (matching.isEmpty()) {
            item(key = "empty") {
                NetKitEmptyState(
                    title = if (query.isEmpty()) "No saved scenarios" else "Nothing matches \"$query\"",
                    detail = if (query.isEmpty()) {
                        "Create one, import a .netkit.json from a bug report, or save your " +
                            "current console setup as a scenario."
                    } else {
                        "Try a different name, description or endpoint path."
                    },
                )
            }
        }

        item(key = "actions") {
            Column(
                modifier = Modifier.padding(horizontal = NetKitGutter, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onNewFromPreset,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(NetKitTestTags.SCENARIO_FROM_PRESET),
                ) {
                    Text("New from a template")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onNew,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(NetKitTestTags.SCENARIO_NEW),
                    ) {
                        Text("Blank scenario")
                    }
                    OutlinedButton(
                        onClick = onImport,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(NetKitTestTags.SCENARIO_IMPORT),
                    ) {
                        Text("Import")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onSaveCurrentSetup,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(NetKitTestTags.SCENARIO_SAVE_CURRENT),
                        enabled = canSaveCurrentSetup,
                    ) {
                        Text("Save current setup")
                    }
                }
                Text(
                    text = if (canSaveCurrentSetup) {
                        "\"Save current setup\" turns the console's global settings and " +
                            "temporary overrides into a reusable scenario."
                    } else {
                        "Set a global mode or add a temporary override in the console tab to " +
                            "save it as a scenario."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The active scenario, spelled out.
 *
 * Shows live sequence progress inline: knowing that `/bookings` is on step 2 of
 * 3 is what tells a developer whether the next tap will finally succeed.
 */
@Composable
private fun ActiveScenarioCard(
    scenario: NetworkScenario,
    sequenceProgress: Map<String, SequenceProgress>,
    onOpen: () -> Unit,
    onDeactivate: () -> Unit,
) {
    NetKitCard(
        modifier = Modifier.testTag(NetKitTestTags.SCENARIO_ACTIVE_CARD),
        emphasised = true,
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = scenario.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            NetKitBadge(
                text = if (scenario.enabled) "ACTIVE" else "PAUSED",
                container = MaterialTheme.colorScheme.tertiary,
                content = MaterialTheme.colorScheme.onTertiary,
            )
        }
        Text(
            text = scenario.summary,
            style = MaterialTheme.typography.bodySmall,
        )

        val progressed = scenario.rules.mapNotNull { rule ->
            sequenceProgress[rule.id]?.let { rule to it }
        }
        progressed.forEach { (rule, progress) ->
            Text(
                text = "${rule.displayTarget} · sequence ${progress.display}",
                style = NetKitMonoStyle,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        OutlinedButton(
            onClick = onDeactivate,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(NetKitTestTags.SCENARIO_DEACTIVATE),
        ) {
            Text("Deactivate")
        }
    }
}

@Composable
private fun ScenarioRow(
    scenario: NetworkScenario,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NetKitCard(
        modifier = modifier.testTag(NetKitTestTags.SCENARIO_ROW_PREFIX + scenario.id.value),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = scenario.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isActive) {
                NetKitBadge(
                    text = "ACTIVE",
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            if (scenario.hasSequence) NetKitBadge(text = "SEQ")
        }
        Text(
            text = NetKitFormat.scenarioSubtitle(scenario),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        scenario.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Name, description and endpoint paths, so search finds "the checkout one". */
private fun NetworkScenario.matches(query: String): Boolean {
    if (query.isEmpty()) return true
    if (name.contains(query, ignoreCase = true)) return true
    if (description?.contains(query, ignoreCase = true) == true) return true
    return rules.any { it.matcher.label.contains(query, ignoreCase = true) }
}
