package dev.ely4everyone.mod.host

import dev.ely4everyone.mod.config.AuthHostTrustState
import dev.ely4everyone.mod.config.ClientAuthConfig
import dev.ely4everyone.mod.config.RememberedAuthHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthHostCatalogTest {
    @Test
    fun `builds catalog with preset, remembered and discovered hosts`() {
        val config = ClientAuthConfig(
            selectedHostId = "lan-1",
            rememberedHosts = listOf(
                RememberedAuthHost(
                    id = "lan-1",
                    displayName = "Trusted LAN",
                    baseUrl = "http://192.168.1.10:19085",
                    trustState = AuthHostTrustState.TRUSTED,
                ),
            ),
        )

        val discovered = listOf(
            DiscoveredAuthHost(
                id = "lan-2",
                displayName = "Pending LAN",
                baseUrl = "http://192.168.1.20:19085",
                issuer = "lan",
                audience = "ely-network",
            ),
        )

        val catalog = AuthHostCatalog.build(config, discovered)

        assertTrue(catalog.entries.any { it.id == ClientAuthConfig.DEFAULT_REMOTE_HOST_ID && it.source == AuthHostSource.PRESET })
        assertTrue(catalog.entries.any { it.id == "lan-1" && it.source == AuthHostSource.REMEMBERED && it.trustState == AuthHostTrustState.TRUSTED })
        assertTrue(catalog.entries.any { it.id == "lan-2" && it.source == AuthHostSource.DISCOVERED && it.trustState == AuthHostTrustState.PENDING })
        assertEquals("lan-1", catalog.selected?.id)
    }

    @Test
    fun `trusting discovered host promotes it into remembered hosts`() {
        val config = ClientAuthConfig()
        val discovered = listOf(
            DiscoveredAuthHost(
                id = "lan-9",
                displayName = "LAN 9",
                baseUrl = "http://192.168.1.9:19085",
                issuer = "lan",
                audience = "ely-network",
            ),
        )

        val updated = AuthHostCatalog.trustHost(config, AuthHostCatalog.build(config, discovered).entries.single { it.id == "lan-9" })

        assertEquals("lan-9", updated.selectedHostId)
        assertEquals(1, updated.rememberedHosts.size)
        assertEquals(AuthHostTrustState.TRUSTED, updated.rememberedHosts.single().trustState)
    }
}
