package dev.ely4everyone.mod.session

import java.time.Instant

/**
 * The stored Ely login. Two kinds exist:
 * - direct: the mod logged in to Ely.by itself and keeps [elyRefreshToken]; [authSessionToken] is empty;
 * - relay (older mod versions): an auth host holds the Ely tokens and gave us [authSessionToken].
 * [authSessionExpiresAt] is when [elyAccessToken] stops being usable in both cases.
 */
data class ClientSessionState(
    val relayBaseUrl: String,
    val authSessionToken: String? = null,
    val elyAccessToken: String? = null,
    val authSessionExpiresAt: Instant? = null,
    val elyUsername: String? = null,
    val elyUuid: String? = null,
    val elyTexturesValue: String? = null,
    val elyTexturesSignature: String? = null,
    val elyRefreshToken: String? = null,
) {
    val isDirectLogin: Boolean
        get() = authSessionToken.isNullOrBlank()

    /** Logged in with whatever kind of session, as far as the game is concerned. */
    fun isSignedIn(now: Instant = Instant.now()): Boolean = hasRestorableElyIdentity(now)

    fun hasUsableAuthSession(now: Instant = Instant.now()): Boolean {
        val expiresAt = authSessionExpiresAt ?: return false
        return !authSessionToken.isNullOrBlank() && expiresAt.isAfter(now)
    }

    fun hasUsableElyAccessToken(now: Instant = Instant.now()): Boolean {
        val expiresAt = authSessionExpiresAt ?: return false
        return !elyAccessToken.isNullOrBlank() && expiresAt.isAfter(now)
    }

    fun hasRestorableElyIdentity(now: Instant = Instant.now()): Boolean {
        return hasUsableElyAccessToken(now) &&
            !elyUsername.isNullOrBlank() &&
            !elyUuid.isNullOrBlank()
    }
}
