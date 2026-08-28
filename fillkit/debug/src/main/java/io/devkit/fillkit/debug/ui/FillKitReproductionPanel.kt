package io.devkit.fillkit.debug.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.devkit.fillkit.FillKitSeed
import io.devkit.fillkit.FillReproductionTokenCodec
import io.devkit.fillkit.debug.deeplink.FillKitDeepLink
import io.devkit.fillkit.debug.runtime.FormRegistry

/**
 * The reproduction surface: the active seed, a copyable report, the token, the
 * deep link and a ready-to-run ADB command. Generated field values are never
 * included — links and reports end up in bug trackers and system logs.
 */
@Composable
internal fun ColumnScope.ReproductionSheet(registry: FormRegistry, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val spec = registry.reproductionSpec()
    val token = FillReproductionTokenCodec.encodeOrNull(spec)
    val scheme = FillKitDeepLink.scheme(context.packageName)
    val deepLink = token?.let { FillKitDeepLink.reproduceUri(scheme, it) }
    var seedInput by remember(spec.seed) { mutableStateOf(spec.seed.toString()) }

    RouteHeader("Reproduction", "Everything needed to bring this exact form state back.")

    Column(
        modifier = Modifier.weight(1f, fill = false).heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SeedCard(
            seed = spec.seed,
            generation = spec.generation,
            onCopy = { context.copyToClipboard("FillKit seed", spec.seed.toString()) },
            onNewSeed = { registry.newSeed(); onDismiss() },
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Apply a seed")
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = seedInput,
                    onValueChange = { value -> seedInput = value.filter(Char::isDigit).take(12) },
                    label = { Text("Seed") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        FillKitSeed.parseOrNull(seedInput)?.let { registry.applySeed(it); onDismiss() }
                    },
                    enabled = FillKitSeed.parseOrNull(seedInput) != null,
                    shape = ControlShape,
                    modifier = Modifier.height(56.dp),
                ) { Text("Apply") }
            }
            Text(
                "Applying a seed reproduces generation zero.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        MonospaceBlock("Report", spec.describe(token))
        token?.let { MonospaceBlock("Token", it) }
        deepLink?.let { MonospaceBlock("Deep link", it) }
        deepLink?.let { MonospaceBlock("ADB", FillKitDeepLink.adbCommand(it)) }
    }

    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FilledTonalButton(
            onClick = { context.copyToClipboard("FillKit reproduction", spec.describe(token)) },
            modifier = Modifier.weight(1f).height(46.dp),
            shape = ControlShape,
        ) { Text("Copy report", fontWeight = FontWeight.SemiBold, maxLines = 1) }
        if (deepLink != null) {
            FilledTonalButton(
                onClick = { context.copyToClipboard("FillKit ADB", FillKitDeepLink.adbCommand(deepLink)) },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = ControlShape,
            ) { Text("Copy ADB", fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
}

@Composable
private fun SeedCard(seed: Long, generation: Int, onCopy: () -> Unit, onNewSeed: () -> Unit) {
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Seed")
                Text(
                    seed.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "generation $generation",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            GhostAction("Copy", MaterialTheme.colorScheme.onPrimaryContainer, onCopy)
            GhostAction("New seed", MaterialTheme.colorScheme.onPrimaryContainer, onNewSeed)
        }
    }
}

@Composable
internal fun MonospaceBlock(label: String, value: String) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionLabel(label)
            GhostAction("Copy") { context.copyToClipboard("FillKit $label", value) }
        }
        Text(
            value,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ValueShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

internal fun Context.copyToClipboard(label: String, value: String) {
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, value))
}

/** Circle-shaped affordance reused by the reproduction and QA surfaces. */
internal val PillShape = CircleShape
