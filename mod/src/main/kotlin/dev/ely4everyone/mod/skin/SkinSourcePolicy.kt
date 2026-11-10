package dev.ely4everyone.mod.skin

import java.util.UUID

/** Where the skin of a player profile comes from. */
enum class SkinSource {
    /** Our own Ely player, with the textures saved at login. */
    OWN_TEXTURES,

    /** Our own Ely player whose login has no textures: look the skin up on Ely.by by nick. */
    OWN_ELY_LOOKUP,

    /** Anyone else: Ely.by skin by nick if there is one, otherwise whatever vanilla found. */
    OTHER_ELY_LOOKUP,

    /** No nick to look up: leave it to vanilla. */
    VANILLA,
}

/** The logged-in Ely player, as far as skins care. */
data class ActiveSkinIdentity(
    val id: UUID,
    val name: String,
    val hasTextures: Boolean,
)

object SkinSourcePolicy {
    fun choose(profileId: UUID?, profileName: String?, active: ActiveSkinIdentity?): SkinSource {
        if (active != null && active.id == profileId && active.name == profileName) {
            // Never the default skin for ourselves just because the login lost its textures.
            return if (active.hasTextures) SkinSource.OWN_TEXTURES else SkinSource.OWN_ELY_LOOKUP
        }
        return if (profileName.isNullOrBlank()) SkinSource.VANILLA else SkinSource.OTHER_ELY_LOOKUP
    }
}
