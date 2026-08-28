package io.devkit.fillkit.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.devkit.fillkit.FillKitConfig
import io.devkit.fillkit.FillKitController
import io.devkit.fillkit.FillKitHost
import io.devkit.fillkit.FillType
import io.devkit.fillkit.FillKitSemantics
import io.devkit.fillkit.fillKit
import io.devkit.fillkit.fillPersona
import io.devkit.fillkit.personaPack
import io.devkit.fillkit.rememberFillKitController
import io.devkit.fillkit.debug.runtime.DebugFillKitRuntime
import io.devkit.fillkit.runtime.FillKitRuntimeProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class FillKitComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun installRuntime() {
        FillKitRuntimeProvider.install(DebugFillKitRuntime)
    }

    @Test
    fun fieldLifecycleRegistersAndUnregisters() {
        var visible by mutableStateOf(true)
        var fills = 0
        lateinit var controller: FillKitController
        compose.setContent {
            controller = rememberFillKitController()
            FillKitHost("lifecycle", config = FillKitConfig(showTrigger = false), controller = controller) {
                if (visible) Box(Modifier.fillKit("name", FillType.FirstName, "", onFill = { fills++ }))
            }
        }
        compose.runOnIdle { controller.fillAll() }
        assertEquals(1, fills)

        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { controller.fillAll() }
        assertEquals(1, fills)
    }

    @Test
    fun recompositionUsesLatestCallback() {
        var useSecond by mutableStateOf(false)
        var first = 0
        var second = 0
        lateinit var controller: FillKitController
        compose.setContent {
            controller = rememberFillKitController()
            val callback: (String) -> Unit = if (useSecond) ({ second++ }) else ({ first++ })
            FillKitHost("callbacks", config = FillKitConfig(showTrigger = false), controller = controller) {
                Box(Modifier.fillKit("email", FillType.Email, "", callback))
            }
        }
        compose.runOnIdle { useSecond = true }
        compose.waitForIdle()
        compose.runOnIdle { controller.fillAll() }
        assertEquals(0, first)
        assertEquals(1, second)
    }

    @Test
    fun multipleHostsAreIsolated() {
        var first = 0
        var second = 0
        lateinit var firstController: FillKitController
        lateinit var secondController: FillKitController
        compose.setContent {
            firstController = rememberFillKitController()
            secondController = rememberFillKitController()
            FillKitHost("first", config = FillKitConfig(showTrigger = false), controller = firstController) {
                Box(Modifier.fillKit("name", FillType.FirstName, "", onFill = { first++ }))
            }
            FillKitHost("second", config = FillKitConfig(showTrigger = false), controller = secondController) {
                Box(Modifier.fillKit("name", FillType.FirstName, "", onFill = { second++ }))
            }
        }
        compose.runOnIdle { firstController.fillAll() }
        assertEquals(1, first)
        assertEquals(0, second)
    }

    @Test
    fun developerPanelDisplaysRegisteredField() {
        compose.setContent {
            MaterialTheme {
                FillKitHost("registration", modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillKit("email", FillType.Email, "", onFill = {}))
                }
            }
        }
        compose.onNodeWithText("⚡").performClick()
        compose.onNodeWithText("FillKit").assertIsDisplayed()
        compose.onNodeWithText("Email").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun fillAllPopulatesFieldsAndDismissesPanel() {
        var email = ""
        compose.setContent {
            MaterialTheme {
                FillKitHost("registration", modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillKit("email", FillType.Email, email, onFill = { email = it }))
                }
            }
        }

        compose.onNodeWithText("⚡").performClick()
        compose.onNodeWithText("Fill All").performClick()

        compose.runOnIdle { check(email.isNotBlank()) }
        compose.onAllNodesWithText("FillKit").assertCountEquals(0)
    }

    @Test
    fun developerPanelSelectsSavedPersonaAndFillsItsValues() {
        var email = ""
        val returningCustomer = fillPersona("returning", "Returning Customer") {
            email("returning@example.com")
        }
        val config = FillKitConfig(
            personaPacks = listOf(personaPack("customers", "Customers") { persona(returningCustomer) }),
        )
        compose.setContent {
            MaterialTheme {
                FillKitHost("checkout", modifier = Modifier.fillMaxSize(), config = config) {
                    Box(Modifier.fillKit("email", FillType.Email, email, onFill = { email = it }))
                }
            }
        }

        compose.onNodeWithText("⚡").performClick()
        compose.onNodeWithText("Returning Customer").performClick()

        compose.runOnIdle { assertEquals("returning@example.com", email) }
        compose.onNodeWithText("Returning Customer").assertIsDisplayed()
    }

    @Test
    fun textFieldStateFillUsesLatestStateCursorAndClearBehavior() {
        lateinit var state: TextFieldState
        lateinit var controller: FillKitController
        compose.setContent {
            controller = rememberFillKitController()
            state = rememberTextFieldState("stale")
            FillKitHost("state", config = FillKitConfig(showTrigger = false), controller = controller) {
                BasicTextField(
                    state = state,
                    modifier = Modifier.testTag("state-field").fillKit(
                        id = "stateEmail", type = FillType.Email, state = state,
                    ),
                )
            }
        }
        compose.runOnIdle { controller.fillAll() }
        compose.runOnIdle {
            assert(state.text.contains('@'))
            assertEquals(state.text.length, state.selection.start)
        }
        compose.runOnIdle { controller.clearAll() }
        compose.runOnIdle {
            assertEquals("", state.text.toString())
            assertEquals(0, state.selection.start)
        }
    }

    @Test
    fun debugMetadataCoexistsWithApplicationAccessibilitySemantics() {
        compose.setContent {
            FillKitHost("semantics", config = FillKitConfig(showTrigger = false)) {
                Box(
                    Modifier
                        .testTag("semantic-field")
                        .semantics { contentDescription = "Application description" }
                        .fillKit("email", FillType.Email, "", {}),
                )
            }
        }
        compose.onNodeWithTag("semantic-field", useUnmergedTree = true)
            .assertContentDescriptionEquals("Application description")
        compose.onNode(
            SemanticsMatcher.expectValue(FillKitSemantics.FieldId, "email"),
            useUnmergedTree = true,
        ).assertExists()
        compose.onNode(
            SemanticsMatcher.expectValue(FillKitSemantics.FormId, "semantics"),
            useUnmergedTree = true,
        ).assertExists()
    }
}
