package dev.ely4everyone.shared.ticket

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class AuthTicketClaims(
    val version: String = "v2",
    val issuer: String,
    val audience: String,
    val subject: String,
    val username: String,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val ticketId: String,
    val nonce: String,
    val hostId: String,
)

data class VerifiedAuthTicket(
    val issuer: String,
    val audience: String,
    val subject: String,
    val username: String,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val ticketId: String,
    val nonce: String,
    val hostId: String,
)

object AuthTicketCodec {
    fun encode(claims: AuthTicketClaims, signingKey: String): String {
        val payload = buildString {
            appendLine("ver=${claims.version}")
            appendLine("iss=${claims.issuer}")
            appendLine("aud=${claims.audience}")
            appendLine("sub=${claims.subject}")
            appendLine("name=${claims.username}")
            appendLine("iat=${claims.issuedAtEpochSeconds}")
            appendLine("exp=${claims.expiresAtEpochSeconds}")
            appendLine("jti=${claims.ticketId}")
            appendLine("nonce=${claims.nonce}")
            append("host_id=${claims.hostId}")
        }
        val payloadPart = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        return payloadPart + "." + sign(payloadPart, signingKey)
    }

    fun verify(
        token: String,
        trustedIssuer: String,
        expectedAudience: String,
        signingKey: String,
        now: Instant = Instant.now(),
    ): VerifiedAuthTicket? {
        val parts = token.split('.')
        if (parts.size != 2) {
            return null
        }
        val payloadPart = parts[0]
        val signaturePart = parts[1]
        if (sign(payloadPart, signingKey) != signaturePart) {
            return null
        }

        val values = String(Base64.getUrlDecoder().decode(payloadPart), StandardCharsets.UTF_8)
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

        if (values["ver"] != "v2") {
            return null
        }

        val issuer = values["iss"] ?: return null
        val audience = values["aud"] ?: return null
        val subject = values["sub"] ?: return null
        val username = values["name"] ?: return null
        val issuedAt = values["iat"]?.toLongOrNull() ?: return null
        val expiresAt = values["exp"]?.toLongOrNull() ?: return null
        val ticketId = values["jti"] ?: return null
        val nonce = values["nonce"] ?: return null
        val hostId = values["host_id"] ?: return null

        if (issuer != trustedIssuer || audience != expectedAudience) {
            return null
        }
        if (expiresAt <= now.epochSecond || issuedAt > now.plusSeconds(30).epochSecond) {
            return null
        }

        return VerifiedAuthTicket(
            issuer = issuer,
            audience = audience,
            subject = subject,
            username = username,
            issuedAtEpochSeconds = issuedAt,
            expiresAtEpochSeconds = expiresAt,
            ticketId = ticketId,
            nonce = nonce,
            hostId = hostId,
        )
    }

    private fun sign(payloadPart: String, signingKey: String): String {
        val keySpec = SecretKeySpec(signingKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(keySpec)
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(payloadPart.toByteArray(StandardCharsets.UTF_8)))
    }
}
