package dev.ely4everyone.shared.host

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.ely4everyone.shared.auth.AuthProfileProperty
import dev.ely4everyone.shared.oauth.LoopbackLogin
import dev.ely4everyone.shared.oauth.OAuthStateStore
import dev.ely4everyone.shared.oauth.PendingAuthSession
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
    /**
     * Lets mods older than the loopback login (0.1.0-alpha) log in by polling. Polling can be
     * hijacked with a crafted login link, so keep this off except for a short migration window.
     */
    val allowLegacyPollLogin: Boolean = false,
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
    upstream: HybridAuthUpstream = CachingHybridAuthUpstream(HttpHybridAuthUpstream()),
    private val profileResolver: HybridProfileResolver = HybridProfileResolver(upstream),
    private val sessionVerifier: HybridSessionVerifier = HybridSessionVerifier(upstream, pendingPremiumLoginStore),
    private val oauthClient: ElyOAuthGateway = ElyOAuthClient(
        OAuthClientSettings(
            publicBaseUrl = config.publicBaseUrl,
            clientId = config.clientId,
            clientSecret = config.clientSecret,
            scopes = config.oauthScopes,
        ),
    ),
) {
    private companion object {
        // Bounded so a slow upstream cannot make the proxy spawn threads without limit.
        const val WORKER_THREADS: Int = 32

        // Same limit as Mojang's batch profile API; authlib clients never send more.
        const val MAX_BATCH_PROFILE_NAMES: Int = 10
        const val MAX_BATCH_BODY_BYTES: Int = 4096

        const val INSECURE_DEFAULT_SIGNING_KEY: String = "change-me"

        // How long a finished loopback login waits for the mod to redeem its one-time code.
        const val HANDOFF_TTL_SECONDS: Long = 120
        const val MAX_FORM_BODY_BYTES: Int = 8192
        const val MAX_LEGACY_STATE_LENGTH: Int = 128

        const val MODRINTH_URL: String = "https://modrinth.com/mod/ely4everyone"

        // 0.1.0-alpha shows this after "Авторизация не удалась: " in a box of 4 lines, about 38
        // characters each. Minecraft renders § colour codes even in literal text. The old mod wraps
        // by word and starts every line uncoloured, so each word carries its own code.
        val OUTDATED_MOD_ERROR: String = listOf(
            "§e" to "мод устарел.",
            "§f" to "Обнови Ely4Everyone:",
            "§b" to "modrinth.com/mod/ely4everyone",
        ).joinToString(" ") { (code, text) -> text.split(" ").joinToString(" ") { word -> code + word } }
        val profileUuidPattern = Regex("^[0-9a-fA-F]{32}$|^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")
    }

    private val stateStore = OAuthStateStore(config.oauthStateTtlSeconds)
    private val clientSessionStore = ClientAuthSessionStore(
        ttlSeconds = config.clientSessionTtlSeconds,
        storagePath = dataDirectory.resolve("client-auth-sessions.bin"),
    )
    private val issuedLoginTicketStore = IssuedLoginTicketStore()
    private var executorService: ExecutorService? = null
    private var server: HttpServer? = null

    fun start() {
        if (!config.enabled) {
            logger.info("Embedded Ely auth host is disabled in config.")
            return
        }
        if (config.ticketSigningKey == INSECURE_DEFAULT_SIGNING_KEY) {
            logger.warn("Embedded Ely auth host uses the default ticket signing key 'change-me'. Set a random secret in the config.")
        }
        if (config.allowLegacyPollLogin) {
            logger.warn(
                "allow_legacy_poll_login is on: anyone can hijack an Ely login with a crafted link, " +
                    "whatever mod version the victim runs. Turn it off once players have updated.",
            )
        }
        val address = InetSocketAddress(config.bindHost, config.bindPort)
        executorService = Executors.newFixedThreadPool(WORKER_THREADS)
        server = HttpServer.create(address, 0).apply {
            executor = executorService
            createContext("/health") { exchange -> handleHealth(exchange) }
            createContext("/api/v2/discovery") { exchange -> handleDiscovery(exchange) }
            createContext("/api/profiles/minecraft") { exchange -> handleBatchProfiles(exchange) }
            createContext("/api/authlib-injector") { exchange -> handleAuthlibInjectorMetadata(exchange) }
            createContext("/api/v1/config") { exchange -> handleConfig(exchange) }
            createContext("/api/v1/auth/start") { exchange -> handleAuthStart(exchange) }
            createContext("/api/v1/auth/poll") { exchange -> handleAuthPoll(exchange) }
            createContext("/api/v1/auth/exchange") { exchange -> handleAuthExchange(exchange) }

            createContext("/api/v1/auth/issue-ticket") { exchange -> handleIssueTicket(exchange) }
            createContext("/api/v1/auth/refresh") { exchange -> handleRefreshSession(exchange) }
            createContext("/api/v1/auth/dev/latest-session") { exchange -> handleLatestSession(exchange) }
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
        // Header only: a token in the URL ends up in access logs. Every released mod sends the header.
        val sessionToken = extractBearerToken(exchange)
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
            val newExpiresAt = Instant.now().plusSeconds(config.clientSessionTtlSeconds).epochSecond
            val updated = clientSessionStore.updateTokens(
                sessionToken = sessionToken,
                newElyAccessToken = tokenResponse.accessToken,
                newRefreshToken = tokenResponse.refreshToken,
                newExpiresAtEpochSeconds = newExpiresAt,
            )
            if (updated != null) {
                // Re-fetch textures profile with new access token
                val texturesProfile = runCatching {
                    oauthClient.fetchTexturesProfile(updated.uuid)
                }.getOrNull()
                val texturesProperty = texturesProfile?.properties?.firstOrNull { it.name == "textures" }

                writeText(
                    exchange,
                    200,
                    "text/plain; charset=utf-8",
                    buildString {
                        appendLine("status=completed")
                        appendLine("ely_access_token=${updated.elyAccessToken}")
                        appendLine("exp=${updated.expiresAtEpochSeconds}")
                        appendLine("username=${updated.username}")
                        appendLine("uuid=${updated.uuid}")
                        if (texturesProperty != null) {
                            appendLine("textures_value=${texturesProperty.value}")
                            if (!texturesProperty.signature.isNullOrBlank()) {
                                appendLine("textures_signature=${texturesProperty.signature}")
                            }
                        }
                    }.trim(),
                )
            } else {
                writeText(exchange, 500, "text/plain; charset=utf-8", "status=failed\nerror=session_update_failed")
            }
        }.onFailure { exception ->
            logger.warn("Failed to refresh token via OAuth client", exception)
            writeText(exchange, 500, "text/plain; charset=utf-8", "status=failed\nerror=${exception.message ?: "refresh_error"}")
        }
    }

    private fun extractBearerToken(exchange: HttpExchange): String? {
        val authHeader = exchange.requestHeaders.getFirst("Authorization") ?: return null
        if (!authHeader.startsWith("Bearer ", ignoreCase = true)) return null
        return authHeader.substring(7).trim().takeIf { it.isNotBlank() }
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
            // Such a name has no premium profile anywhere. Answer "not found" (not 400): LimboAuth
            // treats unexpected status codes as upstream errors and may force a cracked player online.
            if (!MinecraftUsernames.isValidEly(username)) {
                writeText(exchange, 404, "application/json; charset=utf-8", """{"error":"Not Found"}""")
                return
            }
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
            login_flow=loopback
            """.trimIndent(),
        )
    }

    private fun handleBatchProfiles(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            writeText(exchange, 405, "application/json; charset=utf-8", """{"error":"Method Not Allowed"}""")
            return
        }
        // Every name costs up to two upstream requests, so this public endpoint must stay small.
        val body = exchange.requestBody.readNBytes(MAX_BATCH_BODY_BYTES + 1)
        val names = parseJsonStringArray(body.toString(StandardCharsets.UTF_8))
            .distinctBy { it.lowercase() }
        if (body.size > MAX_BATCH_BODY_BYTES || names.size > MAX_BATCH_PROFILE_NAMES) {
            writeText(
                exchange,
                400,
                "application/json; charset=utf-8",
                """{"error":"IllegalArgumentException","errorMessage":"Not more than $MAX_BATCH_PROFILE_NAMES profile names per call are allowed."}""",
            )
            return
        }
        val profiles = names.filter(MinecraftUsernames::isValidEly).mapNotNull { username ->
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
        // The path segment is forwarded into the upstream URL, so only a real UUID may pass.
        if (!profileUuidPattern.matches(uuidPath)) {
            writeText(exchange, 400, "text/plain; charset=utf-8", "error=invalid_profile_uuid")
            return
        }
        val response = sessionVerifier.fetchProfile(uuidPath, exchange.requestURI.rawQuery)
        writeResponse(exchange, response.statusCode, response.contentType, response.body)
    }

    /**
     * Opened in the player's browser by the mod. A current mod sends a PKCE challenge and its
     * loopback address; a request without them comes from an old, polling mod.
     */
    private fun handleAuthStart(exchange: HttpExchange) {
        val params = requestParameters(exchange)
        val state = params["state"]
        if (state.isNullOrBlank() || state.length > MAX_LEGACY_STATE_LENGTH) {
            writeHtml(exchange, 400, "<h1>Ely4Everyone</h1><p>Invalid login request.</p>")
            return
        }
        if (!isOAuthConfigured()) {
            writeHtml(exchange, 503, "<h1>Ely4Everyone</h1><p>Ely.by login is not configured on this server.</p>")
            return
        }
        val codeChallenge = params["code_challenge"]
        if (codeChallenge == null) {
            startLegacyPollLogin(exchange, state)
            return
        }

        val redirectUri = LoopbackLogin.parseRedirectUri(params["client_redirect_uri"])
        val method = params["code_challenge_method"] ?: LoopbackLogin.CHALLENGE_METHOD
        if (!LoopbackLogin.isWellFormedToken(state) ||
            !LoopbackLogin.isWellFormedToken(codeChallenge) ||
            method != LoopbackLogin.CHALLENGE_METHOD ||
            redirectUri == null
        ) {
            writeHtml(exchange, 400, "<h1>Ely4Everyone</h1><p>Invalid login request.</p>")
            return
        }
        if (stateStore.create(state, codeChallenge = codeChallenge, clientRedirectUri = redirectUri.toString()) == null) {
            refuseStart(exchange, state)
            return
        }
        redirect(exchange, oauthClient.buildAuthorizationUri(state))
    }

    private fun refuseStart(exchange: HttpExchange, state: String) {
        if (stateStore.get(state) != null) {
            writeHtml(exchange, 409, "<h1>Ely4Everyone</h1><p>This login link was already used. Start the login again from Minecraft.</p>")
        } else {
            writeHtml(exchange, 503, "<h1>Ely4Everyone</h1><p>Too many logins are in progress. Try again in a few minutes.</p>")
        }
    }

    private fun startLegacyPollLogin(exchange: HttpExchange, state: String) {
        if (!config.allowLegacyPollLogin) {
            // Polling for the result cannot be made safe, so old mods are refused. Nothing is
            // stored (refusals must not use up login capacity); the poll answers statelessly.
            writeOutdatedModPage(exchange)
            return
        }
        if (stateStore.create(state) == null) {
            refuseStart(exchange, state)
            return
        }
        redirect(exchange, oauthClient.buildAuthorizationUri(state))
    }

    /** Legacy result channel for old mods. Loopback logins never hand out tokens here. */
    private fun handleAuthPoll(exchange: HttpExchange) {
        val state = requestParameters(exchange)["state"]
        if (state.isNullOrBlank()) {
            writeText(exchange, 400, "text/plain; charset=utf-8", "status=failed\nerror=missing_state")
            return
        }
        // Current mods never poll, so with legacy login off every poll comes from an old mod.
        if (!config.allowLegacyPollLogin) {
            writeText(exchange, 200, "text/plain; charset=utf-8", "status=failed\nerror=$OUTDATED_MOD_ERROR")
            return
        }
        val session = stateStore.get(state)
        if (session == null) {
            writeText(exchange, 404, "text/plain; charset=utf-8", "status=failed\nerror=unknown_state")
            return
        }
        if (session.isLoopback) {
            writeText(exchange, 400, "text/plain; charset=utf-8", "status=failed\nerror=wrong_login_flow")
            return
        }
        when (session.status) {
            PendingAuthStatus.PENDING, PendingAuthStatus.EXCHANGING ->
                writeText(exchange, 200, "text/plain; charset=utf-8", "status=pending")
            PendingAuthStatus.FAILED ->
                writeText(exchange, 200, "text/plain; charset=utf-8", "status=failed\nerror=${session.error.orEmpty()}")
            PendingAuthStatus.COMPLETED -> {
                // Hand the tokens out once, so a state leaked through a shared log is useless afterwards.
                val consumed = stateStore.consumeIf(state) { it.status == PendingAuthStatus.COMPLETED && !it.isLoopback }
                if (consumed == null) {
                    writeText(exchange, 404, "text/plain; charset=utf-8", "status=failed\nerror=unknown_state")
                } else {
                    writeText(exchange, 200, "text/plain; charset=utf-8", completedLoginBody(consumed))
                }
            }
        }
    }

    /**
     * The mod redeems the one-time code it got on its loopback address. It must also present the
     * PKCE verifier, so a code that leaked (browser history, another local program) is useless.
     */
    private fun handleAuthExchange(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            writeText(exchange, 405, "text/plain; charset=utf-8", "status=failed\nerror=method_not_allowed")
            return
        }
        // Body only: secrets must not end up in URLs and access logs.
        val params = parseQuery(readFormBody(exchange))
        val state = params["state"]
        val code = params["code"]
        val verifier = params["code_verifier"]
        if (state.isNullOrBlank() || code.isNullOrBlank() || verifier.isNullOrBlank()) {
            writeText(exchange, 400, "text/plain; charset=utf-8", "status=failed\nerror=invalid_request")
            return
        }
        val session = stateStore.consumeIf(state) { candidate ->
            val handoffCode = candidate.handoffCode
            val challenge = candidate.codeChallenge
            candidate.status == PendingAuthStatus.COMPLETED &&
                handoffCode != null &&
                challenge != null &&
                LoopbackLogin.constantTimeEquals(handoffCode, code) &&
                LoopbackLogin.verifierMatches(verifier, challenge)
        }
        if (session == null) {
            writeText(exchange, 400, "text/plain; charset=utf-8", "status=failed\nerror=invalid_grant")
            return
        }
        writeText(exchange, 200, "text/plain; charset=utf-8", completedLoginBody(session))
    }

    private fun completedLoginBody(session: PendingAuthSession): String = """
        status=completed
        auth_session_token=${session.authSessionToken.orEmpty()}
        ely_access_token=${session.elyAccessToken.orEmpty()}
        username=${session.username.orEmpty()}
        uuid=${session.uuid.orEmpty()}
        exp=${session.sessionExpiresAtEpochSeconds ?: 0}
        textures_value=${session.texturesValue.orEmpty()}
        textures_signature=${session.texturesSignature.orEmpty()}
    """.trimIndent()



    private fun handleIssueTicket(exchange: HttpExchange) {
        val params = requestParameters(exchange)
        // The query form only exists for 0.1.0-alpha mods; it leaks the token into access logs.
        val sessionToken = extractBearerToken(exchange)
            ?: params["session_token"].takeIf { config.allowLegacyPollLogin }
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

    private fun handleOAuthCallback(exchange: HttpExchange) {
        val params = requestParameters(exchange)
        val state = params["state"]
        if (state.isNullOrBlank()) {
            writeHtml(exchange, 400, "<h1>Ely4Everyone</h1><p>Missing OAuth state.</p>")
            return
        }
        // Only a login that this host started and that is still waiting may continue. That also
        // stops a replayed callback URL from minting a second session or failing a finished one.
        val pending = stateStore.claimForExchange(state)
        if (pending == null) {
            writeHtml(exchange, 400, "<h1>Ely4Everyone</h1><p>This login link has expired or was already used. Start the login again from Minecraft.</p>")
            return
        }
        if (params["error"] != null) {
            failLogin(exchange, pending, params["error_message"] ?: params["error"].orEmpty(), 400)
            return
        }
        val code = params["code"]
        if (code.isNullOrBlank()) {
            failLogin(exchange, pending, "missing authorization code", 400)
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
            val handoffCode = if (pending.isLoopback) LoopbackLogin.newSecret() else null
            val completed = stateStore.complete(
                state = state,
                authSessionToken = clientSession.sessionToken,
                elyAccessToken = clientSession.elyAccessToken,
                username = accountInfo.username,
                uuid = accountInfo.uuid,
                sessionExpiresAtEpochSeconds = clientSession.expiresAtEpochSeconds,
                texturesValue = texturesProperty?.value,
                texturesSignature = texturesProperty?.signature,
                handoffCode = handoffCode,
                entryTtlSeconds = if (handoffCode != null) HANDOFF_TTL_SECONDS else config.oauthStateTtlSeconds,
            )
            if (completed == null) {
                // The entry expired while Ely was answering: a code sent now could never be redeemed.
                failLogin(exchange, pending, "login timed out, try again", 400)
                return
            }
            val clientRedirectUri = pending.clientRedirectUri
            if (handoffCode != null && clientRedirectUri != null) {
                redirect(exchange, LoopbackLogin.redirectWithCode(clientRedirectUri, state, handoffCode))
            } else {
                writeHtml(
                    exchange,
                    200,
                    """
                    <h1>Ely4Everyone</h1>
                    <p>Authorization completed for <strong>${escapeHtml(accountInfo.username)}</strong>.</p>
                    <p>You can return to Minecraft and wait for the mod to finish polling.</p>
                    """.trimIndent(),
                )
            }
        }.onFailure { exception ->
            logger.warn("Failed to complete Ely OAuth callback", exception)
            failLogin(exchange, pending, exception.message ?: exception::class.java.simpleName, 500)
        }
    }

    /** Records the failure and tells the player: a loopback mod hears it directly, an old mod via poll. */
    private fun failLogin(exchange: HttpExchange, pending: PendingAuthSession, error: String, statusCode: Int) {
        stateStore.fail(pending.state, error)
        val clientRedirectUri = pending.clientRedirectUri
        if (clientRedirectUri != null) {
            redirect(exchange, LoopbackLogin.redirectWithError(clientRedirectUri, pending.state, error))
        } else {
            writeHtml(exchange, statusCode, "<h1>Ely4Everyone</h1><p>Authorization failed: ${escapeHtml(error)}</p>")
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
            return LinkedHashMap<String, String>().apply {
                putAll(queryParams)
                putAll(parseQuery(readFormBody(exchange)))
            }
        }
        return queryParams
    }

    /** Reads a form body, ignoring anything past the cap instead of buffering an unbounded upload. */
    private fun readFormBody(exchange: HttpExchange): String {
        val bytes = exchange.requestBody.readNBytes(MAX_FORM_BODY_BYTES)
        return bytes.toString(StandardCharsets.UTF_8)
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

    /** The page an old mod opens in the browser. The player sees it right after clicking "Войти". */
    private fun writeOutdatedModPage(exchange: HttpExchange) {
        val page = """
            <!DOCTYPE html>
            <html lang="ru">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Ely4Everyone: обнови мод</title>
            <style>
              :root { --bg: #f4f6f5; --card: #ffffff; --text: #1d2420; --dim: #5b6660; --accent: #2f9e7a; --accent-text: #ffffff; --border: #dde3e0; }
              @media (prefers-color-scheme: dark) {
                :root { --bg: #0f1412; --card: #171e1b; --text: #e6ece9; --dim: #9aa7a0; --accent: #38b48b; --accent-text: #0f1412; --border: #26302c; }
              }
              * { box-sizing: border-box; }
              body { margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
                     padding: 16px; background: var(--bg); color: var(--text);
                     font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
              .card { width: 100%; max-width: 440px; background: var(--card); border: 1px solid var(--border);
                      border-radius: 8px; padding: 28px 24px; text-align: center; }
              h1 { font-size: 22px; margin: 8px 0 12px; }
              p { color: var(--dim); line-height: 1.5; margin: 0 0 12px; }
              .icon { font-size: 40px; }
              .button { display: inline-block; margin-top: 12px; padding: 12px 22px; border-radius: 6px;
                        background: var(--accent); color: var(--accent-text); font-weight: 600; text-decoration: none; }
              .en { margin-top: 20px; padding-top: 16px; border-top: 1px solid var(--border); font-size: 14px; }
            </style>
            </head>
            <body>
            <main class="card">
              <div class="icon">🔒</div>
              <h1>Пора обновить Ely4Everyone</h1>
              <p>Мы поменяли вход через Ely.by, чтобы никто не мог увести твою сессию по чужой ссылке. Эта версия мода так войти уже не умеет.</p>
              <p>Скачай новую версию, замени файл в папке <code>mods</code> и войди снова.</p>
              <a class="button" href="$MODRINTH_URL">Скачать на Modrinth</a>
              <p class="en">This version of Ely4Everyone can no longer log in: the login was changed so nobody can steal your session with a crafted link. Update the mod and log in again.</p>
            </main>
            </body>
            </html>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Cache-Control", "no-store")
        writeResponse(exchange, 400, "text/html; charset=utf-8", page)
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
