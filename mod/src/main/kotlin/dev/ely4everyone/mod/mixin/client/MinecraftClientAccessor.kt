package dev.ely4everyone.mod.mixin.client

import net.minecraft.client.Minecraft
import net.minecraft.client.User
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Mutable
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(Minecraft::class)
interface MinecraftClientAccessor {
    @Mutable
    @Accessor("user")
    fun `ely4everyone$setUser`(user: User)
}
