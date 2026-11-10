package dev.ely4everyone.mod.ui

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/**
 * Text of the mod, from assets/ely4everyone/lang/<language>.json. Minecraft picks the client's
 * language and falls back to en_us for every language without a file, so Russian players get
 * ru_ru and everybody else English. The text is resolved when it is drawn, so switching the
 * language in game takes effect right away.
 */
object Lang {
    fun tr(key: String, vararg args: Any): MutableComponent = Component.translatable("ely4everyone.$key", *args)
}
