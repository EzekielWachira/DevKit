package io.devkit.netkit.ui.details

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.devkit.netkit.history.NetworkOutcome
import io.devkit.netkit.replay.ReplayEligibility
import io.devkit.netkit.history.NetworkRecord
import io.devkit.netkit.masking.MaskedHeader
import io.devkit.netkit.ui.NetKitFormat
import io.devkit.netkit.ui.NetKitTestTags
import io.devkit.netkit.ui.components.NetKitBadge
import io.devkit.netkit.ui.components.NetKitCodeBlock
import io.devkit.netkit.ui.components.NetKitDivider
import io.devkit.netkit.ui.components.NetKitGutter
import io.devkit.netkit.ui.components.NetKitKeyValueRow
import io.devkit.netkit.ui.components.NetKitMonoStyle
import io.devkit.netkit.ui.components.NetKitSectionLabel

/**
 * Everything NetKit captured about one request.
 *
 * The record is already masked, so what is shown and what "Copy details" puts on
 * the clipboard are the same text — a credential cannot leak through the copy
 * path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordDetailSheet(
    record: NetworkRecord,
    replayEligibility: ReplayEligibility,
    onReplay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(NetKitTestTags.DETAIL),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = NetKitGutter)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NetKitBadge(
                    text = record.method,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = record.path,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                record.badges.forEach { badge ->
                    NetKitBadge(
                        text = badge,
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            Column {
                when (val outcome = record.outcome) {
                    is NetworkOutcome.Completed -> NetKitKeyValueRow(
                        label = "Status",
                        value = "${outcome.statusCode} ${outcome.message}",
                    )

                    is NetworkOutcome.Failed -> {
                        NetKitKeyValueRow(
                            label = "Failure",
                            value = outcome.kind,
                            valueColor = MaterialTheme.colorScheme.error,
                        )
                        outcome.message?.let { NetKitKeyValueRow(label = "Message", value = it) }
                    }

                    NetworkOutcome.InFlight -> NetKitKeyValueRow(label = "Status", value = "In flight")
                }
                NetKitKeyValueRow(
                    label = "Duration",
                    value = NetKitFormat.duration(record.durationMillis),
                )
                NetKitKeyValueRow(
                    label = "Time",
                    value = NetKitFormat.clockTime(record.startedAtMillis),
                )
                NetKitKeyValueRow(
                    label = "Source",
                    value = if (record.isSimulated) "Simulated by NetKit" else "Real server",
                )
                NetKitKeyValueRow(label = "Kind", value = record.kind.label)
                record.replayOfRecordId?.let {
                    NetKitKeyValueRow(label = "Replay of", value = "request #$it")
                }
                record.scenarioLabel?.let { NetKitKeyValueRow(label = "Rule", value = it) }
                record.ruleSource?.let {
                    NetKitKeyValueRow(
                        label = "Applied by",
                        value = NetKitFormat.ruleSourceLabel(it),
                    )
                }
                record.sequenceDisplay?.let {
                    NetKitKeyValueRow(label = "Sequence step", value = it)
                }
            }

            NetKitDivider()

            NetKitSectionLabel("Request")
            Text(
                text = record.url,
                style = NetKitMonoStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            HeaderList(record.requestHeaders, emptyLabel = "No request headers")
            record.requestBody?.let { body ->
                NetKitSectionLabel("Request body", trailing = NetKitFormat.size(body.byteCount))
                NetKitCodeBlock(body.text)
                if (body.truncated) TruncatedNote()
            }

            NetKitDivider()

            NetKitSectionLabel("Response")
            HeaderList(record.responseHeaders, emptyLabel = "No response headers")
            record.responseBody?.let { body ->
                NetKitSectionLabel("Response body", trailing = NetKitFormat.size(body.byteCount))
                NetKitCodeBlock(body.text)
                if (body.truncated) TruncatedNote()
            }
            if (record.responseBody == null && record.outcome !is NetworkOutcome.Failed) {
                Text(
                    text = "Body not captured — it was binary, streamed, or larger than the preview limit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            NetKitDivider()

            ReplaySection(
                record = record,
                eligibility = replayEligibility,
                onReplay = onReplay,
            )

            OutlinedButton(
                onClick = { copyToClipboard(context, NetKitFormat.recordAsText(record)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(NetKitTestTags.DETAIL_COPY),
            ) {
                Text("Copy details (masked)")
            }
        }
    }
}

/**
 * The Replay entry point.
 *
 * Replay is offered as a button, not hidden behind a long-press: it is the whole
 * reason a developer opens this sheet after a failure. When it is unavailable —
 * an evicted snapshot, a one-shot body, replay switched off — the sheet says why
 * instead of showing a button that does nothing.
 */
@Composable
private fun ReplaySection(
    record: NetworkRecord,
    eligibility: ReplayEligibility,
    onReplay: () -> Unit,
) {
    NetKitSectionLabel("Replay")
    when (eligibility) {
        is ReplayEligibility.Eligible -> {
            Button(
                onClick = onReplay,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(NetKitTestTags.DETAIL_REPLAY),
            ) {
                Text("Replay this request")
            }
            Text(
                text = if (eligibility.snapshot.isSideEffectful) {
                    "${record.method} can create or modify real backend data. " +
                        "NetKit asks you to confirm before sending it."
                } else {
                    "Sends the request again through NetKit. The result is recorded in " +
                        "history and is not returned to the screen that made the original call."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (eligibility.snapshot.isSideEffectful) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        is ReplayEligibility.Unavailable -> Text(
            text = eligibility.reason.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HeaderList(headers: List<MaskedHeader>, emptyLabel: String) {
    if (headers.isEmpty()) {
        Text(
            text = emptyLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column {
        headers.forEach { header -> HeaderRow(header) }
    }
}

@Composable
private fun HeaderRow(header: MaskedHeader) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "${header.name}:",
            style = NetKitMonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = header.value,
            modifier = Modifier.weight(1f),
            style = NetKitMonoStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (header.masked) NetKitBadge(text = "MASKED")
    }
}

@Composable
private fun TruncatedNote() {
    Text(
        text = "Preview truncated to the configured limit.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("NetKit request", text))
    // Android 13+ shows its own copy confirmation; a second toast would duplicate it.
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Copied masked request details", Toast.LENGTH_SHORT).show()
    }
}
