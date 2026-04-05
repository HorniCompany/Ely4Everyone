package dev.ely4everyone.mod.session

import dev.ely4everyone.mod.auth.AuthWorkflowManager
import dev.ely4everyone.mod.identity.ActiveElyIdentityManager
import net.minecraft.client.MinecraftClient
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Monitors the health of the current Ely.by session token.
 *
 * Called every client tick. Checks expiration, triggers auto-refresh
 * via auth-host when the token is about to expire, and exposes
 * [currentHealth] for UI indicators.
 */
object TokenHealthMonitor {
    private val logger = LoggerFactory.getLogger("Ely4Everyone/TokenHealth")

    enum class TokenHealth {
        /** Token is valid, plenty of time remaining. */
        HEALTHY,
        /** Token expires within 24 hours — should refresh soon. */
        EXPIRING_SOON,
        /** Token has expired. */
        EXPIRED,
        /** No Ely session is active. */
        NOT_AUTHENTICATED,
    }

    private val EXPIRING_SOON_THRESHOLD = Duration.ofHours(3)
    private val AUTO_REFRESH_COOLDOWN = Duration.ofMinutes(15)
    private val CHECK_INTERVAL_TICKS = 20 * 30 // Check every 30 seconds

    @Volatile
    private var health: TokenHealth = TokenHealth.NOT_AUTHENTICATED

    @Volatile
    private var lastAutoRefreshAttempt: Instant = Instant.EPOCH

    @Volatile
    private var lastAutoRefreshSuccess: Boolean = false

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
        return when (health) {
            TokenHealth.HEALTHY -> {
                val remaining = remainingTimeText()
                "✅ Сессия активна" + if (remaining != null) " ($remaining)" else ""
            }
            TokenHealth.EXPIRING_SOON -> {
                val remaining = remainingTimeText()
                "⚠ Сессия скоро истечёт" + if (remaining != null) " ($remaining)" else ""
            }
            TokenHealth.EXPIRED -> "❌ Сессия истекла — войдите заново"
            TokenHealth.NOT_AUTHENTICATED -> "Войти через Ely.by"
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
        }

        // Auto-refresh when expiring soon
        if (newHealth == TokenHealth.EXPIRING_SOON) {
            tryAutoRefresh()
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
        val now = Instant.now()
        if (Duration.between(lastAutoRefreshAttempt, now) < AUTO_REFRESH_COOLDOWN) {
            return // Cooldown not elapsed
        }

        lastAutoRefreshAttempt = now
        logger.info("Attempting auto-refresh of Ely session via auth-host...")

        try {
            lastAutoRefreshSuccess = AuthWorkflowManager.syncLatestSessionFromAuthHost()
            if (lastAutoRefreshSuccess) {
                logger.info("Auto-refresh succeeded!")
                health = evaluateHealth() // Re-evaluate after refresh
            } else {
                logger.warn("Auto-refresh returned no usable session. Manual re-login may be needed.")
            }
        } catch (e: Exception) {
            logger.warn("Auto-refresh failed: {}", e.message)
            lastAutoRefreshSuccess = false
        }
    }

    /**
     * Force an immediate health re-evaluation (e.g. after manual login/logout).
     */
    fun forceReevaluate() {
        health = evaluateHealth()
        tickCounter = 0
    }
}
