package dev.ely4everyone.shared.host

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.ely4everyone.shared.auth.AuthProfileProperty
import dev.ely4everyone.shared.oauth.OAuthStateStore
import dev.ely4everyone.shared.oauth.PendingAuthStatus
import dev.ely4everyone.shared.protocol.AuthProtocol
import dev.ely4everyone.shared.protocol.AuthProtocolCodec
import dev.ely4everyone.shared.protocol.DiscoveryDocument
import dev.ely4everyone.shared.session.ClientAuthSessionStore
import dev.ely4everyone.shared.ticket.AuthTicketClaims
import dev.ely4everyone.shared.ticket.AuthTicketCodec
import org.slf4j.Logger
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class EmbeddedAuthHostConfig(
    val hostId: String,
    val displayName: String,
    val trustedIssuer: String,
    val expectedAudience: String,
    val ticketSigningKey: String,
    val enabled: Boolean = true,
    val bindHost: String,
    val bindPort: Int,
    val publicBaseUrl: String,
    val clientId: String = "",
    val clientSecret: String = "",
    val oauthScopes: String = "account_info minecraft_server_session",
    val oauthStateTtlSeconds: Long = 300,
    val clientSessionTtlSeconds: Long = 86400,
    val issuedTicketTtlSeconds: Long = 300,
)

data class IssuedTicketResult(
    val ticket: String,
    val expiresAtEpochSeconds: Long,
)

class EmbeddedAuthHttpServer(
    private val config: EmbeddedAuthHostConfig,
    private val logger: Logger,
    dataDirectory: Path,
    private val pendingPremiumLoginStore: PendingPremiumLoginStore = PendingPremiumLoginStore(config.issuedTicketTtlSeconds),
    private val profileResolver: HybridProfileResolver = HybridProfileResolver(HttpHybridAuthUpstream()),
    private val sessionVerifier: HybridSessionVerifier = HybridSessionVerifier(HttpHybridAuthUpstream(), pendingPremiumLoginStore),
) {
    private val stateStore = OAuthStateStore(config.oauthStateTtlSeconds)
    private val clientSessionStore = ClientAuthSessionStore(
        ttlSeconds = config.clientSessionTtlSeconds,
        storagePath = dataDirectory.resolve("client-auth-sessions.bin"),
    )
    private val issuedLoginTicketStore = IssuedLoginTicketStore()
    private val oauthClient = ElyOAuthClient(
        OAuthClientSettings(
            publicBaseUrl = config.publicBaseUrl,
            clientId = config.clientId,
            clientSecret = config.clientSecret,
            scopes = config.oauthScopes,
        ),
    )
    private var executorService: ExecutorService? = null
    private var server: HttpServer? = null

    fun start() {
        if (!config.enabled) {
            logger.info("Embedded Ely auth host is disabled in config.")
            return
        }
        val address = InetSocketAddress(config.bindHost, config.bindPort)
        executorService = Executors.newCachedThreadPool()
        server = HttpServer.create(address, 0).apply {
            executor = executorService
            createContext("/health") { exchange -> handleHealth(exchange) }
            createContext("/api/v2/discovery") { exchange -> handleDiscovery(exchange) }
            createContext("/api/profiles/minecraft") { exchange -> handleBatchProfiles(exchange) }
            createContext("/api/authlib-injector") { exchange -> handleAuthlibInjectorMetadata(exchange) }
            createContext("/api/v1/config") { exchange -> handleConfig(exchange) }
            createContext("/api/v1/auth/start") { exchange -> handleAuthStart(exchange) }
            createContext("/api/v1/auth/poll") { exchange -> handleAuthPoll(exchange) }

            createContext("/api/v1/auth/issue-ticket") { exchange -> handleIssueTicket(exchange) }
            createContext("/api/v1/auth/refresh") { exchange -> handleRefreshSession(exchange) }
            createContext("/api/v1/auth/dev/latest-session") { exchange -> handleLatestSession(exchange) }
            createContext("/api/v1/dev/tickets") { exchange -> handleDevTickets(exchange) }
            createContext("/sessionserver/session/minecraft/join") { exchange -> handleSessionJoin(exchange) }
            createContext("/sessionserver/session/minecraft/hasJoined") { exchange -> handleSessionHasJoined(exchange) }
            createContext("/sessionserver/session/minecraft/profile") { exchange -> handleSessionProfile(exchange) }
            createContext("/oauth/callback") { exchange -> handleOAuthCallback(exchange) }
            createContext("/api/v1/auth/limboauth") { exchange -> handleLimboAuthIsPremium(exchange) }
            createContext("/") { exchange -> handleRoot(exchange) }
            start()
        }
        logger.info(
            "Embedded Ely auth host started on {}:{} (publicBaseUrl={}, hostId={})",
            config.bindHost,
            config.bindPort,
            config.publicBaseUrl,
            config.hostId,
        )
    }

    fun stop() {
        server?.stop(0)
        server = null
        executorService?.shutdownNow()
        executorService = null
    }

    private fun handleRefreshSession(exchange: HttpExchange) {
        val params = requestParameters(exchange)
        val sessionToken = params["session_token"]
        if (sessionToken.isNullOrBlank()) {
            writeText(exchange, 400, "text/plain; charset=utf-8", "status=failed\nerror=missing_session_token")
            return
        }
        val clientSession = clientSessionStore.get(sessionToken)
        if (clientSession == null) {
            writeText(exchange, 404, "text/plain; charset=utf-8", "status=failed\nerror=invalid_session")
            return
        }
        if (clientSession.refreshToken.isNullOrEmpty()) {
            writeText(exchange, 400, "text/plain; charset=utf-8", "status=failed\nerror=no_refresh_token")
            return
        }

        runCatching {
            val tokenResponse = oauthClient.refreshToken(clientSession.refreshToken)
            val updated = clientSessionStore.updateTokens(
                sessionToken = sessionToken,
                newElyAccessToken = tokenResponse.accessToken,
                newRefreshToken = tokenResponse.refreshToken,
                newExpiresAtEpochSeconds = Instant.now().plusSeconds(config.clientSessionTtlSeconds).epochSecond
            )
            if (updated != null) {
                writeText(
                    exchange,
                    200,
                    "text/plain; charset=utf-8",
                    """
                    status=completed
                    ely_access_token=${updated.elyAccessToken}
                    exp=${updated.expiresAtEpochSeconds}
                    """.trimIndent(),
                )
            } else {
                writeText(exchange, 500, "text/plain; charset=utf-8", "status=failed\nerror=session_update_failed")
            }
        }.onFailure { exception ->
            logger.warn("Failed to refresh token via OAuth client", exception)
            writeText(exchange, 500, "text/plain; charset=utf-8", "status=failed\nerror=${exception.message ?: "refresh_error"}")
        }
    }

    private fun handleLatestSession(exchange: HttpExchange) {
        val errorMessage = "status=failed\nerror=Критическая уязвимость!\nВаша версия мода устарела и содержит критический баг (подмена сессии).\nПожалуйста, скачайте и установите новую версию мода."
        writeText(exchange, 403, "text/plain; charset=utf-8", errorMessage)
    }

    fun consumeIssuedTicketRecord(ticketId: String): IssuedLoginTicketRecord? = issuedLoginTicketStore.consume(ticketId)

    fun registerPendingLogin(
        username: String,
        authority: AuthAuthority?,
        expectedUuid: UUID?,
        clientUuid: UUID?,
        allowedAuthorities: Set<AuthAuthority>,
    ) {
        pendingPremiumLoginStore.put(
            username = username,
            authority = authority,
            expectedUuid = expectedUuid,
            clientUuid = clientUuid,
            allowedAuthorities = allowedAuthorities,
        )
    }

    fun clearPendingLogin(username: String) {
        pendingPremiumLoginStore.clear(username)
    }

    fun localBaseUrl(): String? {
        val address = server?.address ?: return null
        return "http://${address.hostString}:${address.port}"
    }

    private fun handleLimboAuthIsPremium(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET") {
                writeText(exchange, 405, "application/json; charset=utf-8", """{"error": "Method Not Allowed"}""")
                return
            }

            // Path is /api/v1/auth/limboauth/<username>
            val path = exchange.requestURI.path
            val parts = path.split("/")
            if (parts.size < 6) {
                writeText(exchange, 400, "application/json; charset=utf-8", """{"error": "Bad Request"}""")
                return
            }

            val username = parts[5]
            val pending = pendingPremiumLoginStore.get(username)
            val preferredAuthority = pending?.authority
            val session = clientSessionStore.findByUsername(username)

            if (preferredAuthority != AuthAuthority.MOJANG && session != null) {
                val response = """{"name":"$username","id":"${session.uuid.replace("-", "")}"}"""
                writeText(exchange, 200, "application/json; charset=utf-8", response)
                return
            }

            val lookup = profileResolver.lookup(username)
            val responseProfile = when (preferredAuthority) {
                AuthAuthority.ELY -> lookup.elyProfile
                AuthAuthority.MOJANG -> lookup.mojangProfile
                else -> lookup.elyProfile ?: lookup.mojangProfile
            }
            if (responseProfile != null) {
                writeText(exchange, 200, "application/json; charset=utf-8", responseProfile.rawJson)
                return
            }

            val notFoundBody = """{"path":"$path","error":"Not Found","errorMessage":"Couldn't find any profile with name $username"}"""
            writeText(exchange, 404, "application/json; charset=utf-8", notFoundBody)
        } catch (e: Exception) {
            logger.error("Error handling LimboAuth isPremium request", e)
            writeText(exchange, 500, "application/json; charset=utf-8", """{"error": "Internal Server Error"}""")
        }
    }

    private fun handleHealth(exchange: HttpExchange) {
        writeText(
            exchange,
            200,
            "text/plain; charset=utf-8",
            """
            status=ok
            version=${AuthProtocol.VERSION}
            host_id=${config.hostId}
            issuer=${config.trustedIssuer}
            oauth_enabled=${isOAuthConfigured()}
            """.trimIndent(),
        )
    }

    private fun handleDiscovery(exchange: HttpExchange) {
        writeResponse(
            exchange,
            200,
            "text/plain; charset=utf-8",
            AuthProtocolCodec.encodeDiscovery(
                DiscoveryDocument(
                    hostId = config.hostId,
                    displayName = config.displayName,
                    publicBaseUrl = config.publicBaseUrl,
                    issuer = config.trustedIssuer,
                    audience = config.expectedAudience,
                ),
            ),
        )
    }

    private fun handleConfig(exchange: HttpExchange) {
        writeText(
            exchange,
            200,
            "text/plain; charset=utf-8",
            """
            issuer=${config.trustedIssuer}
            relay_base_url=${config.publicBaseUrl}
            audience=${config.expectedAudience}
            host_id=${config.hostId}
            oauth_enabled=${isOAuthConfigured()}
            dev_tickets_enabled=true
            """.trimIndent(),
        )
    }

    private fun handleBatchProfiles(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            writeText(exchange, 405, "application/json; charset=utf-8", """{"error":"Method Not Allowed"}""")
            return
        }
        val names = parseJsonStringArray(exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8))
        val profiles = names.mapNotNull { username ->
            val pending = pendingPremiumLoginStore.get(username)
            val lookup = profileResolver.lookup(username)
            when (pending?.authority) {
                AuthAuthority.ELY -> lookup.elyProfile
                AuthAuthority.MOJANG -> lookup.mojangProfile
                else -> lookup.elyProfile ?: lookup.mojangProfile
            }
        }
        writeText(
            exchange,
            200,
            "application/json; charset=utf-8",
            profiles.joinToString(prefix = "[", postfix = "]") { it.rawJson },
        )
    }

    private fun handleSessionJoin(exchange: HttpExchange) {
        val contentType = exchange.requestHeaders.getFirst("Content-Type") ?: "application/json"
        val response = sessionVerifier.submitJoin(exchange.requestBody.readBytes(), contentType)
        writeResponse(exchange, response.statusCode, response.contentType, response.body)
    }

    private fun handleSessionHasJoined(exchange: HttpExchange) {
        val params = parseQuery(exchange.requestURI.rawQuery)
        val username = params["username"]
        if (username.isNullOrBlank()) {
            writeText(exchange, 400, "application/json; charset=utf-8", """{"error":"missing_username"}""")
            return
        }
        val response = sessionVerifier.verifyHasJoined(
            username = username,
            serverId = params["serverId"],
            ip = params["ip"],
        )
        writeResponse(exchange, response.statusCode, response.contentType, response.body)
    }

    private fun handleSessionProfile(exchange: HttpExchange) {
        val prefix = "/sessionserver/session/minecraft/profile"
        val fullPath = exchange.requestURI.path
        val uuidPath = fullPath.removePrefix(prefix).removePrefix("/").takeIf { it.isNotBlank() }
        if (uuidPath == null) {
            writeText(exchange, 400, "text/plain; charset=utf-8", "error=missing_profile_uuid")
            return
        }
        val response = sessionVerifier.fetchProfile(uuidPath, exchange.requestURI.rawQuery)
        writeResponse(exchange, response.statusCode, response.contentType, response.body)
    }

    private fun handleAuthStart(exchange: HttpExchange) {
        val state = requestParameters(exchange)["state"]
        if (state.isNullOrBlank()) {
            writeText(exchange, 400, "text/plain; charset=utf-8", "error=missing_state")
            return
        }
        if (!isOAuthConfigured()) {
            writeText(exchange, 503, "text/plain; charset=utf-8", "error=oauth_not_configured")
            return
        }
        stateStore.create(state)
        redirect(exchange, oauthClient.buildAuthorizationUri(state))
    }

    private fun handleAuthPoll(exchange: HttpExchange) {
        val state = requestParameters(exchange)["state"]
        if (state.isNullOrBlank()) {
            writeText(exchange, 400, "text/plain; charset=utf-8", "status=failed\nerror=missing_state")
            return
        }
        val session = stateStore.get(state)
        if (session == null) {
            writeText(exchange, 404, "text/plain; charset=utf-8", "status=failed\nerror=unknown_state")
            return
        }
        val body = when (session.status) {
            PendingAuthStatus.PENDING -> "status=pending"
            PendingAuthStatus.FAILED -> "status=failed\nerror=${session.error.orEmpty()}"
            PendingAuthStatus.COMPLETED -> """
                status=completed
                auth_session_token=${session.authSessionToken.orEmpty()}
                ely_access_token=${session.elyAccessToken.orEmpty()}
                username=${session.username.orEmpty()}
                uuid=${session.uuid.orEmpty()}
                exp=${session.expiresAtEpochSeconds}
                textures_value=${session.texturesValue.orEmpty()}
                textures_signature=${session.texturesSignature.orEmpty()}
            """.trimIndent()
        }
        writeText(exchange, 200, "text/plain; charset=utf-8", body)
    }



    private fun handleIssueTicket(exchange: HttpExchange) {
        val params = requestParameters(exchange)
        val sessionToken = params["session_token"]
        val nonce = params["nonce"]
        val audience = params["audience"] ?: config.expectedAudience
        if (sessionToken.isNullOrBlank() || nonce.isNullOrBlank()) {
            writeText(exchange, 400, "text/plain; charset=utf-8", "status=failed\nerror=missing_session_token_or_nonce")
            return
        }
        val clientSession = clientSessionStore.get(sessionToken)
        if (clientSession == null) {
            writeText(exchange, 404, "text/plain; charset=utf-8", "status=failed\nerror=invalid_session")
            return
        }
        val issued = issueTicket(clientSession.uuid, clientSession.username, audience, nonce, clientSession.properties)
        writeText(
            exchange,
            200,
            "text/plain; charset=utf-8",
            """
            status=completed
            ticket=${issued.ticket}
            username=${clientSession.username}
            uuid=${clientSession.uuid}
            exp=${issued.expiresAtEpochSeconds}
            """.trimIndent(),
        )
    }

    private fun handleDevTickets(exchange: HttpExchange) {
        val params = requestParameters(exchange)
        val username = params["username"]
        val uuid = params["uuid"]
        val audience = params["audience"] ?: config.expectedAudience
        val nonce = params["nonce"] ?: UUID.randomUUID().toString()
        if (username.isNullOrBlank() || uuid.isNullOrBlank()) {
            writeText(exchange, 400, "text/plain; charset=utf-8", "error=missing_username_or_uuid")
            return
        }
        val issued = issueTicket(uuid, username, audience, nonce, emptyList())
        writeText(
            exchange,
            200,
            "text/plain; charset=utf-8",
            """
            status=completed
            ticket=${issued.ticket}
            username=$username
            uuid=$uuid
            exp=${issued.expiresAtEpochSeconds}
            """.trimIndent(),
        )
    }

    private fun handleOAuthCallback(exchange: HttpExchange) {
        val params = requestParameters(exchange)
        val state = params["state"]
        if (state.isNullOrBlank()) {
            writeHtml(exchange, 400, "<h1>Ely4Everyone</h1><p>Missing OAuth state.</p>")
            return
        }
        if (params["error"] != null) {
            val error = params["error_message"] ?: params["error"].orEmpty()
            stateStore.fail(state, error)
            writeHtml(exchange, 400, "<h1>Ely4Everyone</h1><p>Authorization failed: ${escapeHtml(error)}</p>")
            return
        }
        val code = params["code"]
        if (code.isNullOrBlank()) {
            stateStore.fail(state, "missing authorization code")
            writeHtml(exchange, 400, "<h1>Ely4Everyone</h1><p>Authorization code is missing.</p>")
            return
        }
        runCatching {
            val tokenResponse = oauthClient.exchangeCode(code)
            val accountInfo = oauthClient.fetchAccountInfo(tokenResponse.accessToken)
            val texturesProfile = oauthClient.fetchTexturesProfile(accountInfo.uuid)
            val texturesProperty = texturesProfile.properties.firstOrNull { it.name == "textures" }
            val clientSession = clientSessionStore.create(
                username = accountInfo.username,
                uuid = accountInfo.uuid,
                elyAccessToken = tokenResponse.accessToken,
                refreshToken = tokenResponse.refreshToken,
                properties = texturesProfile.properties,
            )
            stateStore.complete(
                state = state,
                authSessionToken = clientSession.sessionToken,
                elyAccessToken = clientSession.elyAccessToken,
                username = accountInfo.username,
                uuid = accountInfo.uuid,
                expiresAtEpochSeconds = clientSession.expiresAtEpochSeconds,
                texturesValue = texturesProperty?.value,
                texturesSignature = texturesProperty?.signature,
            )
            writeHtml(
                exchange,
                200,
                """
                <h1>Ely4Everyone</h1>
                <p>Authorization completed for <strong>${escapeHtml(accountInfo.username)}</strong>.</p>
                <p>You can return to Minecraft and wait for the mod to finish polling.</p>
                """.trimIndent(),
            )
        }.onFailure { exception ->
            val error = exception.message ?: exception::class.java.simpleName
            stateStore.fail(state, error)
            logger.warn("Failed to complete Ely OAuth callback for state {}", state, exception)
            writeHtml(exchange, 500, "<h1>Ely4Everyone</h1><p>Authorization failed: ${escapeHtml(error)}</p>")
        }
    }

    private fun handleAuthlibInjectorMetadata(exchange: HttpExchange) {
        val response = sessionVerifier.fetchMetadataResponse()
        writeResponse(exchange, response.statusCode, response.contentType, response.body)
    }

    private fun handleRoot(exchange: HttpExchange) {
        if (exchange.requestURI.path != "/") {
            writeText(exchange, 404, "text/plain; charset=utf-8", "Not Found")
            return
        }
        handleAuthlibInjectorMetadata(exchange)
    }

    private fun issueTicket(
        uuid: String,
        username: String,
        audience: String,
        nonce: String,
        properties: List<AuthProfileProperty>,
    ): IssuedTicketResult {
        val now = Instant.now()
        val expiresAt = now.plusSeconds(config.issuedTicketTtlSeconds).epochSecond
        val ticketId = UUID.randomUUID().toString()
        issuedLoginTicketStore.put(IssuedLoginTicketRecord(ticketId, username, uuid, expiresAt, properties), now)
        return IssuedTicketResult(
            ticket = AuthTicketCodec.encode(
                AuthTicketClaims(
                    issuer = config.trustedIssuer,
                    audience = audience,
                    subject = uuid,
                    username = username,
                    issuedAtEpochSeconds = now.epochSecond,
                    expiresAtEpochSeconds = expiresAt,
                    ticketId = ticketId,
                    nonce = nonce,
                    hostId = config.hostId,
                ),
                config.ticketSigningKey,
            ),
            expiresAtEpochSeconds = expiresAt,
        )
    }

    private fun requestParameters(exchange: HttpExchange): Map<String, String> {
        val queryParams = parseQuery(exchange.requestURI.rawQuery)
        if (exchange.requestMethod.equals("POST", ignoreCase = true)) {
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            return LinkedHashMap<String, String>().apply {
                putAll(queryParams)
                putAll(parseQuery(body))
            }
        }
        return queryParams
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) {
            return emptyMap()
        }
        val result = LinkedHashMap<String, String>()
        var segmentStart = 0
        while (segmentStart <= rawQuery.length) {
            val separatorIndex = rawQuery.indexOf('&', segmentStart).let { if (it >= 0) it else rawQuery.length }
            val part = rawQuery.substring(segmentStart, separatorIndex)
            if (part.isNotBlank()) {
                val keyValueSeparator = part.indexOf('=')
                if (keyValueSeparator < 0) {
                    result[decodeUrl(part)] = ""
                } else {
                    result[decodeUrl(part.substring(0, keyValueSeparator))] =
                        decodeUrl(part.substring(keyValueSeparator + 1))
                }
            }
            if (separatorIndex >= rawQuery.length) {
                break
            }
            segmentStart = separatorIndex + 1
        }
        return result
    }

    private fun parseJsonStringArray(json: String): List<String> {
        return Regex(""""((?:\\.|[^"\\])*)"""")
            .findAll(json)
            .map { match -> match.groupValues[1] }
            .toList()
    }

    private fun writeText(exchange: HttpExchange, statusCode: Int, contentType: String, body: String) {
        writeResponse(exchange, statusCode, contentType, body.toByteArray(StandardCharsets.UTF_8))
    }

    private fun writeHtml(exchange: HttpExchange, statusCode: Int, body: String) {
        writeResponse(
            exchange,
            statusCode,
            "text/html; charset=utf-8",
            """
            <!DOCTYPE html>
            <html lang="en">
            <head><meta charset="utf-8"><title>Ely4Everyone</title></head>
            <body style="font-family: sans-serif; margin: 2rem;">$body</body>
            </html>
            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun redirect(exchange: HttpExchange, uri: URI) {
        exchange.responseHeaders.add("Location", uri.toString())
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    private fun writeResponse(exchange: HttpExchange, statusCode: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(statusCode, body.size.toLong())
        exchange.responseBody.use { output -> output.write(body) }
    }

    private fun decodeUrl(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun isOAuthConfigured(): Boolean = config.clientId.isNotBlank() && config.clientSecret.isNotBlank()
}
