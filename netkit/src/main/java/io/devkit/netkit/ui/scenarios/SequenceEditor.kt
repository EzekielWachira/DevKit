package io.devkit.netkit.ui.scenarios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.devkit.netkit.config.NetKitLimits
import io.devkit.netkit.scenario.MalformedResponseType
import io.devkit.netkit.scenario.SequenceCompletionBehavior
import io.devkit.netkit.scenario.TimeoutType
import io.devkit.netkit.ui.NetKitTestTags
import io.devkit.netkit.ui.components.NetKitBadge
import io.devkit.netkit.ui.components.NetKitCard
import io.devkit.netkit.ui.components.NetKitChoiceChip
import io.devkit.netkit.ui.components.NetKitMonoStyle
import io.devkit.netkit.ui.components.NetKitRowAction
import io.devkit.netkit.ui.components.NetKitSectionLabel

/**
 * Builds an ordered list of behaviours for one endpoint.
 *
 * The whole point of a sequence is the *order*, so every step shows its number
 * and its behaviour on one line, and reordering is two taps rather than a drag —
 * a drag handle inside a scrolling bottom sheet is a fight nobody needs while
 * chasing a retry bug.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SequenceEditor(
    form: RuleEditorState,
    onChange: (RuleEditorState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(NetKitTestTags.SEQUENCE_EDITOR),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NetKitSectionLabel(
            label = "Response sequence",
            trailing = "${form.steps.size} of ${NetKitLimits.MAX_SEQUENCE_STEPS}",
        )
        Text(
            text = "Each request to this endpoint runs the next step. Use it to prove a " +
                "retry works: 500 → 500 → 200.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        form.sequenceError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        form.steps.forEachIndexed { index, step ->
            SequenceStepCard(
                index = index,
                step = step,
                isFirst = index == 0,
                isLast = index == form.steps.lastIndex,
                onChange = { onChange(form.withStep(index, it)) },
                onMoveUp = { onChange(form.withStepMovedUp(index)) },
                onMoveDown = { onChange(form.withStepMovedDown(index)) },
                onDelete = { onChange(form.withStepRemoved(index)) },
            )
        }

        OutlinedButton(
            onClick = { onChange(form.withNewStep()) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(NetKitTestTags.SEQUENCE_ADD_STEP),
            enabled = form.steps.size < NetKitLimits.MAX_SEQUENCE_STEPS,
        ) {
            Text("Add step")
        }

        NetKitSectionLabel("After the last step")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SequenceCompletionBehavior.entries.forEach { behavior ->
                NetKitChoiceChip(
                    label = behavior.label,
                    selected = form.completion == behavior,
                    onClick = { onChange(form.copy(completion = behavior)) },
                    modifier = Modifier.testTag(
                        NetKitTestTags.SEQUENCE_COMPLETION_PREFIX + behavior.name,
                    ),
                )
            }
        }
        Text(
            text = when (form.completion) {
                SequenceCompletionBehavior.REPEAT_LAST ->
                    "Every later request repeats the final step."

                SequenceCompletionBehavior.PASS_THROUGH ->
                    "Every later request goes to the real server."

                SequenceCompletionBehavior.LOOP ->
                    "The sequence starts again from step 1."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SequenceStepCard(
    index: Int,
    step: SequenceStepForm,
    isFirst: Boolean,
    isLast: Boolean,
    onChange: (SequenceStepForm) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    NetKitCard(modifier = Modifier.testTag(NetKitTestTags.SEQUENCE_STEP_PREFIX + index)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NetKitBadge(
                text = "${index + 1}",
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = step.label,
                modifier = Modifier.weight(1f),
                style = NetKitMonoStyle,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            NetKitRowAction(
                label = "↑",
                onClick = onMoveUp,
                enabled = !isFirst,
                contentDescription = "Move step ${index + 1} earlier",
                modifier = Modifier.testTag(NetKitTestTags.SEQUENCE_STEP_UP_PREFIX + index),
            )
            NetKitRowAction(
                label = "↓",
                onClick = onMoveDown,
                enabled = !isLast,
                contentDescription = "Move step ${index + 1} later",
                modifier = Modifier.testTag(NetKitTestTags.SEQUENCE_STEP_DOWN_PREFIX + index),
            )
            NetKitRowAction(
                label = "Remove",
                onClick = onDelete,
                contentDescription = "Remove step ${index + 1}",
                modifier = Modifier.testTag(NetKitTestTags.SEQUENCE_STEP_DELETE_PREFIX + index),
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SequenceStepForm.allowed.forEach { behavior ->
                NetKitChoiceChip(
                    label = behavior.label,
                    selected = step.behavior == behavior,
                    onClick = { onChange(step.copy(behavior = behavior)) },
                )
            }
        }

        when (step.behavior) {
            RuleBehavior.RESPONSE -> {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RuleEditorState.statusChips.forEach { status ->
                        NetKitChoiceChip(
                            label = status.toString(),
                            selected = step.statusText.trim() == status.toString(),
                            onClick = { onChange(step.copy(statusText = status.toString())) },
                        )
                    }
                }
                OutlinedTextField(
                    value = step.statusText,
                    onValueChange = { onChange(step.copy(statusText = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTP status") },
                    singleLine = true,
                    isError = step.statusError != null,
                    supportingText = step.statusError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                )
                OutlinedTextField(
                    value = step.bodyText,
                    onValueChange = { onChange(step.copy(bodyText = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Body (optional)") },
                    textStyle = NetKitMonoStyle,
                )
            }

            RuleBehavior.MALFORMED -> MalformedPicker(
                selected = step.malformedType,
                onSelect = { onChange(step.copy(malformedType = it)) },
                modifier = Modifier.padding(top = 2.dp),
            )

            RuleBehavior.TIMEOUT -> FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TimeoutType.entries.forEach { type ->
                    NetKitChoiceChip(
                        label = type.label,
                        selected = step.timeoutType == type,
                        onClick = { onChange(step.copy(timeoutType = type)) },
                    )
                }
            }

            RuleBehavior.DELAY -> OutlinedTextField(
                value = step.delayText,
                onValueChange = { onChange(step.copy(delayText = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Delay") },
                suffix = { Text("ms") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
            )

            RuleBehavior.PASS_THROUGH, RuleBehavior.OFFLINE, RuleBehavior.SEQUENCE -> Unit
        }
    }
}

/** The malformed-payload picker, shared by the rule form and a sequence step. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MalformedPicker(
    selected: MalformedResponseType,
    onSelect: (MalformedResponseType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MalformedResponseType.builtIn.forEach { type ->
                NetKitChoiceChip(
                    label = type.label,
                    selected = selected.label == type.label,
                    onClick = { onSelect(type) },
                    modifier = Modifier.testTag(
                        NetKitTestTags.EDITOR_MALFORMED_PREFIX + type.label,
                    ),
                )
            }
        }
        Text(
            text = "NetKit returns exactly these bytes, so the failure is reproducible:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = selected.body.ifEmpty { "(empty body)" },
            style = NetKitMonoStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 4,
        )
        Text(
            text = "Content-Type: ${selected.contentType}",
            style = NetKitMonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
