package io.devkit.fillkit.debug.activation

import io.devkit.fillkit.FillActivationRequest
import io.devkit.fillkit.FillActivationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingActivationCodecTest {

    private val pending = PendingActivation(
        request = FillActivationRequest(
            formId = "provider-onboarding",
            scenarioPackId = "provider",
            scenarioId = "maximum-values",
            personaPackId = "sample-personas",
            personaId = "experienced-provider",
            locale = "en-KE",
            seed = 845912L,
            generation = 2,
            configurationFingerprint = "abc123",
            source = FillActivationSource.DeepLink,
        ),
        createdAtMillis = 1_700_000_000_000L,
    )

    @Test
    fun survivesAProcessRestart() {
        assertEquals(pending, PendingActivationCodec.decode(PendingActivationCodec.encode(pending)))
    }

    @Test
    fun encodesAMinimalRequest() {
        val minimal = PendingActivation(FillActivationRequest("checkout"), 1L)
        val restored = PendingActivationCodec.decode(PendingActivationCodec.encode(minimal))
        assertEquals("checkout", restored?.request?.formId)
        assertNull(restored?.request?.seed)
    }

    @Test
    fun ignoresCorruptState() {
        assertNull(PendingActivationCodec.decode(null))
        assertNull(PendingActivationCodec.decode(""))
        assertNull(PendingActivationCodec.decode("{not json"))
        assertNull(PendingActivationCodec.decode("""{"version":99,"form":"a","createdAt":1}"""))
    }

    @Test
    fun expiresAfterTheConfiguredWindow() {
        val ttl = 5 * 60 * 1000L
        assertFalse(pending.isExpired(pending.createdAtMillis, ttl))
        assertFalse(pending.isExpired(pending.createdAtMillis + ttl - 1, ttl))
        assertTrue(pending.isExpired(pending.createdAtMillis + ttl, ttl))
        assertTrue(pending.isExpired(pending.createdAtMillis + ttl * 12, ttl))
    }
}
