package dev.ely4everyone.mod.net

import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

/** A property of a game profile as Ely.by and Mojang send it (`textures`, ...). */
data class ProfileProperty(
    val name: String,
    val value: String,
    val signature: String? = null,
)

/**
 * The few JSON fields the mod reads from Ely.by answers. Small on purpose: no JSON library is
 * bundled, and these answers are flat.
 */
object JsonFieldReader {
    fun readString(json: String, fieldName: String): String? {
        val regex = Regex(""""$fieldName"\s*:\s*"((?:\\.|[^"\\])*)"""")
        return regex.find(json)?.groupValues?.get(1)?.let(::decodeJsonString)
    }

    fun readLong(json: String, fieldName: String): Long? {
        val regex = Regex(""""$fieldName"\s*:\s*(\d+)""")
        return regex.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    fun readProperties(json: String): List<ProfileProperty> {
        val propertiesSection = Regex(""""properties"\s*:\s*\[(.*?)]""", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(json)
            ?.groupValues
            ?.get(1)
            ?: return emptyList()
        // Fields are read one by one: Ely.by sends name, signature, value, while Mojang sends
        // name, value, signature. A fixed order silently lost every Ely skin.
        return PROPERTY_OBJECT.findAll(propertiesSection).mapNotNull { match ->
            val property = match.value
            ProfileProperty(
                name = readString(property, "name") ?: return@mapNotNull null,
                value = readString(property, "value") ?: return@mapNotNull null,
                signature = readString(property, "signature")?.takeIf { it.isNotBlank() },
            )
        }.toList()
    }

    private val PROPERTY_OBJECT = Regex("""\{[^{}]*}""")

    private fun decodeJsonString(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }
}

/** Requests to Ely.by carry a User-Agent naming the mod and a timeout. */
object UpstreamHttp {
    const val USER_AGENT: String = "Ely4Everyone (+https://github.com/HorniCompany/Ely4Everyone)"
    val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)

    fun request(uri: URI): HttpRequest.Builder = HttpRequest.newBuilder(uri)
        .timeout(REQUEST_TIMEOUT)
        .header("User-Agent", USER_AGENT)
}
