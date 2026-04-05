package dev.ely4everyone.velocity.config

import dev.ely4everyone.shared.host.EmbeddedAuthHostConfig
import java.util.Properties

data class ProxyConfig(
    val authMode: String = "FULL_HYBRID",
    val enableFastloginHook: Boolean = true,
    val enableSkinsrestorerHook: Boolean = true,
    val trustedIssuer: String = "local-dev",
    val expectedAudience: String = "local-network",
    val ticketSigningKey: String = "change-me",
    val embeddedAuthEnabled: Boolean = true,
    val embeddedAuthBindHost: String = "127.0.0.1",
    val embeddedAuthBindPort: Int = 8085,
    val publicBaseUrl: String = "http://127.0.0.1:8085",
    val elyClientId: String = "",
    val elyClientSecret: String = "",
    val oauthScopes: String = "account_info minecraft_server_session",
    val oauthStateTtlSeconds: Long = 300,
    val clientSessionTtlSeconds: Long = 86400,
    val issuedTicketTtlSeconds: Long = 300,
) {
    fun toEmbeddedAuthHostConfig(hostId: String = "velocity-embedded"): EmbeddedAuthHostConfig {
        return EmbeddedAuthHostConfig(
            hostId = hostId,
            displayName = "Velocity embedded auth host",
            trustedIssuer = trustedIssuer,
            expectedAudience = expectedAudience,
            ticketSigningKey = ticketSigningKey,
            enabled = embeddedAuthEnabled,
            bindHost = embeddedAuthBindHost,
            bindPort = embeddedAuthBindPort,
            publicBaseUrl = publicBaseUrl,
            clientId = elyClientId,
            clientSecret = elyClientSecret,
            oauthScopes = oauthScopes,
            oauthStateTtlSeconds = oauthStateTtlSeconds,
            clientSessionTtlSeconds = clientSessionTtlSeconds,
            issuedTicketTtlSeconds = issuedTicketTtlSeconds,
        )
    }

    fun toProperties(): Properties = Properties().also { props ->
        props.setProperty("auth_mode", authMode)
        props.setProperty("enable_fastlogin_hook", enableFastloginHook.toString())
        props.setProperty("enable_skinsrestorer_hook", enableSkinsrestorerHook.toString())
        props.setProperty("trusted_issuer", trustedIssuer)
        props.setProperty("expected_audience", expectedAudience)
        props.setProperty("ticket_signing_key", ticketSigningKey)
        props.setProperty("embedded_auth_enabled", embeddedAuthEnabled.toString())
        props.setProperty("embedded_auth_bind_host", embeddedAuthBindHost)
        props.setProperty("embedded_auth_bind_port", embeddedAuthBindPort.toString())
        props.setProperty("public_base_url", publicBaseUrl)
        props.setProperty("ely_client_id", elyClientId)
        props.setProperty("ely_client_secret", elyClientSecret)
        props.setProperty("oauth_scopes", oauthScopes)
        props.setProperty("oauth_state_ttl_seconds", oauthStateTtlSeconds.toString())
        props.setProperty("client_session_ttl_seconds", clientSessionTtlSeconds.toString())
        props.setProperty("issued_ticket_ttl_seconds", issuedTicketTtlSeconds.toString())
    }

    companion object {
        fun fromProperties(properties: Properties): ProxyConfig = ProxyConfig(
            authMode = properties.getProperty("auth_mode", "FULL_HYBRID"),
            enableFastloginHook = properties.getProperty("enable_fastlogin_hook", "true").toBooleanStrictOrNull() ?: true,
            enableSkinsrestorerHook = properties.getProperty("enable_skinsrestorer_hook", "true").toBooleanStrictOrNull() ?: true,
            trustedIssuer = properties.getProperty("trusted_issuer", "local-dev"),
            expectedAudience = properties.getProperty("expected_audience", "local-network"),
            ticketSigningKey = properties.getProperty("ticket_signing_key", "change-me"),
            embeddedAuthEnabled = properties.getProperty("embedded_auth_enabled", "true").toBooleanStrictOrNull() ?: true,
            embeddedAuthBindHost = properties.getProperty("embedded_auth_bind_host", "127.0.0.1"),
            embeddedAuthBindPort = properties.getProperty("embedded_auth_bind_port", "8085").toIntOrNull() ?: 8085,
            publicBaseUrl = properties.getProperty("public_base_url", "http://127.0.0.1:8085"),
            elyClientId = properties.getProperty("ely_client_id", ""),
            elyClientSecret = properties.getProperty("ely_client_secret", ""),
            oauthScopes = properties.getProperty("oauth_scopes", "account_info minecraft_server_session"),
            oauthStateTtlSeconds = properties.getProperty("oauth_state_ttl_seconds", "300").toLongOrNull() ?: 300L,
            clientSessionTtlSeconds = properties.getProperty("client_session_ttl_seconds", "86400").toLongOrNull() ?: 86400L,
            issuedTicketTtlSeconds = properties.getProperty("issued_ticket_ttl_seconds", "300").toLongOrNull() ?: 300L,
        )
    }
}
