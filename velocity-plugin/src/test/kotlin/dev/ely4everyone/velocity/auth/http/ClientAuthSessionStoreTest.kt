package dev.ely4everyone.velocity.auth.http

import dev.ely4everyone.shared.auth.AuthProfileProperty
import dev.ely4everyone.shared.session.ClientAuthSessionStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClientAuthSessionStoreTest {
    @Test
    fun `returns created session before expiry`() {
        val store = ClientAuthSessionStore(ttlSeconds = 60)
        val created = store.create(
            username = "PlayerOne",
            uuid = "123e4567-e89b-12d3-a456-426614174000",
            elyAccessToken = "access-token-1",
            properties = listOf(AuthProfileProperty("textures", "value")),
            now = Instant.ofEpochSecond(100),
        )

        val loaded = store.get(created.sessionToken, Instant.ofEpochSecond(120))
        assertNotNull(loaded)
    }

    @Test
    fun `drops expired session`() {
        val store = ClientAuthSessionStore(ttlSeconds = 10)
        val created = store.create(
            username = "PlayerTwo",
            uuid = "123e4567-e89b-12d3-a456-426614174001",
            elyAccessToken = "access-token-2",
            properties = emptyList(),
            now = Instant.ofEpochSecond(100),
        )

        val loaded = store.get(created.sessionToken, Instant.ofEpochSecond(111))
        assertNull(loaded)
    }
}
