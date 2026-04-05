package dev.ely4everyone.shared.oauth

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

enum class PendingAuthStatus {
    PENDING,
    COMPLETED,
    FAILED,
}

data class PendingAuthSession(
    val status: PendingAuthStatus,
    val state: String,
    val expiresAtEpochSeconds: Long,
    val authSessionToken: String? = null,
    val elyAccessToken: String? = null,
    val username: String? = null,
    val uuid: String? = null,
    val texturesValue: String? = null,
    val texturesSignature: String? = null,
    val error: String? = null,
)

class OAuthStateStore(
    private val ttlSeconds: Long,
) {
    private val sessions = ConcurrentHashMap<String, PendingAuthSession>()

    fun create(state: String, now: Instant = Instant.now()): PendingAuthSession {
        purgeExpired(now)
        val session = PendingAuthSession(
            status = PendingAuthStatus.PENDING,
            state = state,
            expiresAtEpochSeconds = now.plusSeconds(ttlSeconds).epochSecond,
        )
        sessions[state] = session
        return session
    }

    fun get(state: String, now: Instant = Instant.now()): PendingAuthSession? {
        purgeExpired(now)
        return sessions[state]
    }

    fun complete(
        state: String,
        authSessionToken: String,
        elyAccessToken: String,
        username: String,
        uuid: String,
        expiresAtEpochSeconds: Long,
        texturesValue: String?,
        texturesSignature: String?,
    ): PendingAuthSession {
        val session = PendingAuthSession(
            status = PendingAuthStatus.COMPLETED,
            state = state,
            expiresAtEpochSeconds = expiresAtEpochSeconds,
            authSessionToken = authSessionToken,
            elyAccessToken = elyAccessToken,
            username = username,
            uuid = uuid,
            texturesValue = texturesValue,
            texturesSignature = texturesSignature,
        )
        sessions[state] = session
        return session
    }

    fun fail(state: String, error: String, now: Instant = Instant.now()): PendingAuthSession {
        val session = PendingAuthSession(
            status = PendingAuthStatus.FAILED,
            state = state,
            expiresAtEpochSeconds = now.plusSeconds(ttlSeconds).epochSecond,
            error = error,
        )
        sessions[state] = session
        return session
    }

    private fun purgeExpired(now: Instant) {
        val epoch = now.epochSecond
        sessions.entries.removeIf { (_, session) -> session.expiresAtEpochSeconds <= epoch }
    }
}
