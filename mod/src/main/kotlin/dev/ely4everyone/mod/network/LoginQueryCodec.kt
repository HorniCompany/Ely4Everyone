package dev.ely4everyone.mod.network

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.minecraft.network.PacketByteBuf
import java.nio.charset.StandardCharsets

data class LoginChallengeRequest(
    val version: String,
    val nonce: String?,
    val audience: String?,
    val hostId: String?,
)

data class LoginChallengeResponse(
    val ticket: String?,
)

object LoginQueryCodec {
    fun decodeChallenge(buf: PacketByteBuf): LoginChallengeRequest {
        val payload = ByteArray(buf.readableBytes())
        buf.readBytes(payload)
        val values = parseLines(String(payload, StandardCharsets.UTF_8))
        return LoginChallengeRequest(
            version = values["ver"] ?: "unknown",
            nonce = values["nonce"],
            audience = values["aud"],
            hostId = values["host_id"],
        )
    }

    fun encodeResponse(ticket: String?): PacketByteBuf {
        val payload = buildString {
            appendLine("ver=v2")
            appendLine("ticket=${ticket.orEmpty()}")
        }.trim()

        return PacketByteBufs.create().apply {
            writeBytes(payload.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun parseLines(payload: String): Map<String, String> {
        return payload.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator) to line.substring(separator + 1)
                }
            }
            .toMap()
    }
}
