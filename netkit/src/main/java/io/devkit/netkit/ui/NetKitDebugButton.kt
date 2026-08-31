package io.devkit.netkit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.devkit.netkit.state.NetKitController

/**
 * An optional, ready-made way to open [NetKitScreen].
 *
 * Purely a convenience: activation is the application's business, and NetKit
 * works just as well from a debug drawer, a shake gesture, a hidden menu or a
 * navigation destination. Nothing else in NetKit depends on this composable.
 *
 * Place it over your content, typically as the last child of a `Box`:
 *
 * ```kotlin
 * Box(Modifier.fillMaxSize()) {
 *     AppContent()
 *     NetKitDebugButton(netKit.controller)
 * }
 * ```
 *
 * The label reflects the runtime state, so an active scenario is visible without
 * opening the console — which prevents the classic "why is the app broken?"
 * half-hour after someone left offline mode on.
 *
 * @param controller the runtime this launcher opens.
 * @param alignment where the button sits within its parent.
 */
@Composable
fun NetKitDebugButton(
    controller: NetKitController,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.BottomEnd,
) {
    val state by controller.state.collectAsState()
    var showConsole by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        ExtendedFloatingActionButton(
            onClick = { showConsole = true },
            modifier = Modifier
                .align(alignment)
                .padding(16.dp)
                .semantics {
                    contentDescription = if (state.isSimulating) {
                        "Open the NetKit network console. Simulating: ${state.activeSummary}"
                    } else {
                        "Open the NetKit network console"
                    }
                },
            containerColor = if (state.isSimulating) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ) {
            Text(if (state.isSimulating) "NetKit · ${state.activeSummary}" else "NetKit")
        }
    }

    if (showConsole) {
        NetKitDialog(controller = controller, onDismiss = { showConsole = false })
    }
}
