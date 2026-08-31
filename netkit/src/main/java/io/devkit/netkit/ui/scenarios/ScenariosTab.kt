package io.devkit.netkit.ui.scenarios

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.state.NetKitState
import io.devkit.netkit.ui.NetKitTestTags
import io.devkit.netkit.ui.components.NetKitDivider
import io.devkit.netkit.ui.components.NetKitEmptyState
import io.devkit.netkit.ui.components.NetKitGutter
import io.devkit.netkit.ui.components.NetKitSectionLabel

/**
 * The scenario console: global network state on top, endpoint overrides below,
 * destructive actions last.
 *
 * Ordered by how often each control is used, so the two controls a QA engineer
 * reaches for most — offline and latency — are reachable without scrolling.
 */
@Composable
internal fun ScenariosTab(
    state: NetKitState,
    onModeChange: (GlobalNetworkMode) -> Unit,
    onLatencyChange: (Long) -> Unit,
    onRuleToggle: (String, Boolean) -> Unit,
    onEditRule: (EndpointRule) -> Unit,
    onAddRule: () -> Unit,
    onReset: () -> Unit,
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
        item(key = "global") {
            GlobalNetworkSection(
                global = state.global,
                enabled = state.enabled,
                onModeChange = onModeChange,
                onLatencyChange = onLatencyChange,
                modifier = Modifier.padding(horizontal = NetKitGutter),
            )
        }

        item(key = "global-divider") {
            NetKitDivider(Modifier.padding(horizontal = NetKitGutter, vertical = 6.dp))
        }

        item(key = "rules-header") {
            NetKitSectionLabel(
                label = "Endpoint overrides",
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
                    title = "No endpoint overrides",
                    detail = "Add one to change a single endpoint while the rest of the app " +
                        "keeps using the real backend.",
                )
            }
        } else {
            items(state.rules, key = { it.id }) { rule ->
                EndpointRuleRow(
                    rule = rule,
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
                Text("Add endpoint override")
            }
        }

        item(key = "footer") {
            Column(
                modifier = Modifier.padding(horizontal = NetKitGutter),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NetKitDivider(Modifier.padding(vertical = 6.dp))
                Text(
                    text = "Reset restores normal networking and removes every override. " +
                        "History is kept until you clear it.",
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
                        Text("Reset network")
                    }
                    TextButton(
                        onClick = onClearHistory,
                        modifier = Modifier.testTag(NetKitTestTags.HISTORY_CLEAR),
                    ) {
                        Text("Clear history")
                    }
                }
            }
        }
    }
}
