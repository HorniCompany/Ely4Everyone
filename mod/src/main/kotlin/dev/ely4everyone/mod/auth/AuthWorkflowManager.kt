package dev.ely4everyone.mod.auth

import dev.ely4everyone.mod.Ely4EveryoneClientMod
import dev.ely4everyone.mod.config.ModConfigStore
import dev.ely4everyone.mod.identity.ElyIdentityManager
import dev.ely4everyone.mod.identity.MinecraftClientSessionBridge
import dev.ely4everyone.mod.session.ClientSessionState
import dev.ely4everyone.mod.session.ClientSessionStore
import net.minecraft.client.MinecraftClient
import net.minecraft.util.Util
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

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

object AuthWorkflowManager {
    private val logger = LoggerFactory.getLogger("${Ely4EveryoneClientMod.MOD_ID}/auth")
    private val pollingInFlight = AtomicBoolean(false)
    private const val POLL_INITIAL_DELAY_MS: Long = 1500
    private const val UNKNOWN_STATE_GRACE_MS: Long = 15000

    @Volatile
    private var state: AuthFlowState = AuthFlowState()

    @Volatile
    private var pendingStateId: String? = null

    @Volatile
    private var pendingRelayBaseUrl: String? = null

    @Volatile
    private var nextPollAtMillis: Long = 0L

    @Volatile
    private var pendingStartedAtMillis: Long = 0L

    @Volatile
    private var lastPolledStatus: String? = null

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
                message = "Вход уже выполняется, дождись завершения опроса.",
            )
            return false
        }

        val relayBaseUrl = ModConfigStore.load().resolvedAuthServerBaseUrl().trimEnd('/')
        val oauthState = UUID.randomUUID().toString()
        localCallbackServer?.stop()
        localCallbackServer = LocalAuthCallbackServer.start(oauthState)
        val startUri = AuthHostClient.buildStartUri(relayBaseUrl, oauthState, localCallbackServer?.callbackUri?.toString())

        logger.info("Starting Ely OAuth browser flow. state={}, authHost={}", oauthState, relayBaseUrl)

        pendingStateId = oauthState
        pendingRelayBaseUrl = relayBaseUrl
        pendingStartedAtMillis = System.currentTimeMillis()
        nextPollAtMillis = pendingStartedAtMillis + POLL_INITIAL_DELAY_MS
        lastPolledStatus = null
        state = AuthFlowState(
            status = AuthFlowStatus.OPENING_BROWSER,
            message = "Открываю браузер и запускаю Ely OAuth...",
        )

        Util.getOperatingSystem().open(startUri)
        state = AuthFlowState(
            status = AuthFlowStatus.POLLING,
            message = "Ожидаю завершения авторизации в браузере...",
        )
        return true
    }

    fun clearSession() {
        pendingStateId = null
        pendingRelayBaseUrl = null
        pollingInFlight.set(false)
        pendingStartedAtMillis = 0L
        lastPolledStatus = null
        localCallbackServer?.stop()
        localCallbackServer = null
        ClientSessionStore.clear()
        MinecraftClientSessionBridge.restoreVanillaIdentity()
        state = AuthFlowState(
            status = AuthFlowStatus.IDLE,
            message = "Локальная Ely-сессия очищена.",
        )
    }

    fun cancelCurrentAttempt() {
        pendingStateId = null
        pendingRelayBaseUrl = null
        pollingInFlight.set(false)
        pendingStartedAtMillis = 0L
        lastPolledStatus = null
        localCallbackServer?.stop()
        localCallbackServer = null
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
                        authSessionExpiresAt = Instant.ofEpochSecond(expiresAt)
                    )
                )
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
        val callbackPayload = localCallbackServer?.consumeResult()
        if (callbackPayload != null) {
            handleLocalCallbackPayload(callbackPayload)
        }

        val oauthState = pendingStateId ?: return
        val relayBaseUrl = pendingRelayBaseUrl ?: return
        val now = System.currentTimeMillis()
        if (now < nextPollAtMillis || !pollingInFlight.compareAndSet(false, true)) {
            return
        }

        CompletableFuture.runAsync {
            try {
                poll(oauthState, relayBaseUrl)
            } finally {
                pollingInFlight.set(false)
            }
        }
    }

    private fun poll(oauthState: String, relayBaseUrl: String) {
        runCatching {
            AuthHostClient.poll(relayBaseUrl, oauthState)
        }.onSuccess { pollResult ->
            if (pollResult.status != lastPolledStatus) {
                logger.info(
                    "Poll result for state {} -> status={}, username={}, uuid={}, hasSessionToken={}",
                    oauthState,
                    pollResult.status,
                    pollResult.username,
                    pollResult.uuid,
                    !pollResult.authSessionToken.isNullOrBlank(),
                )
                lastPolledStatus = pollResult.status
            }
            when (pollResult.status) {
                "pending" -> {
                    nextPollAtMillis = System.currentTimeMillis() + 1500
                    state = AuthFlowState(
                        status = AuthFlowStatus.POLLING,
                        message = "Ожидаю подтверждение от Ely.by...",
                    )
                }

                "completed" -> {
                    val authSessionToken = pollResult.authSessionToken
                    val elyAccessToken = pollResult.elyAccessToken
                    val username = pollResult.username
                    val uuid = pollResult.uuid
                    val expiresAt = pollResult.expiresAtEpochSeconds

                    if (authSessionToken.isNullOrBlank() || elyAccessToken.isNullOrBlank() || username.isNullOrBlank() || uuid.isNullOrBlank() || expiresAt == null) {
                        logger.warn("Auth host returned incomplete auth session response for state {}", oauthState)
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
                            elyTexturesValue = pollResult.texturesValue,
                            elyTexturesSignature = pollResult.texturesSignature,
                        ),
                    )
                    MinecraftClientSessionBridge.refreshActiveIdentity()

                    pendingStateId = null
                    pendingRelayBaseUrl = null
                    pendingStartedAtMillis = 0L
                    lastPolledStatus = null
                    state = AuthFlowState(
                        status = AuthFlowStatus.SUCCESS,
                        message = "Вход завершен: $username ($uuid)",
                    )
                    logger.info("Stored Ely auth session for {} ({})", username, uuid)
                }

                "failed" -> {
                    val error = pollResult.error ?: "unknown error"
                    if (error == "unknown_state" && System.currentTimeMillis() - pendingStartedAtMillis < UNKNOWN_STATE_GRACE_MS) {
                        logger.info("Auth host does not know state {} yet. Retrying within grace window.", oauthState)
                        nextPollAtMillis = System.currentTimeMillis() + 1500
                    } else {
                        logger.warn("Auth host reported failure for state {}: {}", oauthState, error)
                        fail(error)
                    }
                }

                else -> {
                    logger.info("Poll for state {} returned unrecognized status '{}', retrying.", oauthState, pollResult.status)
                    nextPollAtMillis = System.currentTimeMillis() + 1500
                }
            }
        }.onFailure { exception ->
            nextPollAtMillis = System.currentTimeMillis() + 2000
            logger.warn("Auth host polling failed for state {}", oauthState, exception)
            state = AuthFlowState(
                status = AuthFlowStatus.ERROR,
                message = "Ошибка опроса auth host: ${exception.message ?: exception::class.java.simpleName}",
            )
        }
    }

    private fun fail(message: String) {
        pendingStateId = null
        pendingRelayBaseUrl = null
        pendingStartedAtMillis = 0L
        lastPolledStatus = null
        localCallbackServer?.stop()
        localCallbackServer = null
        state = AuthFlowState(
            status = AuthFlowStatus.ERROR,
            message = "Авторизация не удалась: $message",
        )
    }

    private fun handleLocalCallbackPayload(payload: LocalAuthCallbackPayload) {
        logger.info(
            "Received localhost auth callback. state={}, status={}, username={}, uuid={}, hasSessionToken={}",
            payload.state,
            payload.status,
            payload.username,
            payload.uuid,
            !payload.authSessionToken.isNullOrBlank(),
        )

        if (payload.state != pendingStateId) {
            logger.warn("Ignoring localhost callback for unexpected state {}.", payload.state)
            return
        }

        if (payload.status != "completed" ||
            payload.authSessionToken.isNullOrBlank() ||
            payload.elyAccessToken.isNullOrBlank() ||
            payload.username.isNullOrBlank() ||
            payload.uuid.isNullOrBlank() ||
            payload.expiresAtEpochSeconds == null
        ) {
            fail(payload.error ?: "localhost_callback_failed")
            return
        }

        ClientSessionStore.save(
            ClientSessionState(
                relayBaseUrl = pendingRelayBaseUrl.orEmpty(),
                authSessionToken = payload.authSessionToken,
                elyAccessToken = payload.elyAccessToken,
                authSessionExpiresAt = Instant.ofEpochSecond(payload.expiresAtEpochSeconds),
                elyUsername = payload.username,
                elyUuid = payload.uuid,
                elyTexturesValue = payload.texturesValue,
                elyTexturesSignature = payload.texturesSignature,
            ),
        )
        MinecraftClientSessionBridge.refreshActiveIdentity()

        pendingStateId = null
        pendingRelayBaseUrl = null
        pendingStartedAtMillis = 0L
        lastPolledStatus = null
        localCallbackServer?.stop()
        localCallbackServer = null
        state = AuthFlowState(
            status = AuthFlowStatus.SUCCESS,
            message = "Вход завершен: ${payload.username} (${payload.uuid})",
        )
    }
}
