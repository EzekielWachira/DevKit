package io.devkit.netkit.ui.scenariolist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.devkit.netkit.scenario.serialization.ImportSummary
import io.devkit.netkit.scenario.serialization.ScenarioImportResult
import io.devkit.netkit.ui.NetKitTestTags
import io.devkit.netkit.ui.components.NetKitDivider
import io.devkit.netkit.ui.components.NetKitGutter
import io.devkit.netkit.ui.components.NetKitKeyValueRow
import io.devkit.netkit.ui.components.NetKitSectionLabel

/**
 * What a `.netkit.json` contains, shown **before** anything is saved.
 *
 * Importing straight from the file picker would mean a QA engineer discovers
 * what they just added by watching their app behave strangely. This screen is
 * the answer to "what am I about to turn on".
 *
 * A rejected file gets the same sheet with the reason spelled out, because
 * "import failed" in a snackbar is not enough to work out whether the file is
 * corrupt, from a newer NetKit, or simply not a NetKit file at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportPreviewSheet(
    result: ScenarioImportResult,
    fileName: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val summary = when (result) {
        is ScenarioImportResult.Scenario -> result.summary
        is ScenarioImportResult.Pack -> result.summary
        else -> null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(NetKitTestTags.IMPORT_PREVIEW),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = NetKitGutter)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (summary == null) "Unable to import" else "Import scenario",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            fileName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (summary == null) {
                Text(
                    text = result.failureReason ?: "This file could not be read.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "Nothing was imported and nothing on this device changed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Summary(summary, isPack = result is ScenarioImportResult.Pack)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(NetKitTestTags.IMPORT_CANCEL),
                ) {
                    Text(if (summary == null) "Close" else "Cancel")
                }
                if (summary != null) {
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(NetKitTestTags.IMPORT_CONFIRM),
                    ) {
                        Text(if (result is ScenarioImportResult.Pack) "Import pack" else "Import")
                    }
                }
            }
        }
    }
}

@Composable
private fun Summary(summary: ImportSummary, isPack: Boolean) {
    Text(
        text = summary.name,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
    summary.description?.takeIf { it.isNotBlank() }?.let {
        Text(text = it, style = MaterialTheme.typography.bodyMedium)
    }

    NetKitDivider()

    Column {
        if (isPack) {
            NetKitKeyValueRow(label = "Scenarios", value = "${summary.scenarioCount}")
        }
        NetKitKeyValueRow(label = "Rules", value = "${summary.ruleCount}")
        NetKitKeyValueRow(
            label = "Global",
            value = summary.globalSummary ?: "None",
        )
        NetKitKeyValueRow(label = "Schema", value = "${summary.schemaVersion}")
    }

    if (summary.contents.isNotEmpty()) {
        NetKitSectionLabel("Contains")
        summary.contents.forEach { entry ->
            Text(
                text = "✓ $entry",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
    }

    Text(
        text = "A scenario that already exists is imported as a copy rather than replacing " +
            "what is on this device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
