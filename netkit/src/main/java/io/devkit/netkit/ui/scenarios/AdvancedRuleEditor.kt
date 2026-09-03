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
import io.devkit.netkit.scenario.condition.PreviousResult
import io.devkit.netkit.scenario.condition.StringMatch
import io.devkit.netkit.ui.NetKitTestTags
import io.devkit.netkit.ui.components.NetKitBadge
import io.devkit.netkit.ui.components.NetKitChoiceChip
import io.devkit.netkit.ui.components.NetKitDivider
import io.devkit.netkit.ui.components.NetKitRowAction
import io.devkit.netkit.ui.components.NetKitSectionLabel

/**
 * The min/max fields for a random latency range.
 *
 * Two plain number fields rather than a range slider: a QA engineer reproducing a
 * bug types "800" and "3000" from a ticket, and dragging two handles to land on
 * exact values is slower and less precise than typing them.
 */
@Composable
internal fun LatencyRangeFields(
    form: RuleEditorState,
    onChange: (RuleEditorState) -> Unit,
) {
    NetKitSectionLabel("Latency range")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = form.minLatencyText,
            onValueChange = { onChange(form.copy(minLatencyText = it)) },
            modifier = Modifier
                .weight(1f)
                .testTag(NetKitTestTags.EDITOR_LATENCY_MIN),
            label = { Text("Shortest") },
            suffix = { Text("ms") },
            singleLine = true,
            isError = form.latencyRangeError != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
            ),
        )
        OutlinedTextField(
            value = form.maxLatencyText,
            onValueChange = { onChange(form.copy(maxLatencyText = it)) },
            modifier = Modifier
                .weight(1f)
                .testTag(NetKitTestTags.EDITOR_LATENCY_MAX),
            label = { Text("Longest") },
            suffix = { Text("ms") },
            singleLine = true,
            isError = form.latencyRangeError != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
        )
    }
    Text(
        text = form.latencyRangeError
            ?: "Each request waits a different amount inside this range. The same seed " +
                "always picks the same values.",
        style = MaterialTheme.typography.bodySmall,
        color = if (form.latencyRangeError != null) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/**
 * The weighted-outcome editor.
 *
 * Shows the computed percentage beside each weight, live. Weights are relative
 * and people reason in percentages, so a UI that made you do the arithmetic would
 * be asking you to keep a running total in your head while editing the list that
 * changes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WeightedOutcomesEditor(
    form: RuleEditorState,
    onChange: (RuleEditorState) -> Unit,
) {
    val percentages = form.outcomePercentages
    NetKitSectionLabel(
        label = "Random outcomes",
        trailing = if (form.outcomes.isEmpty()) "none" else "${form.outcomes.size}",
    )
    Text(
        text = "One outcome is chosen per request, by relative weight. Weights need not add " +
            "up to 100.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    form.outcomes.forEachIndexed { index, outcome ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(NetKitTestTags.OUTCOME_ROW_PREFIX + index)
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = outcome.step.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                NetKitBadge(text = "${percentages.getOrElse(index) { 0 }}%")
                NetKitRowAction(
                    label = "Remove",
                    onClick = { onChange(form.withOutcomeRemoved(index)) },
                    modifier = Modifier.testTag(NetKitTestTags.OUTCOME_DELETE_PREFIX + index),
                    contentDescription = "Remove outcome ${index + 1}",
                )
            }
            OutlinedTextField(
                value = outcome.weightText,
                onValueChange = {
                    onChange(form.withOutcome(index, outcome.copy(weightText = it)))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(NetKitTestTags.OUTCOME_WEIGHT_PREFIX + index),
                label = { Text("Weight") },
                singleLine = true,
                isError = outcome.weightError != null,
                supportingText = outcome.weightError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SequenceStepForm.allowed.forEach { behavior ->
                    NetKitChoiceChip(
                        label = behavior.label,
                        selected = outcome.step.behavior == behavior,
                        onClick = {
                            onChange(
                                form.withOutcome(
                                    index,
                                    outcome.copy(step = outcome.step.copy(behavior = behavior)),
                                ),
                            )
                        },
                    )
                }
            }
            if (outcome.step.behavior == RuleBehavior.RESPONSE) {
                OutlinedTextField(
                    value = outcome.step.statusText,
                    onValueChange = {
                        onChange(
                            form.withOutcome(
                                index,
                                outcome.copy(step = outcome.step.copy(statusText = it)),
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTP status") },
                    singleLine = true,
                    isError = outcome.step.statusError != null,
                    supportingText = outcome.step.statusError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                )
            }
            NetKitDivider()
        }
    }

    NetKitRowAction(
        label = "+ Add outcome",
        onClick = { onChange(form.withNewOutcome()) },
        modifier = Modifier.testTag(NetKitTestTags.OUTCOME_ADD),
    )
    form.outcomesError?.let {
        Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * Conditions and probability, collapsed by default.
 *
 * Progressive disclosure is doing real work here. The 0.1 workflow — path,
 * status, save — is still three interactions, and everything 0.3 added lives
 * behind one switch that stays off unless the rule already uses it. A rule editor
 * that opened with five empty condition rows would have made the common case
 * worse to buy the rare case a little convenience.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdvancedSection(
    form: RuleEditorState,
    onChange: (RuleEditorState) -> Unit,
) {
    NetKitDivider(Modifier.padding(vertical = 4.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(NetKitTestTags.EDITOR_ADVANCED_TOGGLE),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Conditions and probability", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (form.hasAdvanced) {
                    "This rule only fires sometimes."
                } else {
                    "This rule fires whenever the method and path match."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = form.showAdvanced,
            onCheckedChange = { onChange(form.copy(showAdvanced = it)) },
            modifier = Modifier.semantics {
                contentDescription = "Show conditions and probability"
            },
        )
    }

    if (!form.showAdvanced) return

    // ---- probability -------------------------------------------------------

    NetKitSectionLabel(
        label = "Probability",
        trailing = "${form.probabilityPercent}%",
    )
    Slider(
        value = form.probabilityPercent.toFloat(),
        onValueChange = { onChange(form.copy(probabilityPercent = it.toInt())) },
        valueRange = 0f..100f,
        steps = PROBABILITY_STEPS,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(NetKitTestTags.EDITOR_PROBABILITY)
            .semantics {
                contentDescription = "Probability, ${form.probabilityPercent} percent"
            },
    )
    Text(
        text = when (form.probabilityPercent) {
            100 -> "Always fires."
            0 -> "Never fires — the rule is effectively off."
            else -> "${form.probabilityPercent}% of matching requests. The rest fall through " +
                "to the next rule."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // ---- conditions --------------------------------------------------------

    NetKitSectionLabel(
        label = "Conditions",
        trailing = if (form.conditions.isEmpty()) "none" else "all must match",
    )

    form.conditions.forEachIndexed { index, condition ->
        ConditionRow(
            condition = condition,
            index = index,
            onChange = { onChange(form.withCondition(index, it)) },
            onRemove = { onChange(form.withConditionRemoved(index)) },
        )
    }

    if (form.unsupportedConditions.isNotEmpty()) {
        Text(
            text = "${form.unsupportedConditions.size} condition" +
                "${if (form.unsupportedConditions.size == 1) "" else "s"} on this rule cannot be " +
                "shown here and will be kept unchanged: " +
                form.unsupportedConditions.joinToString(", ") { it.label },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ConditionKind.entries.forEach { kind ->
            NetKitRowAction(
                label = "+ ${kind.label}",
                onClick = { onChange(form.withNewCondition(kind)) },
                modifier = Modifier.testTag(NetKitTestTags.CONDITION_ADD_PREFIX + kind.name),
            )
        }
    }

    form.conditionsError?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    form.warnings.forEach {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** One editable condition. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionRow(
    condition: ConditionForm,
    index: Int,
    onChange: (ConditionForm) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(NetKitTestTags.CONDITION_ROW_PREFIX + index)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = condition.kind.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            NetKitRowAction(
                label = "Remove",
                onClick = onRemove,
                modifier = Modifier.testTag(NetKitTestTags.CONDITION_DELETE_PREFIX + index),
                contentDescription = "Remove condition ${index + 1}",
            )
        }

        when (condition.kind) {
            ConditionKind.REQUEST_COUNT -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CountMode.entries.forEach { mode ->
                        NetKitChoiceChip(
                            label = mode.label,
                            selected = condition.countMode == mode,
                            onClick = { onChange(condition.copy(countMode = mode)) },
                        )
                    }
                }
                when (condition.countMode) {
                    CountMode.EVERY -> NumberField(
                        value = condition.intervalText,
                        label = "Every Nth request",
                        onValueChange = { onChange(condition.copy(intervalText = it)) },
                    )

                    CountMode.RANGE -> Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        NumberField(
                            value = condition.fromText,
                            label = "From",
                            modifier = Modifier.weight(1f),
                            onValueChange = { onChange(condition.copy(fromText = it)) },
                        )
                        NumberField(
                            value = condition.toText,
                            label = "To",
                            modifier = Modifier.weight(1f),
                            onValueChange = { onChange(condition.copy(toText = it)) },
                        )
                    }

                    else -> NumberField(
                        value = condition.fromText,
                        label = "Request number",
                        onValueChange = { onChange(condition.copy(fromText = it)) },
                    )
                }
            }

            ConditionKind.QUERY, ConditionKind.HEADER -> {
                OutlinedTextField(
                    value = condition.name,
                    onValueChange = { onChange(condition.copy(name = it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(NetKitTestTags.CONDITION_NAME_PREFIX + index),
                    label = {
                        Text(if (condition.kind == ConditionKind.HEADER) "Header" else "Parameter")
                    },
                    placeholder = {
                        Text(if (condition.kind == ConditionKind.HEADER) "Authorization" else "page")
                    },
                    singleLine = true,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StringMatch.entries.forEach { match ->
                        NetKitChoiceChip(
                            label = match.label,
                            selected = condition.match == match,
                            onClick = { onChange(condition.copy(match = match)) },
                        )
                    }
                }
                if (condition.match.needsValue) {
                    OutlinedTextField(
                        value = condition.value,
                        onValueChange = { onChange(condition.copy(value = it)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(NetKitTestTags.CONDITION_VALUE_PREFIX + index),
                        label = { Text("Value") },
                        singleLine = true,
                    )
                }
            }

            ConditionKind.BODY -> {
                OutlinedTextField(
                    value = condition.value,
                    onValueChange = { onChange(condition.copy(value = it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(NetKitTestTags.CONDITION_VALUE_PREFIX + index),
                    label = { Text("Body contains") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = condition.jsonField,
                    onValueChange = { onChange(condition.copy(jsonField = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("JSON field (optional)") },
                    placeholder = { Text("couponCode") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            "Left empty, the whole body is searched. Bodies that cannot be read " +
                                "safely — streaming, one-shot or over 64 KB — never match.",
                        )
                    },
                )
            }

            ConditionKind.PREVIOUS_RESULT -> FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PreviousResult.entries.forEach { result ->
                    NetKitChoiceChip(
                        label = result.label,
                        selected = condition.previousResult == result,
                        onClick = { onChange(condition.copy(previousResult = result)) },
                    )
                }
            }
        }

        condition.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next,
        ),
    )
}

/**
 * Slider stops, giving 5% increments.
 *
 * `steps` counts the stops *between* the ends, so 19 yields 0, 5, 10 … 100.
 * Coarse enough to hit a round number by dragging, and any exact value is still
 * reachable because probabilities are stored as integers anyway.
 */
private const val PROBABILITY_STEPS = 19
