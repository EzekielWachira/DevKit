package io.devkit.netkit

import io.devkit.netkit.engine.DefaultNetworkScenarioEngine
import io.devkit.netkit.engine.ScenarioDecision
import io.devkit.netkit.scenario.RequestTarget
import io.devkit.netkit.scenario.condition.RequestBodyPeek
import io.devkit.netkit.scenario.condition.RequestBodySource
import io.devkit.netkit.scenario.runtime.ActiveNetworkConfiguration
import io.devkit.netkit.scenario.runtime.DefaultScenarioExecutionState
import io.devkit.netkit.scenario.run.ExecutionEvent
import io.devkit.netkit.scenario.run.ExecutionTimeline
import io.devkit.netkit.scenario.run.RunStartReason
import io.devkit.netkit.scenario.run.RuleStatistics
import io.devkit.netkit.scenario.run.ScenarioRun
import io.devkit.netkit.scenario.run.ScenarioRunManager
import java.util.concurrent.atomic.AtomicReference

/**
 * A whole NetKit decision pipeline with a fixed seed and no Android.
 *
 * The 0.3 suites test through this rather than against the evaluator directly,
 * because almost every property worth asserting spans several stages: that a
 * failed probability falls through to the next rule, that a rule's hit counter
 * advances at match time, that restarting on a seed reproduces a sequence. Wiring
 * those by hand in each test would be both noise and a place for the tests to
 * disagree with production about how the pieces fit.
 *
 * The seed defaults to a fixed value so **no test is ever flaky**: a suite that
 * generated its own seed would fail on some runs and pass on others, which is
 * precisely the disease 0.3 is the cure for.
 *
 * @param configuration the configuration to evaluate against.
 * @param seed the run seed.
 * @param recordTimeline whether the timeline records; on, since several suites
 *   assert on it.
 */
internal class EngineHarness(
    configuration: ActiveNetworkConfiguration,
    seed: Long = DEFAULT_SEED,
    recordTimeline: Boolean = true,
) {
    private val configurationRef = AtomicReference(configuration)

    val executionState = DefaultScenarioExecutionState()

    val runManager = ScenarioRunManager(
        executionState = executionState,
        timeline = ExecutionTimeline(enabled = recordTimeline),
    ).apply {
        startRun(
            scenarioId = null,
            scenarioName = "Test run",
            reason = RunStartReason.ACTIVATED,
            seed = seed,
        )
    }

    private val engine = DefaultNetworkScenarioEngine(
        executionState = executionState,
        runManager = runManager,
    ) { configurationRef.get() }

    /** Swaps the configuration, as the console does. */
    fun configure(configuration: ActiveNetworkConfiguration) {
        configurationRef.set(configuration)
    }

    /** Evaluates one request. */
    fun decide(
        method: String = "GET",
        path: String = "/api/v1/bookings",
        query: String? = null,
        host: String = "api.example.com",
        headers: List<Pair<String, String>> = emptyList(),
        body: RequestBodyPeek = RequestBodyPeek.Absent,
    ): ScenarioDecision = engine.evaluate(
        RequestTarget(
            method = method,
            scheme = "https",
            host = host,
            port = 443,
            path = path,
            encodedQuery = query,
            headers = headers,
            bodySource = RequestBodySource { body },
        ),
    )

    /** The status of each of [count] decisions, or `null` when nothing responded. */
    fun statuses(
        count: Int,
        method: String = "GET",
        path: String = "/api/v1/bookings",
        query: String? = null,
    ): List<Int?> = List(count) {
        (decide(method, path, query) as? ScenarioDecision.RespondWith)?.statusCode
    }

    /** The delay of each of [count] decisions, or `null` when nothing delayed. */
    fun delays(count: Int, path: String = "/api/v1/bookings"): List<Long?> = List(count) {
        (decide(path = path) as? ScenarioDecision.Delay)?.delayMillis
    }

    /** Restarts the run on the same seed, as the Run tab's button does. */
    fun restart(): ScenarioRun? = runManager.restartWithSameSeed()

    /** Restarts on [seed]. */
    fun restartWith(seed: Long): ScenarioRun? = runManager.restartWithSeed(seed)

    /** The run in progress. */
    val run: ScenarioRun? get() = runManager.run.value

    /** Statistics for one rule. */
    fun statisticsFor(ruleId: String): RuleStatistics? = runManager.ruleStatistics.value[ruleId]

    /** The timeline as of now. */
    val timeline: List<ExecutionEvent> get() = runManager.timeline.snapshot()

    companion object {
        /** Fixed on purpose. See the class documentation. */
        const val DEFAULT_SEED: Long = 843_921_773
    }
}
