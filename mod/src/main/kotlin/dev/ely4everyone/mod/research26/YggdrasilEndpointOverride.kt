package dev.ely4everyone.mod.research26

data class AuthlibServiceRoots(
    val authHost: String,
    val apiHost: String,
    val sessionHost: String,
    val servicesHost: String,
)

object YggdrasilEndpointOverride {
    /** The auth host authlib gets redirected to, e.g. `-Dely4everyone.research26.authHost=http://127.0.0.1:18085`. */
    const val AUTH_HOST_PROPERTY: String = "ely4everyone.research26.authHost"
    const val DEFAULT_AUTH_HOST: String = "https://horni.cc/auth/ely4everyone"

    fun enabled(): Boolean {
        return System.getProperty(Research26MixinPlugin.REDIRECT_FLAG, "false")
            .equals("true", ignoreCase = true)
    }

    fun currentRootsOrNull(): AuthlibServiceRoots? {
        if (!enabled()) {
            return null
        }
        val baseUrl = System.getProperty(AUTH_HOST_PROPERTY)?.takeIf { it.isNotBlank() } ?: DEFAULT_AUTH_HOST
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
