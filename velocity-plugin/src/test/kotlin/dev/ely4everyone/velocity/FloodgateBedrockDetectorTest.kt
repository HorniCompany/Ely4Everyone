package dev.ely4everyone.velocity

import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FloodgateBedrockDetectorTest {
    @Test
    fun `handshake store matches both prefixed and stripped usernames`() {
        val store = FloodgateHandshakeStore(ttlSeconds = 30)
        val session = FloodgateBedrockSession(
            javaUsername = ".Cokek1313",
            correctUsername = ".Cokek1313",
            linked = false,
            bedrockUuid = UUID.fromString("00000000-0000-0000-0009-01f8d4a00cc3"),
            expiresAtEpochSeconds = Instant.now().plusSeconds(30).epochSecond,
        )

        store.put(session)

        assertNotNull(store.get(".Cokek1313"))
        assertNotNull(store.get("Cokek1313"))
    }

    @Test
    fun `detector finds linked session by correct username from handshake store`() {
        val store = FloodgateHandshakeStore(ttlSeconds = 30)
        store.put(
            FloodgateBedrockSession(
                javaUsername = ".BedrockUser",
                correctUsername = "LinkedJavaUser",
                linked = true,
                bedrockUuid = UUID.fromString("00000000-0000-0000-0009-01f8d4a00cc3"),
                expiresAtEpochSeconds = Instant.now().plusSeconds(30).epochSecond,
            ),
        )
        val detector = FloodgateBedrockDetector(
            logger = LoggerFactory.getLogger("FloodgateBedrockDetectorTest"),
            floodgateAccess = EmptyFloodgateAccess,
            handshakeStore = store,
        )

        val player = detector.findPlayer("LinkedJavaUser")

        assertNotNull(player)
        assertTrue(player.linked)
        assertEquals("LinkedJavaUser", player.correctUsername)
    }

    @Test
    fun `handshake store expires entries`() {
        val store = FloodgateHandshakeStore(ttlSeconds = 1)
        store.put(
            FloodgateBedrockSession(
                javaUsername = ".ShortLived",
                correctUsername = ".ShortLived",
                linked = false,
                bedrockUuid = UUID.fromString("00000000-0000-0000-0009-01f8d4a00cc3"),
                expiresAtEpochSeconds = Instant.ofEpochSecond(101).epochSecond,
            ),
            now = Instant.ofEpochSecond(100),
        )

        assertNotNull(store.get("ShortLived", Instant.ofEpochSecond(100)))
        assertNull(store.get("ShortLived", Instant.ofEpochSecond(102)))
    }

    @Test
    fun `detector returns false when no bedrock marker exists`() {
        val detector = FloodgateBedrockDetector(
            logger = LoggerFactory.getLogger("FloodgateBedrockDetectorTest"),
            floodgateAccess = EmptyFloodgateAccess,
            handshakeStore = FloodgateHandshakeStore(),
        )

        assertFalse(detector.findPlayer("RegularJavaPlayer") != null)
    }
}

private object EmptyFloodgateAccess : FloodgateAccess {
    override fun currentPlayers() = emptyList<org.geysermc.floodgate.api.player.FloodgatePlayer>()
    override fun handshakeHandlers() = null
}
