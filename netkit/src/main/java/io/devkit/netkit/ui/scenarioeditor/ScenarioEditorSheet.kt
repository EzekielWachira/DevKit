package io.devkit.netkit.ui.scenarioeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.devkit.netkit.config.NetKitDefaults
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.GlobalNetworkConfig
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.model.ScenarioPack
import io.devkit.netkit.ui.NetKitFormat
import io.devkit.netkit.ui.NetKitTestTags
import io.devkit.netkit.ui.components.NetKitBadge
import io.devkit.netkit.ui.components.NetKitCard
import io.devkit.netkit.ui.components.NetKitChoiceChip
import io.devkit.netkit.ui.components.NetKitDivider
import io.devkit.netkit.ui.components.NetKitGutter
import io.devkit.netkit.ui.components.NetKitMonoStyle
import io.devkit.netkit.ui.components.NetKitRowAction
import io.devkit.netkit.ui.components.NetKitSectionLabel
import io.devkit.netkit.ui.scenarios.RuleEditorSheet
import io.devkit.netkit.ui.scenarios.RuleEditorState

/**
 * Create or edit a saved scenario: name, description, global behaviour, rules.
 *
 * Rule editing is **not** reimplemented here. The sheet opens the same
 * [RuleEditorSheet] the console uses for temporary overrides, so a rule behaves
 * and validates identically wherever it is authored.
 *
 * Edits are held locally and committed on Save, so backing out of a
 * half-finished scenario changes nothing — including for a scenario that is
 * currently active and serving traffic.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ScenarioEditorSheet(
    initial: NetworkScenario,
    packs: List<ScenarioPack>,
    onSave: (NetworkScenario) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var ruleEditor by remember(initial.id) { mutableStateOf<RuleEditorState?>(null) }
    val nameError = if (draft.name.isBlank()) "A scenario needs a name" else null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(NetKitTestTags.SCENARIO_EDITOR),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = NetKitGutter)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (initial.rules.isEmpty() && initial.name.isBlank()) {
                    "New scenario"
                } else {
                    "Edit scenario"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(NetKitTestTags.SCENARIO_EDITOR_NAME),
                label = { Text("Name") },
                placeholder = { Text("Checkout gateway failure") },
                singleLine = true,
                isError = nameError != null,
                supportingText = nameError?.let { { Text(it) } },
            )

            OutlinedTextField(
                value = draft.description.orEmpty(),
                onValueChange = { draft = draft.copy(description = it.takeIf(String::isNotBlank)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp)
                    .testTag(NetKitTestTags.SCENARIO_EDITOR_DESCRIPTION),
                label = { Text("Description (optional)") },
                placeholder = { Text("What this reproduces, and the ticket it belongs to") },
            )

            if (packs.isNotEmpty()) {
                NetKitSectionLabel("Pack")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NetKitChoiceChip(
                        label = "None",
                        selected = draft.metadata.packId == null,
                        onClick = {
                            draft = draft.copy(metadata = draft.metadata.copy(packId = null))
                        },
                    )
                    packs.filterNot { it.isReadOnly }.forEach { pack ->
                        NetKitChoiceChip(
                            label = pack.name,
                            selected = draft.metadata.packId == pack.id,
                            onClick = {
                                draft = draft.copy(
                                    metadata = draft.metadata.copy(packId = pack.id),
                                )
                            },
                        )
                    }
                }
            }

            NetKitDivider()

            GlobalSection(
                global = draft.globalConfig,
                onChange = { draft = draft.copy(globalConfig = it) },
            )

            NetKitDivider()

            NetKitSectionLabel(
                label = "Endpoint rules",
                trailing = if (draft.rules.isEmpty()) "none" else "${draft.rules.size}",
            )
            if (draft.rules.isEmpty()) {
                Text(
                    text = "Add a rule to change one endpoint while the rest of the app keeps " +
                        "using the real backend.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            draft.rules.forEach { rule ->
                NetKitCard(onClick = { ruleEditor = RuleEditorState.from(rule) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NetKitBadge(
                            text = rule.method.label,
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = rule.matcher.label,
                            modifier = Modifier.weight(1f),
                            style = NetKitMonoStyle,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                        if (!rule.enabled) NetKitBadge(text = "OFF")
                        NetKitRowAction(
                            label = "Remove",
                            onClick = {
                                draft = draft.copy(rules = draft.rules.filterNot { it.id == rule.id })
                            },
                            contentDescription = "Remove rule ${rule.displayTarget}",
                        )
                    }
                    Text(
                        text = NetKitFormat.actionSummary(rule.action),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedButton(
                onClick = { ruleEditor = RuleEditorState.new() },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(NetKitTestTags.SCENARIO_EDITOR_ADD_RULE),
            ) {
                Text("Add endpoint rule")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = { onSave(draft) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(NetKitTestTags.SCENARIO_EDITOR_SAVE),
                    enabled = nameError == null,
                ) {
                    Text("Save scenario")
                }
            }
        }
    }

    ruleEditor?.let { editor ->
        RuleEditorSheet(
            initial = editor,
            onSave = { rule ->
                draft = draft.copy(rules = draft.rules.upsert(rule))
                ruleEditor = null
            },
            onDelete = { id ->
                draft = draft.copy(rules = draft.rules.filterNot { it.id == id })
                ruleEditor = null
            },
            onDismiss = { ruleEditor = null },
        )
    }
}

/**
 * The scenario's own global behaviour, with an explicit "leave it alone" option.
 *
 * The distinction matters: a rules-only scenario should not stop a QA engineer
 * from also flipping the console's offline switch, while a scenario that pins
 * the network to normal is a deliberate statement that nothing else may.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlobalSection(
    global: GlobalNetworkConfig?,
    onChange: (GlobalNetworkConfig?) -> Unit,
) {
    NetKitSectionLabel(
        label = "Global network",
        trailing = global?.summary ?: "not set",
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text("This scenario sets the global network", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Off: the console's own global setting stays in charge.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = global != null,
            onCheckedChange = { checked -> onChange(if (checked) GlobalNetworkConfig() else null) },
        )
    }

    if (global == null) return

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlobalNetworkMode.selectable.forEach { mode ->
            NetKitChoiceChip(
                label = mode.label,
                selected = global.mode == mode,
                onClick = { onChange(global.copy(mode = mode)) },
            )
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NetKitDefaults.LATENCY_PRESETS.forEach { preset ->
            NetKitChoiceChip(
                label = NetKitFormat.latency(preset),
                selected = global.latencyMillis == preset,
                onClick = { onChange(global.copy(latencyMillis = preset)) },
            )
        }
    }
}

/** Replaces the rule with the same id, or appends it. */
private fun List<EndpointRule>.upsert(rule: EndpointRule): List<EndpointRule> {
    val index = indexOfFirst { it.id == rule.id }
    return if (index < 0) this + rule else toMutableList().apply { set(index, rule) }
}
