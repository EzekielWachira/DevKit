package io.devkit.netkit.ui.preset

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.preset.PresetConfiguration
import io.devkit.netkit.scenario.preset.PresetFieldKind
import io.devkit.netkit.scenario.preset.ScenarioPreset
import io.devkit.netkit.scenario.preset.ScenarioPresetRegistry
import io.devkit.netkit.ui.NetKitTestTags
import io.devkit.netkit.ui.components.NetKitCard
import io.devkit.netkit.ui.components.NetKitChoiceChip
import io.devkit.netkit.ui.components.NetKitGutter
import io.devkit.netkit.ui.components.NetKitMonoStyle
import io.devkit.netkit.ui.components.NetKitSectionLabel

/**
 * The two-step "new scenario from a template" flow.
 *
 * Step one picks a preset, step two fills it in. Deliberately not a single long
 * form: the presets differ enough that a combined screen would be mostly
 * irrelevant fields, and the picker doubles as a menu of the failure modes worth
 * testing — which is itself the useful half for someone who has not thought about
 * refresh storms before.
 *
 * The result is an ordinary editable scenario. The sheet says so, because a
 * template that produced something opaque would be worse than no template.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PresetSheet(
    registry: ScenarioPresetRegistry,
    onCreate: (NetworkScenario) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<ScenarioPreset?>(null) }
    var values by remember { mutableStateOf(emptyMap<String, String>()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(NetKitTestTags.PRESET_PICKER),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NetKitGutter)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val preset = selected
            if (preset == null) {
                PresetPicker(registry) { chosen ->
                    selected = chosen
                    values = chosen.defaults().values
                }
            } else {
                PresetForm(
                    preset = preset,
                    values = values,
                    onValuesChange = { values = it },
                    onBack = { selected = null },
                    onCreate = { onCreate(preset.build(PresetConfiguration(values))) },
                )
            }
        }
    }
}

@Composable
private fun PresetPicker(
    registry: ScenarioPresetRegistry,
    onSelect: (ScenarioPreset) -> Unit,
) {
    Text("New scenario from a template", style = MaterialTheme.typography.titleMedium)
    Text(
        text = "Each one builds ordinary endpoint rules you can read and change afterwards. " +
            "Nothing here is magic.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    registry.byCategory().forEach { (category, presets) ->
        NetKitSectionLabel(category.label)
        presets.forEach { preset ->
            NetKitCard(
                modifier = Modifier.testTag(NetKitTestTags.PRESET_ROW_PREFIX + preset.id),
                onClick = { onSelect(preset) },
            ) {
                Text(preset.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetForm(
    preset: ScenarioPreset,
    values: Map<String, String>,
    onValuesChange: (Map<String, String>) -> Unit,
    onBack: () -> Unit,
    onCreate: () -> Unit,
) {
    val configuration = PresetConfiguration(values)
    val problems = preset.validate(configuration)
    // A message beginning "Note:" is advice, not an error — it explains something
    // surprising about the configuration without blocking a perfectly valid one.
    val blocking = problems.filterNot { it.startsWith("Note:") }

    Text(preset.name, style = MaterialTheme.typography.titleMedium)
    Text(
        text = preset.description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    preset.fields.forEach { field ->
        val value = values[field.key] ?: field.default
        when (field.kind) {
            PresetFieldKind.CHOICE -> Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NetKitSectionLabel(field.label)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    field.options.forEach { option ->
                        NetKitChoiceChip(
                            label = option,
                            selected = value == option,
                            onClick = { onValuesChange(values + (field.key to option)) },
                            modifier = Modifier.testTag(
                                NetKitTestTags.PRESET_FIELD_PREFIX + field.key + ":" + option,
                            ),
                        )
                    }
                }
                field.hint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> OutlinedTextField(
                value = value,
                onValueChange = { onValuesChange(values + (field.key to it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (field.kind == PresetFieldKind.BODY) {
                            Modifier.heightIn(min = 110.dp)
                        } else {
                            Modifier
                        },
                    )
                    .testTag(NetKitTestTags.PRESET_FIELD_PREFIX + field.key),
                label = { Text(field.label) },
                singleLine = field.kind != PresetFieldKind.BODY,
                textStyle = if (field.kind == PresetFieldKind.BODY) {
                    NetKitMonoStyle
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                supportingText = field.hint?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (field.kind == PresetFieldKind.NUMBER) {
                        KeyboardType.Number
                    } else {
                        KeyboardType.Text
                    },
                    imeAction = ImeAction.Next,
                ),
            )
        }
    }

    problems.forEach { problem ->
        Text(
            text = problem,
            style = MaterialTheme.typography.bodySmall,
            color = if (problem.startsWith("Note:")) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.testTag(NetKitTestTags.PRESET_CANCEL),
        ) {
            Text("Back")
        }
        Button(
            onClick = onCreate,
            enabled = blocking.isEmpty(),
            modifier = Modifier
                .weight(1f)
                .testTag(NetKitTestTags.PRESET_CREATE),
        ) {
            Text("Create scenario")
        }
    }
    Text(
        text = "Creates a normal scenario. Open it afterwards to see and edit the rules it " +
            "generated.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
