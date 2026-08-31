package io.devkit.netkit

import io.devkit.netkit.config.NetKitConfig
import io.devkit.netkit.engine.DefaultNetworkScenarioEngine
import io.devkit.netkit.engine.NetworkScenarioEngine
import io.devkit.netkit.history.InMemoryNetworkHistoryStore
import io.devkit.netkit.history.NetworkHistoryStore
import io.devkit.netkit.history.NoOpNetworkHistoryStore
import io.devkit.netkit.interceptor.NetKitInterceptor
import io.devkit.netkit.state.DefaultNetKitController
import io.devkit.netkit.state.NetKitController
import okhttp3.Interceptor
import java.util.concurrent.atomic.AtomicReference

/**
 * A NetKit runtime: one controller, one engine, one interceptor, one history.
 *
 * NetKit is **debug/QA tooling**. Depend on it with `debugImplementation` so it
 * never reaches a release build; see the module README for the recommended
 * source-set layout that keeps release code free of NetKit references.
 *
 * ### Setup
 *
 * ```kotlin
 * val netKit = NetKit.create()
 *
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(netKit.interceptor)
 *     .build()
 * ```
 *
 * Then show the debug screen from wherever your app keeps developer entry points:
 *
 * ```kotlin
 * NetKitScreen(controller = netKit.controller, onClose = { showNetKit = false })
 * ```
 *
 * ### Scenario precedence
 *
 * 1. NetKit disabled → the request passes through.
 * 2. The first **enabled** endpoint rule that matches wins. Its action fully
 *    replaces the global behaviour — global latency is *not* added on top, so a
 *    rule pinned to `PassThrough` acts as an allow-list entry.
 * 3. Otherwise the global mode and latency apply.
 * 4. Otherwise the request passes through.
 *
 * ### Threading
 *
 * A NetKit instance is safe to create once and share. The controller accepts
 * calls from any thread; the interceptor runs on OkHttp's threads and reads a
 * single immutable scenario snapshot per request.
 *
 * @property config the immutable settings this instance was created with.
 * @property controller the only supported way to change NetKit at runtime.
 * @property historyStore the record store backing [NetKitController.history].
 * @property engine the decision layer; exposed for tests and future transports.
 * @property interceptor the OkHttp interceptor to install on your client.
 */
class NetKit private constructor(
    val config: NetKitConfig,
    val controller: NetKitController,
    val historyStore: NetworkHistoryStore,
    val engine: NetworkScenarioEngine,
    val interceptor: Interceptor,
) {

    companion object {

        private val installed = AtomicReference<NetKit?>(null)

        /**
         * Creates an independent NetKit runtime.
         *
         * Prefer this in applications with dependency injection: hold the
         * instance wherever you build your `OkHttpClient` and hand
         * [controller] to your debug UI. Nothing is registered globally.
         */
        fun create(config: NetKitConfig = NetKitConfig()): NetKit {
            val history = if (config.historyEnabled) {
                InMemoryNetworkHistoryStore(config.maxHistoryEntries)
            } else {
                NoOpNetworkHistoryStore
            }
            val controller = DefaultNetKitController(history, config.initialScenario)
            val engine = DefaultNetworkScenarioEngine { controller.scenario }
            val interceptor = NetKitInterceptor(
                engine = engine,
                history = history,
                config = config,
            )
            return NetKit(config, controller, history, engine, interceptor)
        }

        /**
         * Creates a runtime and remembers it as the process-wide instance, for
         * apps without dependency injection that need the same NetKit in their
         * networking module and their debug menu.
         *
         * This is opt-in global state and the only such state NetKit has:
         * nothing inside the library reads it. Calling it twice replaces the
         * installed instance — clients already holding the previous
         * interceptor keep using it.
         *
         * @return the newly installed instance.
         */
        fun initialize(config: NetKitConfig = NetKitConfig()): NetKit =
            create(config).also { installed.set(it) }

        /** The instance from [initialize], or `null` when there is none. */
        fun instanceOrNull(): NetKit? = installed.get()

        /**
         * The instance from [initialize].
         *
         * @throws IllegalStateException when [initialize] has not been called —
         *   almost always a sign that NetKit was stripped from this build type.
         */
        fun requireInstance(): NetKit = checkNotNull(installed.get()) {
            "NetKit.initialize(...) has not been called in this build. " +
                "NetKit is debug-only: check that :netkit is on the debug classpath."
        }

        /** Clears the installed instance. Intended for tests. */
        fun clearInstance() {
            installed.set(null)
        }
    }
}
