package io.devkit.netdemo

import io.devkit.netkit.NetKit
import io.devkit.netkit.config.NetKitConfig
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.TimeoutType
import io.devkit.netkit.ui.NetKitDebugButton
import io.devkit.netkit.ui.NetKitDialog
import kotlin.concurrent.thread

/**
 * Debug-only wiring for the NetKit demo.
 *
 * This file lives in `src/debug`, which is the whole point: `:netkit` is a
 * `debugImplementation` dependency, so it can only be referenced from here.
 * Shared code reaches the same functionality through [DebugNetworking], and a
 * release build simply never runs this installer.
 */
object NetKitDemoInstaller {

    private var installed = false

    /** Creates the NetKit runtime, starts the demo backend, and fills in the hooks. */
    fun install() {
        if (installed) return
        installed = true

        val netKit = NetKit.initialize(NetKitConfig(maxHistoryEntries = 200))

        DebugNetworking.interceptors = listOf(netKit.interceptor)
        DebugNetworking.console = { onClose ->
            NetKitDialog(controller = netKit.controller, onDismiss = onClose)
        }
        DebugNetworking.launcher = { NetKitDebugButton(controller = netKit.controller) }
        DebugNetworking.reset = { netKit.controller.reset() }
        DebugNetworking.applyPreset = { id -> applyPreset(id) }

        // MockWebServer binds a port, so it must not run on the main thread.
        thread(name = "netkit-demo-backend") {
            DemoBackend.start()?.let { DebugNetworking.baseUrl = it }
        }
    }

    /**
     * The four scenarios from the NetKit README, as one-tap presets. Each starts
     * from a clean slate so presets never stack up unexpectedly.
     */
    private fun applyPreset(id: String) {
        val controller = NetKit.requireInstance().controller
        controller.reset()
        when (id) {
            "offline" -> controller.setOffline(true)

            "latency" -> controller.setGlobalLatency(2_500)

            "bookings-500" -> controller.addRule(
                EndpointRule.forPath(
                    path = "/api/v1/bookings",
                    method = HttpMethod.GET,
                    action = NetworkAction.HttpError(
                        statusCode = 500,
                        body = """{"message":"Bookings service temporarily unavailable"}""",
                    ),
                    name = "Bookings failure",
                ),
            )

            "checkout-timeout" -> controller.addRule(
                EndpointRule.forPath(
                    path = "/api/v1/checkout",
                    method = HttpMethod.POST,
                    action = NetworkAction.Timeout(TimeoutType.READ),
                    name = "Checkout timeout",
                ),
            )
        }
    }
}
