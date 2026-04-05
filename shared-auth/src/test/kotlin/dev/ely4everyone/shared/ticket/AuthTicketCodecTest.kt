package dev.ely4everyone.shared.ticket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AuthTicketCodecTest {
    @Test
    fun `verifies valid v2 ticket`() {
        val claims = AuthTicketClaims(
            version = "v2",
            issuer = "issuer-a",
            audience = "network-a",
            subject = "123e4567-e89b-12d3-a456-426614174000",
            username = "NotchButEly",
            issuedAtEpochSeconds = 1_000,
            expiresAtEpochSeconds = 2_000,
            ticketId = "ticket-1",
            nonce = "nonce-1",
            hostId = "velocity-local",
        )

        val token = AuthTicketCodec.encode(claims, "secret")
        val verified = AuthTicketCodec.verify(
            token = token,
            trustedIssuer = "issuer-a",
            expectedAudience = "network-a",
            signingKey = "secret",
            now = java.time.Instant.ofEpochSecond(1_500),
        )

        assertNotNull(verified)
        assertEquals("NotchButEly", verified.username)
        assertEquals("ticket-1", verified.ticketId)
        assertEquals("velocity-local", verified.hostId)
    }

    @Test
    fun `rejects wrong ticket version`() {
        val claims = AuthTicketClaims(
            version = "v1",
            issuer = "issuer-a",
            audience = "network-a",
            subject = "123e4567-e89b-12d3-a456-426614174000",
            username = "WrongVersion",
            issuedAtEpochSeconds = 1_000,
            expiresAtEpochSeconds = 2_000,
            ticketId = "ticket-2",
            nonce = "nonce-2",
            hostId = "velocity-local",
        )

        val token = AuthTicketCodec.encode(claims, "secret")
        val verified = AuthTicketCodec.verify(
            token = token,
            trustedIssuer = "issuer-a",
            expectedAudience = "network-a",
            signingKey = "secret",
            now = java.time.Instant.ofEpochSecond(1_500),
        )

        assertNull(verified)
    }
}
