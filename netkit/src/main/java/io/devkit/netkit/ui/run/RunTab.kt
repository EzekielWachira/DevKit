package io.devkit.netkit.ui.run

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.devkit.netkit.scenario.random.SeedGenerator
import io.devkit.netkit.scenario.run.ChaosStatistics
import io.devkit.netkit.scenario.run.ExecutionEvent
import io.devkit.netkit.scenario.run.RuleStatistics
import io.devkit.netkit.scenario.run.ScenarioRun
import io.devkit.netkit.ui.NetKitFormat
import io.devkit.netkit.ui.NetKitTestTags
import io.devkit.netkit.ui.components.NetKitBadge
import io.devkit.netkit.ui.components.NetKitCard
import io.devkit.netkit.ui.components.NetKitDivider
import io.devkit.netkit.ui.components.NetKitEmptyState
import io.devkit.netkit.ui.components.NetKitGutter
import io.devkit.netkit.ui.components.NetKitKeyValueRow
import io.devkit.netkit.ui.components.NetKitMonoStyle
import io.devkit.netkit.ui.components.NetKitRowAction
import io.devkit.netkit.ui.components.NetKitSectionLabel

/** What the run tab needs, gathered so the screen takes one parameter per idea. */
internal data class RunTabState(
    val run: ScenarioRun?,
    val timeline: List<ExecutionEvent>,
    val ruleStatistics: Map<String, RuleStatistics>,
    val chaosStatistics: ChaosStatistics,
    val timelineEnabled: Boolean,
    val isStochastic: Boolean,
)

/**
 * The active run: seed, counters, rule statistics and the decision timeline.
 *
 * This is the screen the reproduction workflow ends at. A QA engineer who has
 * just hit a bug opens it, presses **Copy reproduction**, and pastes seven lines
 * into a ticket that a developer can act on. Everything else here exists to
 * answer the question that comes next — "is this scenario even firing?" — which
 * the rule statistics answer precisely, and which nothing before 0.3 answered at
 * all.
 */
@Composable
internal fun RunTab(
    state: RunTabState,
    onRestartSameSeed: () -> Unit,
    onRestartNewSeed: () -> Unit,
    onApplySeed: (Long) -> Unit,
    onCopyReproduction: () -> Unit,
    onCopyTrace: () -> Unit,
    onExportReproduction: () -> Unit,
    onTimelineEnabledChange: (Boolean) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
) {
    val run = state.run
    if (run == null) {
        NetKitEmptyState(
            title = "No run in progress",
            detail = "Activate a scenario or turn on chaos. A run gives you a seed, live " +
                "statistics and a timeline of every decision NetKit made.",
            modifier = modifier.testTag(NetKitTestTags.RUN_SCREEN),
        )
        return
    }

    var seedInput by remember(run.id) { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag(NetKitTestTags.RUN_SCREEN),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            RunHeaderCard(
                run = run,
                isStochastic = state.isStochastic,
                modifier = Modifier.padding(horizontal = NetKitGutter),
            )
        }

        item(key = "seed-actions") {
            Column(
                modifier = Modifier.padding(horizontal = NetKitGutter),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onCopyReproduction,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(NetKitTestTags.RUN_COPY_REPRODUCTION),
                    ) {
                        Text("Copy reproduction")
                    }
                    OutlinedButton(
                        onClick = onRestartSameSeed,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(NetKitTestTags.RUN_RESTART_SAME),
                    ) {
                        Text("Restart · same seed")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onRestartNewSeed,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(NetKitTestTags.RUN_RESTART_NEW),
                    ) {
                        Text("New seed")
                    }
                    OutlinedButton(
                        onClick = onExportReproduction,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(NetKitTestTags.RUN_EXPORT_REPRODUCTION),
                    ) {
                        Text("Export run")
                    }
                }
                Text(
                    text = "Restarting resets the random stream, rule counters, sequences and " +
                        "the timeline. Saved scenarios are never touched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "seed-entry") {
            Column(
                modifier = Modifier.padding(horizontal = NetKitGutter),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NetKitSectionLabel("Reproduce someone else's run")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = seedInput,
                        onValueChange = { seedInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag(NetKitTestTags.RUN_SEED_FIELD),
                        label = { Text("Seed from a ticket") },
                        placeholder = { Text("843921773") },
                        singleLine = true,
                        textStyle = NetKitMonoStyle,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                    )
                    TextButton(
                        onClick = {
                            SeedGenerator.parse(seedInput)?.let {
                                onApplySeed(it)
                                seedInput = ""
                            }
                        },
                        enabled = SeedGenerator.parse(seedInput) != null,
                        modifier = Modifier.testTag(NetKitTestTags.RUN_SEED_APPLY),
                    ) {
                        Text("Run it")
                    }
                }
            }
        }

        item(key = "stats") {
            Column(
                modifier = Modifier
                    .padding(horizontal = NetKitGutter)
                    .testTag(NetKitTestTags.RUN_STATS),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NetKitDivider(Modifier.padding(vertical = 4.dp))
                NetKitSectionLabel("Requests")
                NetKitKeyValueRow("Evaluated", run.evaluationCount.toString())
                NetKitKeyValueRow(
                    label = "Simulated",
                    value = "${run.simulatedCount} (${run.simulatedPercent}%)",
                )
                NetKitKeyValueRow("Pass-through", run.passThroughCount.toString())
                NetKitKeyValueRow("Average added latency", run.averageLatencyLabel)
            }
        }

        if (!state.chaosStatistics.isUntouched) {
            item(key = "chaos-stats") {
                Column(
                    modifier = Modifier.padding(horizontal = NetKitGutter),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    NetKitSectionLabel("Chaos", trailing = state.chaosStatistics.summary)
                    val chaos = state.chaosStatistics
                    NetKitKeyValueRow("In scope", chaos.inScope.toString())
                    NetKitKeyValueRow("Excluded", chaos.excluded.toString())
                    NetKitKeyValueRow("Failed", chaos.failed.toString())
                    NetKitKeyValueRow("Delayed", chaos.latencyInjected.toString())
                    NetKitKeyValueRow("HTTP failures", chaos.httpFailures.toString())
                    NetKitKeyValueRow("Timeouts", chaos.timeouts.toString())
                    NetKitKeyValueRow("Disconnects", chaos.disconnects.toString())
                }
            }
        }

        val rules = state.ruleStatistics.values.filter { !it.isUntouched }
            .sortedWith(
                compareByDescending<RuleStatistics> { it.executed }
                    .thenByDescending { it.matched },
            )
        if (rules.isNotEmpty()) {
            item(key = "rules-header") {
                Column(
                    modifier = Modifier.padding(horizontal = NetKitGutter),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    NetKitDivider(Modifier.padding(vertical = 4.dp))
                    NetKitSectionLabel("Rules", trailing = "${rules.size} reached")
                    Text(
                        text = "The funnel narrows left to right. A rule that matched but never " +
                            "executed tells you which stage stopped it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(rules, key = { it.ruleId }) { statistics ->
                RuleStatisticsRow(
                    statistics = statistics,
                    modifier = Modifier.padding(horizontal = NetKitGutter),
                )
            }
        }

        item(key = "timeline-header") {
            Column(
                modifier = Modifier.padding(horizontal = NetKitGutter),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NetKitDivider(Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        NetKitSectionLabel(
                            label = "Timeline",
                            trailing = if (state.timeline.isEmpty()) {
                                null
                            } else {
                                "${state.timeline.size}"
                            },
                        )
                        Text(
                            text = "Scenario decisions, not network calls. History has the calls.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.timelineEnabled,
                        onCheckedChange = onTimelineEnabledChange,
                        modifier = Modifier
                            .semantics { contentDescription = "Record the timeline" }
                            .testTag(NetKitTestTags.RUN_TIMELINE_TOGGLE),
                    )
                }
                if (state.timeline.isNotEmpty()) {
                    NetKitRowAction(
                        label = "Copy trace",
                        onClick = onCopyTrace,
                        modifier = Modifier.testTag(NetKitTestTags.RUN_COPY_TRACE),
                    )
                }
            }
        }

        if (state.timeline.isEmpty()) {
            item(key = "timeline-empty") {
                NetKitEmptyState(
                    title = if (state.timelineEnabled) "Nothing yet" else "Timeline off",
                    detail = if (state.timelineEnabled) {
                        "Use the app. Every decision NetKit makes appears here."
                    } else {
                        "Turn it on to record what NetKit decides for each request."
                    },
                    modifier = Modifier.testTag(NetKitTestTags.RUN_TIMELINE),
                )
            }
        } else {
            // Newest first: the decision that just broke the app is the one being
            // looked for, and scrolling to the bottom of five hundred events to
            // find it would be absurd.
            items(
                items = state.timeline.asReversed(),
                key = { "${it.evaluationIndex}-${it.atMillis}-${it.type}-${it.detail}" },
            ) { event ->
                TimelineRow(event, Modifier.padding(horizontal = NetKitGutter))
            }
        }

        item(key = "stop") {
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NetKitGutter, vertical = 8.dp),
            ) {
                Text("Stop this run")
            }
        }
    }
}

@Composable
private fun RunHeaderCard(
    run: ScenarioRun,
    isStochastic: Boolean,
    modifier: Modifier = Modifier,
) {
    NetKitCard(modifier = modifier, emphasised = true) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = run.scenarioName ?: "Chaos",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            NetKitBadge(
                text = "RUNNING",
                container = MaterialTheme.colorScheme.tertiary,
                content = MaterialTheme.colorScheme.onTertiary,
            )
        }
        // The most important thing on the screen, and the reason the screen
        // exists — rendered monospaced and large enough to read off a photo of
        // someone else's phone.
        Text(
            text = "Seed ${run.seed}",
            modifier = Modifier.testTag(NetKitTestTags.RUN_SEED),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (!isStochastic) {
            Text(
                text = "This scenario has nothing random in it, so the seed changes nothing. " +
                    "It still identifies the run.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        Text(
            text = "Started ${NetKitFormat.clockTime(run.startedAtMillis)} · " +
                "${run.startReason.label.lowercase()} · run ${run.id.value}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

/** One rule's funnel, with the diagnosis when it never fired. */
@Composable
private fun RuleStatisticsRow(
    statistics: RuleStatistics,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(NetKitTestTags.RUN_RULE_STAT_PREFIX + statistics.ruleId)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = statistics.ruleLabel,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            NetKitBadge(text = "${statistics.executed} fired")
        }
        Text(
            text = "matched ${statistics.matched} · conditions ${statistics.conditionPassed} · " +
                "probability ${statistics.probabilityPassed} · executed ${statistics.executed}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        statistics.diagnosis?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** One timeline event. */
@Composable
private fun TimelineRow(event: ExecutionEvent, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(NetKitTestTags.RUN_TIMELINE_ROW_PREFIX + event.evaluationIndex)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = NetKitFormat.clockTime(event.atMillis),
            style = NetKitMonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (event.evaluationIndex > 0) {
                    // The evaluation index, not a colour, is what ties a timeline
                    // row to a history row and to a line in an exported trace.
                    NetKitBadge(text = "#${event.evaluationIndex}")
                }
                Text(
                    text = event.type.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (event.type.isSimulation) FontWeight.Bold else FontWeight.Normal,
                )
            }
            event.requestSummary?.let {
                Text(
                    text = it,
                    style = NetKitMonoStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            listOfNotNull(event.ruleLabel, event.detail, event.reason)
                .takeIf { it.isNotEmpty() }
                ?.let {
                    Text(
                        text = it.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
    }
}
