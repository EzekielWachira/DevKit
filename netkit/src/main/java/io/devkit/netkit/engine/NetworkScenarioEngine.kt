package io.devkit.netkit.engine

import io.devkit.netkit.scenario.RequestTarget
import io.devkit.netkit.scenario.runtime.ActiveNetworkConfiguration
import io.devkit.netkit.scenario.runtime.RequestInspection
import io.devkit.netkit.scenario.runtime.ScenarioExecutionState
import io.devkit.netkit.scenario.run.ScenarioRunManager

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

    /**
     * What the transport must capture from each request before calling
     * [evaluate].
     *
     * Read per request, so it must be cheap. A configuration with no conditions
     * reports [RequestInspection.Minimal] and the interceptor skips building
     * header lists and body sources entirely.
     */
    val inspection: RequestInspection

    /** Decides what to do with [target]. Must not throw. */
    fun evaluate(target: RequestTarget): ScenarioDecision
}

/**
 * The engine NetKit installs.
 *
 * Reads the current configuration through [configurationProvider], which is
 * backed by the controller's `StateFlow` — a plain volatile read, never a
 * `Flow` collection or a storage lookup, so an active scenario costs the same
 * per request as a temporary rule did in 0.1.
 *
 * Each call reads the provider exactly once, so a request always evaluates
 * against a single consistent snapshot even if the debug UI changes the
 * configuration mid-evaluation.
 *
 * ### The evaluation index
 *
 * A non-idle evaluation claims an index from [runManager] before doing anything
 * else. That index is the anchor for every random draw the request makes and the
 * number a reproduction trace refers to. Idle evaluations claim nothing, so a
 * scenario that is switched on after ten thousand untouched requests still starts
 * at evaluation #1 — the index counts the run, not the process.
 *
 * @param executionState the cursor layer response sequences advance.
 * @param runManager the owner of seeds, counters and the timeline.
 * @param configurationProvider supplies the latest immutable snapshot.
 */
class DefaultNetworkScenarioEngine(
    private val executionState: ScenarioExecutionState,
    /**
     * The run this engine evaluates against.
     *
     * Defaulted to a private one so an engine can be constructed for a test or a
     * one-off harness without wiring the whole runtime. In an application this is
     * always the instance `NetKit.create` shares with the controller — an engine
     * with a run manager of its own would keep counters and a seed nobody could
     * read, which is worse than having none.
     */
    private val runManager: ScenarioRunManager = ScenarioRunManager(executionState),
    private val configurationProvider: () -> ActiveNetworkConfiguration,
) : NetworkScenarioEngine {

    override val isIdle: Boolean get() = configurationProvider().isIdle

    override val inspection: RequestInspection
        get() = configurationProvider().let { configuration ->
            if (configuration.isIdle) RequestInspection.Minimal else configuration.inspection
        }

    /**
     * Reads the provider **once** and both short-circuits and evaluates against
     * that one value. Asking `isIdle` and then `evaluate` would read twice, and
     * a request arriving as the console toggles the master switch could see one
     * answer from each.
     */
    override fun evaluate(target: RequestTarget): ScenarioDecision {
        val configuration = configurationProvider()
        if (configuration.isIdle) return IDLE

        val context = ScenarioExecutionContext(
            target = target,
            evaluationIndex = runManager.beginEvaluation(),
            runManager = runManager,
            timelineEnabled = runManager.timeline.enabled,
        )
        val decision = ScenarioEvaluator.evaluate(configuration, context, executionState)
        // One publish per request, covering both the rule funnel and the run
        // totals — see ScenarioExecutionContext for why they are batched.
        context.flush(decision)
        return decision
    }

    private companion object {
        /** The overwhelmingly common decision should not allocate. */
        val IDLE = ScenarioDecision.PassThrough()
    }
}
