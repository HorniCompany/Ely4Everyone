package dev.ely4everyone.mod.session

import java.time.Instant

/**
 * The stored Ely login: the mod logged in to Ely.by itself and keeps [elyRefreshToken] to renew it.
 * [authSessionExpiresAt] is when [elyAccessToken] stops being usable (the name is kept from the
 * auth host days, it is also the key in the stored file).
 */
data class ClientSessionState(
    val elyAccessToken: String? = null,
    val authSessionExpiresAt: Instant? = null,
    val elyUsername: String? = null,
    val elyUuid: String? = null,
    val elyTexturesValue: String? = null,
    val elyTexturesSignature: String? = null,
    val elyRefreshToken: String? = null,
) {
    /** Logged in, as far as the game is concerned. */
    fun isSignedIn(now: Instant = Instant.now()): Boolean = hasRestorableElyIdentity(now)

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
