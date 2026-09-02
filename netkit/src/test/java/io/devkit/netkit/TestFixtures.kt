package io.devkit.netkit

import io.devkit.netkit.config.NetKitDefaults
import io.devkit.netkit.history.InMemoryNetworkHistoryStore
import io.devkit.netkit.history.NetworkHistoryStore
import io.devkit.netkit.replay.ReplayEligibility
import io.devkit.netkit.replay.ReplayOverride
import io.devkit.netkit.replay.ReplayResult
import io.devkit.netkit.replay.ReplaySnapshotStore
import io.devkit.netkit.replay.ReplayUnavailableReason
import io.devkit.netkit.replay.RequestReplayer
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.pack.BuiltInScenarioPack
import io.devkit.netkit.scenario.persistence.InMemoryScenarioStorage
import io.devkit.netkit.scenario.persistence.JsonScenarioRepository
import io.devkit.netkit.scenario.persistence.ScenarioStorage
import io.devkit.netkit.scenario.runtime.ActiveNetworkConfiguration
import io.devkit.netkit.scenario.runtime.DefaultScenarioExecutionState
import io.devkit.netkit.scenario.runtime.ScenarioManager
import io.devkit.netkit.scenario.serialization.JsonScenarioSerializer
import io.devkit.netkit.state.DefaultNetKitController
import kotlinx.coroutines.CoroutineScope

/**
 * Shared construction for the pieces every suite needs.
 *
 * The 0.2 controller has real collaborators — a repository, a manager, a
 * replayer — and wiring them by hand in each test would make a signature change
 * a fifty-file edit. These helpers keep every suite building the same runtime
 * the production `NetKit.create` does, minus Android.
 */
internal object Fixtures {

    /** A rule that returns HTTP 500 on `GET /api/v1/bookings`. */
    fun rule(
        path: String = "/api/v1/bookings",
        method: HttpMethod = HttpMethod.GET,
        action: NetworkAction = NetworkAction.ReturnResponse(500),
        name: String? = null,
        enabled: Boolean = true,
    ): EndpointRule = EndpointRule.forPath(path, method, action, name, enabled)

    /** A saved scenario with the given rules. */
    fun scenario(
        name: String = "Test scenario",
        vararg rules: EndpointRule,
        global: io.devkit.netkit.scenario.GlobalNetworkConfig? = null,
    ): NetworkScenario = NetworkScenario(
        name = name,
        globalConfig = global,
        rules = rules.toList(),
    )

    /** A repository backed by memory, already loaded. */
    fun repository(storage: ScenarioStorage = InMemoryScenarioStorage()): JsonScenarioRepository =
        JsonScenarioRepository(storage)

    fun manager(
        scope: CoroutineScope,
        repository: JsonScenarioRepository = repository(),
        builtInPacks: List<BuiltInScenarioPack> = emptyList(),
        executionState: DefaultScenarioExecutionState = DefaultScenarioExecutionState(),
    ): ScenarioManager = ScenarioManager(
        repository = repository,
        serializer = JsonScenarioSerializer(),
        scope = scope,
        builtInPacks = builtInPacks,
        executionState = executionState,
    )

    /** A controller with real scenario support and a replayer that never sends. */
    fun controller(
        scope: CoroutineScope,
        initial: ActiveNetworkConfiguration = ActiveNetworkConfiguration.Default,
        history: NetworkHistoryStore = InMemoryNetworkHistoryStore(
            NetKitDefaults.MAX_HISTORY_ENTRIES,
        ),
        manager: ScenarioManager = manager(scope),
        replaySnapshots: ReplaySnapshotStore = ReplaySnapshotStore(),
    ): DefaultNetKitController = DefaultNetKitController(
        historyStore = history,
        manager = manager,
        replayer = NoOpReplayer,
        replaySnapshots = replaySnapshots,
        scope = scope,
        initialConfiguration = initial,
    )
}

/** A replayer for suites that never exercise replay. */
internal object NoOpReplayer : RequestReplayer {
    override fun eligibility(recordId: Long): ReplayEligibility =
        ReplayEligibility.Unavailable(ReplayUnavailableReason.DISABLED)

    override suspend fun replay(
        recordId: Long,
        override: ReplayOverride,
        bypassNetKit: Boolean,
    ): ReplayResult = ReplayResult.Unavailable(ReplayUnavailableReason.DISABLED)
}
