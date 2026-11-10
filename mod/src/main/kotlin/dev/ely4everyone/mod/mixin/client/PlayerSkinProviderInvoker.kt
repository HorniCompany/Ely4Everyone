package dev.ely4everyone.mod.mixin.client

import com.mojang.authlib.minecraft.MinecraftProfileTextures
import java.util.UUID
import java.util.concurrent.CompletableFuture
import net.minecraft.client.resources.SkinManager
import net.minecraft.world.entity.player.PlayerSkin
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker

@Mixin(SkinManager::class)
interface PlayerSkinProviderInvoker {
    @Invoker("registerTextures")
    fun `ely4everyone$invokeRegisterTextures`(
        uuid: UUID,
        profileTextures: MinecraftProfileTextures,
    ): CompletableFuture<PlayerSkin>
}
