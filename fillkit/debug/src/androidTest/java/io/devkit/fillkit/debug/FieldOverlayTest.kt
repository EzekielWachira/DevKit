package io.devkit.fillkit.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import io.devkit.fillkit.FieldOverlayBehavior
import io.devkit.fillkit.FieldOverlayConfig
import io.devkit.fillkit.FieldOverlayMode
import io.devkit.fillkit.FillKitConfig
import io.devkit.fillkit.FillKitHost
import io.devkit.fillkit.FillKitOverlayAction
import io.devkit.fillkit.FillReproductionTokenCodec
import io.devkit.fillkit.FillType
import io.devkit.fillkit.fillKit
import io.devkit.fillkit.fillKitPack
import io.devkit.fillkit.fillPersona
import io.devkit.fillkit.personaPack
import io.devkit.fillkit.scenarioPack
import io.devkit.fillkit.debug.runtime.DebugFillKitRuntime
import io.devkit.fillkit.runtime.FillKitRuntimeProvider
import io.devkit.fillkit.testing.assertOverlayModified
import io.devkit.fillkit.testing.assertOverlayPreview
import io.devkit.fillkit.testing.assertOverlayPreviewMatches
import io.devkit.fillkit.testing.fillKit
import io.devkit.fillkit.testing.onFillKitFieldOverlay
import io.devkit.fillkit.testing.onFillKitOverlayAction
import io.devkit.fillkit.testing.performFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The contextual field overlay: appearance, placement, actions and reproducibility. */
class FieldOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    private val pack = fillKitPack("overlay", "Overlay", version = 1) {
        personas(
            personaPack("people", "People") {
                persona(
                    fillPersona("pinned", "Pinned") {
                        firstName("Amina")
                        lastName("Wanjiku")
                    },
                )
            },
        )
        scenarios(
            scenarioPack("checkout", "Checkout") {
                scenario("pinned-phone", "Pinned Phone", targetForm = "form") { text("phone", "+254700000111") }
            },
        )
    }

    private var firstName by mutableStateOf("")
    private var phone by mutableStateOf("")
    private var password by mutableStateOf("")
    private lateinit var email: TextFieldState

    @Before
    fun installRuntime() {
        FillKitRuntimeProvider.install(DebugFillKitRuntime)
        firstName = ""; phone = ""; password = ""
    }

    private fun setContent(
        overlay: FieldOverlayConfig = FieldOverlayConfig(),
        phoneOverlay: FieldOverlayBehavior = FieldOverlayBehavior.Default,
    ) {
        compose.setContent {
            MaterialTheme {
                email = rememberTextFieldState()
                FillKitHost(
                    formId = "form",
                    modifier = Modifier.fillMaxSize(),
                    config = FillKitConfig(
                        showTrigger = false,
                        seed = 845912L,
                        packs = listOf(pack),
                        fieldOverlay = overlay,
                    ),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        OutlinedTextField(
                            value = firstName,
                            onValueChange = { firstName = it },
                            label = { Text("First name") },
                            modifier = Modifier.fillMaxWidth().testTag("firstName-field")
                                .fillKit("firstName", FillType.FirstName, firstName, { firstName = it }),
                        )
                        OutlinedTextField(
                            state = email,
                            label = { Text("Email") },
                            modifier = Modifier.fillMaxWidth().testTag("email-field")
                                .fillKit(id = "email", type = FillType.Email, state = email),
                        )
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text("Phone") },
                            modifier = Modifier.fillMaxWidth().testTag("phone-field")
                                .fillKit(
                                    id = "phone",
                                    type = FillType.PhoneNumber("KE"),
                                    value = phone,
                                    onFill = { phone = it },
                                    overlay = phoneOverlay,
                                ),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            modifier = Modifier.fillMaxWidth().testTag("password-field")
                                .fillKit(
                                    "password",
                                    FillType.Password(minLength = 12),
                                    password,
                                    { password = it },
                                ),
                        )
                    }
                }
            }
        }
    }

    private fun focus(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    // --- Lifecycle -----------------------------------------------------------

    @Test
    fun focusingARegisteredFieldShowsTheOverlay() {
        setContent()
        compose.onAllNodesFillKitOverlayCount(0)
        focus("firstName-field")
        compose.onFillKitFieldOverlay("firstName").assertIsDisplayed()
    }

    @Test
    fun movingFocusUpdatesTheOverlay() {
        setContent()
        focus("firstName-field")
        compose.onFillKitFieldOverlay("firstName").assertExists()
        focus("phone-field")
        compose.onFillKitFieldOverlay("phone").assertExists()
        compose.onFillKitFieldOverlay("firstName").assertDoesNotExist()
    }

    @Test
    fun removingTheFieldFromCompositionRemovesTheOverlay() {
        var visible by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                FillKitHost("form", config = FillKitConfig(showTrigger = false, seed = 845912L)) {
                    if (visible) {
                        OutlinedTextField(
                            value = firstName,
                            onValueChange = { firstName = it },
                            modifier = Modifier.testTag("firstName-field")
                                .fillKit("firstName", FillType.FirstName, firstName, { firstName = it }),
                        )
                    }
                }
            }
        }
        focus("firstName-field")
        compose.onFillKitFieldOverlay("firstName").assertExists()
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.onFillKitFieldOverlay("firstName").assertDoesNotExist()
    }

    @Test
    fun theOverlayCanBeDisabledForTheWholeForm() {
        setContent(overlay = FieldOverlayConfig(enabled = false))
        focus("firstName-field")
        compose.onFillKitFieldOverlay("firstName").assertDoesNotExist()
    }

    @Test
    fun theOverlayCanBeDisabledForOneField() {
        setContent(
            overlay = FieldOverlayConfig(mode = FieldOverlayMode.FocusedField),
            phoneOverlay = FieldOverlayBehavior.Disabled,
        )
        focus("phone-field")
        compose.onFillKitFieldOverlay("phone").assertDoesNotExist()
        focus("firstName-field")
        compose.onFillKitFieldOverlay("firstName").assertExists()
    }

    // --- Actions -------------------------------------------------------------

    @Test
    fun tappingTheSuggestionFillsOnlyThatFieldAndKeepsFocus() {
        setContent()
        focus("phone-field")
        compose.onFillKitFieldOverlay("phone").performFill()
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue("phone should be filled", phone.startsWith("+254"))
            assertEquals("", firstName)
            assertEquals("", email.text.toString())
        }
        compose.onNodeWithTag("phone-field").assertIsFocused()
        compose.onFillKitFieldOverlay("phone").assertExists()
    }

    @Test
    fun fillingWorksForATextFieldStateField() {
        setContent()
        focus("email-field")
        compose.onFillKitFieldOverlay("email").performFill()
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(email.text.toString().contains('@')) }
        compose.onNodeWithTag("email-field").assertIsFocused()
    }

    @Test
    fun anotherRegeneratesOnlyTheFocusedField() {
        setContent()
        compose.fillKit("form").fillAll()
        val nameBefore = firstName
        val emailBefore = email.text.toString()
        focus("phone-field")
        val phoneBefore = phone

        compose.onFillKitOverlayAction("phone", FillKitOverlayAction.Regenerate).performClick()
        compose.waitForIdle()

        compose.runOnIdle {
            assertNotEquals(phoneBefore, phone)
            assertEquals(nameBefore, firstName)
            assertEquals(emailBefore, email.text.toString())
        }
    }

    @Test
    fun regenerationIsDeterministicForAGivenSeed() {
        setContent()
        focus("phone-field")
        compose.onFillKitOverlayAction("phone", FillKitOverlayAction.Regenerate).performClick()
        compose.waitForIdle()
        val rerolled = phone

        compose.fillKit("form").setSeed(845912)
        compose.runOnIdle { assertNotEquals(rerolled, phone) }

        focus("phone-field")
        compose.onFillKitOverlayAction("phone", FillKitOverlayAction.Regenerate).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(rerolled, phone) }
    }

    @Test
    fun theInspectorOpensFromTheOverlay() {
        setContent()
        focus("firstName-field")
        compose.onFillKitOverlayAction("firstName", FillKitOverlayAction.Inspect).performClick()
        compose.waitForIdle()
        compose.onNodeWithTextExists("Field regenerations")
    }

    // --- Content -------------------------------------------------------------

    @Test
    fun previewsFollowTheActivePersonaCoherently() {
        setContent()
        compose.fillKit("form").persona("pinned").activate()
        focus("firstName-field")
        compose.onFillKitFieldOverlay("firstName").assertOverlayPreview("Amina")
        focus("email-field")
        compose.onFillKitFieldOverlay("email").assertOverlayPreview("amina.wanjiku@example.com")
    }

    @Test
    fun aScenarioPinnedValueIsWhatTheOverlayOffers() {
        setContent()
        compose.fillKit("form").scenario("pinned-phone", pack = "checkout").activate()
        focus("phone-field")
        compose.onFillKitFieldOverlay("phone").assertOverlayPreview("+254700000111")
    }

    @Test
    fun passwordPreviewsAreMasked() {
        setContent()
        focus("password-field")
        compose.onFillKitFieldOverlay("password")
            .assertOverlayPreviewMatches("is fully masked") { it.isNotEmpty() && it.all { char -> char == '•' } }
    }

    @Test
    fun aManuallyEditedValueIsReportedAsModified() {
        setContent()
        focus("phone-field")
        compose.onFillKitFieldOverlay("phone").performFill()
        compose.waitForIdle()
        compose.onFillKitFieldOverlay("phone").assertOverlayModified(false)

        compose.runOnIdle { phone = "$phone-edited" }
        compose.waitForIdle()
        compose.onFillKitFieldOverlay("phone").assertOverlayModified(true)
    }

    // --- Reproduction ---------------------------------------------------------

    @Test
    fun perFieldRegenerationIsCapturedInTheReproduction() {
        setContent()
        focus("phone-field")
        repeat(2) {
            compose.onFillKitOverlayAction("phone", FillKitOverlayAction.Regenerate).performClick()
            compose.waitForIdle()
        }
        val rerolled = phone

        val spec = compose.fillKit("form").currentReproduction()
        assertEquals(mapOf("phone" to 2), spec.fieldGenerations)
        val token = FillReproductionTokenCodec.encode(spec)
        assertEquals(spec, FillReproductionTokenCodec.decode(token))

        compose.fillKit("form").setSeed(112840)
        compose.runOnIdle { assertNotEquals(rerolled, phone) }

        compose.fillKit(spec)
        compose.runOnIdle { assertEquals(rerolled, phone) }
    }

    // --- Layout ---------------------------------------------------------------

    @Test
    fun theOverlayTracksAFieldInsideALazyColumn() {
        compose.setContent {
            MaterialTheme {
                FillKitHost("form", modifier = Modifier.fillMaxSize(), config = FillKitConfig(showTrigger = false)) {
                    LazyColumn(Modifier.fillMaxSize().testTag("list")) {
                        items(40) { index ->
                            if (index == 0) {
                                OutlinedTextField(
                                    value = firstName,
                                    onValueChange = { firstName = it },
                                    modifier = Modifier.fillMaxWidth().testTag("firstName-field")
                                        .fillKit("firstName", FillType.FirstName, firstName, { firstName = it }),
                                )
                            } else {
                                Text("row $index", Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
        focus("firstName-field")
        compose.onFillKitFieldOverlay("firstName").assertExists()

        // Scrolling the field out of composition must not leave a stale overlay.
        compose.onNodeWithTag("list").performScrollToIndex(35)
        compose.waitForIdle()
        compose.onFillKitFieldOverlay("firstName").assertDoesNotExist()
    }

    @Test
    fun overlaysAreScopedToTheirOwnHost() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    FillKitHost("form-a", config = FillKitConfig(showTrigger = false, seed = 845912L)) {
                        OutlinedTextField(
                            value = firstName,
                            onValueChange = { firstName = it },
                            modifier = Modifier.testTag("a-field")
                                .fillKit("firstName", FillType.FirstName, firstName, { firstName = it }),
                        )
                    }
                    FillKitHost("form-b", config = FillKitConfig(showTrigger = false, seed = 112840L)) {
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            modifier = Modifier.testTag("b-field")
                                .fillKit("firstName", FillType.FirstName, phone, { phone = it }),
                        )
                    }
                }
            }
        }
        focus("b-field")
        compose.onFillKitFieldOverlay("firstName").performFill()
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(phone.isNotEmpty())
            assertEquals("", firstName)
        }
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesFillKitOverlayCount(expected: Int) {
    val count = onAllNodes(
        androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
            io.devkit.fillkit.FillKitOverlaySemantics.Preview,
        ),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().size
    check(count == expected) { "expected $expected field overlays but found $count" }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onNodeWithTextExists(text: String) {
    check(
        onAllNodes(
            androidx.compose.ui.test.hasText(text, ignoreCase = true),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().isNotEmpty(),
    ) { "expected a node containing \"$text\"" }
}
