package io.devkit.netdemo

import android.content.Context
import io.devkit.netkit.NetKit
import io.devkit.netkit.android.DataStoreScenarioStorage
import io.devkit.netkit.config.NetKitConfig
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.NetworkAction
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

    /**
     * Creates the NetKit runtime, starts the demo backend, and fills in the hooks.
     *
     * Scenario storage is wired to DataStore, so a scenario saved or imported in
     * the demo is still there after the process is killed — which is what makes
     * the QA → developer handoff worth demonstrating at all.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true

        val netKit = NetKit.create(
            NetKitConfig(
                maxHistoryEntries = 200,
                builtInPacks = DemoScenarioPacks.all,
                scenarioStorage = DataStoreScenarioStorage(context),
            ),
        )

        DebugNetworking.interceptors = listOf(netKit.interceptor)
        DebugNetworking.console = { onClose ->
            NetKitDialog(controller = netKit.controller, onDismiss = onClose)
        }
        DebugNetworking.launcher = { NetKitDebugButton(controller = netKit.controller) }
        DebugNetworking.reset = { netKit.controller.resetEverything() }
        DebugNetworking.applyPreset = { id -> applyPreset(netKit, id) }

        // MockWebServer binds a port, so it must not run on the main thread.
        thread(name = "netkit-demo-backend") {
            DemoBackend.start()?.let { DebugNetworking.baseUrl = it }
        }

        instance = netKit
    }

    /** Held so the presets can reach the controller without a global lookup. */
    private var instance: NetKit? = null

    /**
     * One-tap presets for the temporary-override layer.
     *
     * Deliberately *not* scenarios: these demonstrate the ad-hoc side of NetKit,
     * the thing you reach for mid-investigation before you know whether it is
     * worth saving. The scenario packs in [DemoScenarioPacks] demonstrate the
     * other half.
     */
    private fun applyPreset(netKit: NetKit, id: String) {
        val controller = netKit.controller
        controller.reset()
        when (id) {
            "offline" -> controller.setOffline(true)

            "latency" -> controller.setGlobalLatency(2_500)

            "bookings-500" -> controller.addRule(
                EndpointRule.forPath(
                    path = "/api/v1/bookings",
                    method = HttpMethod.GET,
                    action = NetworkAction.ReturnResponse(
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
                    action = NetworkAction.Timeout(io.devkit.netkit.scenario.TimeoutType.READ),
                    name = "Checkout timeout",
                ),
            )
        }
    }
}
