package dev.ely4everyone.velocity.auth.http

import dev.ely4everyone.shared.oauth.OAuthStateStore
import dev.ely4everyone.shared.oauth.PendingAuthStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OAuthStateStoreTest {
    @Test
    fun `completes state with auth session token`() {
        val store = OAuthStateStore(ttlSeconds = 60)
        store.create("state-1", Instant.ofEpochSecond(100))

        store.complete(
            state = "state-1",
            authSessionToken = "session-1",
            elyAccessToken = "ely-access-token",
            username = "PlayerThree",
            uuid = "uuid-3",
            sessionExpiresAtEpochSeconds = 500,
            texturesValue = "textures-value",
            texturesSignature = "textures-signature",
            now = Instant.ofEpochSecond(110),
        )

        val session = store.get("state-1", Instant.ofEpochSecond(120))
        assertNotNull(session)
        assertEquals(PendingAuthStatus.COMPLETED, session.status)
        assertEquals("session-1", session.authSessionToken)
        assertEquals("ely-access-token", session.elyAccessToken)
        assertEquals(500, session.sessionExpiresAtEpochSeconds)
    }

    @Test
    fun `expires old pending state`() {
        val store = OAuthStateStore(ttlSeconds = 10)
        store.create("state-2", Instant.ofEpochSecond(100))

        val session = store.get("state-2", Instant.ofEpochSecond(111))
        assertNull(session)
    }

    @Test
    fun `a state cannot be started twice and the store stays bounded`() {
        val store = OAuthStateStore(ttlSeconds = 60, maxEntries = 2)

        assertNotNull(store.create("state-a"))
        assertNull(store.create("state-a"))
        assertNotNull(store.create("state-b"))
        assertNull(store.create("state-c"))
    }

    @Test
    fun `only one callback may claim a pending login`() {
        val store = OAuthStateStore(ttlSeconds = 60)
        store.create("state-1")

        assertNotNull(store.claimForExchange("state-1"))
        assertNull(store.claimForExchange("state-1"))
        assertNull(store.claimForExchange("unknown"))
    }

    @Test
    fun `a finished login is handed out once and never failed afterwards`() {
        val store = OAuthStateStore(ttlSeconds = 60)
        store.create("state-1")
        store.complete(
            state = "state-1",
            authSessionToken = "session-1",
            elyAccessToken = "ely-access-token",
            username = "PlayerThree",
            uuid = "uuid-3",
            sessionExpiresAtEpochSeconds = Instant.now().epochSecond + 3600,
            texturesValue = null,
            texturesSignature = null,
        )

        store.fail("state-1", "late failure")
        assertEquals(PendingAuthStatus.COMPLETED, store.get("state-1")?.status)

        assertNull(store.consumeIf("state-1") { false })
        assertNotNull(store.consumeIf("state-1") { it.status == PendingAuthStatus.COMPLETED })
        assertNull(store.consumeIf("state-1") { true })
        assertNull(store.get("state-1"))
    }
}
