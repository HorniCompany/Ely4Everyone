package dev.ely4everyone.mod.identity

import dev.ely4everyone.mod.session.ClientSessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.time.Instant

class ElyIdentityManagerTest {
    @Test
    fun `restores Ely identity when session is complete and not expired`() {
        val now = Instant.parse("2029-01-01T00:00:00Z")
        val sessionState = ClientSessionState(
            elyAccessToken = "ely-access",
            authSessionExpiresAt = Instant.parse("2030-01-01T00:00:00Z"),
            elyUsername = "Cokeef",
            elyUuid = "123e4567-e89b-12d3-a456-426614174000",
            elyTexturesValue = "textures-value",
            elyTexturesSignature = "textures-signature",
        )

        val identity = ElyIdentityManager.fromClientSession(sessionState, now)

        assertNotNull(identity)
        assertEquals("Cokeef", identity.username)
        assertEquals("123e4567-e89b-12d3-a456-426614174000", identity.uuid)
        assertEquals("ely-access", identity.accessToken)
        assertEquals("textures-value", identity.texturesValue)
        assertEquals("textures-signature", identity.texturesSignature)
        assertEquals(true, sessionState.hasRestorableElyIdentity(now))
    }

    @Test
    fun `does not restore Ely identity when Ely access token is missing`() {
        val sessionState = ClientSessionState(
            elyAccessToken = null,
            authSessionExpiresAt = Instant.parse("2030-01-01T00:00:00Z"),
            elyUsername = "Cokeef",
            elyUuid = "123e4567-e89b-12d3-a456-426614174000",
        )

        assertNull(ElyIdentityManager.fromClientSession(sessionState))
        assertFalse(sessionState.hasRestorableElyIdentity(Instant.parse("2029-01-01T00:00:00Z")))
    }

    @Test
    fun `does not restore Ely identity when session is expired`() {
        val sessionState = ClientSessionState(
            elyAccessToken = "ely-access",
            authSessionExpiresAt = Instant.parse("2020-01-01T00:00:00Z"),
            elyUsername = "Cokeef",
            elyUuid = "123e4567-e89b-12d3-a456-426614174000",
        )

        assertNull(ElyIdentityManager.fromClientSession(sessionState))
        assertFalse(sessionState.hasUsableElyAccessToken(Instant.parse("2021-01-01T00:00:00Z")))
        assertFalse(sessionState.hasRestorableElyIdentity(Instant.parse("2021-01-01T00:00:00Z")))
    }

    @Test
    fun `does not restore Ely identity when username or uuid is blank`() {
        val blankUsername = ClientSessionState(
            elyAccessToken = "ely-access",
            authSessionExpiresAt = Instant.parse("2030-01-01T00:00:00Z"),
            elyUsername = "   ",
            elyUuid = "123e4567-e89b-12d3-a456-426614174000",
        )
        val blankUuid = ClientSessionState(
            elyAccessToken = "ely-access",
            authSessionExpiresAt = Instant.parse("2030-01-01T00:00:00Z"),
            elyUsername = "Cokeef",
            elyUuid = "   ",
        )

        assertNull(ElyIdentityManager.fromClientSession(blankUsername))
        assertNull(ElyIdentityManager.fromClientSession(blankUuid))
        assertFalse(blankUsername.hasRestorableElyIdentity(Instant.parse("2029-01-01T00:00:00Z")))
        assertFalse(blankUuid.hasRestorableElyIdentity(Instant.parse("2029-01-01T00:00:00Z")))
    }
}
