package dev.ely4everyone.mod.skin

import com.mojang.authlib.SignatureState
import dev.ely4everyone.mod.auth.ElyDirectLogin
import dev.ely4everyone.mod.identity.ElyIdentityManager
import dev.ely4everyone.mod.session.ClientSessionState
import dev.ely4everyone.shared.host.JsonFieldReader
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole way of our own skin: Ely.by profile JSON -> saved login -> identity -> textures,
 * plus which source the skin mixin picks. 2026-11-10 the skin silently fell back to the default
 * one because Ely sends profile properties in a different field order than Mojang.
 */
class SkinPipelineTest {
    private val uuid = "ed9f5370-4094-44de-a69c-ed1d490e0447"
    private val skinUrl = "http://textures.minecraft.net/texture/acf917ed21579ef898a3d5b52f6daa2f3c7329de1fac626ec5fe817804f4b079"

    private fun texturesValue(url: String = skinUrl, timestamp: Long = 1762750000000, slim: Boolean = false): String {
        val metadata = if (slim) ""","metadata":{"model":"slim"}""" else ""
        val json = """{"timestamp":$timestamp,"profileId":"${uuid.replace("-", "")}","profileName":"Cokeef","textures":{"SKIN":{"url":"$url"$metadata}}}"""
        return Base64.getEncoder().encodeToString(json.toByteArray())
    }

    /** Field order exactly as authserver.ely.by/session/profile sends it: name, signature, value. */
    private fun elyProfileJson(value: String = texturesValue()) =
        """{"id":"${uuid.replace("-", "")}","name":"Cokeef","properties":[{"name":"textures","signature":"c2lnbmF0dXJl","value":"$value"},{"name":"ely","signature":"c2ln","value":"but why are you asking?"}]}"""

    @Test
    fun `Ely profile properties are read whatever the field order`() {
        val properties = JsonFieldReader.readProperties(elyProfileJson())
        assertEquals(listOf("textures", "ely"), properties.map { it.name })
        assertEquals(texturesValue(), properties[0].value)
        assertEquals("c2lnbmF0dXJl", properties[0].signature)
    }

    @Test
    fun `Mojang order and unsigned properties still work`() {
        val mojang = """{"id":"x","name":"Steve","properties":[{"name":"textures","value":"dmFsdWU=","signature":"c2ln"}]}"""
        val unsigned = """{"id":"x","name":"Steve","properties":[{"name":"textures","value":"dmFsdWU="}]}"""
        assertEquals("c2ln", JsonFieldReader.readProperties(mojang).single().signature)
        val property = JsonFieldReader.readProperties(unsigned).single()
        assertEquals("dmFsdWU=", property.value)
        assertNull(property.signature)
        assertTrue(JsonFieldReader.readProperties("""{"properties":[]}""").isEmpty())
    }

    @Test
    fun `login takes the textures out of the Ely profile`() {
        val textures = assertNotNull(ElyDirectLogin.texturesFrom(elyProfileJson()))
        assertEquals(texturesValue(), textures.value)
        assertEquals("c2lnbmF0dXJl", textures.signature)
    }

    @Test
    fun `saved login with a skin gives the identity signed textures and a textures property`() {
        val session = ClientSessionState(
            relayBaseUrl = "",
            elyAccessToken = "token",
            authSessionExpiresAt = Instant.now().plusSeconds(3600),
            elyUsername = "Cokeef",
            elyUuid = uuid,
            elyTexturesValue = texturesValue(slim = true),
            elyTexturesSignature = "c2lnbmF0dXJl",
        )
        val identity = assertNotNull(ElyIdentityManager.fromClientSession(session))

        val textures = assertNotNull(ElyIdentityManager.toMinecraftProfileTextures(identity))
        assertEquals(skinUrl, textures.skin()?.url)
        assertEquals("slim", textures.skin()?.getMetadata("model"))
        assertEquals(SignatureState.SIGNED, textures.signatureState())

        val profile = assertNotNull(ElyIdentityManager.toGameProfile(identity))
        assertEquals(UUID.fromString(uuid), profile.id)
        assertEquals(texturesValue(slim = true), profile.properties["textures"].single().value)
    }

    @Test
    fun `own player always gets an Ely skin, never the default one`() {
        val id = UUID.fromString(uuid)
        val withSkin = ActiveSkinIdentity(id, "Cokeef", hasTextures = true)
        val withoutSkin = ActiveSkinIdentity(id, "Cokeef", hasTextures = false)

        assertEquals(SkinSource.OWN_TEXTURES, SkinSourcePolicy.choose(id, "Cokeef", withSkin))
        // The bug of 2026-11-10: the login had no textures and the default skin was drawn.
        assertEquals(SkinSource.OWN_ELY_LOOKUP, SkinSourcePolicy.choose(id, "Cokeef", withoutSkin))
    }

    @Test
    fun `other players are looked up on Ely by nick, including a pirate with our nick`() {
        val me = ActiveSkinIdentity(UUID.fromString(uuid), "Cokeef", hasTextures = true)
        val pirateUuid = UUID.nameUUIDFromBytes("OfflinePlayer:Cokeef".toByteArray())

        assertEquals(SkinSource.OTHER_ELY_LOOKUP, SkinSourcePolicy.choose(UUID.randomUUID(), "SomePirate", me))
        assertEquals(SkinSource.OTHER_ELY_LOOKUP, SkinSourcePolicy.choose(pirateUuid, "Cokeef", me))
        assertEquals(SkinSource.OTHER_ELY_LOOKUP, SkinSourcePolicy.choose(UUID.randomUUID(), "SomePirate", null))
        assertEquals(SkinSource.VANILLA, SkinSourcePolicy.choose(UUID.randomUUID(), "", me))
        assertEquals(SkinSource.VANILLA, SkinSourcePolicy.choose(UUID.randomUUID(), null, null))
    }

    @Test
    fun `saved skin is updated only when it is missing or really changed`() {
        assertTrue(OwnSkinTextures.needsUpdate(stored = null, fresh = texturesValue()))
        assertTrue(OwnSkinTextures.needsUpdate(stored = "", fresh = texturesValue()))
        // Ely puts a new timestamp into every answer: same skin, nothing to save.
        assertEquals(false, OwnSkinTextures.needsUpdate(texturesValue(timestamp = 1), texturesValue(timestamp = 2)))
        assertTrue(OwnSkinTextures.needsUpdate(texturesValue(), texturesValue(url = "http://textures.minecraft.net/texture/other")))
        assertTrue(OwnSkinTextures.needsUpdate(texturesValue(), texturesValue(slim = true)))
        // A broken answer never wipes a good saved skin.
        assertEquals(false, OwnSkinTextures.needsUpdate(texturesValue(), "not base64 !!"))
        assertEquals(false, OwnSkinTextures.needsUpdate(texturesValue(), null))
    }
}
