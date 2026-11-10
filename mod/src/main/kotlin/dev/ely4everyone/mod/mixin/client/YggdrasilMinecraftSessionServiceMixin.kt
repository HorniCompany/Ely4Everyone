package dev.ely4everyone.mod.mixin.client

import com.mojang.authlib.GameProfile
import com.mojang.authlib.minecraft.MinecraftProfileTextures
import com.mojang.authlib.properties.Property
// authlib 10 (Minecraft 26.3) dropped the yggdrasil package; the session service kept these methods.
//? if >=26.3 {
/*import com.mojang.authlib.services.MinecraftServicesSessionService as SessionServiceImpl
*///?} else
import com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService as SessionServiceImpl
import dev.ely4everyone.mod.identity.ActiveElyIdentityManager
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(SessionServiceImpl::class)
abstract class YggdrasilMinecraftSessionServiceMixin {
    @Inject(method = ["getPackedTextures"], at = [At("HEAD")], cancellable = true)
    private fun `ely4everyone$provideTexturesForActiveIdentity`(
        profile: GameProfile,
        cir: CallbackInfoReturnable<Property?>,
    ) {
        val currentProfile = ActiveElyIdentityManager.currentGameProfileOrNull() ?: return
        if (currentProfile.id != profile.id || currentProfile.name != profile.name) {
            return
        }

        cir.returnValue = ActiveElyIdentityManager.currentTexturesPropertyOrNull()
    }

    @Inject(method = ["unpackTextures"], at = [At("HEAD")], cancellable = true)
    private fun `ely4everyone$unpackTexturesForActiveIdentity`(
        property: Property,
        cir: CallbackInfoReturnable<MinecraftProfileTextures>,
    ) {
        val activeProperty = ActiveElyIdentityManager.currentTexturesPropertyOrNull() ?: return
        if (activeProperty.value != property.value || activeProperty.signature != property.signature) {
            return
        }

        ActiveElyIdentityManager.currentProfileTexturesOrNull()?.let { cir.returnValue = it }
    }
}
