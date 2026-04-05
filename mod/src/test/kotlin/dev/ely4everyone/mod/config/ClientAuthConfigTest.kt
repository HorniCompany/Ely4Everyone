package dev.ely4everyone.mod.config

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientAuthConfigTest {
    @Test
    fun `round-trips trusted and selected host config`() {
        val config = ClientAuthConfig(
            selectedHostId = "horni-remote",
            customHostUrl = "https://ely.example.com/auth",
            rememberedHosts = listOf(
                RememberedAuthHost(
                    id = "lan-host-1",
                    displayName = "LAN host",
                    baseUrl = "http://192.168.1.20:19085",
                    trustState = AuthHostTrustState.TRUSTED,
                ),
            ),
        )

        val restored = ClientAuthConfig.fromProperties(config.toProperties())

        assertEquals("horni-remote", restored.selectedHostId)
        assertEquals("https://ely.example.com/auth", restored.customHostUrl)
        assertEquals(1, restored.rememberedHosts.size)
        assertEquals(AuthHostTrustState.TRUSTED, restored.rememberedHosts.single().trustState)
    }

    @Test
    fun `prefers selected trusted host over custom and preset`() {
        val config = ClientAuthConfig(
            selectedHostId = "lan-host-1",
            customHostUrl = "https://ignored.example.com/auth",
            rememberedHosts = listOf(
                RememberedAuthHost(
                    id = "lan-host-1",
                    displayName = "LAN host",
                    baseUrl = "http://192.168.1.20:19085",
                    trustState = AuthHostTrustState.TRUSTED,
                ),
            ),
        )

        val resolved = config.resolveAuthHostBaseUrl()

        assertEquals("http://192.168.1.20:19085", resolved)
    }

    @Test
    fun `ignores untrusted remembered hosts when resolving current auth host`() {
        val config = ClientAuthConfig(
            selectedHostId = "lan-host-1",
            rememberedHosts = listOf(
                RememberedAuthHost(
                    id = "lan-host-1",
                    displayName = "LAN host",
                    baseUrl = "http://192.168.1.20:19085",
                    trustState = AuthHostTrustState.PENDING,
                ),
            ),
        )

        assertEquals(ClientAuthConfig.DEFAULT_REMOTE_HOST_URL, config.resolveAuthHostBaseUrl())
    }

    @Test
    fun `migrates legacy properties into clean break defaults`() {
        val properties = Properties().apply {
            setProperty("relay_base_url", "http://127.0.0.1:18085")
            setProperty("selected_auth_server_id", "custom")
            setProperty("custom_auth_server_url", "http://legacy-host:18085")
        }

        val restored = ClientAuthConfig.fromProperties(properties)

        assertEquals(ClientAuthConfig.DEFAULT_REMOTE_HOST_ID, restored.selectedHostId)
        assertTrue(restored.rememberedHosts.isEmpty())
        assertFalse(restored.customHostUrl.isBlank())
    }
}
