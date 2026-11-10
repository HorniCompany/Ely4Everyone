package dev.ely4everyone.mod.research26

import dev.ely4everyone.mod.config.ModConfigStore

data class AuthlibServiceRoots(
    val authHost: String,
    val apiHost: String,
    val sessionHost: String,
    val servicesHost: String,
)

object YggdrasilEndpointOverride {
    fun enabled(): Boolean {
        return System.getProperty(Research26MixinPlugin.REDIRECT_FLAG, "false")
            .equals("true", ignoreCase = true)
    }

    fun currentRootsOrNull(): AuthlibServiceRoots? {
        if (!enabled()) {
            return null
        }
        val baseUrl = ModConfigStore.load().resolvedAuthServerBaseUrl().trimEnd('/')
        return fromAuthHostBaseUrl(baseUrl)
    }

    fun fromAuthHostBaseUrl(baseUrl: String): AuthlibServiceRoots {
        val normalized = baseUrl.trimEnd('/')
        return AuthlibServiceRoots(
            authHost = "$normalized/authserver",
            apiHost = "$normalized/api",
            sessionHost = "$normalized/sessionserver",
            servicesHost = "$normalized/minecraftservices",
        )
    }

    fun sessionBaseUrl(roots: AuthlibServiceRoots): String {
        return roots.sessionHost.trimEnd('/') + "/session/minecraft/"
    }
}
