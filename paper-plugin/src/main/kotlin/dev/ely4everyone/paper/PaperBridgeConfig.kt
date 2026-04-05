package dev.ely4everyone.paper

import dev.ely4everyone.shared.host.EmbeddedAuthHostConfig

data class PaperBridgeConfig(
    val enabled: Boolean,
    val logTrustedLogins: Boolean,
    val autoLoginDelayTicks: Long,
    val autoLoginCommand: String,
    val authHostEnabled: Boolean,
    val authHostBindHost: String,
    val authHostBindPort: Int,
    val publicBaseUrl: String,
    val trustedIssuer: String,
    val expectedAudience: String,
    val ticketSigningKey: String,
    val elyClientId: String,
    val elyClientSecret: String,
    val oauthScopes: String,
    val oauthStateTtlSeconds: Long,
    val clientSessionTtlSeconds: Long,
    val issuedTicketTtlSeconds: Long,
) {
    fun toEmbeddedAuthHostConfig(hostId: String = "paper-embedded"): EmbeddedAuthHostConfig {
        return EmbeddedAuthHostConfig(
            hostId = hostId,
            displayName = "Paper embedded auth host",
            trustedIssuer = trustedIssuer,
            expectedAudience = expectedAudience,
            ticketSigningKey = ticketSigningKey,
            enabled = authHostEnabled,
            bindHost = authHostBindHost,
            bindPort = authHostBindPort,
            publicBaseUrl = publicBaseUrl,
            clientId = elyClientId,
            clientSecret = elyClientSecret,
            oauthScopes = oauthScopes,
            oauthStateTtlSeconds = oauthStateTtlSeconds,
            clientSessionTtlSeconds = clientSessionTtlSeconds,
            issuedTicketTtlSeconds = issuedTicketTtlSeconds,
        )
    }
}
