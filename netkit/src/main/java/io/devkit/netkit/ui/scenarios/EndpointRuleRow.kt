package io.devkit.netkit.ui.scenarios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.runtime.SequenceProgress
import io.devkit.netkit.ui.NetKitFormat
import io.devkit.netkit.ui.NetKitTestTags
import io.devkit.netkit.ui.components.NetKitBadge
import io.devkit.netkit.ui.components.NetKitCardShape
import io.devkit.netkit.ui.components.NetKitMinTouchTarget
import io.devkit.netkit.ui.components.NetKitMonoStyle

/**
 * One endpoint override.
 *
 * The row reads top-down as method → path → behaviour, which is the order a QA
 * engineer scans for. A disabled rule stays in place but is dimmed *and*
 * labelled `OFF`, so its state never depends on colour alone.
 *
 * A rule running a response sequence shows its live position (`2 / 3`) as a
 * badge, so the next result is predictable without opening the rule.
 */
@Composable
internal fun EndpointRuleRow(
    rule: EndpointRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    progress: SequenceProgress? = null,
) {
    val contentAlpha = if (rule.enabled) 1f else 0.55f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(NetKitCardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onEdit)
            .heightIn(min = NetKitMinTouchTarget)
            .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)
            .testTag(NetKitTestTags.RULE_ROW_PREFIX + rule.id),
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
                NetKitBadge(
                    text = rule.method.label,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = contentAlpha),
                )
                Text(
                    text = rule.matcher.label,
                    modifier = Modifier.weight(1f, fill = false),
                    style = NetKitMonoStyle,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                if (progress != null) NetKitBadge(text = progress.display)
                // A rule that only fires sometimes must not read like one that
                // always does. Without this badge a 30% rule and a plain rule are
                // indistinguishable in the list, and "why did that not happen?"
                // becomes a question the list cannot answer.
                if (!rule.probability.isAlways) {
                    NetKitBadge(text = rule.probability.percentLabel)
                }
                if (rule.conditions.isNotEmpty()) {
                    NetKitBadge(text = "IF")
                }
                if (!rule.enabled) {
                    NetKitBadge(text = "OFF")
                }
            }
            Text(
                text = NetKitFormat.actionSummary(rule.action),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Spelled out under the action, because "IF" alone says a rule is
            // conditional without saying on what.
            rule.conditionSummary?.let { conditions ->
                Text(
                    text = conditions,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            rule.name?.takeIf { it.isNotBlank() }?.let { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(
            checked = rule.enabled,
            onCheckedChange = onToggle,
            modifier = Modifier
                .semantics {
                    contentDescription = "Enable override ${rule.displayTarget}"
                }
                .testTag(NetKitTestTags.RULE_TOGGLE_PREFIX + rule.id),
        )
    }
}
