package io.devkit.netkit.scenario.runtime

import io.devkit.netkit.scenario.SequenceCompletionBehavior
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** How far one response sequence has run, for the runtime indicator. */
data class SequenceProgress(
    /** How many requests this rule has already served. */
    val completed: Int,
    /** How many steps the sequence has. */
    val stepCount: Int,
) {
    /** The 1-based step the **next** request will get, or `null` once past the end. */
    val nextStepNumber: Int? get() = if (completed < stepCount) completed + 1 else null

    /** `2 / 3` — what the rule row shows. */
    val display: String get() = "${completed.coerceAtMost(stepCount)} / $stepCount"

    /** True when every step has been served at least once. */
    val isComplete: Boolean get() = completed >= stepCount
}

/**
 * The cursor layer for response sequences.
 *
 * Scenario **definitions** are immutable; a sequence still has to know that this
 * is the third request to `/api/v1/bookings`. That "how far along" lives here,
 * keyed on rule id, so the definition can be saved, exported and re-imported
 * without ever carrying a cursor with it.
 *
 * ### Reset points
 *
 * Progress is cleared when a scenario is activated or reactivated, when the
 * active scenario's definition is edited, when a rule or the whole state is
 * explicitly reset, and by `NetKitController.reset()`. It is **not** cleared by
 * merely opening the console or clearing history.
 *
 * Implementations are read and advanced from OkHttp's threads while the debug UI
 * resets them from the main thread, so every operation must be atomic.
 */
interface ScenarioExecutionState {

    /** Per-rule progress, for the UI. Emits on every advance and every reset. */
    val progress: StateFlow<Map<String, SequenceProgress>>

    /**
     * Claims the next step for [ruleId] and advances the cursor.
     *
     * @param stepCount how many steps the sequence has; must be positive.
     * @param completion what to do once the steps run out.
     * @return the 0-based step to run, or `-1` to pass the request through
     *   because a [SequenceCompletionBehavior.PASS_THROUGH] sequence is finished.
     */
    fun advance(ruleId: String, stepCount: Int, completion: SequenceCompletionBehavior): Int

    /** Progress for [ruleId] without advancing it. */
    fun peek(ruleId: String, stepCount: Int): SequenceProgress

    /** Restarts one rule's sequence from step 1. */
    fun reset(ruleId: String)

    /** Restarts every sequence. */
    fun resetAll()

    /** Forgets every rule not in [ruleIds]; called when the active scenario changes. */
    fun retainOnly(ruleIds: Set<String>)
}

/**
 * The execution state NetKit installs.
 *
 * Each rule gets one [AtomicInteger] and each advance is a single
 * `getAndIncrement`, so two concurrent requests to the same sequenced endpoint
 * always receive two different steps — never the same one twice, and never a
 * skipped one. The counter is clamped on read rather than on write, which keeps
 * the hot path to one atomic operation while still bounding the value.
 */
class DefaultScenarioExecutionState : ScenarioExecutionState {

    private val cursors = ConcurrentHashMap<String, AtomicInteger>()
    private val _progress = MutableStateFlow<Map<String, SequenceProgress>>(emptyMap())

    override val progress: StateFlow<Map<String, SequenceProgress>> = _progress.asStateFlow()

    override fun advance(
        ruleId: String,
        stepCount: Int,
        completion: SequenceCompletionBehavior,
    ): Int {
        require(stepCount > 0) { "NetKit sequence needs at least one step" }
        val cursor = cursors.computeIfAbsent(ruleId) { AtomicInteger(0) }

        // Clamp inside the CAS loop so a long-running LOOP or REPEAT_LAST rule
        // cannot drift towards Int.MAX_VALUE over a debugging session.
        val served = cursor.getAndUpdate { current ->
            when (completion) {
                SequenceCompletionBehavior.LOOP ->
                    if (current + 1 >= stepCount) 0 else current + 1

                SequenceCompletionBehavior.REPEAT_LAST,
                SequenceCompletionBehavior.PASS_THROUGH,
                -> if (current >= stepCount) current else current + 1
            }
        }

        publish(ruleId, stepCount)

        return when (completion) {
            SequenceCompletionBehavior.LOOP -> served % stepCount
            SequenceCompletionBehavior.REPEAT_LAST -> served.coerceAtMost(stepCount - 1)
            SequenceCompletionBehavior.PASS_THROUGH -> if (served >= stepCount) -1 else served
        }
    }

    override fun peek(ruleId: String, stepCount: Int): SequenceProgress = SequenceProgress(
        completed = cursors[ruleId]?.get() ?: 0,
        stepCount = stepCount,
    )

    override fun reset(ruleId: String) {
        cursors.remove(ruleId)
        _progress.update { current ->
            if (ruleId !in current) current else current - ruleId
        }
    }

    override fun resetAll() {
        cursors.clear()
        _progress.value = emptyMap()
    }

    override fun retainOnly(ruleIds: Set<String>) {
        cursors.keys.retainAll(ruleIds)
        _progress.update { current ->
            val kept = current.filterKeys { it in ruleIds }
            if (kept.size == current.size) current else kept
        }
    }

    private fun publish(ruleId: String, stepCount: Int) {
        val completed = cursors[ruleId]?.get() ?: return
        val next = SequenceProgress(completed, stepCount)
        _progress.update { current ->
            if (current[ruleId] == next) current else current + (ruleId to next)
        }
    }
}

/**
 * Execution state that never advances, installed when a NetKit instance has no
 * scenario support (for example a runtime built without persistence).
 */
object NoOpScenarioExecutionState : ScenarioExecutionState {
    private val empty = MutableStateFlow<Map<String, SequenceProgress>>(emptyMap())

    override val progress: StateFlow<Map<String, SequenceProgress>> = empty.asStateFlow()

    override fun advance(
        ruleId: String,
        stepCount: Int,
        completion: SequenceCompletionBehavior,
    ): Int = 0

    override fun peek(ruleId: String, stepCount: Int): SequenceProgress =
        SequenceProgress(0, stepCount)

    override fun reset(ruleId: String) = Unit
    override fun resetAll() = Unit
    override fun retainOnly(ruleIds: Set<String>) = Unit
}
