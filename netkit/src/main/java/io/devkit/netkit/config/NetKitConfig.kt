package io.devkit.netkit.config

import io.devkit.netkit.masking.DefaultSensitiveDataMasker
import io.devkit.netkit.masking.SensitiveDataMasker
import io.devkit.netkit.scenario.pack.BuiltInScenarioPack
import io.devkit.netkit.scenario.persistence.ScenarioStorage
import io.devkit.netkit.scenario.runtime.ActiveNetworkConfiguration
import okhttp3.OkHttpClient

/**
 * Construction-time settings for one NetKit instance.
 *
 * Everything here is fixed for the lifetime of the instance. Anything a
 * developer or QA engineer changes while the app runs lives in
 * [io.devkit.netkit.state.NetKitController] instead.
 *
 * @param initialConfiguration the temporary setup NetKit starts with. Useful for
 *   wiring a known QA state at launch; the debug UI can still change it. Renamed
 *   from 0.1's `initialScenario`, because "scenario" now means a saved, named
 *   definition — see the module README's migration notes.
 * @param builtInPacks scenario packs declared in application code. They are
 *   never written to storage: they are re-declared on every launch, activated or
 *   duplicated by the user, and never edited in place.
 * @param scenarioStorage where saved scenarios live. Defaults to an in-memory
 *   store, so scenarios work without a `Context` but do not survive a restart;
 *   pass `DataStoreScenarioStorage(context)` — or use `NetKit.create(context)` —
 *   for persistence.
 * @param maxHistoryEntries how many requests history keeps before evicting the
 *   oldest. Must be positive.
 * @param historyEnabled set false to skip history entirely, e.g. in a
 *   performance-sensitive build. The UI then shows an empty history tab.
 * @param replayEnabled whether NetKit keeps the in-memory snapshots that make
 *   replay possible. Snapshots hold the *unmasked* request, so an app that never
 *   wants a credential held in memory longer than the call itself can turn this
 *   off; the history detail then says replay is disabled.
 * @param maxReplaySnapshots how many recent requests stay replayable.
 * @param replayClientFactory builds the client replays go through. `null` — the
 *   default — makes NetKit build a plain client with its own interceptor
 *   installed, so active scenarios apply to a replay. Supply your own to reuse
 *   the application's TLS, DNS or auth configuration.
 * @param captureBodies whether request/response body previews are captured.
 *   Bodies are only read when they are textual, buffered and smaller than
 *   [maxBodyPreviewBytes]; streaming and binary payloads are never consumed.
 * @param maxBodyPreviewBytes size cap for a single captured body preview.
 * @param masker redacts credentials before anything is stored. Replace it to add
 *   organisation-specific header or parameter names.
 * @param timelineEnabled whether the execution timeline records scenario
 *   decisions. On by default: it is the thing that turns "it failed sometimes"
 *   into "it failed on evaluation #17", and it is bounded and in-memory only.
 *   Turn it off in a build where even a bounded per-request append matters.
 * @param maxExecutionEvents how many timeline events are kept before the oldest
 *   are evicted. Must be positive.
 * @param simulatedTimeoutDelayMillis how long a simulated timeout blocks before
 *   throwing. `null` — the default — makes NetKit wait for the OkHttp client's
 *   own connect/read timeout, which is the realistic behaviour. Tests should set
 *   a small value so they stay fast.
 */
data class NetKitConfig(
    val initialConfiguration: ActiveNetworkConfiguration = ActiveNetworkConfiguration.Default,
    val builtInPacks: List<BuiltInScenarioPack> = emptyList(),
    val scenarioStorage: ScenarioStorage? = null,
    val maxHistoryEntries: Int = NetKitDefaults.MAX_HISTORY_ENTRIES,
    val historyEnabled: Boolean = true,
    val replayEnabled: Boolean = true,
    val maxReplaySnapshots: Int = NetKitLimits.MAX_REPLAY_SNAPSHOTS,
    val replayClientFactory: (() -> OkHttpClient)? = null,
    val captureBodies: Boolean = true,
    val maxBodyPreviewBytes: Long = NetKitDefaults.MAX_BODY_PREVIEW_BYTES,
    val masker: SensitiveDataMasker = DefaultSensitiveDataMasker(),
    val timelineEnabled: Boolean = true,
    val maxExecutionEvents: Int = NetKitLimits.MAX_EXECUTION_EVENTS,
    val simulatedTimeoutDelayMillis: Long? = null,
) {
    init {
        require(maxExecutionEvents > 0) {
            "NetKit maxExecutionEvents must be positive (was $maxExecutionEvents)"
        }
        require(maxHistoryEntries > 0) {
            "NetKit maxHistoryEntries must be positive (was $maxHistoryEntries)"
        }
        require(maxBodyPreviewBytes > 0) {
            "NetKit maxBodyPreviewBytes must be positive (was $maxBodyPreviewBytes)"
        }
        require(maxReplaySnapshots > 0) {
            "NetKit maxReplaySnapshots must be positive (was $maxReplaySnapshots)"
        }
        val timeoutDelay = simulatedTimeoutDelayMillis
        require(timeoutDelay == null || timeoutDelay >= 0) {
            "NetKit simulatedTimeoutDelayMillis cannot be negative (was $timeoutDelay)"
        }
    }
}
