package io.devkit.netkit.ui.scenarios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.devkit.netkit.config.NetKitDefaults
import io.devkit.netkit.scenario.GlobalNetworkConfig
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.ui.NetKitFormat
import io.devkit.netkit.ui.NetKitTestTags
import io.devkit.netkit.ui.components.NetKitChoiceChip
import io.devkit.netkit.ui.components.NetKitSectionLabel

/**
 * Global connectivity mode and artificial latency.
 *
 * Mode and latency are independent on purpose: "online but slow" is the most
 * common QA scenario and should not require leaving offline mode first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlobalNetworkSection(
    global: GlobalNetworkConfig,
    enabled: Boolean,
    onModeChange: (GlobalNetworkMode) -> Unit,
    onLatencyChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isPresetLatency = global.latencyMillis in NetKitDefaults.LATENCY_PRESETS
    var customSelected by remember { mutableStateOf(!isPresetLatency) }
    var customText by remember { mutableStateOf(global.latencyMillis.toString()) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NetKitSectionLabel("Global network", trailing = global.summary)

        Text(
            text = "Mode",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlobalNetworkMode.selectable.forEach { mode ->
                NetKitChoiceChip(
                    label = mode.label,
                    selected = global.mode == mode,
                    enabled = enabled,
                    onClick = { onModeChange(mode) },
                    modifier = Modifier.testTag(NetKitTestTags.GLOBAL_MODE_PREFIX + mode.label),
                )
            }
        }

        Text(
            text = "Latency",
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NetKitDefaults.LATENCY_PRESETS.forEach { preset ->
                NetKitChoiceChip(
                    label = NetKitFormat.latency(preset),
                    selected = !customSelected && global.latencyMillis == preset,
                    enabled = enabled,
                    onClick = {
                        customSelected = false
                        onLatencyChange(preset)
                    },
                    modifier = Modifier.testTag(NetKitTestTags.LATENCY_PRESET_PREFIX + preset),
                )
            }
            NetKitChoiceChip(
                label = "Custom",
                selected = customSelected,
                enabled = enabled,
                onClick = { customSelected = true },
                modifier = Modifier.testTag(NetKitTestTags.LATENCY_PRESET_PREFIX + "custom"),
            )
        }

        if (customSelected) {
            val parsed = customText.trim().toLongOrNull()
            val error = when {
                customText.isBlank() -> null
                parsed == null -> "Enter a whole number of milliseconds"
                parsed < 0 -> "Latency cannot be negative"
                else -> null
            }
            OutlinedTextField(
                value = customText,
                onValueChange = { input ->
                    customText = input
                    input.trim().toLongOrNull()?.takeIf { it >= 0 }?.let(onLatencyChange)
                },
                modifier = Modifier
                    .widthIn(min = 180.dp)
                    .testTag(NetKitTestTags.LATENCY_CUSTOM_FIELD),
                enabled = enabled,
                label = { Text("Custom latency") },
                suffix = { Text("ms") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
            )
        } else {
            Text(
                text = "Current latency ${NetKitFormat.latency(global.latencyMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
