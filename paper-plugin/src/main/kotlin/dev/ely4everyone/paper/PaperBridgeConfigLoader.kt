package dev.ely4everyone.paper

import org.bukkit.plugin.java.JavaPlugin

object PaperBridgeConfigLoader {
    fun load(plugin: JavaPlugin): PaperBridgeConfig {
        val config = plugin.config
        return PaperBridgeConfig(
            enabled = config.getBoolean("enabled", true),
            logTrustedLogins = config.getBoolean("log-trusted-logins", true),
            autoLoginDelayTicks = config.getLong("auto-login-delay-ticks", 20L),
            autoLoginCommand = config.getString("auto-login-command", "authme forcelogin {player}").orEmpty(),
            authHostEnabled = config.getBoolean("auth-host.enabled", true),
            authHostBindHost = config.getString("auth-host.bind-host", "127.0.0.1").orEmpty(),
            authHostBindPort = config.getInt("auth-host.bind-port", 19085),
            publicBaseUrl = config.getString("auth-host.public-base-url", "http://127.0.0.1:19085").orEmpty(),
            trustedIssuer = config.getString("auth-host.trusted-issuer", "paper-local").orEmpty(),
            expectedAudience = config.getString("auth-host.expected-audience", "ely-network").orEmpty(),
            ticketSigningKey = config.getString("auth-host.ticket-signing-key", "change-me").orEmpty(),
            elyClientId = config.getString("auth-host.ely-client-id", "").orEmpty(),
            elyClientSecret = config.getString("auth-host.ely-client-secret", "").orEmpty(),
            oauthScopes = config.getString("auth-host.oauth-scopes", "account_info minecraft_server_session").orEmpty(),
            oauthStateTtlSeconds = config.getLong("auth-host.oauth-state-ttl-seconds", 300L),
            clientSessionTtlSeconds = config.getLong("auth-host.client-session-ttl-seconds", 86400L),
            issuedTicketTtlSeconds = config.getLong("auth-host.issued-ticket-ttl-seconds", 300L),
        )
    }
}
