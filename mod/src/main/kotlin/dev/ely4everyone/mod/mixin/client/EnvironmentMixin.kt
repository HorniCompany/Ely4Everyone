package dev.ely4everyone.mod.mixin.client

import com.mojang.authlib.Environment
import dev.ely4everyone.mod.research26.YggdrasilEndpointOverride
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(Environment::class)
abstract class EnvironmentMixin {
    @Inject(method = ["sessionHost"], at = [At("HEAD")], cancellable = true)
    private fun `ely4everyone$overrideSessionHost`(cir: CallbackInfoReturnable<String>) {
        val roots = YggdrasilEndpointOverride.currentRootsOrNull() ?: return
        cir.returnValue = roots.sessionHost
    }

    @Inject(method = ["servicesHost"], at = [At("HEAD")], cancellable = true)
    private fun `ely4everyone$overrideServicesHost`(cir: CallbackInfoReturnable<String>) {
        val roots = YggdrasilEndpointOverride.currentRootsOrNull() ?: return
        cir.returnValue = roots.servicesHost
    }
}
