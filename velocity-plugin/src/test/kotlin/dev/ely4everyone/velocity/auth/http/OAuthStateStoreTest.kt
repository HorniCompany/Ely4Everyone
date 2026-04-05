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
            expiresAtEpochSeconds = 500,
            texturesValue = "textures-value",
            texturesSignature = "textures-signature",
        )

        val session = store.get("state-1", Instant.ofEpochSecond(120))
        assertNotNull(session)
        assertEquals(PendingAuthStatus.COMPLETED, session.status)
        assertEquals("session-1", session.authSessionToken)
        assertEquals("ely-access-token", session.elyAccessToken)
    }

    @Test
    fun `expires old pending state`() {
        val store = OAuthStateStore(ttlSeconds = 10)
        store.create("state-2", Instant.ofEpochSecond(100))

        val session = store.get("state-2", Instant.ofEpochSecond(111))
        assertNull(session)
    }
}
