package dev.ely4everyone.mod.session

import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Talks to the real Credential Manager under a throwaway name, so it only runs on Windows. */
@EnabledOnOs(OS.WINDOWS)
class WindowsCredentialVaultTest {
    @Test
    fun `secret round-trips through Credential Manager and can be deleted`() {
        val vault = WindowsCredentialVault("Ely4Everyone/test-${System.nanoTime()}")
        try {
            assertNull(vault.read())

            val secret = "ely_access_token=" + "a".repeat(400) + "\nely_refresh_token=" + "б".repeat(400) + "\n"
            vault.write(secret)
            assertEquals(secret, vault.read())

            vault.write("replaced")
            assertEquals("replaced", vault.read())
        } finally {
            vault.delete()
        }
        assertNull(vault.read())
    }
}
