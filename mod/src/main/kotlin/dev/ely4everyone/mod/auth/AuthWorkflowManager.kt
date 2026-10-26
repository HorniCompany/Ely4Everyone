package dev.ely4everyone.mod.auth

import dev.ely4everyone.mod.Ely4EveryoneClientMod
import dev.ely4everyone.mod.identity.MinecraftClientSessionBridge
import dev.ely4everyone.mod.session.ClientSessionState
import dev.ely4everyone.mod.session.ClientSessionStore
import net.minecraft.client.MinecraftClient
import net.minecraft.util.Util
import org.slf4j.LoggerFactory
import java.time.Duration
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
    /** Code the player confirms on Ely.by while [status] is [AuthFlowStatus.POLLING]. */
    val userCode: String? = null,
    /** Where to type [userCode] by hand, e.g. from a phone. */
    val verificationUri: String? = null,
)

/**
 * Login straight against Ely.by with the device code grant, see [ElyDirectLogin].
 *
 * The browser opens on account.ely.by with the code already filled in, the player confirms, and
 * [tick] keeps asking Ely whether the code was confirmed. The screen also shows the code, so the
 * player can confirm it from another device if the browser did not open.
 *
 * Sessions made by older versions through an auth host keep working: [refreshSession] still renews
 * them through that host until the player logs in again.
 */
object AuthWorkflowManager {
    private val logger = LoggerFactory.getLogger("${Ely4EveryoneClientMod.MOD_ID}/auth")

    // RFC 8628: on slow_down the client must poll at least 5 seconds less often.
    private const val SLOW_DOWN_STEP_MS: Long = 5_000

    // Without an expiry from Ely the token still has to expire somewhere; a refresh renews it.
    private val FALLBACK_TOKEN_LIFETIME: Duration = Duration.ofDays(30)

    /** One login attempt. A new attempt gets a new object, so late results of an old one are ignored. */
    private class DeviceAttempt(
        val code: ElyDeviceCode,
        var intervalMs: Long,
        val expiresAtMillis: Long,
        var nextPollAtMillis: Long,
        var pollInFlight: Boolean = false,
    )

    private sealed interface PollOutcome {
        data object Wait : PollOutcome
        data object SlowDown : PollOutcome
        data class Done(val tokens: ElyTokens, val profile: ElyProfile) : PollOutcome
        data class Error(val message: String) : PollOutcome
    }

    @Volatile
    private var state: AuthFlowState = AuthFlowState()

    /** Set while a login is starting or waiting; [attempt] is null until Ely hands out a code. */
    @Volatile
    private var loginInProgress: Boolean = false

    @Volatile
    private var startToken: Any? = null

    @Volatile
    private var attempt: DeviceAttempt? = null

    fun currentState(): AuthFlowState = state

    fun resetUiState(message: String = "Готов к входу через Ely.by") {
        if (loginInProgress) {
            return
        }
        state = AuthFlowState(
            status = AuthFlowStatus.IDLE,
            message = message,
        )
    }

    fun startBrowserLogin(): Boolean {
        if (loginInProgress) {
            return false
        }

        val token = Any()
        startToken = token
        loginInProgress = true
        state = AuthFlowState(
            status = AuthFlowStatus.OPENING_BROWSER,
            message = "Получаю код входа от Ely.by...",
        )

        // Off the render thread: Ely may be slow or unreachable.
        CompletableFuture
            .supplyAsync { ElyDirectLogin.requestDeviceCode() }
            .whenComplete { code, error ->
                MinecraftClient.getInstance().execute {
                    if (startToken !== token) {
                        return@execute
                    }
                    startToken = null
                    if (error != null || code == null) {
                        val cause = error?.cause ?: error
                        logger.warn("Could not get a login code from Ely.by", cause)
                        fail("Ely.by недоступен (${cause?.message ?: cause?.javaClass?.simpleName ?: "unknown error"}).")
                        return@execute
                    }
                    beginWaiting(code)
                }
            }
        return true
    }

    private fun beginWaiting(code: ElyDeviceCode) {
        val now = System.currentTimeMillis()
        attempt = DeviceAttempt(
            code = code,
            intervalMs = code.intervalSeconds * 1000,
            expiresAtMillis = now + code.expiresInSeconds * 1000,
            nextPollAtMillis = now + code.intervalSeconds * 1000,
        )
        // The code stays out of the log: latest.log gets shared in help threads.
        logger.info("Waiting for the player to confirm the Ely.by login code")
        state = AuthFlowState(
            status = AuthFlowStatus.POLLING,
            message = "Подтверди вход в браузере, игра подхватит его сама...",
            userCode = code.userCode,
            verificationUri = code.verificationUri,
        )
        runCatching { Util.getOperatingSystem().open(code.verificationUriComplete) }
            .onFailure { logger.warn("Could not open the browser for the Ely.by login", it) }
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

    /** Renews the stored login: directly at Ely.by, or through the auth host for older sessions. */
    fun refreshSession(): Boolean {
        val seenSession = ClientSessionStore.load()
        // All games of this user share one login. Only one may renew it at a time, or a refresh token
        // that Ely rotates in one game would be spent again by the other and log the player out.
        return ClientSessionStore.tryWithLock {
            val currentSession = ClientSessionStore.load()
            when {
                renewedElsewhere(seenSession, currentSession) -> {
                    logger.info("Another game already renewed the Ely login, using it.")
                    MinecraftClientSessionBridge.refreshActiveIdentity()
                    true
                }
                currentSession.isDirectLogin -> refreshDirectSession(currentSession)
                else -> refreshRelaySession(currentSession)
            }
        } ?: run {
            logger.info("Another game is renewing the Ely login right now, skipping this attempt.")
            false
        }
    }

    private fun renewedElsewhere(seen: ClientSessionState, current: ClientSessionState): Boolean {
        val seenExpiry = seen.authSessionExpiresAt ?: return false
        val currentExpiry = current.authSessionExpiresAt ?: return false
        return currentExpiry.isAfter(seenExpiry) && current.hasUsableElyAccessToken()
    }

    private fun refreshDirectSession(currentSession: ClientSessionState): Boolean {
        val refreshToken = currentSession.elyRefreshToken
        if (refreshToken.isNullOrBlank()) {
            logger.warn("Cannot refresh the Ely login: no refresh token stored.")
            return false
        }
        return try {
            val tokens = ElyDirectLogin.refresh(refreshToken)
            ClientSessionStore.save(
                currentSession.copy(
                    elyAccessToken = tokens.accessToken,
                    elyRefreshToken = tokens.refreshToken ?: refreshToken,
                    authSessionExpiresAt = expiryOf(tokens),
                ),
            )
            MinecraftClientSessionBridge.refreshActiveIdentity()
            logger.info("Refreshed the Ely login directly at Ely.by.")
            true
        } catch (exception: Exception) {
            logger.warn("Refreshing the Ely login failed: {}", exception.toString())
            false
        }
    }

    private fun refreshRelaySession(currentSession: ClientSessionState): Boolean {
        val authSessionToken = currentSession.authSessionToken
        if (authSessionToken.isNullOrBlank() || currentSession.relayBaseUrl.isBlank()) {
            logger.warn("Cannot auto-refresh: missing session token or relay base url.")
            return false
        }

        return try {
            val result = AuthHostClient.refreshSession(currentSession.relayBaseUrl, authSessionToken)
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
                    ),
                )
                MinecraftClientSessionBridge.refreshActiveIdentity()
                logger.info("Successfully refreshed Ely session token via auth host.")
                true
            } else {
                logger.warn("Refresh failed with status {}", result.status)
                state = AuthFlowState(
                    status = AuthFlowStatus.ERROR,
                    message = "Не удалось продлить вход (${result.status}: ${result.error}). Войди через Ely.by заново.",
                )
                false
            }
        } catch (e: Exception) {
            logger.error("Auto-refresh error", e)
            false
        }
    }

    fun tick(client: MinecraftClient) {
        val current = attempt ?: return
        if (current.pollInFlight) {
            return
        }
        val now = System.currentTimeMillis()
        if (now >= current.expiresAtMillis) {
            fail("код истёк, попробуй ещё раз.")
            return
        }
        if (now < current.nextPollAtMillis) {
            return
        }

        current.pollInFlight = true
        CompletableFuture
            .supplyAsync { poll(current.code.deviceCode) }
            .whenComplete { outcome, error ->
                // Back on the client thread: the identity swap below must not race the game.
                client.execute {
                    current.pollInFlight = false
                    if (attempt !== current) {
                        // The player cancelled or restarted the login meanwhile.
                        return@execute
                    }
                    handleOutcome(current, outcome ?: PollOutcome.Error(error?.cause?.message ?: error?.message ?: "unknown error"))
                }
            }
    }

    /** Runs off the client thread. A confirmed code also fetches the profile, so the save is instant. */
    private fun poll(deviceCode: String): PollOutcome {
        return try {
            when (val result = ElyDirectLogin.pollDeviceToken(deviceCode)) {
                DeviceTokenPoll.Pending -> PollOutcome.Wait
                DeviceTokenPoll.SlowDown -> PollOutcome.SlowDown
                is DeviceTokenPoll.Failed -> PollOutcome.Error(result.error)
                is DeviceTokenPoll.Granted -> PollOutcome.Done(result.tokens, ElyDirectLogin.fetchProfile(result.tokens.accessToken))
            }
        } catch (exception: Exception) {
            // A network hiccup while the player is still in the browser is not fatal: try again later.
            logger.warn("Asking Ely.by about the login code failed: {}", exception.message)
            PollOutcome.Wait
        }
    }

    private fun handleOutcome(current: DeviceAttempt, outcome: PollOutcome) {
        val now = System.currentTimeMillis()
        when (outcome) {
            PollOutcome.Wait -> current.nextPollAtMillis = now + current.intervalMs
            PollOutcome.SlowDown -> {
                current.intervalMs += SLOW_DOWN_STEP_MS
                current.nextPollAtMillis = now + current.intervalMs
            }
            is PollOutcome.Error -> {
                logger.warn("Ely.by refused the login: {}", outcome.message)
                fail(outcome.message)
            }
            is PollOutcome.Done -> finishLogin(outcome.tokens, outcome.profile)
        }
    }

    private fun finishLogin(tokens: ElyTokens, profile: ElyProfile) {
        ClientSessionStore.save(
            ClientSessionState(
                relayBaseUrl = "",
                elyAccessToken = tokens.accessToken,
                elyRefreshToken = tokens.refreshToken,
                authSessionExpiresAt = expiryOf(tokens),
                elyUsername = profile.username,
                elyUuid = profile.uuid,
                elyTexturesValue = profile.texturesValue,
                elyTexturesSignature = profile.texturesSignature,
            ),
        )
        MinecraftClientSessionBridge.refreshActiveIdentity()

        resetPendingLogin()
        state = AuthFlowState(
            status = AuthFlowStatus.SUCCESS,
            message = "Вход завершен: ${profile.username}",
        )
        logger.info("Stored Ely login for {} ({})", profile.username, profile.uuid)
    }

    private fun expiryOf(tokens: ElyTokens): Instant {
        val lifetime = if (tokens.expiresInSeconds > 0) Duration.ofSeconds(tokens.expiresInSeconds) else FALLBACK_TOKEN_LIFETIME
        return Instant.now().plus(lifetime)
    }

    private fun fail(message: String) {
        resetPendingLogin()
        state = AuthFlowState(
            status = AuthFlowStatus.ERROR,
            message = "Авторизация не удалась: $message",
        )
    }

    private fun resetPendingLogin() {
        loginInProgress = false
        startToken = null
        attempt = null
    }
}
