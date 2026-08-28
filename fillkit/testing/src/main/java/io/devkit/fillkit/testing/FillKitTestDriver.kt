package io.devkit.fillkit.testing

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performSemanticsAction
import io.devkit.fillkit.FillActivationRequest
import io.devkit.fillkit.FillActivationResult
import io.devkit.fillkit.FillActivationSource
import io.devkit.fillkit.FillKitCommand
import io.devkit.fillkit.FillKitFormSnapshot
import io.devkit.fillkit.FillKitTestSemantics
import io.devkit.fillkit.FillReproductionSpec
import io.devkit.fillkit.FillReproductionTokenCodec
import io.devkit.fillkit.FillSeed
import io.devkit.fillkit.appliedSpec
import io.devkit.fillkit.toActivationRequest

/**
 * Stable facade for driving FillKit from a Compose UI test.
 *
 * Tests never open the developer panel and never touch the debug runtime: every
 * operation goes through the semantics control node the debug runtime publishes,
 * so FillKit's internals can change without breaking consumer tests.
 */
class FillKitTestDriver internal constructor(
    private val rule: ComposeTestRule,
    val formId: String,
) {
    private var request = FillActivationRequest(formId = formId, source = FillActivationSource.Test)
    private var lastResult: FillActivationResult? = null

    /** Named arguments allow the `scenario(pack = ..., id = ...)` reading order too. */
    fun scenario(id: String, pack: String? = null): FillKitTestDriver = apply {
        request = request.copy(scenarioId = id, scenarioPackId = pack)
    }

    fun persona(id: String, pack: String? = null): FillKitTestDriver = apply {
        request = request.copy(personaId = id, personaPackId = pack)
    }

    fun randomPersona(): FillKitTestDriver = apply { request = request.copy(personaId = null, personaPackId = null) }

    fun useLocale(tag: String): FillKitTestDriver = apply { request = request.copy(locale = tag) }

    fun seed(value: Long): FillKitTestDriver = apply { request = request.copy(seed = value) }

    fun seed(value: FillSeed): FillKitTestDriver = seed(value.value)

    fun generation(value: Int): FillKitTestDriver = apply { request = request.copy(generation = value) }

    fun fingerprint(value: String?): FillKitTestDriver = apply { request = request.copy(configurationFingerprint = value) }

    /** Applies the state without filling, for tests that assert on empty fields. */
    fun withoutFilling(): FillKitTestDriver = apply { request = request.copy(fill = false) }

    fun reproduction(spec: FillReproductionSpec): FillKitTestDriver = apply {
        request = spec.toActivationRequest(FillActivationSource.Test)
    }

    fun reproductionToken(token: String): FillKitTestDriver =
        reproduction(FillReproductionTokenCodec.decode(token))

    /** Applies everything configured so far and waits for the resulting UI state. */
    fun activate(): FillActivationResult {
        val results = mutableListOf<FillActivationResult>()
        control().performSemanticsAction(FillKitTestSemantics.Activate) { action -> action(request, results) }
        rule.waitForIdle()
        val result = results.firstOrNull() ?: error("the FillKit host for \"$formId\" did not answer the activation")
        result.appliedSpec?.let(FillKitTestReporter::record)
        lastResult = result
        return result
    }

    fun fillAll(): FillKitTestDriver = command(FillKitCommand.FillAll)
    fun clearAll(): FillKitTestDriver = command(FillKitCommand.ClearAll)
    fun regenerateAll(): FillKitTestDriver = command(FillKitCommand.RegenerateAll)
    fun selectRandomPersona(): FillKitTestDriver = command(FillKitCommand.SelectRandomPersona)
    fun fill(fieldId: String): FillKitTestDriver = command(FillKitCommand.Fill(fieldId))
    fun clear(fieldId: String): FillKitTestDriver = command(FillKitCommand.Clear(fieldId))
    fun selectPersona(personaId: String): FillKitTestDriver = command(FillKitCommand.SelectPersona(personaId))
    fun setLocale(tag: String): FillKitTestDriver = command(FillKitCommand.SetLocale(tag))
    fun setSeed(seed: Long, generation: Int = 0): FillKitTestDriver =
        command(FillKitCommand.SetSeed(seed, generation))
    fun applyScenario(scenarioId: String): FillKitTestDriver = command(FillKitCommand.ApplyScenario(scenarioId))

    fun snapshot(): FillKitFormSnapshot = control().fillKitSnapshot().also { FillKitTestReporter.record(it.reproduction) }

    fun currentReproduction(): FillReproductionSpec = snapshot().reproduction

    fun currentReproductionToken(): String? = snapshot().reproductionToken

    fun registeredFieldIds(): List<String> = snapshot().fieldIds

    internal fun activateIfNeeded(): FillActivationResult = lastResult ?: activate()

    private fun command(command: FillKitCommand): FillKitTestDriver = apply {
        var accepted = false
        control().performSemanticsAction(FillKitTestSemantics.Command) { action -> accepted = action(command) }
        rule.waitForIdle()
        check(accepted) { "FillKit rejected $command on form \"$formId\"" }
    }

    private fun control() = (rule as SemanticsNodeInteractionsProvider).onFillKitForm(formId)
}

/** Chainable entry point: `composeRule.fillKit("checkout").scenario(...).seed(...).activate()`. */
fun ComposeTestRule.fillKit(formId: String): FillKitTestDriver = FillKitTestDriver(this, formId)

/**
 * Block entry point. The block activates explicitly, or the activation happens
 * once the block returns; either way the helper waits for Compose to settle.
 */
fun ComposeTestRule.fillKit(formId: String, block: FillKitTestDriver.() -> Unit): FillActivationResult {
    val driver = FillKitTestDriver(this, formId)
    driver.block()
    return driver.activateIfNeeded()
}

fun ComposeTestRule.fillKit(reproduction: FillReproductionSpec): FillActivationResult =
    FillKitTestDriver(this, reproduction.formId).reproduction(reproduction).activate()

/** Turns a reproduction token straight from a bug report into a regression test. */
fun ComposeTestRule.applyFillKitReproduction(token: String): FillActivationResult =
    fillKit(FillReproductionTokenCodec.decode(token))

fun ComposeTestRule.applyFillKitReproduction(spec: FillReproductionSpec): FillActivationResult = fillKit(spec)

/** `fillKitScenario { form(...); scenario(...); seed(...) }` for readable test setup. */
fun ComposeTestRule.fillKitScenario(block: FillKitScenarioBuilder.() -> Unit): FillActivationResult {
    val builder = FillKitScenarioBuilder().apply(block)
    val driver = FillKitTestDriver(this, builder.requireForm())
    builder.applyTo(driver)
    return driver.activate()
}

class FillKitScenarioBuilder internal constructor() {
    private var formId: String? = null
    private var scenarioId: String? = null
    private var scenarioPack: String? = null
    private var personaId: String? = null
    private var locale: String? = null
    private var seed: Long? = null
    private var generation: Int = 0

    fun form(id: String) { formId = id }
    fun scenario(id: String) { scenarioId = id }
    fun scenario(pack: String, id: String) { scenarioPack = pack; scenarioId = id }
    fun persona(id: String) { personaId = id }
    fun locale(tag: String) { locale = tag }
    fun seed(value: Long) { seed = value }
    fun generation(value: Int) { generation = value }

    internal fun requireForm(): String = requireNotNull(formId) { "fillKitScenario requires form(\"...\")" }

    internal fun applyTo(driver: FillKitTestDriver) {
        scenarioId?.let { driver.scenario(it, scenarioPack) }
        personaId?.let { driver.persona(it) }
        locale?.let { driver.useLocale(it) }
        seed?.let { driver.seed(it) }
        if (generation != 0) driver.generation(generation)
    }
}
