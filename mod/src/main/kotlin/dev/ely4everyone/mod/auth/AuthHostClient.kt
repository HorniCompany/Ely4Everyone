package dev.ely4everyone.mod.auth

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture

data class AuthPollResult(
    val status: String,
    val authSessionToken: String? = null,
    val elyAccessToken: String? = null,
    val username: String? = null,
    val uuid: String? = null,
    val expiresAtEpochSeconds: Long? = null,
    val texturesValue: String? = null,
    val texturesSignature: String? = null,
    val error: String? = null,
)

data class IssuedLoginTicketResult(
    val ticket: String?,
    val expiresAtEpochSeconds: Long?,
    val error: String? = null,
)

/**
 * Talks to an auth host (relay). New logins go straight to Ely.by ([ElyDirectLogin]); this stays for
 * sessions made by older versions through a host, and for the host's login tickets.
 */
object AuthHostClient {
    // A slow or dead auth host must fail the request, not hang it until the OS gives up.
    private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    fun issueLoginTicketAsync(
        relayBaseUrl: String,
        authSessionToken: String,
        nonce: String,
        audience: String?,
    ): CompletableFuture<IssuedLoginTicketResult> {
        val uri = buildString {
            append(relayBaseUrl.trimEnd('/'))
            append("/api/v1/auth/issue-ticket?nonce=")
            append(encode(nonce))
            if (!audience.isNullOrBlank()) {
                append("&audience=")
                append(encode(audience))
            }
        }

        // The session token goes in a header: a URL ends up in the host's access logs.
        val request = HttpRequest.newBuilder(URI.create(uri))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer $authSessionToken")
            .GET()
            .build()
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .thenApply { response ->
                val values = parsePayload(response.body())
                IssuedLoginTicketResult(
                    ticket = values["ticket"],
                    expiresAtEpochSeconds = values["exp"]?.toLongOrNull(),
                    error = values["error"],
                )
            }
    }

    fun refreshSession(relayBaseUrl: String, sessionToken: String): AuthPollResult {
        val request = HttpRequest.newBuilder(
            URI.create(relayBaseUrl.trimEnd('/') + "/api/v1/auth/refresh"),
        )
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer $sessionToken")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        val values = parsePayload(response.body())
        return AuthPollResult(
            status = values["status"] ?: if (response.statusCode() == 404) "failed" else "pending",
            elyAccessToken = values["ely_access_token"],
            expiresAtEpochSeconds = values["exp"]?.toLongOrNull(),
            username = values["username"],
            uuid = values["uuid"],
            texturesValue = values["textures_value"],
            texturesSignature = values["textures_signature"],
            error = values["error"],
        )
    }



    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
    }

    private fun parsePayload(payload: String): Map<String, String> {
        val trimmed = payload.trim()
        return if (trimmed.startsWith("{")) {
            parseJsonObject(trimmed)
        } else {
            parseLines(trimmed)
        }
    }

    private fun parseLines(payload: String): Map<String, String> {
        return payload.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator) to line.substring(separator + 1)
                }
            }
            .toMap()
    }

    private fun parseJsonObject(payload: String): Map<String, String> {
        fun readString(fieldName: String): String? {
            val regex = Regex("\"" + Regex.escape(fieldName) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            return regex.find(payload)?.groupValues?.get(1)?.let(::decodeJsonString)
        }

        fun readLong(fieldName: String): String? {
            val regex = Regex("\"" + Regex.escape(fieldName) + "\"\\s*:\\s*(\\d+)")
            return regex.find(payload)?.groupValues?.get(1)
        }

        return buildMap {
            readString("status")?.let { put("status", it) }
            readString("auth_session_token")?.let { put("auth_session_token", it) }
            readString("ely_access_token")?.let { put("ely_access_token", it) }
            readString("username")?.let { put("username", it) }
            readString("uuid")?.let { put("uuid", it) }
            readString("textures_value")?.let { put("textures_value", it) }
            readString("textures_signature")?.let { put("textures_signature", it) }
            readString("ticket")?.let { put("ticket", it) }
            readString("error")?.let { put("error", it) }
            readLong("exp")?.let { put("exp", it) }
        }
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
