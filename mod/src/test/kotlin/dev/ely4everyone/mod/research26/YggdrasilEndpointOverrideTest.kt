package dev.ely4everyone.mod.research26

import kotlin.test.Test
import kotlin.test.assertEquals

class YggdrasilEndpointOverrideTest {
    @Test
    fun `builds authlib injector style roots from auth host base url`() {
        val roots = YggdrasilEndpointOverride.fromAuthHostBaseUrl("https://ely.example.com/root")

        assertEquals("https://ely.example.com/root/authserver", roots.authHost)
        assertEquals("https://ely.example.com/root/api", roots.apiHost)
        assertEquals("https://ely.example.com/root/sessionserver", roots.sessionHost)
        assertEquals("https://ely.example.com/root/minecraftservices", roots.servicesHost)
    }

    @Test
    fun `builds minecraft session urls from session host`() {
        val roots = YggdrasilEndpointOverride.fromAuthHostBaseUrl("http://127.0.0.1:19085")

        assertEquals(
            "http://127.0.0.1:19085/sessionserver/session/minecraft/",
            YggdrasilEndpointOverride.sessionBaseUrl(roots),
        )
    }
}
