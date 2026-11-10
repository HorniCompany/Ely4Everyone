package dev.ely4everyone.mod.mixin.client

import com.mojang.authlib.GameProfile
import dev.ely4everyone.mod.identity.ActiveElyIdentityManager
import net.minecraft.client.Minecraft
import net.minecraft.client.User
import net.minecraft.client.multiplayer.ProfileKeyPairManager
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(Minecraft::class)
abstract class MinecraftClientGameProfileMixin {
    @Inject(method = ["getUser"], at = [At("HEAD")], cancellable = true)
    private fun `ely4everyone$overrideUser`(cir: CallbackInfoReturnable<User>) {
        ActiveElyIdentityManager.currentSessionOrNull()?.let { cir.returnValue = it }
    }

    @Inject(method = ["getGameProfile"], at = [At("HEAD")], cancellable = true)
    private fun `ely4everyone$overrideGameProfile`(cir: CallbackInfoReturnable<GameProfile>) {
        ActiveElyIdentityManager.currentGameProfileOrNull()?.let { cir.returnValue = it }
    }

    @Inject(method = ["getProfileKeyPairManager"], at = [At("HEAD")], cancellable = true)
    private fun `ely4everyone$overrideProfileKeys`(cir: CallbackInfoReturnable<ProfileKeyPairManager>) {
        if (ActiveElyIdentityManager.isActive()) {
            cir.returnValue = ProfileKeyPairManager.EMPTY_KEY_MANAGER
        }
    }
}
