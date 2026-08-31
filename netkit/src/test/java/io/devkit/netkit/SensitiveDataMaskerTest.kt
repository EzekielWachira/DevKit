package io.devkit.netkit

import io.devkit.netkit.masking.DefaultSensitiveDataMasker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveDataMaskerTest {

    private val masker = DefaultSensitiveDataMasker()
    private val mask = DefaultSensitiveDataMasker.MASK

    private fun mask(vararg headers: Pair<String, String>) = masker.maskHeaders(headers.toList())

    @Test
    fun `authorization keeps its scheme and loses the credential`() {
        val masked = mask("Authorization" to "Bearer eyJhbGciOiJIUzI1NiJ9.payload").single()

        assertEquals("Bearer $mask", masked.value)
        assertTrue(masked.masked)
    }

    @Test
    fun `a schemeless credential is fully redacted`() {
        assertEquals(mask, mask("X-Api-Key" to "abc123").single().value)
    }

    @Test
    fun `cookie and set-cookie are masked`() {
        val masked = mask(
            "Cookie" to "session=abc; theme=dark",
            "Set-Cookie" to "session=abc; HttpOnly",
        )

        assertTrue(masked.all { it.masked })
        assertTrue(masked.none { it.value.contains("abc") })
    }

    @Test
    fun `proxy authorization and api-key variants are masked`() {
        val masked = mask(
            "Proxy-Authorization" to "Basic dXNlcjpwYXNz",
            "Api-Key" to "key-1",
            "X-Auth-Token" to "token-1",
        )

        assertTrue(masked.all { it.masked })
    }

    @Test
    fun `header names are matched case-insensitively`() {
        listOf("authorization", "AUTHORIZATION", "AuThOrIzAtIoN").forEach { name ->
            val masked = mask(name to "Bearer secret").single()
            assertTrue("$name should be masked", masked.masked)
            assertFalse(masked.value.contains("secret"))
        }
    }

    @Test
    fun `non-sensitive headers are left untouched`() {
        val masked = mask(
            "Accept" to "application/json",
            "User-Agent" to "okhttp/5",
        )

        assertTrue(masked.none { it.masked })
        assertEquals(listOf("application/json", "okhttp/5"), masked.map { it.value })
    }

    @Test
    fun `duplicate headers are all masked and order is preserved`() {
        val masked = mask(
            "Set-Cookie" to "a=1",
            "Accept" to "application/json",
            "Set-Cookie" to "b=2",
        )

        assertEquals(listOf("Set-Cookie", "Accept", "Set-Cookie"), masked.map { it.name })
        assertEquals(listOf(true, false, true), masked.map { it.masked })
    }

    @Test
    fun `additional header names can be supplied`() {
        val custom = DefaultSensitiveDataMasker(additionalHeaderNames = setOf("X-Tenant-Secret"))

        val masked = custom.maskHeaders(listOf("x-tenant-secret" to "value")).single()

        assertTrue(masked.masked)
    }

    @Test
    fun `credential query parameters are masked in urls`() {
        val masked = masker.maskUrl("https://api.example.com/v1/me?access_token=abc&page=2")

        assertEquals("https://api.example.com/v1/me?access_token=$mask&page=2", masked)
    }

    @Test
    fun `a url with nothing sensitive is returned unchanged`() {
        val url = "https://api.example.com/v1/bookings?page=2&size=20"

        assertEquals(url, masker.maskUrl(url))
    }

    @Test
    fun `a url without a query is returned unchanged`() {
        val url = "https://api.example.com/v1/bookings"

        assertEquals(url, masker.maskUrl(url))
    }

    @Test
    fun `a fragment survives query masking`() {
        val masked = masker.maskUrl("https://api.example.com/v1?token=abc#section")

        assertEquals("https://api.example.com/v1?token=$mask#section", masked)
    }

    @Test
    fun `an empty header list is handled`() {
        assertTrue(masker.maskHeaders(emptyList()).isEmpty())
    }
}
