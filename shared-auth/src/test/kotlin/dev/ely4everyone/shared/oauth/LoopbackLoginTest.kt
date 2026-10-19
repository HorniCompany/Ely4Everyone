package dev.ely4everyone.shared.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoopbackLoginTest {
    @Test
    fun `secrets are fresh and well formed`() {
        val first = LoopbackLogin.newSecret()
        val second = LoopbackLogin.newSecret()

        assertNotEquals(first, second)
        assertEquals(43, first.length)
        assertTrue(LoopbackLogin.isWellFormedToken(first))
        assertFalse(LoopbackLogin.isWellFormedToken("too-short"))
        assertFalse(LoopbackLogin.isWellFormedToken(first.dropLast(1) + "="))
        assertFalse(LoopbackLogin.isWellFormedToken(null))
    }

    @Test
    fun `challenge matches the rfc 7636 test vector`() {
        // RFC 7636, Appendix B.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", LoopbackLogin.challengeFor(verifier))
        assertTrue(LoopbackLogin.verifierMatches(verifier, "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"))
        assertFalse(LoopbackLogin.verifierMatches(LoopbackLogin.newSecret(), "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"))
    }

    @Test
    fun `only loopback callback addresses are accepted`() {
        assertNotNull(LoopbackLogin.parseRedirectUri("http://127.0.0.1:54321/callback"))
        assertNotNull(LoopbackLogin.parseRedirectUri("http://[::1]:54321/callback"))

        listOf(
            null,
            "",
            "http://evil.example:54321/callback",
            "http://localhost:54321/callback",
            "https://127.0.0.1:54321/callback",
            "http://127.0.0.1/callback",
            "http://127.0.0.1:54321/other",
            "http://127.0.0.1:54321/callback?next=http://evil.example",
            "http://127.0.0.1:54321/callback#fragment",
            "http://user@127.0.0.1:54321/callback",
            "http://127.0.0.1.evil.example:54321/callback",
            "not a uri",
        ).forEach { candidate ->
            assertNull(LoopbackLogin.parseRedirectUri(candidate), "accepted $candidate")
        }
    }

    @Test
    fun `redirects carry the state and encode values`() {
        val withCode = LoopbackLogin.redirectWithCode("http://127.0.0.1:1/callback", "state", "code")
        assertEquals("http://127.0.0.1:1/callback?state=state&code=code", withCode.toString())

        val withError = LoopbackLogin.redirectWithError("http://127.0.0.1:1/callback", "state", "a b&c")
        assertEquals("http://127.0.0.1:1/callback?state=state&error=a+b%26c", withError.toString())
    }
}
