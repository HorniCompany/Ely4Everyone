package dev.ely4everyone.mod.mixin.client

import com.mojang.authlib.GameProfile
import dev.ely4everyone.mod.identity.ActiveElyIdentityManager
import dev.ely4everyone.mod.skin.ElySkinResolver
import dev.ely4everyone.mod.skin.SkinSource
import dev.ely4everyone.mod.skin.SkinSourcePolicy
import java.util.Optional
import java.util.concurrent.CompletableFuture
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.client.resources.SkinManager
import net.minecraft.world.entity.player.PlayerSkin
import org.slf4j.LoggerFactory
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(SkinManager::class)
abstract class PlayerSkinProviderMixin {

    /**
     * Priority 1 (HEAD): Active Ely identity — our own logged-in user.
     * Short-circuits before vanilla even runs.
     */
    @Inject(
        method = ["get(Lcom/mojang/authlib/GameProfile;)Ljava/util/concurrent/CompletableFuture;"],
        at = [At("HEAD")],
        cancellable = true,
    )
    private fun `ely4everyone$handleActiveIdentity`(
        profile: GameProfile,
        cir: CallbackInfoReturnable<CompletableFuture<Optional<PlayerSkin>>>,
    ) {
        val activeState = ActiveElyIdentityManager.activeStateOrNull() ?: return
        val provider = this as PlayerSkinProviderInvoker
        when (SkinSourcePolicy.choose(profile.id, profile.name, ActiveElyIdentityManager.currentSkinIdentityOrNull())) {
            SkinSource.OWN_TEXTURES -> {
                val profileTextures = activeState.profileTextures ?: return
                val defaultSkin = DefaultPlayerSkin.get(activeState.gameProfile)
                cir.returnValue = provider.`ely4everyone$invokeRegisterTextures`(
                    activeState.elySession.profileId, profileTextures,
                ).thenApply { Optional.of(it) }
                    .exceptionally { Optional.of(defaultSkin) }
            }
            SkinSource.OWN_ELY_LOOKUP -> {
                val defaultSkin = DefaultPlayerSkin.get(activeState.gameProfile)
                cir.returnValue = ElySkinResolver.resolve(profile.name).thenCompose { elyTextures ->
                    if (elyTextures != null) {
                        provider.`ely4everyone$invokeRegisterTextures`(profile.id, elyTextures).thenApply { Optional.of(it) }
                    } else {
                        CompletableFuture.completedFuture(Optional.of(defaultSkin))
                    }
                }.exceptionally { Optional.of(defaultSkin) }
            }
            SkinSource.OTHER_ELY_LOOKUP, SkinSource.VANILLA -> Unit
        }
    }

    /**
     * Priority 2 (RETURN): All other players — check Ely.by, fallback to vanilla Mojang.
     *
     * Runs AFTER vanilla has already computed its result. We wrap the vanilla future
     * with an Ely.by check: if Ely has a skin, use it; otherwise use vanilla's result.
     */
    @Inject(
        method = ["get(Lcom/mojang/authlib/GameProfile;)Ljava/util/concurrent/CompletableFuture;"],
        at = [At("RETURN")],
        cancellable = true,
    )
    private fun `ely4everyone$overrideWithElySkin`(
        profile: GameProfile,
        cir: CallbackInfoReturnable<CompletableFuture<Optional<PlayerSkin>>>,
    ) {
        // Our own player was already handled at HEAD.
        if (SkinSourcePolicy.choose(profile.id, profile.name, ActiveElyIdentityManager.currentSkinIdentityOrNull()) != SkinSource.OTHER_ELY_LOOKUP) {
            return
        }

        val vanillaFuture = cir.returnValue ?: return
        val provider = this as PlayerSkinProviderInvoker
        // The skin lookup must never fail: a failed future here reaches the render thread and crashes it.
        // Worst case is vanilla's skin, and if even that failed, the default one (empty).
        val safeVanilla = vanillaFuture.exceptionally { Optional.empty() }

        try {
            // Ely.by first, vanilla/Mojang when Ely has no skin for this name.
            cir.returnValue = ElySkinResolver.resolve(profile.name).thenCompose { elyTextures ->
                if (elyTextures != null) {
                    provider.`ely4everyone$invokeRegisterTextures`(profile.id, elyTextures)
                        .thenApply { Optional.of(it) }
                } else {
                    safeVanilla
                }
            }.exceptionallyCompose { safeVanilla }
        } catch (error: RuntimeException) {
            LoggerFactory.getLogger("Ely4Everyone/Skins").warn("Ely.by skin lookup failed for '{}': {}", profile.name, error.toString())
            cir.returnValue = safeVanilla
        }
    }
}
