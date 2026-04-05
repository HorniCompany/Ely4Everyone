package dev.ely4everyone.mod.network

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import kotlin.test.Test
import kotlin.test.assertEquals

class LoginQueryCodecTest {
    @Test
    fun `decodes challenge and encodes response`() {
        val challengeBuf = PacketByteBufs.create().apply {
            writeBytes(
                """
                ver=v2
                nonce=test-nonce
                aud=test-audience
                host_id=velocity-local
                """.trimIndent().toByteArray(),
            )
        }

        val challenge = LoginQueryCodec.decodeChallenge(challengeBuf)
        assertEquals("v2", challenge.version)
        assertEquals("test-nonce", challenge.nonce)
        assertEquals("test-audience", challenge.audience)
        assertEquals("velocity-local", challenge.hostId)

        val responseBuf = LoginQueryCodec.encodeResponse("ticket-123")
        val payload = ByteArray(responseBuf.readableBytes())
        responseBuf.readBytes(payload)
        assertEquals("ver=v2\nticket=ticket-123", payload.toString(Charsets.UTF_8))
    }
}
