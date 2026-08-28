package io.devkit.fillkit.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import io.devkit.fillkit.FillActivationResult
import io.devkit.fillkit.FillKit
import io.devkit.fillkit.FillKitConfig
import io.devkit.fillkit.FillKitHost
import io.devkit.fillkit.FillReproductionTokenCodec
import io.devkit.fillkit.FillType
import io.devkit.fillkit.appliedSpec
import io.devkit.fillkit.fillKit
import io.devkit.fillkit.fillKitPack
import io.devkit.fillkit.fillPersona
import io.devkit.fillkit.personaPack
import io.devkit.fillkit.scenarioPack
import io.devkit.fillkit.debug.activation.FillKitActivationEngine
import io.devkit.fillkit.debug.runtime.DebugFillKitRuntime
import io.devkit.fillkit.runtime.FillKitRuntimeProvider
import io.devkit.fillkit.testing.applyFillKitReproduction
import io.devkit.fillkit.testing.assertFillKitType
import io.devkit.fillkit.testing.assertRegistered
import io.devkit.fillkit.testing.assertRegisteredFieldCount
import io.devkit.fillkit.testing.fillKit
import io.devkit.fillkit.testing.onFillKitField
import io.devkit.fillkit.testing.onFillKitForm
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Everything here runs without ever opening the FillKit developer panel. */
class FillKitTestingIntegrationTest {

    @get:Rule
    val compose = createComposeRule()

    private val returningCustomer = fillPersona("returning-customer", "Returning Customer") {
        firstName("Amina")
        lastName("Wanjiku")
        email("amina.wanjiku@example.com")
    }

    private val pack = fillKitPack("qa", "QA", version = 1) {
        personas(personaPack("people", "People") { persona(returningCustomer) })
        scenarios(
            scenarioPack("checkout", "Checkout", version = "2") {
                scenario(
                    id = "returning-customer",
                    name = "Returning Customer",
                    targetForm = "checkout",
                    tags = setOf("happy-path"),
                ) {
                    persona("returning-customer")
                    text("note", "scenario-note")
                }
            },
        )
    }

    private val config = FillKitConfig(showTrigger = false, packs = listOf(pack))

    private var email = ""
    private var name = ""
    private var note = ""

    @Before
    fun installRuntime() {
        FillKitRuntimeProvider.install(DebugFillKitRuntime)
        FillKitActivationEngine.reset()
        FillKit.resetDebugConfig()
        email = ""; name = ""; note = ""
    }

    @After
    fun tearDown() {
        FillKitActivationEngine.reset()
        FillKit.resetDebugConfig()
    }

    private fun setCheckoutContent() {
        compose.setContent {
            MaterialTheme {
                FillKitHost("checkout", modifier = Modifier.fillMaxSize(), config = config) {
                    Box(Modifier.fillKit("email", FillType.Email, email, onFill = { email = it }))
                    Box(Modifier.fillKit("fullName", FillType.FullName, name, onFill = { name = it }))
                    Box(Modifier.fillKit("note", FillType.Text(4, 12), note, onFill = { note = it }))
                }
            }
        }
    }

    @Test
    fun activatesAScenarioWithoutOpeningTheDeveloperPanel() {
        setCheckoutContent()
        val result = compose.fillKit("checkout") {
            scenario(pack = "checkout", id = "returning-customer")
            seed(845912)
        }
        assertTrue(result is FillActivationResult.Applied)
        compose.runOnIdle {
            assertEquals("amina.wanjiku@example.com", email)
            assertEquals("scenario-note", note)
        }
        compose.onAllNodesFillKitPanelAbsent()
    }

    @Test
    fun selectsAPersonaThroughTheTestApi() {
        setCheckoutContent()
        compose.fillKit("checkout").persona("returning-customer").activate()
        compose.runOnIdle { assertEquals("amina.wanjiku@example.com", email) }
        assertEquals("returning-customer", compose.fillKit("checkout").currentReproduction().personaId)
    }

    @Test
    fun setsTheSeedAndReproducesTheSameValues() {
        setCheckoutContent()
        compose.fillKit("checkout").seed(845912).activate()
        val first = email
        compose.fillKit("checkout").setSeed(112840)
        compose.runOnIdle { assertNotEquals(first, email) }
        compose.fillKit("checkout").setSeed(845912)
        compose.runOnIdle { assertEquals(first, email) }
    }

    @Test
    fun setsTheLocaleThroughTheTestApi() {
        setCheckoutContent()
        compose.fillKit("checkout").useLocale("en-KE").seed(845912).activate()
        val kenyan = email
        compose.fillKit("checkout").useLocale("en-GB").seed(845912).activate()
        compose.runOnIdle { assertNotEquals(kenyan, email) }
        assertEquals("en-GB", compose.fillKit("checkout").currentReproduction().locale)
    }

    @Test
    fun fillsAndClearsEveryField() {
        setCheckoutContent()
        compose.fillKit("checkout").fillAll()
        compose.runOnIdle { assertTrue(email.isNotEmpty() && name.isNotEmpty()) }
        compose.fillKit("checkout").clearAll()
        compose.runOnIdle { assertEquals("", email) }
    }

    @Test
    fun fillsAndClearsOneFieldById() {
        setCheckoutContent()
        compose.fillKit("checkout").fill("email")
        compose.runOnIdle {
            assertTrue(email.isNotEmpty())
            assertEquals("", name)
        }
        compose.fillKit("checkout").clear("email")
        compose.runOnIdle { assertEquals("", email) }
    }

    @Test
    fun regenerateAdvancesTheGenerationCounterDeterministically() {
        setCheckoutContent()
        compose.fillKit("checkout").seed(845912).activate()
        val first = email
        compose.fillKit("checkout").regenerateAll()
        val second = email
        assertNotEquals(first, second)
        assertEquals(1, compose.fillKit("checkout").currentReproduction().generation)
        compose.fillKit("checkout").setSeed(845912, generation = 1)
        compose.runOnIdle { assertEquals(second, email) }
    }

    @Test
    fun findsFieldsByFillKitIdRatherThanLabel() {
        setCheckoutContent()
        compose.onFillKitField("email").assertRegistered().assertFillKitType(FillType.Email)
        compose.onFillKitField("email", formId = "checkout").assertRegistered()
        compose.onFillKitForm("checkout").assertRegisteredFieldCount(3)
        assertEquals(listOf("email", "fullName", "note"), compose.fillKit("checkout").registeredFieldIds())
    }

    @Test
    fun theSameReproductionTokenRecreatesTheSameValues() {
        setCheckoutContent()
        compose.fillKit("checkout") {
            scenario(pack = "checkout", id = "returning-customer")
            seed(845912)
        }
        val token = requireNotNull(compose.fillKit("checkout").currentReproductionToken())
        val recorded = email to name

        compose.fillKit("checkout").setSeed(112840)
        compose.fillKit("checkout").selectRandomPersona()
        compose.runOnIdle { assertNotEquals(recorded, email to name) }

        val result = compose.applyFillKitReproduction(token)
        assertTrue(result is FillActivationResult.Applied)
        compose.runOnIdle { assertEquals(recorded, email to name) }
        assertEquals(FillReproductionTokenCodec.decode(token), result.appliedSpec)
    }

    @Test
    fun reportsAConfigurationMismatchWithoutRefusingToRun() {
        setCheckoutContent()
        val result = compose.fillKit("checkout").seed(845912).fingerprint("stale-fingerprint").activate()
        assertTrue(result is FillActivationResult.PartiallyApplied)
        assertTrue((result as FillActivationResult.PartiallyApplied).warnings.single().contains("stale-fingerprint"))
        compose.runOnIdle { assertTrue(email.isNotEmpty()) }
    }

    @Test
    fun rejectsUnknownScenariosAndPersonas() {
        setCheckoutContent()
        val scenario = compose.fillKit("checkout").scenario("does-not-exist").activate()
        assertTrue(scenario is FillActivationResult.Rejected)
        val persona = compose.fillKit("checkout").persona("nobody").activate()
        assertTrue(persona is FillActivationResult.Rejected)
    }

    @Test
    fun multipleHostsRemainIsolated() {
        var checkoutEmail = ""
        var profileEmail = ""
        compose.setContent {
            MaterialTheme {
                FillKitHost("checkout", config = FillKitConfig(showTrigger = false)) {
                    Box(Modifier.fillKit("email", FillType.Email, checkoutEmail, onFill = { checkoutEmail = it }))
                }
                FillKitHost("profile", config = FillKitConfig(showTrigger = false)) {
                    Box(Modifier.fillKit("email", FillType.Email, profileEmail, onFill = { profileEmail = it }))
                }
            }
        }
        compose.fillKit("checkout").seed(845912).activate()
        compose.runOnIdle {
            assertTrue(checkoutEmail.isNotEmpty())
            assertEquals("", profileEmail)
        }
        compose.fillKit("profile").seed(845912).activate()
        compose.runOnIdle { assertTrue(profileEmail.isNotEmpty()) }
        compose.onFillKitField("email", formId = "profile").assertRegistered()
    }

    // --- Pending activation --------------------------------------------------

    @Test
    fun aRequestForAFormThatIsNotComposedYetWaitsForIt() {
        var showCheckout by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                FillKitHost("profile", config = FillKitConfig(showTrigger = false)) { Box(Modifier) }
                if (showCheckout) {
                    FillKitHost("checkout", config = config) {
                        Box(Modifier.fillKit("email", FillType.Email, email, onFill = { email = it }))
                    }
                }
            }
        }
        val pending = compose.runOnIdle {
            FillKitActivationEngine.submit(
                io.devkit.fillkit.FillActivationRequest(formId = "checkout", seed = 845912L),
            )
        }
        assertTrue(pending is FillActivationResult.Pending)
        compose.runOnIdle { assertEquals("", email) }

        compose.runOnIdle { showCheckout = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(email.isNotEmpty())
            assertNull(FillKitActivationEngine.pending)
        }
    }

    @Test
    fun aPendingRequestIsConsumedOnlyOnce() {
        var showCheckout by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (showCheckout) {
                    FillKitHost("checkout", config = FillKitConfig(showTrigger = false)) {
                        Box(Modifier.fillKit("email", FillType.Email, email, onFill = { email = it }))
                    }
                }
            }
        }
        compose.runOnIdle { showCheckout = false }
        compose.waitForIdle()
        compose.runOnIdle {
            FillKitActivationEngine.submit(io.devkit.fillkit.FillActivationRequest("checkout", seed = 845912L))
        }
        compose.runOnIdle { showCheckout = true }
        compose.waitForIdle()
        val applied = email
        assertTrue(applied.isNotEmpty())

        compose.fillKit("checkout").clearAll()
        compose.runOnIdle { showCheckout = false }
        compose.waitForIdle()
        compose.runOnIdle { showCheckout = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("", email) }
    }

    @Test
    fun anExpiredPendingRequestIsDiscarded() {
        var showCheckout by mutableStateOf(false)
        // Long enough that submit() cannot expire its own request, short enough to await.
        FillKit.configureDebug { pendingActivationTtlMillis(TTL) }
        compose.setContent {
            MaterialTheme {
                if (showCheckout) {
                    FillKitHost("checkout", config = FillKitConfig(showTrigger = false)) {
                        Box(Modifier.fillKit("email", FillType.Email, email, onFill = { email = it }))
                    }
                }
            }
        }
        compose.runOnIdle {
            FillKitActivationEngine.submit(io.devkit.fillkit.FillActivationRequest("checkout", seed = 845912L))
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            FillKitActivationEngine.pending?.isExpired(System.currentTimeMillis(), TTL) == true
        }
        compose.runOnIdle { showCheckout = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("", email)
            assertNull(FillKitActivationEngine.pending)
        }
    }

    private companion object {
        const val TTL = 150L
    }

    @Test
    fun aNonTargetFormIgnoresAPendingRequest() {
        compose.setContent {
            MaterialTheme {
                FillKitHost("profile", config = FillKitConfig(showTrigger = false)) {
                    Box(Modifier.fillKit("email", FillType.Email, email, onFill = { email = it }))
                }
            }
        }
        compose.runOnIdle {
            FillKitActivationEngine.submit(io.devkit.fillkit.FillActivationRequest("checkout", seed = 845912L))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("", email)
            assertEquals("checkout", FillKitActivationEngine.pending?.request?.formId)
        }
    }
}

/** The developer panel must never be involved in a programmatic activation. */
private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesFillKitPanelAbsent() {
    onAllNodes(androidx.compose.ui.test.hasText("Fill All")).fetchSemanticsNodes().let {
        check(it.isEmpty()) { "the FillKit panel was opened by a programmatic activation" }
    }
}
