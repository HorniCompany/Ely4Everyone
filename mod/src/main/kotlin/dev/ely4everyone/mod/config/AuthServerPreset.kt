package dev.ely4everyone.mod.config

data class AuthServerPreset(
    val id: String,
    val label: String,
    val defaultUrl: String?,
)

object AuthServerPresets {
    const val LOCAL_VELOCITY: String = "local-embedded"
    const val HORNI_RELAY: String = ClientAuthConfig.DEFAULT_REMOTE_HOST_ID
    const val CUSTOM: String = ClientAuthConfig.CUSTOM_HOST_ID

    val ALL: List<AuthServerPreset> = listOf(
        AuthServerPreset(
            id = LOCAL_VELOCITY,
            label = "Локальный auth-host",
            defaultUrl = "http://127.0.0.1:18085",
        ),
        AuthServerPreset(
            id = HORNI_RELAY,
            label = "horni.cc relay",
            defaultUrl = ClientAuthConfig.DEFAULT_REMOTE_HOST_URL,
        ),
        AuthServerPreset(
            id = CUSTOM,
            label = "Свой auth-host",
            defaultUrl = null,
        ),
    )

    fun byId(id: String): AuthServerPreset {
        return ALL.firstOrNull { it.id == id } ?: ALL.first()
    }
}
