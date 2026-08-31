package io.devkit.netkit.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.devkit.netkit.state.NetKitController

/**
 * The NetKit console presented as a full-screen overlay.
 *
 * This is the presentation to reach for in most apps. [NetKitScreen] renders the
 * console into whatever space it is given; a debugging console wants the whole
 * window, and embedding it in a partially-expanded sheet or inside another
 * screen's padded content area leaves too little room for its header, tabs and
 * lists at once.
 *
 * ```kotlin
 * if (showNetKit) {
 *     NetKitDialog(netKit.controller) { showNetKit = false }
 * }
 * ```
 *
 * Use [NetKitScreen] directly when you want the console as a navigation
 * destination or inside your own container — it only needs a bounded height.
 *
 * @param controller the runtime this console drives.
 * @param onDismiss invoked on back press, on an outside tap, and by the Close button.
 */
@Composable
fun NetKitDialog(
    controller: NetKitController,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        NetKitScreen(controller = controller, onClose = onDismiss)
    }
}
