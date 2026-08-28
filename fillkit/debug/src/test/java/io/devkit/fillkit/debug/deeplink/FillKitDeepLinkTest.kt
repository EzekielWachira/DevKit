package io.devkit.fillkit.debug.deeplink

import io.devkit.fillkit.FillActivationRequest
import io.devkit.fillkit.FillActivationSource
import io.devkit.fillkit.FillReproductionSpec
import io.devkit.fillkit.FillReproductionTokenCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FillKitDeepLinkTest {

    private val scheme = FillKitDeepLink.scheme("com.example.app")

    private val spec = FillReproductionSpec(
        formId = "provider-onboarding",
        seed = 845912L,
        generation = 1,
        locale = "en-KE",
        scenarioPackId = "provider",
        scenarioId = "maximum-values",
    )

    private fun parse(uri: String?, allowOverrides: Boolean = false) =
        FillKitDeepLink.parse(uri, scheme, allowOverrides)

    @Test
    fun schemeIsDerivedFromTheApplicationId() {
        assertEquals("com.example.app.fillkit", scheme)
    }

    @Test
    fun acceptsAReproductionLink() {
        val token = FillReproductionTokenCodec.encode(spec)
        val result = parse(FillKitDeepLink.reproduceUri(scheme, token))
        val request = (result as FillDeepLinkResult.Activation).request
        assertEquals(spec.formId, request.formId)
        assertEquals(spec.seed, request.seed)
        assertEquals(spec.scenarioId, request.scenarioId)
        assertEquals(FillActivationSource.DeepLink, request.source)
    }

    @Test
    fun acceptsAScenarioLink() {
        val uri = FillKitDeepLink.scenarioUri(scheme, spec.toRequest())
        val request = (parse(uri) as FillDeepLinkResult.Activation).request
        assertEquals("provider-onboarding", request.formId)
        assertEquals("maximum-values", request.scenarioId)
        assertEquals("provider", request.scenarioPackId)
        assertEquals("en-KE", request.locale)
        assertEquals(845912L, request.seed)
        assertEquals(1, request.generation)
    }

    @Test
    fun rejectsAnotherApplicationsScheme() {
        val result = parse("com.other.app.fillkit://scenario?form=checkout")
        assertTrue((result as FillDeepLinkResult.Invalid).reason.contains("unexpected scheme"))
    }

    @Test
    fun rejectsAnUnknownPath() {
        val result = parse("$scheme://inject?form=checkout")
        assertTrue((result as FillDeepLinkResult.Invalid).reason.contains("unsupported path"))
    }

    @Test
    fun rejectsAnInvalidToken() {
        val result = parse("$scheme://reproduce/FK1-not-real")
        assertTrue(result is FillDeepLinkResult.Invalid)
    }

    @Test
    fun rejectsAnOversizedLink() {
        val result = parse("$scheme://scenario?form=" + "a".repeat(FillKitDeepLink.MAX_URI_LENGTH))
        assertTrue((result as FillDeepLinkResult.Invalid).reason.contains("exceeds"))
    }

    @Test
    fun rejectsAnOutOfRangeSeed() {
        val result = parse("$scheme://scenario?form=checkout&seed=99999999999999")
        assertTrue((result as FillDeepLinkResult.Invalid).reason.contains("seed"))
    }

    @Test
    fun rejectsAnOutOfRangeGeneration() {
        val result = parse("$scheme://scenario?form=checkout&generation=-1")
        assertTrue((result as FillDeepLinkResult.Invalid).reason.contains("generation"))
    }

    @Test
    fun rejectsAMalformedIdentifier() {
        val result = parse("$scheme://scenario?form=check%20out%2Fnope%21")
        assertTrue((result as FillDeepLinkResult.Invalid).reason.contains("not a valid identifier"))
    }

    @Test
    fun rejectsRawFieldInjectionByDefault() {
        val result = parse("$scheme://scenario?form=checkout&email=foo&password=bar")
        assertTrue((result as FillDeepLinkResult.Invalid).reason.contains("raw field injection is disabled"))
    }

    @Test
    fun ignoresExtraParametersOnlyWhenExplicitlyEnabled() {
        val result = parse("$scheme://scenario?form=checkout&email=foo", allowOverrides = true)
        assertEquals("checkout", (result as FillDeepLinkResult.Activation).request.formId)
    }

    @Test
    fun rejectsEmptyInput() {
        assertTrue(parse(null) is FillDeepLinkResult.Invalid)
        assertTrue(parse("   ") is FillDeepLinkResult.Invalid)
        assertTrue(parse("not-a-uri") is FillDeepLinkResult.Invalid)
    }

    @Test
    fun linksNeverCarryGeneratedValues() {
        val uri = FillKitDeepLink.scenarioUri(scheme, spec.toRequest())
        listOf("email", "password", "phone", "@").forEach { forbidden ->
            assertTrue("\"$forbidden\" must not appear in $uri", !uri.contains(forbidden))
        }
    }

    @Test
    fun producesARunnableAdbCommand() {
        val uri = FillKitDeepLink.reproduceUri(scheme, FillReproductionTokenCodec.encode(spec))
        val command = FillKitDeepLink.adbCommand(uri)
        assertTrue(command.startsWith("adb shell \"am start -a android.intent.action.VIEW -d '"))
        assertTrue(command.endsWith("'\""))
        assertTrue(command.contains(uri))
    }

    @Test
    fun theAdbCommandProtectsQuerySeparators() {
        // A scenario link carries `&`; without quoting, the device shell would
        // treat everything after the first parameter as separate commands.
        val uri = FillKitDeepLink.scenarioUri(scheme, spec.toRequest())
        assertTrue(uri.contains("&"))
        val command = FillKitDeepLink.adbCommand(uri)
        val quoted = command.substringAfter("-d '").substringBeforeLast("'")
        assertEquals(uri, quoted)
        assertTrue("the whole am start must be one adb argument", command.startsWith("adb shell \""))
    }

    private fun FillReproductionSpec.toRequest() = FillActivationRequest(
        formId = formId,
        scenarioPackId = scenarioPackId,
        scenarioId = scenarioId,
        locale = locale,
        seed = seed,
        generation = generation,
        source = FillActivationSource.DeepLink,
    )
}
