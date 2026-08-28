package io.devkit

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.devkit.fillkit.FillActivationResult
import io.devkit.fillkit.FillActivationSource
import io.devkit.fillkit.FillKit
import io.devkit.fillkit.FillReproductionSpec
import io.devkit.fillkit.FillReproductionTokenCodec
import io.devkit.fillkit.FillType
import io.devkit.fillkit.appliedSpec
import io.devkit.fillkit.testing.FillKitReproductionRule
import io.devkit.fillkit.testing.applyFillKitReproduction
import io.devkit.fillkit.testing.assertFillKitType
import io.devkit.fillkit.testing.assertRegistered
import io.devkit.fillkit.testing.fillKit
import io.devkit.fillkit.testing.fillKitScenario
import io.devkit.fillkit.testing.onFillKitField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The FillKit 0.4 workflow end to end: QA launches a scenario, copies a
 * reproduction token, and the same token becomes a CI regression test.
 */
class FillKitSampleRegressionTest {

    @get:Rule(order = 0)
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule(order = 1)
    val reproduction = FillKitReproductionRule()

    @Test
    fun providerMaximumValuesScenario() {
        compose.fillKit(formId = "registration") {
            scenario(pack = "validation", id = "maximum-values")
            seed(845912)
        }

        compose.onNodeWithText("Amina-Extremely-Long-Synthetic-First-Name").assertIsDisplayed()
        compose.onFillKitField("email").assertRegistered().assertFillKitType(FillType.Email)
    }

    @Test
    fun reproduceReportedIssue() {
        val reported = FillReproductionSpec(
            formId = "registration",
            seed = 845912L,
            locale = "en-KE",
            scenarioPackId = "validation",
            scenarioId = "maximum-values",
        )
        val token = FillReproductionTokenCodec.encode(reported)

        val result = compose.applyFillKitReproduction(token)

        assertTrue("unexpected activation outcome: $result", result is FillActivationResult.Applied ||
            result is FillActivationResult.PartiallyApplied)
        assertEquals(845912L, result.appliedSpec?.seed)
        compose.onNodeWithText("Amina-Extremely-Long-Synthetic-First-Name").assertIsDisplayed()
    }

    @Test
    fun theSameSeedReproducesTheSameGeneratedData() {
        compose.fillKitScenario {
            form("registration")
            scenario("provider", "experienced-provider")
            seed(94821)
        }
        val first = compose.fillKit("registration").currentReproductionToken()

        compose.fillKit("registration").setSeed(112840)
        compose.fillKit("registration").setSeed(94821)
        compose.fillKit("registration").scenario("experienced-provider", pack = "provider").activate()

        assertEquals(first, compose.fillKit("registration").currentReproductionToken())
    }

    @Test
    fun qaLaunchNavigatesToTheTargetFormAndAppliesTheScenario() {
        compose.onNodeWithText("Checkout").performClick()
        compose.waitForIdle()

        val entryRequest = io.devkit.fillkit.FillActivationRequest(
            formId = "registration",
            scenarioPackId = "validation",
            scenarioId = "maximum-values",
            seed = 845912L,
            source = FillActivationSource.QaLauncher,
        )
        val result = compose.runOnIdle { FillKit.activate(entryRequest) }

        assertTrue(result is FillActivationResult.Pending)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Amina-Extremely-Long-Synthetic-First-Name"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Amina-Extremely-Long-Synthetic-First-Name").assertIsDisplayed()
    }

    @Test
    fun theDeepLinkEntryPointExistsInDebugBuilds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${context.packageName}.fillkit://scenario?form=registration"))
        val matches = context.packageManager.queryIntentActivities(intent, 0)
        assertTrue(
            "the debug build should answer ${intent.data}",
            matches.any { it.activityInfo.packageName == context.packageName },
        )
    }

    @Test
    fun theDeepLinkSchemeIsApplicationSpecific() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val generic = Intent(Intent.ACTION_VIEW, Uri.parse("fillkit://scenario?form=registration"))
        assertTrue(
            "a generic fillkit:// link must not be claimed by this application",
            context.packageManager.queryIntentActivities(generic, 0)
                .none { it.activityInfo.packageName == context.packageName },
        )
    }
}
