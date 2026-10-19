package dev.ely4everyone.shared.oauth

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Browser login for a desktop client, done the RFC 8252 way: loopback redirect plus PKCE (RFC 7636).
 *
 * Polling the auth host for a finished login cannot be made safe: whoever created the login link
 * can poll it, so a phishing link hands the victim's tokens to the attacker. Here the browser that
 * approved the login carries only a one-time code, and only to 127.0.0.1 on its own machine.
 * The code is redeemed together with a verifier that never left the client.
 */
object LoopbackLogin {
    const val CALLBACK_PATH: String = "/callback"
    const val CHALLENGE_METHOD: String = "S256"

    // 32 random bytes in unpadded base64url are 43 characters; RFC 7636 allows up to 128.
    private val tokenPattern = Regex("^[A-Za-z0-9_-]{43,128}$")
    private val random = SecureRandom()

    fun newSecret(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /** True for values shaped like [newSecret] output: states, verifiers, challenges, handoff codes. */
    fun isWellFormedToken(value: String?): Boolean = value != null && tokenPattern.matches(value)

    fun verifierMatches(verifier: String, challenge: String): Boolean =
        isWellFormedToken(verifier) && constantTimeEquals(challengeFor(verifier), challenge)

    fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(StandardCharsets.UTF_8), right.toByteArray(StandardCharsets.UTF_8))

    /**
     * Accepts only `http://127.0.0.1:<port>/callback` (or the `[::1]` equivalent). An IP literal,
     * not "localhost": a name could resolve somewhere else (RFC 8252, section 8.3), and anything
     * looser would let a crafted login link send the one-time code off the player's machine.
     */
    fun parseRedirectUri(raw: String?): URI? {
        if (raw.isNullOrBlank()) {
            return null
        }
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val loopbackHost = uri.host == "127.0.0.1" || uri.host == "[::1]"
        val clean = uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
        if (uri.scheme != "http" || !loopbackHost || uri.port !in 1..65535 || !clean || uri.rawPath != CALLBACK_PATH) {
            return null
        }
        return uri
    }

    fun redirectWithCode(redirectUri: String, state: String, code: String): URI =
        URI.create("$redirectUri?state=${encode(state)}&code=${encode(code)}")

    fun redirectWithError(redirectUri: String, state: String, error: String): URI =
        URI.create("$redirectUri?state=${encode(state)}&error=${encode(error)}")

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
