package io.devkit.netkit.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.devkit.netkit.history.NetworkRecord
import io.devkit.netkit.ui.NetKitTestTags
import io.devkit.netkit.ui.components.NetKitEmptyState
import io.devkit.netkit.ui.components.NetKitGutter
import io.devkit.netkit.ui.components.NetKitSectionLabel

/** Captured requests, newest first. */
@Composable
internal fun HistoryTab(
    records: List<NetworkRecord>,
    onSelect: (NetworkRecord) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
) {
    if (records.isEmpty()) {
        NetKitEmptyState(
            title = "No requests captured yet",
            detail = "Requests appear here as soon as they pass through the NetKit interceptor.",
            modifier = modifier,
        )
        return
    }

    val simulated = records.count(NetworkRecord::isSimulated)
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag(NetKitTestTags.HISTORY_LIST),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "history-header") {
            NetKitSectionLabel(
                label = "Requests",
                trailing = if (simulated == 0) {
                    "${records.size} captured"
                } else {
                    "${records.size} captured · $simulated simulated"
                },
                modifier = Modifier.padding(horizontal = NetKitGutter, vertical = 2.dp),
            )
        }
        items(records, key = { it.id }) { record ->
            HistoryRow(
                record = record,
                onClick = { onSelect(record) },
                modifier = Modifier.padding(horizontal = NetKitGutter),
            )
        }
    }
}
