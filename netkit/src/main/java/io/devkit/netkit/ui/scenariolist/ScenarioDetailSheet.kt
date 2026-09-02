package io.devkit.netkit.ui.scenariolist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.runtime.SequenceProgress
import io.devkit.netkit.ui.NetKitFormat
import io.devkit.netkit.ui.NetKitTestTags
import io.devkit.netkit.ui.components.NetKitBadge
import io.devkit.netkit.ui.components.NetKitCard
import io.devkit.netkit.ui.components.NetKitConfirmDialog
import io.devkit.netkit.ui.components.NetKitDivider
import io.devkit.netkit.ui.components.NetKitGutter
import io.devkit.netkit.ui.components.NetKitKeyValueRow
import io.devkit.netkit.ui.components.NetKitMonoStyle
import io.devkit.netkit.ui.components.NetKitRowAction
import io.devkit.netkit.ui.components.NetKitSectionLabel

/**
 * Everything about one saved scenario, and everything you can do with it.
 *
 * A built-in scenario shows the same information but hides Edit and Delete: it
 * is code, not data, and pretending otherwise would let a QA engineer make
 * changes that vanish on the next build. Duplicate is offered instead, which is
 * the honest way to make one editable.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ScenarioDetailSheet(
    scenario: NetworkScenario,
    isActive: Boolean,
    packName: String?,
    sequenceProgress: Map<String, SequenceProgress>,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onResetSequence: (String) -> Unit,
    onResetAllSequences: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmingDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(NetKitTestTags.SCENARIO_DETAIL),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = NetKitGutter)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = scenario.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (isActive) {
                    NetKitBadge(
                        text = if (scenario.enabled) "ACTIVE" else "PAUSED",
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                NetKitBadge(text = scenario.metadata.source.label.uppercase())
            }

            scenario.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(text = description, style = MaterialTheme.typography.bodyMedium)
            }

            Column {
                NetKitKeyValueRow(
                    label = "Global",
                    value = scenario.globalConfig?.summary
                        ?: "Not set — the console's own setting applies",
                )
                NetKitKeyValueRow(label = "Rules", value = "${scenario.rules.size}")
                packName?.let { NetKitKeyValueRow(label = "Pack", value = it) }
                NetKitKeyValueRow(
                    label = "Updated",
                    value = NetKitFormat.dateTime(scenario.metadata.updatedAtMillis),
                )
            }

            NetKitDivider()

            NetKitSectionLabel(
                label = "Endpoint rules",
                trailing = if (scenario.rules.isEmpty()) "none" else "${scenario.rules.size}",
            )
            if (scenario.rules.isEmpty()) {
                Text(
                    text = "This scenario has no endpoint rules. It only changes the global " +
                        "network, if it sets one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            scenario.rules.forEach { rule ->
                val sequence = rule.action as? NetworkAction.Sequence
                NetKitCard {
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
                        )
                        if (!rule.enabled) NetKitBadge(text = "OFF")
                    }
                    Text(
                        text = NetKitFormat.actionSummary(rule.action),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Live progress is only meaningful for the active scenario:
                    // a cursor on an inactive definition would be a stale number.
                    if (sequence != null && isActive) {
                        val progress = sequenceProgress[rule.id]
                            ?: SequenceProgress(0, sequence.steps.size)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "Sequence ${progress.display}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = progress.nextStepNumber?.let { next ->
                                        "Next: ${sequence.steps[next - 1].label}"
                                    } ?: "Complete · ${sequence.completion.label.lowercase()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            NetKitRowAction(
                                label = "Reset",
                                onClick = { onResetSequence(rule.id) },
                                contentDescription = "Reset the sequence for ${rule.displayTarget}",
                                modifier = Modifier.testTag(
                                    NetKitTestTags.RULE_SEQUENCE_RESET_PREFIX + rule.id,
                                ),
                            )
                        }
                    }
                }
            }

            if (isActive && scenario.hasSequence) {
                OutlinedButton(
                    onClick = onResetAllSequences,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(NetKitTestTags.SCENARIO_DETAIL_RESET_SEQUENCES),
                ) {
                    Text("Reset all sequence progress")
                }
            }

            NetKitDivider()

            Button(
                onClick = if (isActive) onDeactivate else onActivate,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(NetKitTestTags.SCENARIO_DETAIL_ACTIVATE),
            ) {
                Text(if (isActive) "Deactivate" else "Activate")
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!scenario.metadata.isReadOnly) {
                    OutlinedButton(
                        onClick = onEdit,
                        modifier = Modifier.testTag(NetKitTestTags.SCENARIO_DETAIL_EDIT),
                    ) {
                        Text("Edit")
                    }
                }
                OutlinedButton(
                    onClick = onDuplicate,
                    modifier = Modifier.testTag(NetKitTestTags.SCENARIO_DETAIL_DUPLICATE),
                ) {
                    Text("Duplicate")
                }
                OutlinedButton(
                    onClick = onExport,
                    modifier = Modifier.testTag(NetKitTestTags.SCENARIO_DETAIL_EXPORT),
                ) {
                    Text("Export")
                }
                if (!scenario.metadata.isReadOnly) {
                    TextButton(
                        onClick = { confirmingDelete = true },
                        modifier = Modifier.testTag(NetKitTestTags.SCENARIO_DETAIL_DELETE),
                    ) {
                        Text("Delete")
                    }
                }
            }

            if (scenario.metadata.isReadOnly) {
                Text(
                    text = "This scenario is declared in application code, so it cannot be " +
                        "edited or deleted here. Duplicate it to make an editable copy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmingDelete) {
        NetKitConfirmDialog(
            title = "Delete \"${scenario.name}\"?",
            message = if (isActive) {
                "This scenario is active. Deleting it deactivates it first and returns the " +
                    "network to normal. This cannot be undone."
            } else {
                "This cannot be undone. Export it first if you want to keep a copy."
            },
            confirmLabel = "Delete",
            onConfirm = {
                confirmingDelete = false
                onDelete()
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}
