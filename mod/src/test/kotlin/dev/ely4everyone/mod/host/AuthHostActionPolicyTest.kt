package dev.ely4everyone.mod.host

import dev.ely4everyone.mod.config.AuthHostTrustState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthHostActionPolicyTest {
    @Test
    fun `discovered pending host cannot start auth until trusted`() {
        val entry = AuthHostEntry(
            id = "lan-pending",
            displayName = "LAN pending",
            baseUrl = "http://192.168.1.20:19085",
            source = AuthHostSource.DISCOVERED,
            trustState = AuthHostTrustState.PENDING,
        )

        val actions = AuthHostActionPolicy.forEntry(entry)

        assertFalse(actions.canStartAuth)
        assertFalse(actions.canSyncSession)
        assertTrue(actions.canTrust)
    }

    @Test
    fun `trusted remembered host can start auth and be forgotten`() {
        val entry = AuthHostEntry(
            id = "lan-trusted",
            displayName = "LAN trusted",
            baseUrl = "http://192.168.1.10:19085",
            source = AuthHostSource.REMEMBERED,
            trustState = AuthHostTrustState.TRUSTED,
        )

        val actions = AuthHostActionPolicy.forEntry(entry)

        assertTrue(actions.canStartAuth)
        assertTrue(actions.canSyncSession)
        assertFalse(actions.canTrust)
        assertTrue(actions.canForget)
    }

    @Test
    fun `preset remote host can start auth without extra trust step`() {
        val entry = AuthHostEntry(
            id = "horni-remote",
            displayName = "horni.cc relay",
            baseUrl = "https://horni.cc/auth/ely4everyone",
            source = AuthHostSource.PRESET,
            trustState = AuthHostTrustState.TRUSTED,
        )

        val actions = AuthHostActionPolicy.forEntry(entry)

        assertTrue(actions.canStartAuth)
        assertTrue(actions.canSyncSession)
        assertFalse(actions.canForget)
    }
}
