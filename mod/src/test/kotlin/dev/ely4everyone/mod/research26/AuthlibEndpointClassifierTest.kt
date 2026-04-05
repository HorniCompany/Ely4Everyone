package dev.ely4everyone.mod.research26

import kotlin.test.Test
import kotlin.test.assertEquals

class AuthlibEndpointClassifierTest {
    @Test
    fun `classifies mojang sessionserver endpoint`() {
        val classified = AuthlibEndpointClassifier.classify("https://sessionserver.mojang.com/session/minecraft/join")
        assertEquals(AuthlibEndpointKind.SESSIONSERVER, classified.kind)
        assertEquals(true, classified.isMojang)
    }

    @Test
    fun `classifies minecraft services endpoint`() {
        val classified = AuthlibEndpointClassifier.classify("https://api.minecraftservices.com/player/certificates")
        assertEquals(AuthlibEndpointKind.MINECRAFTSERVICES, classified.kind)
        assertEquals(true, classified.isMinecraftServices)
    }

    @Test
    fun `classifies custom ely endpoint as non mojang`() {
        val classified = AuthlibEndpointClassifier.classify("https://auth.example.com/sessionserver/session/minecraft/profile/uuid")
        assertEquals(AuthlibEndpointKind.SESSIONSERVER, classified.kind)
        assertEquals(false, classified.isMojang)
    }
}
