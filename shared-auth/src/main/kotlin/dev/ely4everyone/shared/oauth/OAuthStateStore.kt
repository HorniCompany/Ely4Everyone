package dev.ely4everyone.shared.oauth

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

enum class PendingAuthStatus {
    PENDING,

    /** The Ely callback arrived and its code is being exchanged; a second callback must not run. */
    EXCHANGING,
    COMPLETED,
    FAILED,
}

data class PendingAuthSession(
    val status: PendingAuthStatus,
    val state: String,
    /** When this login entry is dropped. Not the lifetime of the issued session. */
    val expiresAtEpochSeconds: Long,
    /** Set for loopback logins: the PKCE challenge and where the browser gets sent back to. */
    val codeChallenge: String? = null,
    val clientRedirectUri: String? = null,
    /** One-time code the loopback client redeems for the tokens below. */
    val handoffCode: String? = null,
    val authSessionToken: String? = null,
    val elyAccessToken: String? = null,
    val sessionExpiresAtEpochSeconds: Long? = null,
    val username: String? = null,
    val uuid: String? = null,
    val texturesValue: String? = null,
    val texturesSignature: String? = null,
    val error: String? = null,
) {
    val isLoopback: Boolean get() = codeChallenge != null
}

class OAuthStateStore(
    private val ttlSeconds: Long,
    private val maxEntries: Int = 10_000,
) {
    private val sessions = ConcurrentHashMap<String, PendingAuthSession>()

    /**
     * Starts a login. Returns null when the state is already taken (a state is never reused,
     * so a known state cannot be hijacked or reset) or when too many logins are in flight.
     */
    fun create(
        state: String,
        now: Instant = Instant.now(),
        codeChallenge: String? = null,
        clientRedirectUri: String? = null,
    ): PendingAuthSession? {
        purgeExpired(now)
        if (sessions.size >= maxEntries) {
            return null
        }
        val session = PendingAuthSession(
            status = PendingAuthStatus.PENDING,
            state = state,
            expiresAtEpochSeconds = now.plusSeconds(ttlSeconds).epochSecond,
            codeChallenge = codeChallenge,
            clientRedirectUri = clientRedirectUri,
        )
        return session.takeIf { sessions.putIfAbsent(state, it) == null }
    }

    fun get(state: String, now: Instant = Instant.now()): PendingAuthSession? {
        purgeExpired(now)
        return sessions[state]
    }

    /** Moves a PENDING login to EXCHANGING. Only the first caller wins; everyone else gets null. */
    fun claimForExchange(state: String, now: Instant = Instant.now()): PendingAuthSession? {
        purgeExpired(now)
        var claimed: PendingAuthSession? = null
        sessions.computeIfPresent(state) { _, session ->
            if (session.status == PendingAuthStatus.PENDING) {
                session.copy(status = PendingAuthStatus.EXCHANGING).also { claimed = it }
            } else {
                session
            }
        }
        return claimed
    }

    /**
     * Stores the finished login. [entryTtlSeconds] is how long the result waits to be collected,
     * which is much shorter than the session itself.
     */
    fun complete(
        state: String,
        authSessionToken: String,
        elyAccessToken: String,
        username: String,
        uuid: String,
        sessionExpiresAtEpochSeconds: Long,
        texturesValue: String?,
        texturesSignature: String?,
        handoffCode: String? = null,
        entryTtlSeconds: Long = ttlSeconds,
        now: Instant = Instant.now(),
    ): PendingAuthSession? {
        return sessions.computeIfPresent(state) { _, session ->
            session.copy(
                status = PendingAuthStatus.COMPLETED,
                expiresAtEpochSeconds = now.plusSeconds(entryTtlSeconds).epochSecond,
                handoffCode = handoffCode,
                authSessionToken = authSessionToken,
                elyAccessToken = elyAccessToken,
                sessionExpiresAtEpochSeconds = sessionExpiresAtEpochSeconds,
                username = username,
                uuid = uuid,
                texturesValue = texturesValue,
                texturesSignature = texturesSignature,
                error = null,
            )
        }
    }

    /** Marks a login as failed. A completed login is never overwritten. */
    fun fail(state: String, error: String, now: Instant = Instant.now()): PendingAuthSession? {
        return sessions.computeIfPresent(state) { _, session ->
            if (session.status == PendingAuthStatus.COMPLETED) {
                session
            } else {
                session.copy(
                    status = PendingAuthStatus.FAILED,
                    expiresAtEpochSeconds = now.plusSeconds(ttlSeconds).epochSecond,
                    error = error,
                )
            }
        }
    }

    /** Atomically removes and returns the entry if [accept] says so, so a result is handed out once. */
    fun consumeIf(
        state: String,
        now: Instant = Instant.now(),
        accept: (PendingAuthSession) -> Boolean,
    ): PendingAuthSession? {
        purgeExpired(now)
        var consumed: PendingAuthSession? = null
        sessions.computeIfPresent(state) { _, session ->
            if (accept(session)) {
                consumed = session
                null
            } else {
                session
            }
        }
        return consumed
    }

    private fun purgeExpired(now: Instant) {
        val epoch = now.epochSecond
        sessions.entries.removeIf { (_, session) -> session.expiresAtEpochSeconds <= epoch }
    }
}
