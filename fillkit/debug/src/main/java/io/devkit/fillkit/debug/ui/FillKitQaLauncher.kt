package io.devkit.fillkit.debug.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.devkit.fillkit.FillActivationResult
import io.devkit.fillkit.FillKit
import io.devkit.fillkit.FillKitSeed
import io.devkit.fillkit.FillScenarioCatalog
import io.devkit.fillkit.FillScenarioCatalogEntry
import io.devkit.fillkit.debug.activation.FillKitActivationEngine
import io.devkit.fillkit.debug.deeplink.FillKitDeepLink
import io.devkit.fillkit.debug.persistence.QaPreferencesStore
import io.devkit.fillkit.debug.persistence.RecentScenario
import io.devkit.fillkit.debug.runtime.FormRegistry
import kotlinx.coroutines.launch

private enum class QaTab(val label: String) { All("All"), Recent("Recent"), Favorites("Favorites") }

/**
 * Debug-only QA scenario launcher.
 *
 * It reads the same packs the developer panel and Compose tests use, and every
 * launch goes through [FillKitActivationEngine] — there is no separate QA
 * activation path.
 */
@Composable
internal fun ColumnScope.QaLauncherSheet(registry: FormRegistry, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context.applicationContext) { QaPreferencesStore(context.applicationContext) }
    val debugConfig = FillKit.debugConfig
    val catalog = remember(registry, debugConfig) {
        FillScenarioCatalog.build(
            registry.catalogInput(debugConfig.navigableForms, debugConfig.navigator != null),
        )
    }
    val recent by preferences.recent.collectAsState(initial = emptyList())
    val favorites by preferences.favorites.collectAsState(initial = emptySet())

    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(QaTab.All) }
    var form by remember { mutableStateOf<String?>(null) }
    var tag by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<FillScenarioCatalogEntry?>(null) }

    val active = selected
    if (active != null) {
        ScenarioLaunchPanel(
            entry = active,
            registry = registry,
            onBack = { selected = null },
            onLaunched = { launched ->
                scope.launch {
                    preferences.remember(
                        RecentScenario(launched.packId, launched.id, launched.targetForm ?: registry.formId),
                        debugConfig.recentScenarioLimit,
                    )
                }
                onDismiss()
            },
        )
        return
    }

    RouteHeader("FillKit QA", "Launch a known scenario without walking the form by hand.")

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Search scenarios") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QaTab.entries.forEach { entry ->
            FilterPill(entry.label, tab == entry) { tab = entry }
        }
        catalog.forms.forEach { candidate ->
            FilterPill(candidate.humanizeId(), form == candidate) { form = if (form == candidate) null else candidate }
        }
        catalog.tags.forEach { candidate ->
            FilterPill("#$candidate", tag == candidate) { tag = if (tag == candidate) null else candidate }
        }
    }
    Spacer(Modifier.height(12.dp))

    val results = remember(catalog, query, form, tag, tab, recent, favorites) {
        val matches = catalog.search(query = query, form = form, tag = tag)
        when (tab) {
            QaTab.All -> matches
            QaTab.Favorites -> matches.filter { it.favoriteKey() in favorites }
            QaTab.Recent -> recent.mapNotNull { entry ->
                matches.firstOrNull { it.id == entry.scenarioId && it.packId == entry.packId }
            }
        }
    }

    if (results.isEmpty()) {
        EmptyStateCard(
            title = "No scenarios match",
            body = when (tab) {
                QaTab.Recent -> "Launch a scenario and it will show up here."
                QaTab.Favorites -> "Star a scenario to keep it at hand."
                QaTab.All -> "Add scenario packs to FillKit.configureDebug so QA can reach them."
            },
        )
        return
    }

    LazyColumn(
        modifier = Modifier.weight(1f, fill = false).heightIn(max = 460.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        results.groupBy { it.packName ?: "Form scenarios" }.forEach { (packName, entries) ->
            item(key = "header-$packName") { SectionLabel(packName, Modifier.padding(top = 4.dp)) }
            items(entries, key = { "${it.packId}/${it.id}" }) { entry ->
                ScenarioRow(
                    entry = entry,
                    favorite = entry.favoriteKey() in favorites,
                    onToggleFavorite = { scope.launch { preferences.toggleFavorite(entry.favoriteKey()) } },
                    onSelect = { selected = entry },
                )
            }
        }
    }
}

@Composable
private fun ScenarioRow(
    entry: FillScenarioCatalogEntry,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onSelect: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(CardShape).clickable(role = Role.Button, onClick = onSelect),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    entry.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (favorite) "★" else "☆",
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(role = Role.Checkbox, onClick = onToggleFavorite)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            entry.scenario.description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                entry.targetForm?.let { MiniBadge(it.humanizeId()) }
                entry.scenario.category?.let { MiniBadge(it) }
                entry.tags.take(2).forEach { MiniBadge("#$it") }
            }
            if (!entry.launchable) {
                Text(
                    "Cannot launch — ${entry.blockingReason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Seed control before launching, so QA can pin or reroll a reproduction. */
@Composable
private fun ColumnScope.ScenarioLaunchPanel(
    entry: FillScenarioCatalogEntry,
    registry: FormRegistry,
    onBack: () -> Unit,
    onLaunched: (FillScenarioCatalogEntry) -> Unit,
) {
    val context = LocalContext.current
    val defaultForm = entry.targetForm ?: registry.formId
    var seedInput by remember(entry.id) { mutableStateOf(registry.masterSeed.value.toString()) }
    var localeTag by remember(entry.id) { mutableStateOf(registry.localeTag) }
    var outcome by remember(entry.id) { mutableStateOf<String?>(null) }

    RouteHeader(entry.name, entry.scenario.description ?: "Launch this scenario into $defaultForm.")

    Column(
        modifier = Modifier.weight(1f, fill = false).heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DetailRow("Form", defaultForm.humanizeId())
        DetailRow("Scenario", "${entry.packId?.plus("/") ?: ""}${entry.id}")
        DetailRow("Persona", entry.scenario.personaId ?: "Random")
        DetailRow("Locale", localeTag)
        entry.issues.forEach { issue ->
            Text(
                (if (issue.blocking) "Cannot launch — " else "Heads up — ") + issue.message,
                style = MaterialTheme.typography.labelMedium,
                color = if (issue.blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = seedInput,
                onValueChange = { value -> seedInput = value.filter(Char::isDigit).take(12) },
                label = { Text("Seed") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = { seedInput = FillKitSeed.random().value.toString() },
                shape = ControlShape,
                modifier = Modifier.height(56.dp),
            ) { Text("Random", maxLines = 1) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = localeTag,
                onValueChange = { localeTag = it.trim() },
                label = { Text("Locale") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        outcome?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    Spacer(Modifier.height(10.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledTonalButton(
            onClick = onBack,
            modifier = Modifier.height(46.dp),
            shape = ControlShape,
        ) { Text("Back", maxLines = 1) }
        Button(
            onClick = {
                val request = entry.launchRequest(
                    formId = defaultForm,
                    seed = FillKitSeed.parseOrNull(seedInput)?.value,
                    locale = localeTag.takeIf(String::isNotBlank),
                    fingerprint = registry.configurationFingerprint,
                )
                when (val result = FillKitActivationEngine.submit(request)) {
                    is FillActivationResult.Rejected -> outcome = "Rejected: ${result.message}"
                    is FillActivationResult.Pending -> {
                        context.copyToClipboard(
                            "FillKit deep link",
                            FillKitDeepLink.scenarioUri(FillKitDeepLink.scheme(context.packageName), request),
                        )
                        onLaunched(entry)
                    }
                    else -> onLaunched(entry)
                }
            },
            enabled = entry.launchable,
            modifier = Modifier.weight(1f).height(46.dp),
            shape = ControlShape,
        ) { Text("Launch scenario", fontWeight = FontWeight.SemiBold, maxLines = 1) }
    }

    LaunchedEffect(entry.id) { outcome = null }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(label)
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(CircleShape).clickable(role = Role.RadioButton, onClick = onClick),
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
internal fun EmptyStateCard(title: String, body: String) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun FillScenarioCatalogEntry.favoriteKey(): String = "${packId.orEmpty()}/$id"
