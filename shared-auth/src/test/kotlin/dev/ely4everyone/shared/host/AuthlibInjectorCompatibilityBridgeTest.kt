package dev.ely4everyone.shared.host

import kotlin.test.Test
import kotlin.test.assertEquals

class AuthlibInjectorCompatibilityBridgeTest {
    @Test
    fun `maps join endpoint to Ely session join`() {
        assertEquals(
            "https://authserver.ely.by/session/join",
            AuthlibInjectorCompatibilityBridge.joinTarget().toString(),
        )
    }

    @Test
    fun `maps hasJoined query to Ely session endpoint`() {
        assertEquals(
            "https://authserver.ely.by/session/hasJoined?username=Cokeef&serverId=test",
            AuthlibInjectorCompatibilityBridge.hasJoinedTarget("username=Cokeef&serverId=test").toString(),
        )
    }

    @Test
    fun `maps session profile path to Ely profile endpoint`() {
        assertEquals(
            "https://authserver.ely.by/session/profile/ed9f5370409444dea69ced1d490e0447?unsigned=true",
            AuthlibInjectorCompatibilityBridge.profileTarget(
                uuidPath = "ed9f5370409444dea69ced1d490e0447",
                rawQuery = "unsigned=true",
            ).toString(),
        )
    }

    @Test
    fun `maps name batch resolution to Ely batch endpoint`() {
        assertEquals(
            "https://authserver.ely.by/api/profiles/minecraft",
            AuthlibInjectorCompatibilityBridge.batchProfilesTarget().toString(),
        )
    }
}
