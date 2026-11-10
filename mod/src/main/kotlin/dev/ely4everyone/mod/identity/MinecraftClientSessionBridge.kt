package dev.ely4everyone.mod.identity

import dev.ely4everyone.mod.Ely4EveryoneClientMod
import dev.ely4everyone.mod.mixin.client.MinecraftClientAccessor
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory

object MinecraftClientSessionBridge {
    private val logger = LoggerFactory.getLogger("${Ely4EveryoneClientMod.MOD_ID}/session-bridge")

    fun applyElyIdentity(identity: ElyIdentity): Boolean {
        val client = Minecraft.getInstance()
        val currentSession = client.user ?: return false
        val activated = ActiveElyIdentityManager.activate(identity, currentSession)
        val replacement = ActiveElyIdentityManager.currentSessionOrNull() ?: return false
        (client as MinecraftClientAccessor).`ely4everyone$setUser`(replacement)
        logger.info("Applied Ely identity to Minecraft session. username={}, uuid={}", identity.username, identity.uuid)
        return activated
    }

    fun refreshActiveIdentity(): Boolean {
        val client = Minecraft.getInstance()
        val currentSession = client.user ?: return false
        val activated = ActiveElyIdentityManager.refreshFromStore(currentSession)
        val activeSession = ActiveElyIdentityManager.currentSessionOrNull()
        if (activated && activeSession != null) {
            (client as MinecraftClientAccessor).`ely4everyone$setUser`(activeSession)
            logger.info("Refreshed active Ely identity from local session store.")
            return true
        }

        return restoreVanillaIdentity()
    }

    fun restoreVanillaIdentity(): Boolean {
        val client = Minecraft.getInstance()
        val vanillaSession = ActiveElyIdentityManager.deactivate() ?: return false
        (client as MinecraftClientAccessor).`ely4everyone$setUser`(vanillaSession)
        logger.info("Restored vanilla Minecraft session.")
        return true
    }

    fun currentSessionSummary(): String {
        val currentSession = ActiveElyIdentityManager.currentSessionOrNull()
            ?: Minecraft.getInstance().user
            ?: return "session=none"
        return "session(username=${currentSession.name}, uuid=${currentSession.profileId}, tokenPresent=${currentSession.accessToken.isNotBlank()})"
    }
}
