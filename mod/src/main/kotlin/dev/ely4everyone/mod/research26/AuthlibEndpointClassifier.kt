package dev.ely4everyone.mod.research26

enum class AuthlibEndpointKind {
    AUTHSERVER,
    API,
    SESSIONSERVER,
    MINECRAFTSERVICES,
    TEXTURES,
    UNKNOWN,
}

data class AuthlibEndpointClassification(
    val url: String,
    val kind: AuthlibEndpointKind,
    val isMojang: Boolean,
    val isMinecraftServices: Boolean,
)

object AuthlibEndpointClassifier {
    fun classify(url: String): AuthlibEndpointClassification {
        val normalized = url.lowercase()
        val kind = when {
            "authserver" in normalized -> AuthlibEndpointKind.AUTHSERVER
            "sessionserver" in normalized || "/session/minecraft/" in normalized -> AuthlibEndpointKind.SESSIONSERVER
            "minecraftservices" in normalized -> AuthlibEndpointKind.MINECRAFTSERVICES
            "/api/" in normalized || "api.mojang.com" in normalized -> AuthlibEndpointKind.API
            "skins.minecraft.net" in normalized || "/skins/" in normalized || "textures" in normalized -> AuthlibEndpointKind.TEXTURES
            else -> AuthlibEndpointKind.UNKNOWN
        }
        return AuthlibEndpointClassification(
            url = url,
            kind = kind,
            isMojang = "mojang.com" in normalized || "minecraft.net" in normalized || "minecraftservices.com" in normalized,
            isMinecraftServices = "minecraftservices" in normalized,
        )
    }
}
