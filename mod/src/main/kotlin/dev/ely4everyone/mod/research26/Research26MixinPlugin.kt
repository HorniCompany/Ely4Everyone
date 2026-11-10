package dev.ely4everyone.mod.research26

import org.objectweb.asm.tree.ClassNode
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin
import org.spongepowered.asm.mixin.extensibility.IMixinInfo

/**
 * Research mixins only get applied when their -D flag is set. Without the flags
 * Mixin never touches their targets, so a Minecraft version that removed those
 * classes (26.3 dropped com.mojang.authlib.yggdrasil) still starts.
 *
 * Runs before the game loads: must not touch Minecraft or mod config classes.
 */
class Research26MixinPlugin : IMixinConfigPlugin {
    override fun onLoad(mixinPackage: String) = Unit

    override fun getRefMapperConfig(): String? = null

    override fun shouldApplyMixin(targetClassName: String, mixinClassName: String): Boolean {
        val redirect = flag(REDIRECT_FLAG)
        return when (mixinClassName.substringAfterLast('.')) {
            "EnvironmentMixin" -> redirect
            "YggdrasilResearchTraceMixin" -> redirect || flag(TRACE_FLAG)
            else -> false
        }
    }

    override fun acceptTargets(myTargets: MutableSet<String>, otherTargets: MutableSet<String>) = Unit

    override fun getMixins(): MutableList<String>? = null

    override fun preApply(targetClassName: String, targetClass: ClassNode, mixinClassName: String, mixinInfo: IMixinInfo) = Unit

    override fun postApply(targetClassName: String, targetClass: ClassNode, mixinClassName: String, mixinInfo: IMixinInfo) = Unit

    companion object {
        const val REDIRECT_FLAG = "ely4everyone.research26.redirectSessionHost"
        const val TRACE_FLAG = "ely4everyone.research26.trace"

        private fun flag(name: String): Boolean =
            System.getProperty(name, "false").equals("true", ignoreCase = true)
    }
}
