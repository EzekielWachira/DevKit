package io.devkit.netkit.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import io.devkit.netkit.ui.components.NetKitChoiceChip
import io.devkit.netkit.ui.components.NetKitEmptyState
import io.devkit.netkit.ui.components.NetKitGutter
import io.devkit.netkit.ui.components.NetKitSectionLabel

/**
 * The filters the history list offers.
 *
 * Deliberately few and orthogonal. `Real` vs `Simulated` answers "did this come
 * from the backend"; `Replay` answers "did I cause this"; `Errors` answers
 * "where did it go wrong". Anything finer is search, which 0.2 does not attempt.
 */
internal enum class HistoryFilter(val label: String) {
    ALL("All"),
    REAL("Real"),
    SIMULATED("Simulated"),
    REPLAY("Replay"),
    ERRORS("Errors"),
    ;

    fun matches(record: NetworkRecord): Boolean = when (this) {
        ALL -> true
        REAL -> !record.isSimulated
        SIMULATED -> record.isSimulated
        REPLAY -> record.isReplay
        ERRORS -> record.isFailure
    }
}

/** Captured requests, newest first, with a filter row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HistoryTab(
    records: List<NetworkRecord>,
    filter: HistoryFilter,
    onFilterChange: (HistoryFilter) -> Unit,
    onSelect: (NetworkRecord) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
) {
    val visible = records.filter(filter::matches)
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

        item(key = "history-filters") {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NetKitGutter),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HistoryFilter.entries.forEach { entry ->
                    val count = records.count(entry::matches)
                    NetKitChoiceChip(
                        label = if (entry == HistoryFilter.ALL) {
                            entry.label
                        } else {
                            "${entry.label} $count"
                        },
                        selected = filter == entry,
                        onClick = { onFilterChange(entry) },
                        modifier = Modifier.testTag(
                            NetKitTestTags.HISTORY_FILTER_PREFIX + entry.name,
                        ),
                    )
                }
            }
        }

        if (visible.isEmpty()) {
            item(key = "history-empty") {
                NetKitEmptyState(
                    title = if (records.isEmpty()) {
                        "No requests captured yet"
                    } else {
                        "Nothing matches \"${filter.label}\""
                    },
                    detail = if (records.isEmpty()) {
                        "Requests appear here as soon as they pass through the NetKit interceptor."
                    } else {
                        "Switch back to All to see every captured request."
                    },
                )
            }
        }

        items(visible, key = { it.id }) { record ->
            HistoryRow(
                record = record,
                onClick = { onSelect(record) },
                modifier = Modifier.padding(horizontal = NetKitGutter),
            )
        }
    }
}
