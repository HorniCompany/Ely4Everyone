package dev.ely4everyone.mod.config

import java.util.Properties

enum class AuthHostTrustState {
    TRUSTED,
    PENDING,
    BLOCKED,
}

data class RememberedAuthHost(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val trustState: AuthHostTrustState,
)

data class ClientAuthConfig(
    val selectedHostId: String = DEFAULT_REMOTE_HOST_ID,
    val customHostUrl: String = DEFAULT_CUSTOM_HOST_URL,
    val rememberedHosts: List<RememberedAuthHost> = emptyList(),
    val discoveryMode: String = "scan-first",
    val preferredLoginMode: String = "ely-first",
) {
    fun toProperties(): Properties = Properties().also { props ->
        props.setProperty("config_version", "2")
        props.setProperty("selected_host_id", selectedHostId)
        props.setProperty("custom_host_url", customHostUrl)
        props.setProperty("discovery_mode", discoveryMode)
        props.setProperty("preferred_login_mode", preferredLoginMode)
        rememberedHosts.forEachIndexed { index, host ->
            props.setProperty("remembered_host.$index.id", host.id)
            props.setProperty("remembered_host.$index.display_name", host.displayName)
            props.setProperty("remembered_host.$index.base_url", host.baseUrl)
            props.setProperty("remembered_host.$index.trust_state", host.trustState.name)
        }
    }

    fun resolveAuthHostBaseUrl(): String {
        rememberedHosts.firstOrNull { it.id == selectedHostId && it.trustState == AuthHostTrustState.TRUSTED }
            ?.let { return it.baseUrl }
        return when (selectedHostId) {
            DEFAULT_REMOTE_HOST_ID -> DEFAULT_REMOTE_HOST_URL
            CUSTOM_HOST_ID -> customHostUrl.ifBlank { DEFAULT_REMOTE_HOST_URL }
            else -> DEFAULT_REMOTE_HOST_URL
        }
    }

    companion object {
        const val DEFAULT_REMOTE_HOST_ID: String = "horni-remote"
        const val DEFAULT_REMOTE_HOST_URL: String = "https://horni.cc/auth/ely4everyone"
        const val CUSTOM_HOST_ID: String = "custom"
        const val DEFAULT_CUSTOM_HOST_URL: String = "http://127.0.0.1:18085"

        fun fromProperties(properties: Properties): ClientAuthConfig {
            val version = properties.getProperty("config_version")
            val customHostUrl = properties.getProperty("custom_host_url")
                ?: properties.getProperty("custom_auth_server_url")
                ?: DEFAULT_CUSTOM_HOST_URL
            if (version != "2") {
                return ClientAuthConfig(
                    selectedHostId = DEFAULT_REMOTE_HOST_ID,
                    customHostUrl = customHostUrl,
                )
            }
            val rememberedHosts = buildList {
                var index = 0
                while (true) {
                    val prefix = "remembered_host.$index"
                    val id = properties.getProperty("$prefix.id") ?: break
                    add(
                        RememberedAuthHost(
                            id = id,
                            displayName = properties.getProperty("$prefix.display_name", id),
                            baseUrl = properties.getProperty("$prefix.base_url", DEFAULT_REMOTE_HOST_URL),
                            trustState = properties.getProperty("$prefix.trust_state")
                                ?.let { runCatching { AuthHostTrustState.valueOf(it) }.getOrNull() }
                                ?: AuthHostTrustState.PENDING,
                        ),
                    )
                    index++
                }
            }
            return ClientAuthConfig(
                selectedHostId = properties.getProperty("selected_host_id", DEFAULT_REMOTE_HOST_ID),
                customHostUrl = customHostUrl,
                rememberedHosts = rememberedHosts,
                discoveryMode = properties.getProperty("discovery_mode", "scan-first"),
                preferredLoginMode = properties.getProperty("preferred_login_mode", "ely-first"),
            )
        }
    }
}
