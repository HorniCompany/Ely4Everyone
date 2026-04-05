package dev.ely4everyone.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthProtocolCodecTest {
    @Test
    fun `encodes and decodes protocol v2 login challenge`() {
        val encoded = AuthProtocolCodec.encodeChallenge(
            LoginChallenge(
                version = AuthProtocol.VERSION,
                nonce = "nonce-42",
                audience = "ely-network",
                hostId = "velocity-local",
            ),
        )

        val decoded = AuthProtocolCodec.decodeChallenge(encoded)

        assertEquals(AuthProtocol.VERSION, decoded.version)
        assertEquals("nonce-42", decoded.nonce)
        assertEquals("ely-network", decoded.audience)
        assertEquals("velocity-local", decoded.hostId)
    }

    @Test
    fun `encodes empty login response when ticket is absent`() {
        val encoded = AuthProtocolCodec.encodeResponse(LoginResponse(ticket = null))
        val decoded = AuthProtocolCodec.decodeResponse(encoded)

        assertNull(decoded.ticket)
    }

    @Test
    fun `encodes and decodes discovery document`() {
        val encoded = AuthProtocolCodec.encodeDiscovery(
            DiscoveryDocument(
                version = AuthProtocol.VERSION,
                hostId = "paper-standalone",
                displayName = "Paper standalone auth host",
                publicBaseUrl = "http://10.0.0.15:19085",
                issuer = "paper-local",
                audience = "ely-network",
            ),
        )

        val decoded = AuthProtocolCodec.decodeDiscovery(encoded)

        assertEquals("paper-standalone", decoded.hostId)
        assertEquals("Paper standalone auth host", decoded.displayName)
        assertEquals("http://10.0.0.15:19085", decoded.publicBaseUrl)
        assertEquals("paper-local", decoded.issuer)
    }
}
