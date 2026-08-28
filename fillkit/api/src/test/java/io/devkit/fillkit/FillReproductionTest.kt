package io.devkit.fillkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FillReproductionTest {

    private val spec = FillReproductionSpec(
        formId = "provider-onboarding",
        seed = 845912L,
        generation = 2,
        locale = "en-KE",
        scenarioPackId = "provider-validation",
        scenarioId = "maximum-values",
        personaPackId = "sample-personas",
        personaId = "experienced-provider",
        configurationFingerprint = "abc123def456",
    )

    @Test
    fun encodesAndDecodesEveryField() {
        val token = FillReproductionTokenCodec.encode(spec)
        assertTrue(token.startsWith("FK1-"))
        assertEquals(spec, FillReproductionTokenCodec.decode(token))
    }

    @Test
    fun encodesMinimalSpecs() {
        val minimal = FillReproductionSpec(formId = "checkout", seed = 1L)
        assertEquals(minimal, FillReproductionTokenCodec.decode(FillReproductionTokenCodec.encode(minimal)))
    }

    @Test
    fun tokensAreStableForTheSameSpec() {
        assertEquals(FillReproductionTokenCodec.encode(spec), FillReproductionTokenCodec.encode(spec))
        assertNotEquals(
            FillReproductionTokenCodec.encode(spec),
            FillReproductionTokenCodec.encode(spec.copy(seed = 845913L)),
        )
    }

    @Test
    fun rejectsAMissingPrefix() {
        assertEquals(FillTokenError.MissingPrefix, errorFor("XX1-abc-1234"))
    }

    @Test
    fun rejectsAFutureVersion() {
        val token = FillReproductionTokenCodec.encode(spec)
        val bumped = token.replaceFirst("FK1-", "FK9-")
        assertEquals(FillTokenError.UnsupportedVersion, errorFor(bumped))
    }

    @Test
    fun rejectsACorruptToken() {
        val token = FillReproductionTokenCodec.encode(spec)
        val corrupted = token.dropLast(1) + if (token.last() == 'a') 'b' else 'a'
        assertEquals(FillTokenError.ChecksumMismatch, errorFor(corrupted))
    }

    @Test
    fun rejectsAnOversizedToken() {
        assertEquals(FillTokenError.TooLong, errorFor("FK1-" + "a".repeat(FillReproductionTokenCodec.MAX_TOKEN_LENGTH)))
    }

    @Test
    fun rejectsBlankInput() {
        assertEquals(FillTokenError.Blank, errorFor("   "))
        assertNull(FillReproductionTokenCodec.decodeOrNull(null))
    }

    @Test
    fun rejectsAnOutOfRangeSeed() {
        val invalid = FillReproductionTokenCodec.encode(FillReproductionSpec("f", 1L))
            .let { FillReproductionTokenCodec.decode(it) }
        assertEquals(1L, invalid.seed)
        val error = runCatching { FillReproductionSpec("f", FillKitSeed.MAX + 1) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun reportNeverContainsGeneratedValues() {
        val report = spec.describe()
        assertTrue(report.contains("form=provider-onboarding"))
        assertTrue(report.contains("scenario=provider-validation/maximum-values"))
        assertTrue(report.contains("seed=845912"))
        assertTrue(report.contains("generation=2"))
        assertTrue(report.contains("token=FK1-"))
    }

    @Test
    fun specAndActivationRequestRoundTrip() {
        val request = spec.toActivationRequest(FillActivationSource.DeepLink)
        assertEquals(spec.formId, request.formId)
        assertEquals(spec.seed, request.seed)
        assertEquals(spec, request.toReproductionSpec())
    }

    @Test
    fun fingerprintChangesWithPackVersions() {
        val pack = fillKitPack("provider", "Provider", version = 3) {
            scenarios(scenarioPack("provider-validation", "Validation") { scenario("a", "A") { text("x", "y") } })
        }
        val bumped = pack.copy(version = "4")
        val base = FillKitConfig(packs = listOf(pack)).configurationFingerprint()
        assertEquals(base, FillKitConfig(packs = listOf(pack)).configurationFingerprint())
        assertNotEquals(base, FillKitConfig(packs = listOf(bumped)).configurationFingerprint())
    }

    @Test
    fun fingerprintIgnoresPackOrdering() {
        val first = fillKitPack("a", "A", version = "1") {}
        val second = fillKitPack("b", "B", version = "1") {}
        assertEquals(
            FillKitConfig(packs = listOf(first, second)).configurationFingerprint(),
            FillKitConfig(packs = listOf(second, first)).configurationFingerprint(),
        )
    }

    @Test
    fun seedHelpersStayInRange() {
        assertTrue(FillKitSeed.isValid(FillKitSeed.random().value))
        assertEquals(845912L, FillKitSeed.parseOrNull(" 845912 ")?.value)
        assertNull(FillKitSeed.parseOrNull("not-a-seed"))
        assertNull(FillKitSeed.parseOrNull("-1"))
    }

    @Test
    fun derivedStreamsDependOnEveryNamespaceSegment() {
        val base = FillSeedDerivation.derive(1L, 0, "form", "field")
        assertEquals(base, FillSeedDerivation.derive(1L, 0, "form", "field"))
        assertNotEquals(base, FillSeedDerivation.derive(1L, 1, "form", "field"))
        assertNotEquals(base, FillSeedDerivation.derive(2L, 0, "form", "field"))
        assertNotEquals(base, FillSeedDerivation.derive(1L, 0, "form", "other"))
        assertNotEquals(base, FillSeedDerivation.derive(1L, 0, "field", "form"))
    }

    private fun errorFor(token: String): FillTokenError? =
        runCatching { FillReproductionTokenCodec.decode(token) }
            .exceptionOrNull()
            .let { it as? FillReproductionTokenException }
            ?.error
}
