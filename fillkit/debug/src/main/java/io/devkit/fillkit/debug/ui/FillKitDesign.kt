package io.devkit.fillkit.debug.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.devkit.fillkit.FillType
import io.devkit.fillkit.displayName

/** Shared shapes, tokens and primitives for every FillKit surface. */
/** Share of the available sheet height the main panel may occupy at most. */
internal const val MaxPanelHeightFraction = 0.9f

internal val SheetGutter = 20.dp
internal val ActionHeight = 54.dp
internal val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
internal val CardShape = RoundedCornerShape(20.dp)
internal val ChipShape = RoundedCornerShape(16.dp)
internal val ControlShape = RoundedCornerShape(14.dp)
internal val ValueShape = RoundedCornerShape(8.dp)
internal val FieldCardShape = RoundedCornerShape(14.dp)

@Composable
internal fun FillKitDragHandle() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .padding(top = 12.dp, bottom = 6.dp)
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)),
        )
    }
}

@Composable
internal fun SectionLabel(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
    )
}

/** Title / count row used above list sections. */
@Composable
internal fun SectionHeader(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Full-width selectable row used by the secondary settings sheets: a large tap
 * target with room for a supporting line and a trailing action.
 */
@Composable
internal fun OptionRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    badge: String? = null,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ChipShape)
            .clickable(role = Role.RadioButton, onClick = onClick),
        shape = ChipShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SelectionDot(selected)
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (badge != null) MiniBadge(badge)
            trailing?.invoke()
        }
    }
}

@Composable
private fun SelectionDot(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text(
                "✓",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
internal fun MiniBadge(
    text: String,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(shape = CircleShape, color = container) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** Square avatar tile carrying a one-glyph identity for a field or persona. */
@Composable
internal fun GlyphTile(
    glyph: String,
    container: Color,
    content: Color,
    size: Int = 38,
    shape: androidx.compose.ui.graphics.Shape = ControlShape,
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(shape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = content,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Low-emphasis text action; used where a full button would be too loud. */
@Composable
internal fun GhostAction(
    label: String,
    color: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    Text(
        label,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = color,
        fontWeight = FontWeight.SemiBold,
    )
}

internal enum class FieldCategory { Identity, Contact, Location, Work, Security, Numeric, Other }

internal fun FillType<*>.category(): FieldCategory = when (this) {
    FillType.FirstName, FillType.LastName, FillType.FullName, FillType.MiddleName,
    FillType.NamePrefix, FillType.NameSuffix, FillType.Username, FillType.DateOfBirth,
    -> FieldCategory.Identity

    FillType.Email, FillType.PhoneCountryCode, FillType.Website, FillType.Url -> FieldCategory.Contact
    is FillType.PhoneNumber -> FieldCategory.Contact

    FillType.StreetAddress, FillType.City, FillType.Region, FillType.Country,
    FillType.PostalCode,
    -> FieldCategory.Location

    FillType.CompanyName, FillType.JobTitle -> FieldCategory.Work
    is FillType.Password, is FillType.OtpCode -> FieldCategory.Security
    FillType.Age -> FieldCategory.Numeric
    is FillType.Integer, is FillType.Decimal -> FieldCategory.Numeric
    else -> FieldCategory.Other
}

/** Single-letter identity for a field tile — cheaper and steadier than emoji. */
internal fun FillType<*>.glyph(): String = when (this) {
    FillType.Username -> "@"
    is FillType.Password -> "•"
    is FillType.OtpCode -> "#"
    is FillType.Unsupported -> "!"
    else -> displayName().firstOrNull()?.uppercase() ?: "?"
}

@Composable
internal fun FieldCategory.container(): Color = when (this) {
    FieldCategory.Identity -> MaterialTheme.colorScheme.primaryContainer
    FieldCategory.Contact -> MaterialTheme.colorScheme.tertiaryContainer
    FieldCategory.Location -> MaterialTheme.colorScheme.secondaryContainer
    FieldCategory.Work -> MaterialTheme.colorScheme.surfaceContainerHighest
    FieldCategory.Security -> MaterialTheme.colorScheme.errorContainer
    FieldCategory.Numeric -> MaterialTheme.colorScheme.tertiaryContainer
    FieldCategory.Other -> MaterialTheme.colorScheme.surfaceContainerHighest
}

@Composable
internal fun FieldCategory.onContainer(): Color = when (this) {
    FieldCategory.Identity -> MaterialTheme.colorScheme.onPrimaryContainer
    FieldCategory.Contact -> MaterialTheme.colorScheme.onTertiaryContainer
    FieldCategory.Location -> MaterialTheme.colorScheme.onSecondaryContainer
    FieldCategory.Work -> MaterialTheme.colorScheme.onSurfaceVariant
    FieldCategory.Security -> MaterialTheme.colorScheme.onErrorContainer
    FieldCategory.Numeric -> MaterialTheme.colorScheme.onTertiaryContainer
    FieldCategory.Other -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal fun String.humanizeId(): String = replace('-', ' ').replaceFirstChar(Char::uppercase)
