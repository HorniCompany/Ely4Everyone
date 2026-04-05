package dev.ely4everyone.mod.config

import java.util.Properties

data class ModConfig(
    val relayBaseUrl: String = ClientAuthConfig.DEFAULT_REMOTE_HOST_URL,
    val selectedAuthServerId: String = ClientAuthConfig.DEFAULT_REMOTE_HOST_ID,
    val customAuthServerUrl: String = ClientAuthConfig.DEFAULT_CUSTOM_HOST_URL,
    val serverDiscoveryMode: String = "scan-first",
    val preferredLoginMode: String = "ely-first",
    val rememberedHosts: List<RememberedAuthHost> = emptyList(),
) {
    fun toProperties(): Properties = Properties().also { props ->
        ClientAuthConfig(
            selectedHostId = selectedAuthServerId,
            customHostUrl = customAuthServerUrl,
            rememberedHosts = rememberedHosts,
            discoveryMode = serverDiscoveryMode,
            preferredLoginMode = preferredLoginMode,
        ).toProperties().forEach { key, value -> props[key] = value }
        props.setProperty("relay_base_url", resolvedAuthServerBaseUrl())
    }

    fun resolvedAuthServerBaseUrl(): String {
        rememberedHosts.firstOrNull { it.id == selectedAuthServerId && it.trustState == AuthHostTrustState.TRUSTED }
            ?.let { return it.baseUrl }
        val preset = AuthServerPresets.byId(selectedAuthServerId)
        return when (preset.id) {
            AuthServerPresets.CUSTOM -> customAuthServerUrl.ifBlank { relayBaseUrl }
            else -> preset.defaultUrl ?: relayBaseUrl
        }
    }

    companion object {
        fun fromProperties(properties: Properties): ModConfig {
            val typed = ClientAuthConfig.fromProperties(properties)
            return ModConfig(
                relayBaseUrl = properties.getProperty("relay_base_url", typed.resolveAuthHostBaseUrl()),
                selectedAuthServerId = typed.selectedHostId,
                customAuthServerUrl = typed.customHostUrl,
                serverDiscoveryMode = typed.discoveryMode,
                preferredLoginMode = typed.preferredLoginMode,
                rememberedHosts = typed.rememberedHosts,
            )
        }
    }
}
