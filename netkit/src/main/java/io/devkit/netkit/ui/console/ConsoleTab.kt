package io.devkit.netkit.ui.console

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.runtime.SequenceProgress
import io.devkit.netkit.state.NetKitState
import io.devkit.netkit.ui.NetKitTestTags
import io.devkit.netkit.ui.components.NetKitBadge
import io.devkit.netkit.ui.components.NetKitCard
import io.devkit.netkit.ui.components.NetKitDivider
import io.devkit.netkit.ui.components.NetKitEmptyState
import io.devkit.netkit.ui.components.NetKitGutter
import io.devkit.netkit.ui.components.NetKitSectionLabel
import io.devkit.netkit.ui.scenarios.EndpointRuleRow
import io.devkit.netkit.ui.scenarios.GlobalNetworkSection

/**
 * The network right now: which scenario is active, what the global setting is,
 * and any one-off overrides.
 *
 * Ordered by how often each control is used, and led by the active-scenario
 * indicator so a QA engineer never has to go hunting to discover that a scenario
 * is still on.
 *
 * This is 0.1's scenarios tab, kept intact for the temporary layer and given an
 * active-scenario header on top — the two layers are shown together because
 * their interaction is exactly what a person needs to reason about.
 */
@Composable
internal fun ConsoleTab(
    state: NetKitState,
    activeScenario: NetworkScenario?,
    sequenceProgress: Map<String, SequenceProgress>,
    onModeChange: (GlobalNetworkMode) -> Unit,
    onLatencyChange: (Long) -> Unit,
    onRuleToggle: (String, Boolean) -> Unit,
    onEditRule: (EndpointRule) -> Unit,
    onAddRule: () -> Unit,
    onOpenActiveScenario: () -> Unit,
    onDeactivateScenario: () -> Unit,
    onReset: () -> Unit,
    onResetEverything: () -> Unit,
    onResetSequences: () -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag(NetKitTestTags.RULE_LIST),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "active-scenario") {
            Column(
                modifier = Modifier.padding(horizontal = NetKitGutter),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NetKitSectionLabel("Active scenario")
                if (activeScenario == null) {
                    Text(
                        text = "None — only the settings below apply.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ActiveScenarioSummary(
                        scenario = activeScenario,
                        sequenceProgress = sequenceProgress,
                        onOpen = onOpenActiveScenario,
                        onDeactivate = onDeactivateScenario,
                    )
                }
            }
        }

        item(key = "global") {
            Column(
                modifier = Modifier.padding(horizontal = NetKitGutter),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GlobalNetworkSection(
                    global = state.global,
                    enabled = state.enabled,
                    onModeChange = onModeChange,
                    onLatencyChange = onLatencyChange,
                )
                // Precedence is invisible unless it is stated: a scenario that
                // sets its own global silently outranks these chips.
                if (state.effectiveGlobal.scenarioName != null) {
                    Text(
                        text = "In force: ${state.effectiveGlobal.explanation}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item(key = "global-divider") {
            NetKitDivider(Modifier.padding(horizontal = NetKitGutter, vertical = 6.dp))
        }

        item(key = "rules-header") {
            NetKitSectionLabel(
                label = "Temporary overrides",
                trailing = if (state.rules.isEmpty()) {
                    "none"
                } else {
                    "${state.activeRuleCount} of ${state.rules.size} active"
                },
                modifier = Modifier.padding(horizontal = NetKitGutter),
            )
        }

        if (state.rules.isEmpty()) {
            item(key = "rules-empty") {
                NetKitEmptyState(
                    title = "No temporary overrides",
                    detail = "Add one to change a single endpoint while the rest of the app " +
                        "keeps using the real backend. Overrides are not saved — put them in a " +
                        "scenario to keep them.",
                )
            }
        } else {
            items(state.rules, key = { it.id }) { rule ->
                EndpointRuleRow(
                    rule = rule,
                    progress = sequenceProgress[rule.id],
                    onToggle = { enabled -> onRuleToggle(rule.id, enabled) },
                    onEdit = { onEditRule(rule) },
                    modifier = Modifier.padding(horizontal = NetKitGutter),
                )
            }
        }

        item(key = "add-rule") {
            Button(
                onClick = onAddRule,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NetKitGutter, vertical = 4.dp)
                    .testTag(NetKitTestTags.ADD_RULE),
            ) {
                Text("Add temporary override")
            }
        }

        item(key = "footer") {
            Column(
                modifier = Modifier.padding(horizontal = NetKitGutter),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NetKitDivider(Modifier.padding(vertical = 6.dp))
                NetKitSectionLabel("Reset")
                Text(
                    text = "Saved scenarios are never deleted by any of these.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onReset,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(NetKitTestTags.RESET),
                    ) {
                        Text("Reset overrides")
                    }
                    OutlinedButton(
                        onClick = onResetEverything,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(NetKitTestTags.RESET_EVERYTHING),
                    ) {
                        Text("Reset everything")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        onClick = onResetSequences,
                        modifier = Modifier.testTag(NetKitTestTags.RESET_SEQUENCES),
                    ) {
                        Text("Reset sequences")
                    }
                    TextButton(
                        onClick = onClearHistory,
                        modifier = Modifier.testTag(NetKitTestTags.HISTORY_CLEAR),
                    ) {
                        Text("Clear history")
                    }
                }
                Text(
                    text = "Reset overrides restores normal global networking and removes " +
                        "temporary rules. Reset everything also deactivates the scenario and " +
                        "drops replay data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActiveScenarioSummary(
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
        Text(text = scenario.summary, style = MaterialTheme.typography.bodySmall)

        scenario.rules.mapNotNull { rule -> sequenceProgress[rule.id]?.let { rule to it } }
            .forEach { (rule, progress) ->
                Text(
                    text = "${rule.displayTarget} · sequence ${progress.display}",
                    style = MaterialTheme.typography.bodySmall,
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
