package io.devkit.fillkit.testing

import io.devkit.fillkit.FillReproductionSpec
import io.devkit.fillkit.FillReproductionTokenCodec
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Remembers the last FillKit state a test activated, so a failure can name it. */
object FillKitTestReporter {
    @Volatile
    var lastReproduction: FillReproductionSpec? = null
        private set

    fun record(spec: FillReproductionSpec) {
        lastReproduction = spec
    }

    fun reset() {
        lastReproduction = null
    }

    fun report(): String? = lastReproduction?.let { spec ->
        spec.describe(FillReproductionTokenCodec.encodeOrNull(spec))
    }
}

/**
 * Optional rule. On failure it prints the reproduction the test was running, so
 * a red CI run already carries the token needed to reproduce it locally.
 */
class FillKitReproductionRule(
    private val sink: (String) -> Unit = ::println,
) : TestWatcher() {

    override fun starting(description: Description) = FillKitTestReporter.reset()

    override fun failed(e: Throwable?, description: Description) {
        val report = FillKitTestReporter.report() ?: return
        sink("\n${description.displayName} failed with:\n$report\n")
    }
}
