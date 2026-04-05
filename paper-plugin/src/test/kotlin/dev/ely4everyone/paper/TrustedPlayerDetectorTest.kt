package dev.ely4everyone.paper

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrustedPlayerDetectorTest {
    @Test
    fun `detects offline uuid as untrusted`() {
        val username = "PlayerOffline"
        val offlineUuid = TrustedPlayerDetector.offlineUuid(username)

        assertFalse(TrustedPlayerDetector.isTrustedForwardedUuid(username, offlineUuid))
    }

    @Test
    fun `detects non-offline uuid as trusted`() {
        val username = "PlayerTrusted"
        val forwardedUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")

        assertTrue(TrustedPlayerDetector.isTrustedForwardedUuid(username, forwardedUuid))
    }
}
