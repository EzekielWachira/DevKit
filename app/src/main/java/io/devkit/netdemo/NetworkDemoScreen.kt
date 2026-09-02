package io.devkit.netdemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The NetKit demo.
 *
 * Four endpoints, one button each, and the raw result of every call. Turn on a
 * scenario in the NetKit console — or with one of the presets here — then press
 * the buttons and watch which endpoints change and which keep talking to the
 * demo backend.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetworkDemoScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateMapOf<String, DemoResult>() }
    var showConsole by remember { mutableStateOf(false) }

    fun call(endpoint: DemoEndpoint) {
        results[endpoint.id] = DemoResult.Loading
        scope.launch {
            results[endpoint.id] = withContext(Dispatchers.IO) { DemoApi.call(endpoint) }
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "NetKit demo",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (DebugNetworking.isAvailable) {
                            "These calls go through the NetKit interceptor to a local demo " +
                                "backend. Open the console, activate a scenario, and call them " +
                                "again."
                        } else {
                            "NetKit is not on this build's classpath, so every call goes " +
                                "straight to the backend. That is what a release build looks like."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (DebugNetworking.isAvailable) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "TEMPORARY OVERRIDES",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "One-tap ad-hoc changes. For saved, shareable setups open the " +
                                "console and look at the Scenarios tab — this build registers " +
                                "two packs from application code.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DemoScenarioPresets.all.forEach { preset ->
                                AssistChip(
                                    onClick = { DebugNetworking.applyPreset(preset.id) },
                                    label = { Text(preset.label) },
                                )
                            }
                            AssistChip(
                                onClick = { DebugNetworking.reset() },
                                label = { Text("Reset") },
                            )
                        }
                        Button(
                            onClick = { showConsole = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Open NetKit console")
                        }
                        DemoWalkthrough()
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { DemoApi.endpoints.forEach(::call) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Call all endpoints")
                    }
                    OutlinedButton(onClick = { results.clear() }) { Text("Clear") }
                }
            }

            items(DemoApi.endpoints, key = { it.id }) { endpoint ->
                EndpointCard(
                    endpoint = endpoint,
                    result = results[endpoint.id] ?: DemoResult.Idle,
                    onCall = { call(endpoint) },
                )
            }
        }

        if (!showConsole) DebugNetworking.launcher?.invoke()
    }

    if (showConsole) {
        DebugNetworking.console?.invoke { showConsole = false }
    }
}

@Composable
private fun EndpointCard(
    endpoint: DemoEndpoint,
    result: DemoResult,
    onCall: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${endpoint.method} ${endpoint.path}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    endpoint.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onCall) { Text("Call") }
        }
        ResultLine(result)
    }
}

@Composable
private fun ResultLine(result: DemoResult) {
    val (text, color) = when (result) {
        DemoResult.Idle -> "Not called yet" to MaterialTheme.colorScheme.onSurfaceVariant
        DemoResult.Loading -> "Calling…" to MaterialTheme.colorScheme.onSurfaceVariant
        is DemoResult.Success ->
            "HTTP ${result.statusCode} · ${result.durationMillis}ms\n${result.body}" to
                MaterialTheme.colorScheme.onSurface

        is DemoResult.Failure ->
            "${result.kind} · ${result.durationMillis}ms\n${result.message}" to
                MaterialTheme.colorScheme.error
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = color,
    )
}

/**
 * What to try, in the order that shows NetKit 0.2 off best.
 *
 * The demo is also the manual test plan for the toolkit, so this list doubles as
 * a checklist: each line exercises one 0.2 capability end to end.
 */
@Composable
private fun DemoWalkthrough() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "TRY THIS",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf(
            "Scenarios → activate \"Retry eventually succeeds\", then call " +
                "GET /bookings three times and watch the sequence go 500 → 500 → 200.",
            "Scenarios → \"Malformed JSON\" returns a 200 the parser cannot read.",
            "Scenarios → \"Rate limited\" adds Retry-After and X-RateLimit-Remaining headers.",
            "History → open any request and press Replay. A POST warns before it sends.",
            "Scenarios → open one and press Export to share a .netkit.json, " +
                "then Import it back.",
            "Console → set a global delay, add an override, then Save current setup.",
        ).forEach { line ->
            Text(
                text = "• $line",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The ad-hoc overrides the demo can apply with one tap. */
object DemoScenarioPresets {
    data class Preset(val id: String, val label: String)

    val all: List<Preset> = listOf(
        Preset("offline", "Offline"),
        Preset("latency", "2.5s latency"),
        Preset("bookings-500", "GET /bookings → 500"),
        Preset("checkout-timeout", "POST /checkout → timeout"),
    )
}
