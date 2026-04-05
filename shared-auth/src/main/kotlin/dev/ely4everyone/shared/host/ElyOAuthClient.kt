package dev.ely4everyone.shared.host

import dev.ely4everyone.shared.auth.AuthProfileProperty
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

data class OAuthClientSettings(
    val publicBaseUrl: String,
    val clientId: String,
    val clientSecret: String,
    val scopes: String,
)

data class ElyTokenResponse(
    val accessToken: String,
    val expiresInSeconds: Long,
    val refreshToken: String?,
)

data class ElyAccountInfo(
    val uuid: String,
    val username: String,
)

data class ElyTexturesProfile(
    val properties: List<AuthProfileProperty>,
)

class ElyOAuthClient(
    private val settings: OAuthClientSettings,
) {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun buildAuthorizationUri(state: String): URI {
        val query = listOf(
            "client_id" to settings.clientId,
            "redirect_uri" to redirectUri(),
            "response_type" to "code",
            "scope" to settings.scopes,
            "state" to state,
            "prompt" to "select_account",
        ).joinToString("&") { (key, value) ->
            key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)
        }
        return URI.create("https://account.ely.by/oauth2/v1?$query")
    }

    fun exchangeCode(code: String): ElyTokenResponse {
        val formBody = listOf(
            "client_id" to settings.clientId,
            "client_secret" to settings.clientSecret,
            "redirect_uri" to redirectUri(),
            "grant_type" to "authorization_code",
            "code" to code,
        ).joinToString("&") { (key, value) ->
            key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)
        }
        val response = httpClient.send(
            HttpRequest.newBuilder(URI.create("https://account.ely.by/api/oauth2/v1/token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        if (response.statusCode() !in 200..299) {
            val error = JsonFieldReader.readString(response.body(), "error_description")
                ?: JsonFieldReader.readString(response.body(), "error")
                ?: "HTTP ${response.statusCode()}"
            throw IllegalStateException("Failed to exchange Ely authorization code: $error")
        }
        return ElyTokenResponse(
            accessToken = JsonFieldReader.readString(response.body(), "access_token")
                ?: throw IllegalStateException("Ely token response does not contain access_token"),
            expiresInSeconds = JsonFieldReader.readLong(response.body(), "expires_in") ?: 0L,
            refreshToken = JsonFieldReader.readString(response.body(), "refresh_token"),
        )
    }

    fun refreshToken(refreshToken: String): ElyTokenResponse {
        val formBody = listOf(
            "client_id" to settings.clientId,
            "client_secret" to settings.clientSecret,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
        ).joinToString("&") { (key, value) ->
            key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)
        }
        val response = httpClient.send(
            HttpRequest.newBuilder(URI.create("https://account.ely.by/api/oauth2/v1/token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        if (response.statusCode() !in 200..299) {
            val error = JsonFieldReader.readString(response.body(), "error_description")
                ?: JsonFieldReader.readString(response.body(), "error")
                ?: "HTTP ${response.statusCode()}"
            throw IllegalStateException("Failed to refresh Ely token: $error")
        }
        return ElyTokenResponse(
            accessToken = JsonFieldReader.readString(response.body(), "access_token")
                ?: throw IllegalStateException("Ely token response does not contain access_token"),
            expiresInSeconds = JsonFieldReader.readLong(response.body(), "expires_in") ?: 0L,
            refreshToken = JsonFieldReader.readString(response.body(), "refresh_token"),
        )
    }

    fun fetchAccountInfo(accessToken: String): ElyAccountInfo {
        val response = httpClient.send(
            HttpRequest.newBuilder(URI.create("https://account.ely.by/api/account/v1/info"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer $accessToken")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        if (response.statusCode() !in 200..299) {
            val error = JsonFieldReader.readString(response.body(), "message") ?: "HTTP ${response.statusCode()}"
            throw IllegalStateException("Failed to fetch Ely account info: $error")
        }
        return ElyAccountInfo(
            uuid = JsonFieldReader.readString(response.body(), "uuid")
                ?: throw IllegalStateException("Ely account response does not contain uuid"),
            username = JsonFieldReader.readString(response.body(), "username")
                ?: throw IllegalStateException("Ely account response does not contain username"),
        )
    }

    fun fetchTexturesProfile(uuid: String): ElyTexturesProfile {
        val response = httpClient.send(
            HttpRequest.newBuilder(
                URI.create("https://authserver.ely.by/session/profile/" + uuid.replace("-", "")),
            )
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        if (response.statusCode() !in 200..299) {
            return ElyTexturesProfile(emptyList())
        }
        return ElyTexturesProfile(JsonFieldReader.readProperties(response.body()))
    }

    private fun redirectUri(): String = settings.publicBaseUrl.trimEnd('/') + "/oauth/callback"
}

object JsonFieldReader {
    fun readString(json: String, fieldName: String): String? {
        val regex = Regex(""""$fieldName"\s*:\s*"((?:\\.|[^"\\])*)"""")
        return regex.find(json)?.groupValues?.get(1)?.let(::decodeJsonString)
    }

    fun readLong(json: String, fieldName: String): Long? {
        val regex = Regex(""""$fieldName"\s*:\s*(\d+)""")
        return regex.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    fun readProperties(json: String): List<AuthProfileProperty> {
        val propertiesSection = Regex(""""properties"\s*:\s*\[(.*?)]""", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(json)
            ?.groupValues
            ?.get(1)
            ?: return emptyList()
        val propertyRegex = Regex(
            """\{\s*"name"\s*:\s*"((?:\\.|[^"\\])*)"\s*,\s*"value"\s*:\s*"((?:\\.|[^"\\])*)"(?:\s*,\s*"signature"\s*:\s*"((?:\\.|[^"\\])*)")?\s*}""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        return propertyRegex.findAll(propertiesSection).map { match ->
            AuthProfileProperty(
                name = decodeJsonString(match.groupValues[1]),
                value = decodeJsonString(match.groupValues[2]),
                signature = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.let(::decodeJsonString),
            )
        }.toList()
    }

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
