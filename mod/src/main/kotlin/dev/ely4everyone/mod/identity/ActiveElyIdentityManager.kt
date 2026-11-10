package dev.ely4everyone.mod.identity

import com.mojang.authlib.GameProfile
import com.mojang.authlib.minecraft.MinecraftProfileTextures
import com.mojang.authlib.properties.Property
import dev.ely4everyone.mod.session.ClientSessionState
import dev.ely4everyone.mod.session.ClientSessionStore
import net.minecraft.client.User

sealed interface ActiveElyIdentityState {
    data object Vanilla : ActiveElyIdentityState

    data class Active(
        val vanillaSession: User?,
        val elySession: User,
        val gameProfile: GameProfile,
        val identity: ElyIdentity,
        val texturesProperty: Property?,
        val profileTextures: MinecraftProfileTextures?,
    ) : ActiveElyIdentityState
}

object ActiveElyIdentityManager {
    @Volatile
    private var state: ActiveElyIdentityState = ActiveElyIdentityState.Vanilla

    fun currentState(): ActiveElyIdentityState = state

    fun isActive(): Boolean = state is ActiveElyIdentityState.Active

    fun activeStateOrNull(): ActiveElyIdentityState.Active? = state as? ActiveElyIdentityState.Active

    fun currentSessionOrNull(): User? = activeStateOrNull()?.elySession

    fun currentGameProfileOrNull(): GameProfile? = activeStateOrNull()?.gameProfile

    fun currentTexturesPropertyOrNull(): Property? = activeStateOrNull()?.texturesProperty

    fun currentProfileTexturesOrNull(): MinecraftProfileTextures? = activeStateOrNull()?.profileTextures

    fun refreshFromStore(currentSession: User?): Boolean {
        if (currentSession == null) {
            deactivate()
            return false
        }

        val sessionState = ClientSessionStore.load()
        return activate(sessionState, currentSession)
    }

    fun activate(sessionState: ClientSessionState, currentSession: User): Boolean {
        val identity = ElyIdentityManager.fromClientSession(sessionState) ?: run {
            deactivate()
            return false
        }
        return activate(identity, currentSession)
    }

    fun activate(identity: ElyIdentity, currentSession: User): Boolean {
        val sessionUuid = runCatching { java.util.UUID.fromString(identity.uuid) }.getOrNull() ?: return false
        val elySession = User(
            identity.username,
            sessionUuid,
            identity.accessToken,
            currentSession.xuid,
            currentSession.clientId,
        )
        val gameProfile = ElyIdentityManager.toGameProfile(identity) ?: return false
        val previous = activeStateOrNull()
        state = ActiveElyIdentityState.Active(
            vanillaSession = previous?.vanillaSession ?: currentSession,
            elySession = elySession,
            gameProfile = gameProfile,
            identity = identity,
            texturesProperty = ElyIdentityManager.toTexturesProperty(identity),
            profileTextures = ElyIdentityManager.toMinecraftProfileTextures(identity),
        )
        return true
    }

    fun deactivate(): User? {
        val previous = activeStateOrNull()
        state = ActiveElyIdentityState.Vanilla
        return previous?.vanillaSession
    }
}
