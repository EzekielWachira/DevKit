package io.devkit.netkit.engine

import io.devkit.netkit.scenario.NetworkScenario
import io.devkit.netkit.scenario.RequestTarget

/**
 * Decides what happens to each request.
 *
 * The engine is the only component the interceptor talks to, and it is the seam
 * a future Android Studio bridge or automation API plugs into: everything above
 * it manipulates state, everything below it executes decisions.
 *
 * Implementations must be safe to call concurrently from OkHttp threads.
 */
interface NetworkScenarioEngine {

    /**
     * True when no configured behaviour could affect any request. The
     * interceptor uses this to keep the untouched path cheap.
     */
    val isIdle: Boolean

    /** Decides what to do with [target]. Must not throw. */
    fun evaluate(target: RequestTarget): ScenarioDecision
}

/**
 * The engine NetKit installs. Reads the current scenario through [scenarioProvider],
 * which is backed by the controller's `StateFlow`.
 *
 * Each call reads the provider exactly once, so a request always evaluates
 * against a single consistent snapshot even if the debug UI changes the
 * scenario mid-evaluation.
 *
 * @param scenarioProvider supplies the latest immutable scenario snapshot.
 */
class DefaultNetworkScenarioEngine(
    private val scenarioProvider: () -> NetworkScenario,
) : NetworkScenarioEngine {

    override val isIdle: Boolean get() = scenarioProvider().isIdle

    override fun evaluate(target: RequestTarget): ScenarioDecision =
        ScenarioEvaluator.evaluate(scenarioProvider(), target)
}
