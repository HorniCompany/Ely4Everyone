package dev.ely4everyone.shared.protocol

import java.nio.charset.StandardCharsets

object AuthProtocol {
    const val VERSION: String = "v2"
}

data class LoginChallenge(
    val version: String = AuthProtocol.VERSION,
    val nonce: String,
    val audience: String,
    val hostId: String,
)

data class LoginResponse(
    val version: String = AuthProtocol.VERSION,
    val ticket: String?,
)

data class DiscoveryDocument(
    val version: String = AuthProtocol.VERSION,
    val hostId: String,
    val displayName: String,
    val publicBaseUrl: String,
    val issuer: String,
    val audience: String,
)

object AuthProtocolCodec {
    fun encodeChallenge(challenge: LoginChallenge): ByteArray {
        return encodeMap(
            mapOf(
                "ver" to challenge.version,
                "nonce" to challenge.nonce,
                "aud" to challenge.audience,
                "host_id" to challenge.hostId,
            ),
        )
    }

    fun decodeChallenge(payload: ByteArray): LoginChallenge {
        val values = decodeMap(payload)
        return LoginChallenge(
            version = values["ver"] ?: AuthProtocol.VERSION,
            nonce = values["nonce"].orEmpty(),
            audience = values["aud"].orEmpty(),
            hostId = values["host_id"].orEmpty(),
        )
    }

    fun encodeResponse(response: LoginResponse): ByteArray {
        return encodeMap(
            buildMap {
                put("ver", response.version)
                response.ticket?.let { put("ticket", it) }
            },
        )
    }

    fun decodeResponse(payload: ByteArray?): LoginResponse {
        if (payload == null || payload.isEmpty()) {
            return LoginResponse(ticket = null)
        }
        val values = decodeMap(payload)
        return LoginResponse(
            version = values["ver"] ?: AuthProtocol.VERSION,
            ticket = values["ticket"]?.ifBlank { null },
        )
    }

    fun encodeDiscovery(document: DiscoveryDocument): ByteArray {
        return encodeMap(
            mapOf(
                "ver" to document.version,
                "host_id" to document.hostId,
                "display_name" to document.displayName,
                "base_url" to document.publicBaseUrl,
                "issuer" to document.issuer,
                "aud" to document.audience,
            ),
        )
    }

    fun decodeDiscovery(payload: ByteArray): DiscoveryDocument {
        val values = decodeMap(payload)
        return DiscoveryDocument(
            version = values["ver"] ?: AuthProtocol.VERSION,
            hostId = values["host_id"].orEmpty(),
            displayName = values["display_name"].orEmpty(),
            publicBaseUrl = values["base_url"].orEmpty(),
            issuer = values["issuer"].orEmpty(),
            audience = values["aud"].orEmpty(),
        )
    }

    private fun encodeMap(values: Map<String, String>): ByteArray {
        return values.entries.joinToString("\n") { (key, value) -> "$key=$value" }
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun decodeMap(payload: ByteArray): Map<String, String> {
        return String(payload, StandardCharsets.UTF_8)
            .lineSequence()
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
