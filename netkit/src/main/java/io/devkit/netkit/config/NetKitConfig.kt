package io.devkit.netkit.config

import io.devkit.netkit.masking.DefaultSensitiveDataMasker
import io.devkit.netkit.masking.SensitiveDataMasker
import io.devkit.netkit.scenario.NetworkScenario

/**
 * Construction-time settings for one NetKit instance.
 *
 * Everything here is fixed for the lifetime of the instance. Anything a
 * developer or QA engineer changes while the app runs lives in
 * [io.devkit.netkit.state.NetKitController] instead.
 *
 * @param initialScenario the scenario NetKit starts with. Useful for wiring a
 *   known QA setup at launch; the debug UI can still change it afterwards.
 * @param maxHistoryEntries how many requests history keeps before evicting the
 *   oldest. Must be positive.
 * @param historyEnabled set false to skip history entirely, e.g. in a
 *   performance-sensitive build. The UI then shows an empty history tab.
 * @param captureBodies whether request/response body previews are captured.
 *   Bodies are only read when they are textual, buffered and smaller than
 *   [maxBodyPreviewBytes]; streaming and binary payloads are never consumed.
 * @param maxBodyPreviewBytes size cap for a single captured body preview.
 * @param masker redacts credentials before anything is stored. Replace it to add
 *   organisation-specific header or parameter names.
 * @param simulatedTimeoutDelayMillis how long a simulated timeout blocks before
 *   throwing. `null` — the default — makes NetKit wait for the OkHttp client's
 *   own connect/read timeout, which is the realistic behaviour. Tests should set
 *   a small value so they stay fast.
 */
data class NetKitConfig(
    val initialScenario: NetworkScenario = NetworkScenario.Default,
    val maxHistoryEntries: Int = NetKitDefaults.MAX_HISTORY_ENTRIES,
    val historyEnabled: Boolean = true,
    val captureBodies: Boolean = true,
    val maxBodyPreviewBytes: Long = NetKitDefaults.MAX_BODY_PREVIEW_BYTES,
    val masker: SensitiveDataMasker = DefaultSensitiveDataMasker(),
    val simulatedTimeoutDelayMillis: Long? = null,
) {
    init {
        require(maxHistoryEntries > 0) {
            "NetKit maxHistoryEntries must be positive (was $maxHistoryEntries)"
        }
        require(maxBodyPreviewBytes > 0) {
            "NetKit maxBodyPreviewBytes must be positive (was $maxBodyPreviewBytes)"
        }
        val timeoutDelay = simulatedTimeoutDelayMillis
        require(timeoutDelay == null || timeoutDelay >= 0) {
            "NetKit simulatedTimeoutDelayMillis cannot be negative (was $timeoutDelay)"
        }
    }
}
