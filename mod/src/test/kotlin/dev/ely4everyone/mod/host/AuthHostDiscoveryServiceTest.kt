package dev.ely4everyone.mod.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthHostDiscoveryServiceTest {
    @Test
    fun `builds local scan targets for loopback and subnet addresses`() {
        val targets = AuthHostDiscoveryService.buildCandidateBaseUrls(
            ipv4Addresses = listOf("127.0.0.1", "192.168.1.44"),
            ports = listOf(18085, 19085),
        )

        assertTrue(targets.contains("http://127.0.0.1:18085"))
        assertTrue(targets.contains("http://127.0.0.1:19085"))
        assertTrue(targets.contains("http://192.168.1.1:18085"))
        assertTrue(targets.contains("http://192.168.1.254:19085"))
    }

    @Test
    fun `parses discovery payload into discovered host`() {
        val payload = """
            ver=v2
            host_id=paper-embedded
            display_name=Paper embedded auth host
            base_url=http://192.168.1.10:19085
            issuer=paper-local
            aud=ely-network
        """.trimIndent()

        val host = AuthHostDiscoveryService.parseDiscoveryPayload(payload)

        assertEquals("paper-embedded", host?.id)
        assertEquals("Paper embedded auth host", host?.displayName)
        assertEquals("http://192.168.1.10:19085", host?.baseUrl)
    }
}
