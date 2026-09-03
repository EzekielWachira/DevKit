package io.devkit.netkit.ui.chaos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.devkit.netkit.scenario.LatencyRange
import io.devkit.netkit.scenario.Probability
import io.devkit.netkit.scenario.chaos.ChaosConfig
import io.devkit.netkit.scenario.chaos.ChaosExclusions
import io.devkit.netkit.scenario.chaos.ChaosPresets
import io.devkit.netkit.scenario.percentages
import io.devkit.netkit.scenario.run.ChaosStatistics
import io.devkit.netkit.scenario.run.ScenarioRun
import io.devkit.netkit.ui.NetKitTestTags
import io.devkit.netkit.ui.components.NetKitBadge
import io.devkit.netkit.ui.components.NetKitCard
import io.devkit.netkit.ui.components.NetKitChoiceChip
import io.devkit.netkit.ui.components.NetKitDivider
import io.devkit.netkit.ui.components.NetKitGutter
import io.devkit.netkit.ui.components.NetKitKeyValueRow
import io.devkit.netkit.ui.components.NetKitRowAction
import io.devkit.netkit.ui.components.NetKitSectionLabel

/**
 * The chaos editor.
 *
 * Ordered by the decision a person actually makes: turn it on, pick roughly how
 * bad, then narrow the blast radius. The exclusions section is deliberately not
 * buried at the bottom of an "advanced" drawer — turning on failures across a
 * whole app without sparing its auth endpoint produces a logout loop that looks
 * exactly like the bug you were hunting, and the control that prevents that
 * should be visible before the failure rate is turned up.
 *
 * When a scenario declares its own chaos, this screen shows that it is in force
 * and says so rather than silently editing a setting that has no effect.
 *
 * @param chaos the console's own chaos configuration.
 * @param scenarioChaos the active scenario's chaos, when it declares one.
 * @param scenarioName the scenario imposing [scenarioChaos].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChaosTab(
    chaos: ChaosConfig,
    scenarioChaos: ChaosConfig?,
    scenarioName: String?,
    run: ScenarioRun?,
    statistics: ChaosStatistics,
    onChange: (ChaosConfig) -> Unit,
    onRestartRun: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
) {
    val effective = scenarioChaos ?: chaos
    val editable = scenarioChaos == null

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag(NetKitTestTags.CHAOS_SCREEN),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "intro") {
            Column(
                modifier = Modifier.padding(horizontal = NetKitGutter),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Chaos mode",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (effective.enabled) {
                        NetKitBadge(
                            text = "ON",
                            container = MaterialTheme.colorScheme.tertiary,
                            content = MaterialTheme.colorScheme.onTertiary,
                        )
                    }
                    Switch(
                        checked = effective.enabled,
                        enabled = editable,
                        onCheckedChange = { onChange(chaos.copy(enabled = it)) },
                        modifier = Modifier
                            .semantics { contentDescription = "Chaos enabled" }
                            .testTag(NetKitTestTags.CHAOS_ENABLED),
                    )
                }
                Text(
                    // Stated plainly and up front, because the terminology matters:
                    // a QA engineer must not report "packet loss" on the strength
                    // of an interceptor returning a 503.
                    text = "Application-layer instability: delays, error statuses, simulated " +
                        "timeouts and simulated disconnects. NetKit works inside OkHttp — it " +
                        "does not drop packets or affect the radio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!editable) {
                    Text(
                        text = "The scenario \"$scenarioName\" sets its own chaos, which is what " +
                            "is running. Edit the scenario to change it, or deactivate it to use " +
                            "the settings below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (run != null && effective.enabled) {
            item(key = "run") {
                ChaosRunCard(
                    run = run,
                    statistics = statistics,
                    onRestart = onRestartRun,
                    modifier = Modifier.padding(horizontal = NetKitGutter),
                )
            }
        }

        item(key = "presets") {
            Column(
                modifier = Modifier.padding(horizontal = NetKitGutter),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NetKitSectionLabel("Presets")
                Text(
                    text = "A starting point. Everything stays editable afterwards.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChaosPresets.all.forEach { preset ->
                        NetKitChoiceChip(
                            label = preset.name,
                            selected = false,
                            enabled = editable,
                            onClick = { onChange(preset.config) },
                            modifier = Modifier.testTag(
                                NetKitTestTags.CHAOS_PRESET_PREFIX + preset.id,
                            ),
                        )
                    }
                }
            }
        }

        item(key = "failure-rate") {
            Column(
                modifier = Modifier.padding(horizontal = NetKitGutter),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NetKitDivider(Modifier.padding(vertical = 4.dp))
                NetKitSectionLabel(
                    label = "Failure rate",
                    trailing = effective.failureProbability.percentLabel,
                )
                Slider(
                    value = effective.failureProbability.percent.toFloat(),
                    enabled = editable,
                    onValueChange = {
                        onChange(chaos.copy(failureProbability = Probability.ofPercent(it.toInt())))
                    },
                    valueRange = 0f..100f,
                    steps = FAILURE_RATE_STEPS,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(NetKitTestTags.CHAOS_FAILURE_RATE)
                        .semantics {
                            contentDescription = "Failure rate, " +
                                "${effective.failureProbability.percent} percent"
                        },
                )
                Text(
                    text = if (effective.failureProbability.isNever) {
                        "No request fails. Latency below still applies."
                    } else {
                        "About ${effective.failureProbability.percent} in every 100 in-scope " +
                            "requests fails, chosen deterministically from the run's seed."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "latency") {
            Column(
                modifier = Modifier.padding(horizontal = NetKitGutter),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NetKitSectionLabel("Latency", trailing = effective.latency.label)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = effective.latency.minMillis.toString(),
                        enabled = editable,
                        onValueChange = { raw ->
                            val min = raw.trim().toLongOrNull()?.coerceAtLeast(0) ?: return@OutlinedTextField
                            onChange(
                                chaos.copy(
                                    latency = LatencyRange(
                                        min,
                                        maxOf(min, chaos.latency.maxMillis),
                                    ),
                                ),
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag(NetKitTestTags.CHAOS_LATENCY_MIN),
                        label = { Text("Shortest") },
                        suffix = { Text("ms") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    OutlinedTextField(
                        value = effective.latency.maxMillis.toString(),
                        enabled = editable,
                        onValueChange = { raw ->
                            val max = raw.trim().toLongOrNull()?.coerceAtLeast(0) ?: return@OutlinedTextField
                            onChange(
                                chaos.copy(
                                    latency = LatencyRange(
                                        minOf(chaos.latency.minMillis, max),
                                        max,
                                    ),
                                ),
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag(NetKitTestTags.CHAOS_LATENCY_MAX),
                        label = { Text("Longest") },
                        suffix = { Text("ms") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                    )
                }
                Text(
                    text = "Applied to in-scope requests that do not fail.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "outcomes") {
            Column(
                modifier = Modifier.padding(horizontal = NetKitGutter),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NetKitDivider(Modifier.padding(vertical = 4.dp))
                NetKitSectionLabel("Failure mix", trailing = "${effective.failures.size}")
                val percentages = effective.failures.percentages()
                effective.failures.forEachIndexed { index, outcome ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = outcome.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        NetKitBadge(text = "${percentages.getOrElse(index) { 0 }}%")
                        OutlinedTextField(
                            value = outcome.weight.toString(),
                            enabled = editable,
                            onValueChange = { raw ->
                                val weight = raw.trim().toIntOrNull()?.coerceAtLeast(1)
                                    ?: return@OutlinedTextField
                                onChange(
                                    chaos.copy(
                                        failures = chaos.failures.toMutableList().apply {
                                            set(index, this[index].copy(weight = weight))
                                        },
                                    ),
                                )
                            },
                            modifier = Modifier
                                .width(96.dp)
                                .testTag(NetKitTestTags.CHAOS_OUTCOME_WEIGHT_PREFIX + index),
                            label = { Text("Weight") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        NetKitRowAction(
                            label = "Remove",
                            enabled = editable && effective.failures.size > 1,
                            onClick = {
                                onChange(
                                    chaos.copy(
                                        failures = chaos.failures
                                            .filterIndexed { i, _ -> i != index },
                                    ),
                                )
                            },
                            modifier = Modifier.testTag(
                                NetKitTestTags.CHAOS_OUTCOME_DELETE_PREFIX + index,
                            ),
                            contentDescription = "Remove failure ${index + 1}",
                        )
                    }
                }
                NetKitRowAction(
                    label = "+ Reset to the default mix",
                    enabled = editable,
                    onClick = { onChange(chaos.copy(failures = ChaosConfig.defaultFailures)) },
                    modifier = Modifier.testTag(NetKitTestTags.CHAOS_OUTCOME_ADD),
                )
            }
        }

        item(key = "scope") {
            Column(
                modifier = Modifier.padding(horizontal = NetKitGutter),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NetKitDivider(Modifier.padding(vertical = 4.dp))
                NetKitSectionLabel("Scope", trailing = effective.scope.label)
                OutlinedTextField(
                    value = effective.scope.hosts.joinToString(", "),
                    enabled = editable,
                    onValueChange = { raw ->
                        onChange(
                            chaos.copy(scope = chaos.scope.copy(hosts = raw.splitList())),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(NetKitTestTags.CHAOS_HOSTS),
                    label = { Text("Hosts") },
                    placeholder = { Text("api.staging.example.com") },
                    singleLine = true,
                    supportingText = { Text("Comma-separated. Empty means every host.") },
                )
                OutlinedTextField(
                    value = effective.scope.pathPrefixes.joinToString(", "),
                    enabled = editable,
                    onValueChange = { raw ->
                        onChange(
                            chaos.copy(scope = chaos.scope.copy(pathPrefixes = raw.splitList())),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(NetKitTestTags.CHAOS_PATHS),
                    label = { Text("Path prefixes") },
                    placeholder = { Text("/api/v1") },
                    singleLine = true,
                    supportingText = { Text("Comma-separated. Empty means every path.") },
                )
            }
        }

        item(key = "exclusions") {
            Column(
                modifier = Modifier.padding(horizontal = NetKitGutter),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NetKitSectionLabel("Exclusions", trailing = effective.exclusions.label)
                Text(
                    text = "Chaos never touches these, whatever the scope says. Worth setting " +
                        "before you turn the failure rate up: breaking your own token refresh " +
                        "or crash reporter produces failures that look like the bug you are " +
                        "chasing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = effective.exclusions.prefixes.joinToString(", "),
                    enabled = editable,
                    onValueChange = { raw ->
                        onChange(chaos.copy(exclusions = ChaosExclusions(raw.splitList())))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(NetKitTestTags.CHAOS_EXCLUSIONS),
                    label = { Text("Excluded path prefixes") },
                    placeholder = { Text("/auth/refresh, /analytics") },
                    singleLine = true,
                )
                NetKitRowAction(
                    label = "+ Add the recommended exclusions",
                    enabled = editable,
                    onClick = {
                        onChange(
                            chaos.copy(
                                exclusions = ChaosExclusions(
                                    (chaos.exclusions.prefixes + ChaosExclusions.recommended)
                                        .distinct(),
                                ),
                            ),
                        )
                    },
                )
            }
        }
    }
}

/** The seed and counters for a chaos-driven run. */
@Composable
private fun ChaosRunCard(
    run: ScenarioRun,
    statistics: ChaosStatistics,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NetKitCard(modifier = modifier, emphasised = true) {
        Text(
            text = "Current run",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        NetKitKeyValueRow("Seed", run.seed.toString(), monospace = true)
        NetKitKeyValueRow("Evaluated", statistics.evaluated.toString())
        NetKitKeyValueRow("In scope", statistics.inScope.toString())
        NetKitKeyValueRow("Failed", statistics.failed.toString())
        NetKitKeyValueRow("Excluded", statistics.excluded.toString())
        NetKitRowAction(label = "Restart with a new seed", onClick = onRestart)
    }
}

/** `"/a, /b , "` → `["/a", "/b"]`. */
private fun String.splitList(): List<String> =
    split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** Slider stops giving 1% increments across the useful part of the range. */
private const val FAILURE_RATE_STEPS = 99
