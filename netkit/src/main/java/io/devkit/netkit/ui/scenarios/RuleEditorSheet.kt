package io.devkit.netkit.ui.scenarios

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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.devkit.netkit.config.NetKitDefaults
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.TimeoutType
import io.devkit.netkit.ui.NetKitTestTags
import io.devkit.netkit.ui.components.NetKitChoiceChip
import io.devkit.netkit.ui.components.NetKitGutter
import io.devkit.netkit.ui.components.NetKitMonoStyle
import io.devkit.netkit.ui.components.NetKitSectionLabel

/**
 * Create or edit one endpoint override.
 *
 * Behaviour-specific fields appear only for the selected behaviour, so the form
 * never shows a field that cannot affect the result. Every picker is a chip row
 * rather than a dropdown: the whole option set stays visible and each choice is
 * one tap.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RuleEditorSheet(
    initial: RuleEditorState,
    onSave: (EndpointRule) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var form by remember(initial.ruleId) { mutableStateOf(initial) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(NetKitTestTags.EDITOR),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = NetKitGutter)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (form.isEditing) "Edit endpoint override" else "New endpoint override",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            NetKitSectionLabel("Method")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HttpMethod.selectable.forEach { method ->
                    NetKitChoiceChip(
                        label = method.label,
                        selected = form.method == method,
                        onClick = { form = form.copy(method = method) },
                        modifier = Modifier.testTag(NetKitTestTags.EDITOR_METHOD_PREFIX + method.label),
                    )
                }
            }

            if (form.matcherOverride != null) {
                // A rule created in code with a custom matcher: NetKit 0.1 cannot
                // edit it without silently downgrading it to an exact path.
                NetKitSectionLabel("Match")
                Text(
                    text = form.matcherOverride?.label.orEmpty(),
                    style = NetKitMonoStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "This rule uses a custom matcher and keeps it when saved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedTextField(
                    value = form.pathText,
                    onValueChange = { form = form.copy(pathText = it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(NetKitTestTags.EDITOR_PATH),
                    label = { Text("Path") },
                    placeholder = { Text("/api/v1/bookings") },
                    singleLine = true,
                    textStyle = NetKitMonoStyle,
                    isError = form.pathError != null,
                    supportingText = {
                        Text(form.pathError ?: "Matched exactly, ignoring host and query string")
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                )
            }

            NetKitSectionLabel("Behavior")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuleBehavior.entries.forEach { behavior ->
                    NetKitChoiceChip(
                        label = behavior.label,
                        selected = form.behavior == behavior,
                        onClick = { form = form.copy(behavior = behavior) },
                        modifier = Modifier.testTag(
                            NetKitTestTags.EDITOR_BEHAVIOR_PREFIX + behavior.name,
                        ),
                    )
                }
            }

            when (form.behavior) {
                RuleBehavior.PASS_THROUGH -> Text(
                    text = "This endpoint keeps reaching the real server even while the " +
                        "global network is offline, delayed or timing out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                RuleBehavior.DELAY -> DelayField(form) { form = it }

                RuleBehavior.HTTP_ERROR -> {
                    NetKitSectionLabel("Status")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RuleEditorState.statusChips.forEach { status ->
                            NetKitChoiceChip(
                                label = status.toString(),
                                selected = form.statusText.trim() == status.toString(),
                                onClick = { form = form.copy(statusText = status.toString()) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = form.statusText,
                        onValueChange = { form = form.copy(statusText = it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(NetKitTestTags.EDITOR_STATUS),
                        label = { Text("HTTP status") },
                        singleLine = true,
                        isError = form.statusError != null,
                        supportingText = {
                            Text(
                                form.statusError
                                    ?: NetKitDefaults.statusLabel(
                                        form.statusText.trim().toIntOrNull() ?: 500,
                                    ),
                            )
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    NetKitSectionLabel("Content type")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NetKitDefaults.COMMON_CONTENT_TYPES.forEach { type ->
                            NetKitChoiceChip(
                                label = type,
                                selected = form.contentType == type,
                                onClick = { form = form.copy(contentType = type) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = form.bodyText,
                        onValueChange = { form = form.copy(bodyText = it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 110.dp)
                            .testTag(NetKitTestTags.EDITOR_BODY),
                        label = { Text("Response body (optional)") },
                        placeholder = { Text("{\"message\":\"Service unavailable\"}") },
                        textStyle = NetKitMonoStyle,
                        supportingText = { Text("Left empty, NetKit returns a default JSON envelope.") },
                    )
                    DelayField(form) { form = it }
                }

                RuleBehavior.OFFLINE -> Text(
                    text = "Requests to this endpoint fail with an UnknownHostException, " +
                        "exactly as they would with no connectivity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                RuleBehavior.TIMEOUT -> {
                    NetKitSectionLabel("Timeout")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TimeoutType.entries.forEach { type ->
                            NetKitChoiceChip(
                                label = type.label,
                                selected = form.timeoutType == type,
                                onClick = { form = form.copy(timeoutType = type) },
                            )
                        }
                    }
                    Text(
                        text = "NetKit waits for your OkHttp client's own timeout, then throws " +
                            "a SocketTimeoutException.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = form.name,
                onValueChange = { form = form.copy(name = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Name (optional)") },
                placeholder = { Text("Bookings failure") },
                singleLine = true,
                supportingText = { Text("Shown in history as the scenario responsible.") },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Rule enabled", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = form.enabled,
                    onCheckedChange = { form = form.copy(enabled = it) },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (form.isEditing) {
                    OutlinedButton(
                        onClick = { form.ruleId?.let(onDelete) },
                        modifier = Modifier.testTag(NetKitTestTags.EDITOR_DELETE),
                    ) {
                        Text("Delete")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = { form.toRule()?.let(onSave) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(NetKitTestTags.EDITOR_SAVE),
                    enabled = form.isValid,
                ) {
                    Text(if (form.isEditing) "Save changes" else "Add override")
                }
            }
        }
    }
}

@Composable
private fun DelayField(form: RuleEditorState, onChange: (RuleEditorState) -> Unit) {
    OutlinedTextField(
        value = form.delayText,
        onValueChange = { onChange(form.copy(delayText = it)) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(NetKitTestTags.EDITOR_DELAY),
        label = { Text("Delay") },
        suffix = { Text("ms") },
        singleLine = true,
        isError = form.delayError != null,
        supportingText = {
            Text(form.delayError ?: "Applied before the result is returned.")
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
    )
}
