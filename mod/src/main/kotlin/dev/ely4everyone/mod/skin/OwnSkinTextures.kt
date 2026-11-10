package dev.ely4everyone.mod.skin

import java.util.Base64

/** Compares the skin inside Ely `textures` values, which also carry a timestamp that changes every time. */
object OwnSkinTextures {
    private val SKIN_URL = Regex(""""SKIN"\s*:\s*\{[^}]*"url"\s*:\s*"([^"]+)"""")
    private val MODEL = Regex(""""SKIN"\s*:\s*\{[^}]*"model"\s*:\s*"([^"]+)"""")

    /** Skin URL and model, or null if the value has no skin or is not valid base64 JSON. */
    fun skinOf(texturesValue: String?): Pair<String, String?>? {
        if (texturesValue.isNullOrBlank()) return null
        val decoded = runCatching { String(Base64.getDecoder().decode(texturesValue), Charsets.UTF_8) }.getOrNull() ?: return null
        val url = SKIN_URL.find(decoded)?.groupValues?.get(1) ?: return null
        return url to MODEL.find(decoded)?.groupValues?.get(1)
    }

    /** True when the saved login should take [fresh]: it has no skin yet, or Ely now has a different one. */
    fun needsUpdate(stored: String?, fresh: String?): Boolean {
        val freshSkin = skinOf(fresh) ?: return false
        return skinOf(stored) != freshSkin
    }
}
