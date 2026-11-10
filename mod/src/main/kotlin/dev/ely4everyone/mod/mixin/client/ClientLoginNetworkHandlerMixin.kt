package dev.ely4everyone.mod.mixin.client

import dev.ely4everyone.mod.identity.ElyAuthlibBridge
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl
import net.minecraft.network.chat.Component
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(ClientHandshakePacketListenerImpl::class)
abstract class ClientLoginNetworkHandlerMixin {
    @Inject(method = ["authenticateServer"], at = [At("HEAD")], cancellable = true)
    private fun `ely4everyone$joinViaEly`(serverId: String, cir: CallbackInfoReturnable<Component?>) {
        if (!ElyAuthlibBridge.hasUsableElySession()) {
            return
        }

        cir.returnValue = ElyAuthlibBridge.tryJoinServerSession(serverId)
    }
}
