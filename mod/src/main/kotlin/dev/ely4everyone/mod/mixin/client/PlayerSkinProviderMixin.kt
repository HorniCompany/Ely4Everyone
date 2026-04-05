package dev.ely4everyone.mod.mixin.client

import com.mojang.authlib.GameProfile
import dev.ely4everyone.mod.identity.ActiveElyIdentityManager
import dev.ely4everyone.mod.skin.ElySkinResolver
import net.minecraft.client.texture.PlayerSkinProvider
import net.minecraft.client.util.DefaultSkinHelper
import net.minecraft.entity.player.SkinTextures
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.util.Optional
import java.util.concurrent.CompletableFuture

@Mixin(PlayerSkinProvider::class)
abstract class PlayerSkinProviderMixin {

    /**
     * Priority 1 (HEAD): Active Ely identity — our own logged-in user.
     * Short-circuits before vanilla even runs.
     */
    @Inject(
        method = ["fetchSkinTextures(Lcom/mojang/authlib/GameProfile;)Ljava/util/concurrent/CompletableFuture;"],
        at = [At("HEAD")],
        cancellable = true,
    )
    private fun `ely4everyone$handleActiveIdentity`(
        profile: GameProfile,
        cir: CallbackInfoReturnable<CompletableFuture<Optional<SkinTextures>>>,
    ) {
        val activeState = ActiveElyIdentityManager.activeStateOrNull() ?: return
        if (activeState.gameProfile.id != profile.id || activeState.gameProfile.name != profile.name) {
            return
        }

        val profileTextures = activeState.profileTextures
        if (profileTextures != null) {
            val provider = this as PlayerSkinProviderInvoker
            cir.returnValue = provider.`ely4everyone$invokeFetchSkinTextures`(
                activeState.elySession.uuidOrNull, profileTextures,
            ).thenApply { Optional.of(it) }
            return
        }

        cir.returnValue = CompletableFuture.completedFuture(
            Optional.of(DefaultSkinHelper.getSkinTextures(activeState.gameProfile)),
        )
    }

    /**
     * Priority 2 (RETURN): All other players — check Ely.by, fallback to vanilla Mojang.
     *
     * Runs AFTER vanilla has already computed its result. We wrap the vanilla future
     * with an Ely.by check: if Ely has a skin, use it; otherwise use vanilla's result.
     */
    @Inject(
        method = ["fetchSkinTextures(Lcom/mojang/authlib/GameProfile;)Ljava/util/concurrent/CompletableFuture;"],
        at = [At("RETURN")],
        cancellable = true,
    )
    private fun `ely4everyone$overrideWithElySkin`(
        profile: GameProfile,
        cir: CallbackInfoReturnable<CompletableFuture<Optional<SkinTextures>>>,
    ) {
        // Skip if this is our active Ely identity (already handled by HEAD inject)
        val activeState = ActiveElyIdentityManager.activeStateOrNull()
        if (activeState != null &&
            activeState.gameProfile.id == profile.id &&
            activeState.gameProfile.name == profile.name
        ) {
            return
        }

        val username = profile.name
        if (username.isNullOrBlank()) return

        val vanillaFuture = cir.returnValue ?: return
        val provider = this as PlayerSkinProviderInvoker

        // Wrap: check Ely.by first, fallback to vanilla
        cir.returnValue = ElySkinResolver.resolve(username).thenCompose { elyTextures ->
            if (elyTextures != null) {
                // Ely.by has a skin for this player — use it!
                provider.`ely4everyone$invokeFetchSkinTextures`(profile.id, elyTextures)
                    .thenApply { Optional.of(it) }
            } else {
                // No Ely.by skin — use whatever vanilla/Mojang returned
                vanillaFuture
            }
        }.exceptionally { _ ->
            // If anything goes wrong, fall back to vanilla silently
            vanillaFuture.join()
        }
    }
}
