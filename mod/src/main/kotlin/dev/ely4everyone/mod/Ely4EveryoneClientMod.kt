package dev.ely4everyone.mod

import dev.ely4everyone.mod.auth.AuthWorkflowManager
import dev.ely4everyone.mod.config.ModConfigStore
import dev.ely4everyone.mod.identity.MinecraftClientSessionBridge
import dev.ely4everyone.mod.session.ClientSessionStore
import dev.ely4everyone.mod.session.TokenHealthMonitor
import dev.ely4everyone.mod.ui.TitleScreenIntegration
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import org.slf4j.LoggerFactory

class Ely4EveryoneClientMod : ClientModInitializer {
    companion object {
        const val MOD_ID: String = "ely4everyone"
        private val logger = LoggerFactory.getLogger(MOD_ID)
    }

    override fun onInitializeClient() {
        ModConfigStore.saveDefaultsIfMissing()
        ClientSessionStore.saveDefaultsIfMissing()
        val config = ModConfigStore.load()
        val sessionState = ClientSessionStore.load()
        MinecraftClientSessionBridge.refreshActiveIdentity()
        TitleScreenIntegration.register()
        dev.ely4everyone.mod.network.LoginQueryResponder.register()
        ClientTickEvents.END_CLIENT_TICK.register(AuthWorkflowManager::tick)
        ClientTickEvents.END_CLIENT_TICK.register(TokenHealthMonitor::tick)

        // Attempt to silently refresh an expiring/expired session at boot time
        // so the player doesn't have to re-login through browser every day
        if (sessionState.authSessionToken != null && sessionState.relayBaseUrl.isNotBlank()) {
            java.util.concurrent.CompletableFuture.runAsync {
                TokenHealthMonitor.bootTimeRefresh()
            }
        }

        logger.info(
            "Ely4Everyone client initialized. relayBaseUrl={}, discoveryMode={}, preferredLoginMode={}, hasSession={}, {}",
            config.relayBaseUrl,
            config.serverDiscoveryMode,
            config.preferredLoginMode,
            sessionState.hasUsableAuthSession(),
            MinecraftClientSessionBridge.currentSessionSummary(),
        )
    }
}
