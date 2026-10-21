package dev.ely4everyone.mod.auth

import dev.ely4everyone.shared.host.JsonFieldReader
import dev.ely4everyone.shared.host.UpstreamHttp
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

data class ElyDeviceCode(
    val deviceCode: String,
    val userCode: String,
    /** Page where the player types the code by hand, e.g. from a phone. */
    val verificationUri: String,
    /** Same page with the code filled in, so the browser on this PC skips the typing. */
    val verificationUriComplete: String,
    val intervalSeconds: Long,
    val expiresInSeconds: Long,
)

data class ElyTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long,
)

data class ElyProfile(
    val username: String,
    val uuid: String,
    val texturesValue: String?,
    val texturesSignature: String?,
)

sealed interface DeviceTokenPoll {
    /** The player has not confirmed yet. */
    data object Pending : DeviceTokenPoll

    /** Ely asks to poll less often (RFC 8628, section 3.5). */
    data object SlowDown : DeviceTokenPoll

    data class Granted(val tokens: ElyTokens) : DeviceTokenPoll

    data class Failed(val error: String) : DeviceTokenPoll
}

/**
 * Login straight against Ely.by, no auth host in between.
 *
 * Ely.by has a public "desktop application" client type: it has no secret, so the mod can talk to
 * Ely itself instead of going through a relay that holds a client secret. The login uses the
 * device code grant (RFC 8628): the player confirms a short code on account.ely.by and the mod
 * polls for the token. See docs/research-direct-ely-login.md.
 *
 * The browser loopback flow (RFC 8252) would also fit a desktop client, but Ely's own login page
 * currently drops `code_challenge`, so it cannot finish for public clients yet.
 */
object ElyDirectLogin {
    /** Public by design: desktop clients on Ely.by have no secret, PKCE or the device code protect the login. */
    const val CLIENT_ID: String = "ely4everyone1"
    const val SCOPES: String = "account_info minecraft_server_session offline_access"

    private const val DEVICE_CODE_URL: String = "https://account.ely.by/api/oauth2/v1/devicecode"
    private const val TOKEN_URL: String = "https://account.ely.by/api/oauth2/v1/token"
    private const val ACCOUNT_INFO_URL: String = "https://account.ely.by/api/account/v1/info"
    private const val SESSION_PROFILE_URL: String = "https://authserver.ely.by/session/profile/"
    private const val CODE_PAGE_URL: String = "https://account.ely.by/code"
    private const val DEVICE_CODE_GRANT: String = "urn:ietf:params:oauth:grant-type:device_code"

    private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    fun requestDeviceCode(): ElyDeviceCode {
        val response = postForm(DEVICE_CODE_URL, listOf("client_id" to CLIENT_ID, "scope" to SCOPES))
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Ely.by refused the login code: ${describeError(response.body(), response.statusCode())}")
        }
        return parseDeviceCode(response.body())
    }

    fun pollDeviceToken(deviceCode: String): DeviceTokenPoll {
        val response = postForm(
            TOKEN_URL,
            listOf("grant_type" to DEVICE_CODE_GRANT, "client_id" to CLIENT_ID, "device_code" to deviceCode),
        )
        return parseDeviceTokenPoll(response.statusCode(), response.body())
    }

    /** Public client: no secret, just the refresh token and our client id. */
    fun refresh(refreshToken: String): ElyTokens {
        val response = postForm(
            TOKEN_URL,
            listOf("grant_type" to "refresh_token", "client_id" to CLIENT_ID, "refresh_token" to refreshToken),
        )
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Ely.by refused to refresh the login: ${describeError(response.body(), response.statusCode())}")
        }
        return parseTokens(response.body())
            ?: throw IllegalStateException("Ely.by sent a refresh response without a token")
    }

    /** Nickname and UUID from the account, signed skin textures from the session server. */
    fun fetchProfile(accessToken: String): ElyProfile {
        val info = httpClient.send(
            UpstreamHttp.request(URI.create(ACCOUNT_INFO_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer $accessToken")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        if (info.statusCode() !in 200..299) {
            throw IllegalStateException("Ely.by did not return the account: ${describeError(info.body(), info.statusCode())}")
        }
        val username = JsonFieldReader.readString(info.body(), "username")
            ?: throw IllegalStateException("Ely.by account response has no username")
        val uuid = JsonFieldReader.readString(info.body(), "uuid")
            ?: throw IllegalStateException("Ely.by account response has no uuid")

        // Skins are nice to have: a failed lookup must not fail the login.
        val textures = runCatching {
            val profile = httpClient.send(
                // Without unsigned=false Ely, like Mojang, leaves the signature out.
                UpstreamHttp.request(URI.create(SESSION_PROFILE_URL + uuid.replace("-", "") + "?unsigned=false"))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
            )
            if (profile.statusCode() in 200..299) {
                JsonFieldReader.readProperties(profile.body()).firstOrNull { it.name == "textures" }
            } else {
                null
            }
        }.getOrNull()

        return ElyProfile(
            username = username,
            uuid = uuid,
            texturesValue = textures?.value,
            texturesSignature = textures?.signature,
        )
    }

    fun parseDeviceCode(body: String): ElyDeviceCode {
        val deviceCode = JsonFieldReader.readString(body, "device_code")
            ?: throw IllegalStateException("Ely.by sent no device_code")
        val userCode = JsonFieldReader.readString(body, "user_code")
            ?: throw IllegalStateException("Ely.by sent no user_code")
        // Ely builds the link from its own request and sends it as plain http. Always show https.
        val verificationUri = JsonFieldReader.readString(body, "verification_uri")
            ?.replaceFirst(Regex("^http://"), "https://")
            ?.takeIf { it.startsWith("https://") }
            ?: CODE_PAGE_URL
        return ElyDeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = verificationUri,
            verificationUriComplete = verificationUri + "?user_code=" + URLEncoder.encode(userCode, StandardCharsets.UTF_8),
            intervalSeconds = (JsonFieldReader.readLong(body, "interval") ?: 5L).coerceIn(1L, 60L),
            expiresInSeconds = (JsonFieldReader.readLong(body, "expires_in") ?: 600L).coerceIn(30L, 3600L),
        )
    }

    fun parseDeviceTokenPoll(statusCode: Int, body: String): DeviceTokenPoll {
        if (statusCode in 200..299) {
            return parseTokens(body)?.let(DeviceTokenPoll::Granted)
                ?: DeviceTokenPoll.Failed("Ely.by sent a token response without a token")
        }
        return when (val error = JsonFieldReader.readString(body, "error")) {
            "authorization_pending" -> DeviceTokenPoll.Pending
            "slow_down" -> DeviceTokenPoll.SlowDown
            "access_denied" -> DeviceTokenPoll.Failed("вход отклонён на Ely.by.")
            "expired_token" -> DeviceTokenPoll.Failed("код истёк, попробуй ещё раз.")
            else -> DeviceTokenPoll.Failed(describeError(body, statusCode, error))
        }
    }

    private fun parseTokens(body: String): ElyTokens? {
        val accessToken = JsonFieldReader.readString(body, "access_token")?.takeIf { it.isNotBlank() } ?: return null
        return ElyTokens(
            accessToken = accessToken,
            refreshToken = JsonFieldReader.readString(body, "refresh_token")?.takeIf { it.isNotBlank() },
            expiresInSeconds = JsonFieldReader.readLong(body, "expires_in") ?: 0L,
        )
    }

    private fun describeError(body: String, statusCode: Int, error: String? = JsonFieldReader.readString(body, "error")): String {
        val description = JsonFieldReader.readString(body, "error_description")
            ?: JsonFieldReader.readString(body, "message")
        return listOfNotNull(error, description).joinToString(": ").ifBlank { "HTTP $statusCode" }
    }

    private fun postForm(url: String, form: List<Pair<String, String>>): HttpResponse<String> {
        val body = form.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)
        }
        return httpClient.send(
            UpstreamHttp.request(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
    }
}
