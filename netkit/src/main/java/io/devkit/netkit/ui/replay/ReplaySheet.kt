package io.devkit.netkit.ui.replay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.unit.dp
import io.devkit.netkit.history.NetworkRecord
import io.devkit.netkit.replay.ReplayEligibility
import io.devkit.netkit.replay.ReplayOverride
import io.devkit.netkit.ui.NetKitTestTags
import io.devkit.netkit.ui.components.NetKitBadge
import io.devkit.netkit.ui.components.NetKitDivider
import io.devkit.netkit.ui.components.NetKitGutter
import io.devkit.netkit.ui.components.NetKitMonoStyle
import io.devkit.netkit.ui.components.NetKitSectionLabel

/**
 * Confirm, optionally modify, and send a recorded request again.
 *
 * ### Why the warning is not decoration
 *
 * A replayed `POST /payments` can charge a card. NetKit is a debug tool, which
 * makes it *more* likely to be pointed at a real staging or production backend,
 * not less. So a side-effectful method gets an explicit, unmissable warning and
 * a button labelled with the method itself — never a bare "Replay" that reads
 * the same for `GET` and `DELETE`.
 *
 * ### Why no credentials appear here
 *
 * The sheet renders the **masked** history record. The unmasked request lives in
 * a separate in-memory snapshot that the UI never reads, so nothing here can
 * show, prefill or copy a token. Setting a header means typing a new value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReplaySheet(
    record: NetworkRecord,
    eligibility: ReplayEligibility,
    onReplay: (ReplayOverride, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snapshot = (eligibility as? ReplayEligibility.Eligible)?.snapshot
    val sideEffectful = snapshot?.isSideEffectful ?: false

    var urlText by remember(record.id) { mutableStateOf("") }
    // Deliberately NOT prefilled from the snapshot. A request body is exactly
    // where `{"password":…}` and `{"refresh_token":…}` live, and NetKit does not
    // mask bodies — so rendering the original here would put a secret on screen
    // in a copyable field. Empty means "send the body that was captured".
    var bodyText by remember(record.id) { mutableStateOf("") }
    var bypass by remember(record.id) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(NetKitTestTags.REPLAY_SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
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
                    text = "Replay ${record.method} ${record.path}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                NetKitBadge(
                    text = record.method,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            if (snapshot == null) {
                val reason = (eligibility as? ReplayEligibility.Unavailable)?.reason
                Text(
                    text = reason?.message ?: "Replay is not available for this request.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(NetKitTestTags.REPLAY_CANCEL),
                ) {
                    Text("Close")
                }
                return@Column
            }

            if (sideEffectful) {
                Text(
                    text = "This request may create or modify real backend data.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "${record.method} is not a safe method. Unless a NetKit rule " +
                        "intercepts it, this goes to ${record.host}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Replay runs inside NetKit. The response is recorded in history " +
                        "and is not delivered back to the screen that made the original call.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            NetKitDivider()

            NetKitSectionLabel("URL")
            Text(
                text = record.url,
                style = NetKitMonoStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(NetKitTestTags.REPLAY_URL),
                label = { Text("Replace the URL (optional)") },
                placeholder = { Text("https://…") },
                singleLine = true,
                textStyle = NetKitMonoStyle,
                supportingText = { Text("Leave empty to use the original URL.") },
            )

            if (snapshot.hasReplaceableBody) {
                NetKitSectionLabel("Request body")
                OutlinedTextField(
                    value = bodyText,
                    onValueChange = { bodyText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 110.dp)
                        .testTag(NetKitTestTags.REPLAY_BODY),
                    label = { Text("Replace the body (optional)") },
                    placeholder = { Text("{\"cartId\":\"c-1\"}") },
                    textStyle = NetKitMonoStyle,
                    supportingText = {
                        Text("Leave empty to send the body that was captured.")
                    },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(NetKitTestTags.REPLAY_BYPASS),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Bypass NetKit rules", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Send straight to the server, ignoring the active scenario — " +
                            "useful for comparing simulated and real behaviour.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = bypass, onCheckedChange = { bypass = it })
            }

            Text(
                text = "The original headers and body are re-sent as captured, but never " +
                    "shown or prefilled here — NetKit keeps them separately from this " +
                    "masked record.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(NetKitTestTags.REPLAY_CANCEL),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        onReplay(
                            ReplayOverride(
                                url = urlText.trim().takeIf { it.isNotEmpty() },
                                body = bodyText.takeIf {
                                    it.isNotEmpty() && snapshot.hasReplaceableBody
                                },
                            ),
                            bypass,
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(NetKitTestTags.REPLAY_CONFIRM),
                ) {
                    Text(if (sideEffectful) "Replay ${record.method}" else "Replay")
                }
            }
        }
    }
}
