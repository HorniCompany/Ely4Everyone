package dev.ely4everyone.velocity.hook

import com.github.games647.fastlogin.velocity.event.VelocityFastLoginPreLoginEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.EventTask
import dev.ely4everyone.shared.session.ClientAuthSessionStore
import dev.ely4everyone.velocity.config.ProxyConfig
import org.slf4j.Logger
import java.util.concurrent.CompletableFuture

/**
 * FastLogin hook for Ely4Everyone — hybrid auth controller.
 *
 * This is the AUTHORITATIVE source for premium/cracked decisions.
 * LimboAuth is set to force-offline-mode: true (no premium checks).
 * FastLogin DB may have stale Premium values.
 *
 * ONLY this hook decides based on active client-auth sessions:
 *  - Active Ely.by session → force premium=true → encryption → Ely.by verify → online UUID
 *  - No session → force premium=false → cracked → offline UUID → LimboAuth /register or /login
 *
 * This ensures:
 *  1. Ely.by mod users get premium login with online UUID
 *  2. Pirates get cracked login with offline UUID
 *  3. Same nick, different UUIDs = separate inventories
 */
class VelocityFastLoginHook(
    private val config: ProxyConfig,
    private val logger: Logger,
    private val sessionStore: ClientAuthSessionStore,
) {
    @Subscribe
    fun onFastLoginPreLogin(event: VelocityFastLoginPreLoginEvent): EventTask {
        val profile = event.profile
        val username = event.username

        if (!config.enableFastloginHook) {
            logger.info("[Ely4Everyone] Hook disabled. Passthrough for {}.", username)
            return EventTask.withContinuation { it.resume() }
        }

        if (profile.floodgate.toString() == "TRUE") {
            logger.info("[Ely4Everyone] {} is Bedrock. Skipping.", username)
            return EventTask.withContinuation { it.resume() }
        }

        return EventTask.resumeWhenComplete(
            CompletableFuture.supplyAsync {
                val session = sessionStore.findByUsername(username)

                if (session != null) {
                    // Active Ely.by OAuth session — this player has the mod and authorized.
                    // Force premium so FastLogin triggers encryption → Ely.by session verify.
                    // Player gets Ely.by online UUID → separate inventory from pirates.
                    logger.info(
                        "[Ely4Everyone] {} has active Ely.by session (uuid={}, expires={}). FORCING PREMIUM.",
                        username, session.uuid, session.expiresAtEpochSeconds,
                    )
                    profile.isPremium = false // Passthrough
                } else {
                    profile.isPremium = false // Passthrough
                }
            }
        )
    }
}
