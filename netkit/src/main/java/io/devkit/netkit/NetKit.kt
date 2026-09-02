package io.devkit.netkit

import io.devkit.netkit.config.NetKitConfig
import io.devkit.netkit.engine.DefaultNetworkScenarioEngine
import io.devkit.netkit.engine.NetworkScenarioEngine
import io.devkit.netkit.history.InMemoryNetworkHistoryStore
import io.devkit.netkit.history.NetworkHistoryStore
import io.devkit.netkit.history.NoOpNetworkHistoryStore
import io.devkit.netkit.interceptor.NetKitInterceptor
import io.devkit.netkit.replay.DefaultRequestReplayer
import io.devkit.netkit.replay.ReplaySnapshotStore
import io.devkit.netkit.replay.RequestReplayer
import io.devkit.netkit.scenario.persistence.InMemoryScenarioStorage
import io.devkit.netkit.scenario.persistence.JsonScenarioRepository
import io.devkit.netkit.scenario.persistence.ScenarioRepository
import io.devkit.netkit.scenario.runtime.ActiveNetworkConfiguration
import io.devkit.netkit.scenario.runtime.DefaultScenarioExecutionState
import io.devkit.netkit.scenario.runtime.ScenarioManager
import io.devkit.netkit.scenario.serialization.JsonScenarioSerializer
import io.devkit.netkit.scenario.serialization.ScenarioSerializer
import io.devkit.netkit.state.DefaultNetKitController
import io.devkit.netkit.state.NetKitController
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicReference

/**
 * A NetKit runtime: one controller, one engine, one interceptor, one history,
 * one scenario store.
 *
 * NetKit is **debug/QA tooling**. Depend on it with `debugImplementation` so it
 * never reaches a release build; see the module README for the recommended
 * source-set layout that keeps release code free of NetKit references.
 *
 * ### Setup
 *
 * ```kotlin
 * val netKit = NetKit.create(context)
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
 * ```text
 * 1. NetKit disabled                → the request passes through
 * 2. temporary endpoint override    → wins
 * 3. active saved scenario rule     → next
 * 4. active saved scenario global   → next, when the scenario sets one
 * 5. temporary global configuration → next
 * 6. otherwise                      → pass through
 * ```
 *
 * A matching rule fully replaces global behaviour — global latency is *not*
 * added on top — so a rule pinned to `PassThrough` acts as an allow-list entry.
 *
 * ### Threading
 *
 * A NetKit instance is safe to create once and share. The controller accepts
 * calls from any thread; the interceptor runs on OkHttp's threads and reads a
 * single immutable configuration snapshot per request. Scenario persistence
 * happens on [scope], never on an OkHttp thread.
 *
 * @property config the immutable settings this instance was created with.
 * @property controller the only supported way to change NetKit at runtime.
 * @property historyStore the record store backing [NetKitController.history].
 * @property engine the decision layer; exposed for tests and future transports.
 * @property interceptor the OkHttp interceptor to install on your client.
 * @property repository saved scenarios and packs.
 * @property serializer the `.netkit.json` reader and writer.
 * @property replaySnapshots in-memory replay data; `null` when replay is off.
 * @property scope where NetKit's own coroutines run.
 */
class NetKit private constructor(
    val config: NetKitConfig,
    val controller: NetKitController,
    val historyStore: NetworkHistoryStore,
    val engine: NetworkScenarioEngine,
    val interceptor: Interceptor,
    val repository: ScenarioRepository,
    val serializer: ScenarioSerializer,
    val manager: ScenarioManager,
    val replayer: RequestReplayer,
    val replaySnapshots: ReplaySnapshotStore?,
    val scope: CoroutineScope,
    private val ownedReplayClient: OkHttpClient?,
) {

    /**
     * Releases this runtime's coroutines.
     *
     * Rarely needed in an application — a debug runtime normally lives as long
     * as the process — but tests that create many instances should call it.
     * Installed clients keep working; nothing else observes scenario state
     * afterwards.
     */
    fun shutdown() {
        scope.cancel()
        replaySnapshots?.clear()
        // Only the client NetKit built itself: a caller-supplied one via
        // `replayClientFactory` belongs to the caller.
        ownedReplayClient?.let { client ->
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    companion object {

        private val installed = AtomicReference<NetKit?>(null)

        /**
         * Creates an independent NetKit runtime.
         *
         * Prefer this in applications with dependency injection: hold the
         * instance wherever you build your `OkHttpClient` and hand
         * [controller] to your debug UI. Nothing is registered globally.
         *
         * Without a `scenarioStorage` in [config], saved scenarios live in
         * memory only. Use `NetKit.create(context)` for scenarios that survive a
         * restart.
         */
        fun create(config: NetKitConfig = NetKitConfig()): NetKit {
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default + CoroutineName("NetKit"),
            )

            val history = if (config.historyEnabled) {
                InMemoryNetworkHistoryStore(config.maxHistoryEntries)
            } else {
                NoOpNetworkHistoryStore
            }
            val replaySnapshots = if (config.replayEnabled) {
                ReplaySnapshotStore(config.maxReplaySnapshots)
            } else {
                null
            }

            val serializer = JsonScenarioSerializer()
            val repository = JsonScenarioRepository(
                storage = config.scenarioStorage ?: InMemoryScenarioStorage(),
            )
            val executionState = DefaultScenarioExecutionState()
            val manager = ScenarioManager(
                repository = repository,
                serializer = serializer,
                scope = scope,
                builtInPacks = config.builtInPacks,
                executionState = executionState,
            )

            // The engine needs the controller's configuration and the controller
            // needs the interceptor's replayer, so one of the two has to be
            // supplied late. An AtomicReference rather than a captured `lateinit`
            // local: the engine reads this from OkHttp threads, and a plain
            // captured var would be a data race with no happens-before edge.
            val controllerRef = AtomicReference<DefaultNetKitController?>(null)
            val engine = DefaultNetworkScenarioEngine(executionState) {
                controllerRef.get()?.configuration ?: ActiveNetworkConfiguration.Default
            }
            val interceptor = NetKitInterceptor(
                engine = engine,
                history = history,
                config = config,
                replaySnapshots = replaySnapshots,
            )

            // Built eagerly only when NetKit owns it, so `shutdown()` has
            // something concrete to release. A caller-supplied factory stays lazy
            // and stays the caller's to close.
            val ownedReplayClient: OkHttpClient? = if (config.replayClientFactory == null) {
                OkHttpClient.Builder().addInterceptor(interceptor).build()
            } else {
                null
            }
            val replayClient: OkHttpClient by lazy {
                ownedReplayClient ?: config.replayClientFactory!!.invoke()
            }
            val replayer = DefaultRequestReplayer(
                snapshots = replaySnapshots ?: ReplaySnapshotStore(1),
                history = history,
                clientProvider = { replayClient },
                enabled = config.replayEnabled,
            )

            val controller = DefaultNetKitController(
                historyStore = history,
                manager = manager,
                replayer = replayer,
                replaySnapshots = replaySnapshots,
                scope = scope,
                initialConfiguration = config.initialConfiguration,
            )
            controllerRef.set(controller)

            // Reading saved scenarios must never block the caller: an app that
            // creates NetKit in Application.onCreate should not wait on a disk
            // read before its first frame.
            scope.launch { manager.load() }

            return NetKit(
                config = config,
                controller = controller,
                historyStore = history,
                engine = engine,
                interceptor = interceptor,
                repository = repository,
                serializer = serializer,
                manager = manager,
                replayer = replayer,
                replaySnapshots = replaySnapshots,
                scope = scope,
                ownedReplayClient = ownedReplayClient,
            )
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
