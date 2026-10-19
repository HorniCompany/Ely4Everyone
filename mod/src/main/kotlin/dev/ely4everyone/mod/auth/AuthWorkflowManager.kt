package dev.ely4everyone.mod.auth

import dev.ely4everyone.mod.Ely4EveryoneClientMod
import dev.ely4everyone.mod.config.ModConfigStore
import dev.ely4everyone.mod.identity.MinecraftClientSessionBridge
import dev.ely4everyone.mod.session.ClientSessionState
import dev.ely4everyone.mod.session.ClientSessionStore
import dev.ely4everyone.shared.oauth.LoopbackLogin
import net.minecraft.client.MinecraftClient
import net.minecraft.util.Util
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CompletableFuture

enum class AuthFlowStatus {
    IDLE,
    OPENING_BROWSER,
    POLLING,
    SUCCESS,
    ERROR,
}

data class AuthFlowState(
    val status: AuthFlowStatus = AuthFlowStatus.IDLE,
    val message: String = "Готов к входу через Ely.by",
)

/**
 * Browser login: the auth host sends the player's browser back to [LocalAuthCallbackServer] on
 * 127.0.0.1 with a one-time code, which is redeemed together with a PKCE verifier that never
 * leaves this process. See [LoopbackLogin] for why the old "poll the host" flow was dropped.
 */
object AuthWorkflowManager {
    private val logger = LoggerFactory.getLogger("${Ely4EveryoneClientMod.MOD_ID}/auth")

    // A browser tab the player abandoned must not keep the login "in progress" forever.
    private const val LOGIN_TIMEOUT_MS: Long = 5 * 60 * 1000

    @Volatile
    private var state: AuthFlowState = AuthFlowState()

    @Volatile
    private var pendingStateId: String? = null

    /** State whose code is being redeemed right now. Tied to the state, so a cancelled attempt cannot block the next. */
    @Volatile
    private var exchangingStateId: String? = null

    @Volatile
    private var pendingVerifier: String? = null

    @Volatile
    private var pendingRelayBaseUrl: String? = null

    @Volatile
    private var pendingStartedAtMillis: Long = 0L

    @Volatile
    private var localCallbackServer: LocalAuthCallbackServer? = null

    fun currentState(): AuthFlowState = state

    fun resetUiState(message: String = "Готов к входу через Ely.by") {
        if (pendingStateId != null) {
            return
        }
        state = AuthFlowState(
            status = AuthFlowStatus.IDLE,
            message = message,
        )
    }

    fun startBrowserLogin(): Boolean {
        if (pendingStateId != null) {
            state = AuthFlowState(
                status = AuthFlowStatus.POLLING,
                message = "Вход уже выполняется, заверши его в браузере.",
            )
            return false
        }

        val relayBaseUrl = ModConfigStore.load().resolvedAuthServerBaseUrl().trimEnd('/')
        val oauthState = LoopbackLogin.newSecret()
        pendingStateId = oauthState
        pendingRelayBaseUrl = relayBaseUrl
        pendingStartedAtMillis = System.currentTimeMillis()
        state = AuthFlowState(
            status = AuthFlowStatus.OPENING_BROWSER,
            message = "Проверяю сервер входа...",
        )

        // Off the render thread: the host may be slow or down.
        CompletableFuture
            .supplyAsync { AuthHostClient.fetchLoginSupport(relayBaseUrl) }
            .whenComplete { support, error ->
                MinecraftClient.getInstance().execute {
                    if (pendingStateId != oauthState) {
                        return@execute
                    }
                    when {
                        error != null || support == null -> {
                            val cause = error?.cause ?: error
                            logger.warn("Auth host {} is unreachable", relayBaseUrl, cause)
                            fail("сервер входа недоступен (${cause?.message ?: cause?.javaClass?.simpleName ?: "unknown error"}).")
                        }
                        !support.oauthEnabled -> fail("на этом сервере входа не настроен Ely.by OAuth.")
                        !support.loopbackLogin -> fail("сервер входа устарел и не поддерживает безопасный вход. Его плагин нужно обновить.")
                        else -> openBrowser(oauthState, relayBaseUrl)
                    }
                }
            }
        return true
    }

    private fun openBrowser(oauthState: String, relayBaseUrl: String) {
        val verifier = LoopbackLogin.newSecret()
        val callbackServer = try {
            LocalAuthCallbackServer.start(oauthState)
        } catch (exception: IOException) {
            logger.warn("Could not open the local port for the login callback", exception)
            fail("не удалось открыть локальный порт для входа (${exception.message}).")
            return
        }
        localCallbackServer?.stop()
        localCallbackServer = callbackServer
        pendingVerifier = verifier
        val startUri = AuthHostClient.buildStartUri(
            relayBaseUrl = relayBaseUrl,
            state = oauthState,
            codeChallenge = LoopbackLogin.challengeFor(verifier),
            clientRedirectUri = callbackServer.callbackUri,
        )

        // The state stays out of the log: latest.log gets shared in help threads.
        logger.info("Starting Ely browser login via auth host {}", relayBaseUrl)
        state = AuthFlowState(
            status = AuthFlowStatus.OPENING_BROWSER,
            message = "Открываю браузер и запускаю Ely OAuth...",
        )

        Util.getOperatingSystem().open(startUri)
        state = AuthFlowState(
            status = AuthFlowStatus.POLLING,
            message = "Подтверди вход в браузере, игра подхватит его сама...",
        )
    }

    fun clearSession() {
        resetPendingLogin()
        ClientSessionStore.clear()
        MinecraftClientSessionBridge.restoreVanillaIdentity()
        state = AuthFlowState(
            status = AuthFlowStatus.IDLE,
            message = "Локальная Ely-сессия очищена.",
        )
    }

    fun cancelCurrentAttempt() {
        resetPendingLogin()
        state = AuthFlowState(
            status = AuthFlowStatus.IDLE,
            message = "Авторизация отменена.",
        )
    }

    fun syncLatestSessionFromAuthHost(): Boolean {
        val currentSession = ClientSessionStore.load()
        if (currentSession.authSessionToken.isNullOrBlank() || currentSession.relayBaseUrl.isNullOrBlank()) {
            logger.warn("Cannot auto-refresh: missing session token or relay base url.")
            return false
        }

        return try {
            val result = AuthHostClient.refreshSession(currentSession.relayBaseUrl, currentSession.authSessionToken)
            if (result.status == "completed") {
                val expiresAt = result.expiresAtEpochSeconds ?: return false
                val elyAccessToken = result.elyAccessToken ?: return false

                ClientSessionStore.save(
                    currentSession.copy(
                        elyAccessToken = elyAccessToken,
                        authSessionExpiresAt = Instant.ofEpochSecond(expiresAt),
                        elyUsername = result.username ?: currentSession.elyUsername,
                        elyUuid = result.uuid ?: currentSession.elyUuid,
                        elyTexturesValue = result.texturesValue ?: currentSession.elyTexturesValue,
                        elyTexturesSignature = result.texturesSignature ?: currentSession.elyTexturesSignature,
                    )
                )
                // Refresh the in-game identity with updated data
                MinecraftClientSessionBridge.refreshActiveIdentity()
                logger.info("Successfully refreshed Ely session token via auth host.")
                true
            } else {
                logger.warn("Refresh failed with status {}", result.status)
                state = AuthFlowState(
                    status = AuthFlowStatus.ERROR,
                    message = "Auto-refresh failed (${result.status}: ${result.error}). Please log in via browser.",
                )
                false
            }
        } catch (e: Exception) {
            logger.error("Auto-refresh error", e)
            false
        }
    }

    fun tick(client: MinecraftClient) {
        val oauthState = pendingStateId ?: return
        val callbackPayload = localCallbackServer?.consumeResult()
        if (callbackPayload != null) {
            handleCallback(client, oauthState, callbackPayload)
            return
        }
        if (exchangingStateId != oauthState && System.currentTimeMillis() - pendingStartedAtMillis > LOGIN_TIMEOUT_MS) {
            fail("время на вход вышло, попробуй ещё раз.")
        }
    }

    private fun handleCallback(client: MinecraftClient, oauthState: String, payload: LocalAuthCallbackPayload) {
        val code = payload.code
        if (payload.error != null || code == null) {
            fail(payload.error ?: "сервер входа не вернул код.")
            return
        }
        val verifier = pendingVerifier ?: return
        val relayBaseUrl = pendingRelayBaseUrl ?: return
        if (exchangingStateId == oauthState) {
            return
        }
        exchangingStateId = oauthState
        // The local server keeps running until the login ends, so the browser gets its page in full.
        // It ignores everything after the first callback anyway.
        state = AuthFlowState(
            status = AuthFlowStatus.POLLING,
            message = "Завершаю вход...",
        )

        CompletableFuture
            .supplyAsync { AuthHostClient.exchangeCode(relayBaseUrl, oauthState, code, verifier) }
            .whenComplete { result, error ->
                // Back on the client thread: the identity swap below must not race the game.
                client.execute {
                    if (exchangingStateId == oauthState) {
                        exchangingStateId = null
                    }
                    finishLogin(oauthState, relayBaseUrl, result, error)
                }
            }
    }

    private fun finishLogin(oauthState: String, relayBaseUrl: String, result: AuthPollResult?, error: Throwable?) {
        if (pendingStateId != oauthState) {
            // The player cancelled or restarted the login while the code was being redeemed.
            return
        }
        if (error != null || result == null) {
            val cause = error?.cause ?: error
            logger.warn("Redeeming the login code failed", cause)
            fail("сервер входа недоступен (${cause?.message ?: cause?.javaClass?.simpleName ?: "unknown error"}).")
            return
        }
        if (result.status != "completed") {
            logger.warn("Auth host refused the login code: {}", result.error)
            fail(result.error ?: "сервер входа отказал во входе.")
            return
        }

        val authSessionToken = result.authSessionToken
        val elyAccessToken = result.elyAccessToken
        val username = result.username
        val uuid = result.uuid
        val expiresAt = result.expiresAtEpochSeconds
        if (authSessionToken.isNullOrBlank() || elyAccessToken.isNullOrBlank() || username.isNullOrBlank() || uuid.isNullOrBlank() || expiresAt == null) {
            logger.warn("Auth host returned an incomplete login response")
            fail("Auth host вернул неполный auth session response.")
            return
        }

        ClientSessionStore.save(
            ClientSessionState(
                relayBaseUrl = relayBaseUrl,
                authSessionToken = authSessionToken,
                elyAccessToken = elyAccessToken,
                authSessionExpiresAt = Instant.ofEpochSecond(expiresAt),
                elyUsername = username,
                elyUuid = uuid,
                elyTexturesValue = result.texturesValue,
                elyTexturesSignature = result.texturesSignature,
            ),
        )
        MinecraftClientSessionBridge.refreshActiveIdentity()

        resetPendingLogin()
        state = AuthFlowState(
            status = AuthFlowStatus.SUCCESS,
            message = "Вход завершен: $username ($uuid)",
        )
        logger.info("Stored Ely auth session for {} ({})", username, uuid)
    }

    private fun fail(message: String) {
        resetPendingLogin()
        state = AuthFlowState(
            status = AuthFlowStatus.ERROR,
            message = "Авторизация не удалась: $message",
        )
    }

    private fun resetPendingLogin() {
        pendingStateId = null
        exchangingStateId = null
        pendingVerifier = null
        pendingRelayBaseUrl = null
        pendingStartedAtMillis = 0L
        localCallbackServer?.stop()
        localCallbackServer = null
    }
}
