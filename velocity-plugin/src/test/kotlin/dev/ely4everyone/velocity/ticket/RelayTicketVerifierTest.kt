package dev.ely4everyone.velocity.ticket

import dev.ely4everyone.shared.ticket.AuthTicketClaims
import dev.ely4everyone.shared.ticket.AuthTicketCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RelayTicketVerifierTest {
    @Test
    fun `verifies valid issued ticket`() {
        val claims = AuthTicketClaims(
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
        assertEquals("nonce-1", verified.nonce)
        assertEquals("velocity-local", verified.hostId)
    }

    @Test
    fun `rejects expired ticket`() {
        val claims = AuthTicketClaims(
            issuer = "issuer-a",
            audience = "network-a",
            subject = "123e4567-e89b-12d3-a456-426614174000",
            username = "Expired",
            issuedAtEpochSeconds = 1_000,
            expiresAtEpochSeconds = 1_100,
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
