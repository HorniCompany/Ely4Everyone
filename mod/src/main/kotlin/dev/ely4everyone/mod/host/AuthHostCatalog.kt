package dev.ely4everyone.mod.host

import dev.ely4everyone.mod.config.AuthHostTrustState
import dev.ely4everyone.mod.config.ClientAuthConfig
import dev.ely4everyone.mod.config.RememberedAuthHost

enum class AuthHostSource {
    PRESET,
    REMEMBERED,
    DISCOVERED,
}

data class DiscoveredAuthHost(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val issuer: String,
    val audience: String,
)

data class AuthHostEntry(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val source: AuthHostSource,
    val trustState: AuthHostTrustState,
    val issuer: String? = null,
    val audience: String? = null,
)

data class AuthHostCatalog(
    val entries: List<AuthHostEntry>,
    val selected: AuthHostEntry?,
) {
    companion object {
        fun build(config: ClientAuthConfig, discoveredHosts: List<DiscoveredAuthHost>): AuthHostCatalog {
            val presetEntries = listOf(
                AuthHostEntry(
                    id = ClientAuthConfig.DEFAULT_REMOTE_HOST_ID,
                    displayName = "horni.cc relay",
                    baseUrl = ClientAuthConfig.DEFAULT_REMOTE_HOST_URL,
                    source = AuthHostSource.PRESET,
                    trustState = AuthHostTrustState.TRUSTED,
                ),
                AuthHostEntry(
                    id = ClientAuthConfig.CUSTOM_HOST_ID,
                    displayName = "Свой auth-host",
                    baseUrl = config.customHostUrl,
                    source = AuthHostSource.PRESET,
                    trustState = AuthHostTrustState.TRUSTED,
                ),
            )
            val rememberedEntries = config.rememberedHosts.map { host ->
                AuthHostEntry(
                    id = host.id,
                    displayName = host.displayName,
                    baseUrl = host.baseUrl,
                    source = AuthHostSource.REMEMBERED,
                    trustState = host.trustState,
                )
            }
            val discoveredEntries = discoveredHosts
                .filterNot { discovered -> rememberedEntries.any { it.id == discovered.id } }
                .map { discovered ->
                    AuthHostEntry(
                        id = discovered.id,
                        displayName = discovered.displayName,
                        baseUrl = discovered.baseUrl,
                        source = AuthHostSource.DISCOVERED,
                        trustState = AuthHostTrustState.PENDING,
                        issuer = discovered.issuer,
                        audience = discovered.audience,
                    )
                }
            val entries = (rememberedEntries + discoveredEntries + presetEntries)
                .distinctBy { it.id }
                .sortedWith(compareBy<AuthHostEntry> { sortRank(it.source) }.thenBy { it.displayName.lowercase() })
            return AuthHostCatalog(entries = entries, selected = entries.firstOrNull { it.id == config.selectedHostId })
        }

        fun trustHost(config: ClientAuthConfig, host: AuthHostEntry): ClientAuthConfig {
            val updatedRemembered = config.rememberedHosts
                .filterNot { it.id == host.id }
                .plus(
                    RememberedAuthHost(
                        id = host.id,
                        displayName = host.displayName,
                        baseUrl = host.baseUrl,
                        trustState = AuthHostTrustState.TRUSTED,
                    ),
                )
                .sortedBy { it.displayName.lowercase() }
            return config.copy(
                selectedHostId = host.id,
                rememberedHosts = updatedRemembered,
            )
        }

        fun forgetHost(config: ClientAuthConfig, hostId: String): ClientAuthConfig {
            return config.copy(
                selectedHostId = if (config.selectedHostId == hostId) ClientAuthConfig.DEFAULT_REMOTE_HOST_ID else config.selectedHostId,
                rememberedHosts = config.rememberedHosts.filterNot { it.id == hostId },
            )
        }

        private fun sortRank(source: AuthHostSource): Int {
            return when (source) {
                AuthHostSource.REMEMBERED -> 0
                AuthHostSource.DISCOVERED -> 1
                AuthHostSource.PRESET -> 2
            }
        }
    }
}
