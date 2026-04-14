package dev.ely4everyone.mod.session

import dev.ely4everyone.mod.auth.AuthWorkflowManager
import dev.ely4everyone.mod.identity.ActiveElyIdentityManager
import dev.ely4everyone.mod.skin.ElySkinResolver
import net.minecraft.client.MinecraftClient
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Monitors the health of the current Ely.by session token.
 *
 * Called every client tick. Checks expiration, triggers auto-refresh
 * via auth-host when the token is about to expire or has already expired,
 * and exposes [currentHealth] for UI indicators.
 *
 * Also periodically refreshes the player skin from Ely.by (every 5 minutes)
 * to keep textures up-to-date without restarting the client.
 */
object TokenHealthMonitor {
    private val logger = LoggerFactory.getLogger("Ely4Everyone/TokenHealth")

    enum class TokenHealth {
        /** Token is valid, plenty of time remaining. */
        HEALTHY,
        /** Token expires within 12 hours — should refresh soon. */
        EXPIRING_SOON,
        /** Token has expired. */
        EXPIRED,
        /** No Ely session is active. */
        NOT_AUTHENTICATED,
    }

    private val EXPIRING_SOON_THRESHOLD = Duration.ofHours(12)
    private val AUTO_REFRESH_COOLDOWN = Duration.ofMinutes(15)
    private val SKIN_REFRESH_INTERVAL = Duration.ofMinutes(5)
    private val CHECK_INTERVAL_TICKS = 20 * 30 // Check every 30 seconds

    /** Max auto-refresh attempts per session to avoid infinite retry loops. */
    private const val MAX_CONSECUTIVE_FAILURES = 5

    @Volatile
    private var health: TokenHealth = TokenHealth.NOT_AUTHENTICATED

    @Volatile
    private var lastAutoRefreshAttempt: Instant = Instant.EPOCH

    @Volatile
    private var lastAutoRefreshSuccess: Boolean = false

    @Volatile
    private var consecutiveFailures: Int = 0

    @Volatile
    private var lastSkinRefresh: Instant = Instant.EPOCH

    private var tickCounter: Int = 0

    fun currentHealth(): TokenHealth = health

    /**
     * Returns a human-readable remaining time string, or null if not applicable.
     */
    fun remainingTimeText(): String? {
        val session = ClientSessionStore.load()
        val expiresAt = session.authSessionExpiresAt ?: return null
        val remaining = Duration.between(Instant.now(), expiresAt)
        if (remaining.isNegative) return "Истёк"

        val hours = remaining.toHours()
        val minutes = remaining.toMinutes() % 60
        return when {
            hours >= 24 -> "${remaining.toDays()}д ${hours % 24}ч"
            hours >= 1 -> "${hours}ч ${minutes}мин"
            else -> "${remaining.toMinutes()}мин"
        }
    }

    /**
     * Returns tooltip text for the title screen icon button.
     */
    fun tooltipText(): String {
        val remaining = remainingTimeText()
        val suffix = if (remaining != null) " ($remaining)" else ""
        return when (health) {
            TokenHealth.HEALTHY -> "✅ Сессия активна$suffix"
            TokenHealth.EXPIRING_SOON -> "⚠ Сессия скоро истечёт$suffix"
            TokenHealth.EXPIRED -> "❌ Сессия истекла — войдите заново$suffix"
            TokenHealth.NOT_AUTHENTICATED -> "Войти через Ely.by"
        }
    }

    /**
     * Attempt a token refresh at mod boot time.
     * Called once during mod initialization if a session exists but is
     * expiring soon or already expired — tries to silently renew it
     * so the player doesn't have to re-login through the browser.
     */
    fun bootTimeRefresh() {
        val bootHealth = evaluateHealth()
        health = bootHealth
        logger.info("Boot-time token health: {}", bootHealth)

        if (bootHealth == TokenHealth.EXPIRING_SOON || bootHealth == TokenHealth.EXPIRED) {
            logger.info("Token is {} at boot — attempting background refresh...", bootHealth)
            try {
                val success = AuthWorkflowManager.syncLatestSessionFromAuthHost()
                if (success) {
                    consecutiveFailures = 0
                    lastAutoRefreshSuccess = true
                    health = evaluateHealth()
                    logger.info("Boot-time refresh succeeded! New health: {}", health)
                } else {
                    consecutiveFailures++
                    lastAutoRefreshSuccess = false
                    logger.warn("Boot-time refresh failed. Manual re-login may be needed.")
                }
            } catch (e: Exception) {
                consecutiveFailures++
                lastAutoRefreshSuccess = false
                logger.warn("Boot-time refresh error: {}", e.message)
            }
            lastAutoRefreshAttempt = Instant.now()
        }
    }

    fun tick(client: MinecraftClient) {
        tickCounter++
        if (tickCounter < CHECK_INTERVAL_TICKS) return
        tickCounter = 0

        // Evaluate current health
        val newHealth = evaluateHealth()
        val oldHealth = health
        health = newHealth

        if (oldHealth != newHealth) {
            logger.info("Token health changed: {} -> {}", oldHealth, newHealth)
            // Reset failure counter on any health transition
            if (newHealth == TokenHealth.HEALTHY) {
                consecutiveFailures = 0
            }
        }

        // Auto-refresh when expiring soon OR expired (refresh_token may still be valid on the server)
        if (newHealth == TokenHealth.EXPIRING_SOON || newHealth == TokenHealth.EXPIRED) {
            tryAutoRefresh()
        }

        // Periodic skin refresh for authenticated players (every 5 minutes)
        if (newHealth != TokenHealth.NOT_AUTHENTICATED) {
            trySkinRefresh()
        }
    }

    private fun evaluateHealth(): TokenHealth {
        if (!ActiveElyIdentityManager.isActive()) {
            return TokenHealth.NOT_AUTHENTICATED
        }

        val session = ClientSessionStore.load()
        val expiresAt = session.authSessionExpiresAt ?: return TokenHealth.HEALTHY // No expiry = eternal

        val now = Instant.now()
        if (expiresAt.isBefore(now)) {
            return TokenHealth.EXPIRED
        }

        val remaining = Duration.between(now, expiresAt)
        if (remaining < EXPIRING_SOON_THRESHOLD) {
            return TokenHealth.EXPIRING_SOON
        }

        return TokenHealth.HEALTHY
    }

    private fun tryAutoRefresh() {
        // Don't retry forever — after MAX_CONSECUTIVE_FAILURES, stop until next login
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            return
        }

        val now = Instant.now()
        if (Duration.between(lastAutoRefreshAttempt, now) < AUTO_REFRESH_COOLDOWN) {
            return // Cooldown not elapsed
        }

        lastAutoRefreshAttempt = now
        logger.info("Attempting auto-refresh of Ely session via auth-host (attempt #{})...", consecutiveFailures + 1)

        try {
            lastAutoRefreshSuccess = AuthWorkflowManager.syncLatestSessionFromAuthHost()
            if (lastAutoRefreshSuccess) {
                consecutiveFailures = 0
                logger.info("Auto-refresh succeeded!")
                health = evaluateHealth() // Re-evaluate after refresh
            } else {
                consecutiveFailures++
                logger.warn("Auto-refresh returned no usable session (failure #{}/{}). Manual re-login may be needed.",
                    consecutiveFailures, MAX_CONSECUTIVE_FAILURES)
            }
        } catch (e: Exception) {
            consecutiveFailures++
            logger.warn("Auto-refresh failed (failure #{}/{}): {}", consecutiveFailures, MAX_CONSECUTIVE_FAILURES, e.message)
            lastAutoRefreshSuccess = false
        }
    }

    /**
     * Periodically refresh the skin cache from Ely.by to pick up skin changes
     * without requiring a client restart.
     */
    private fun trySkinRefresh() {
        val now = Instant.now()
        if (Duration.between(lastSkinRefresh, now) < SKIN_REFRESH_INTERVAL) {
            return
        }
        lastSkinRefresh = now

        val session = ClientSessionStore.load()
        val username = session.elyUsername ?: return

        // Invalidate cached skin and trigger a fresh fetch
        ElySkinResolver.invalidate(username)
        ElySkinResolver.resolve(username).thenAccept { textures ->
            if (textures != null) {
                logger.debug("Skin refreshed for '{}'", username)
            }
        }
    }

    /**
     * Force an immediate health re-evaluation (e.g. after manual login/logout).
     */
    fun forceReevaluate() {
        health = evaluateHealth()
        tickCounter = 0
        consecutiveFailures = 0
    }
}
