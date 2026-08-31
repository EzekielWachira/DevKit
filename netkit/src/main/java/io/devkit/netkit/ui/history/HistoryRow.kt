package io.devkit.netkit.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.devkit.netkit.history.NetworkOutcome
import io.devkit.netkit.history.NetworkRecord
import io.devkit.netkit.ui.NetKitFormat
import io.devkit.netkit.ui.NetKitTestTags
import io.devkit.netkit.ui.components.NetKitBadge
import io.devkit.netkit.ui.components.NetKitCardShape
import io.devkit.netkit.ui.components.NetKitMinTouchTarget
import io.devkit.netkit.ui.components.NetKitMonoStyle

/**
 * One captured request.
 *
 * Outcome is always spelled out (`200`, `500`, `TIMEOUT`, `OFFLINE`) and the
 * simulation badge is the literal word `SIMULATED`, so nothing here depends on
 * colour. Colour only reinforces what the text already says.
 */
@Composable
internal fun HistoryRow(
    record: NetworkRecord,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(NetKitCardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = NetKitMinTouchTarget)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(NetKitTestTags.HISTORY_ROW_PREFIX + record.id),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = NetKitFormat.clockTime(record.startedAtMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NetKitBadge(
                    text = record.method,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (record.isSimulated) {
                    NetKitBadge(
                        text = "SIMULATED",
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Text(
                text = record.path,
                style = NetKitMonoStyle,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(
                text = buildString {
                    append(record.host)
                    append(" · ")
                    append(NetKitFormat.duration(record.durationMillis))
                    record.scenarioLabel?.let {
                        append(" · ")
                        append(it)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutcomeBadge(record)
    }
}

@Composable
private fun OutcomeBadge(record: NetworkRecord) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (val outcome = record.outcome) {
        is NetworkOutcome.Completed -> when {
            outcome.isSuccessful -> scheme.surfaceContainerHighest to scheme.onSurface
            else -> scheme.errorContainer to scheme.onErrorContainer
        }

        is NetworkOutcome.Failed -> scheme.errorContainer to scheme.onErrorContainer
        NetworkOutcome.InFlight -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    NetKitBadge(
        text = NetKitFormat.outcomeLabel(record),
        container = container,
        content = content as Color,
    )
}
