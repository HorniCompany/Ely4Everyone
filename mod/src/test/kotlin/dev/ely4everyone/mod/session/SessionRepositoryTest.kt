package dev.ely4everyone.mod.session

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionRepositoryTest {
    private val directory: Path = Files.createTempDirectory("ely4everyone-session-test")
    private val vault = FileSecretVault(directory.resolve("secrets.properties"))

    @AfterTest
    fun cleanup() {
        directory.toFile().deleteRecursively()
    }

    private val signedIn = ClientSessionState(
        relayBaseUrl = "",
        elyAccessToken = "access-token-value",
        elyRefreshToken = "refresh-token-value",
        authSessionExpiresAt = Instant.ofEpochSecond(2_000_000_000),
        elyUsername = "Cokeef",
        elyUuid = "ed9f5370-4094-44de-a69c-ed1d490e0447",
        elyTexturesValue = "textures",
        elyTexturesSignature = "signature",
    )

    @Test
    fun `nothing stored means signed out`() {
        val state = SessionRepository(directory, vault).load()

        assertFalse(state.isSignedIn())
        assertEquals(SessionRepository.DEFAULT_RELAY_BASE_URL, state.relayBaseUrl)
    }

    @Test
    fun `a saved login comes back in full, also in another game`() {
        SessionRepository(directory, vault).save(signedIn)

        // A second repository on the same folder is what a second game instance sees.
        assertEquals(signedIn, SessionRepository(directory, vault).load())
    }

    @Test
    fun `tokens stay in the vault, never in the profile file`() {
        SessionRepository(directory, vault).save(signedIn)

        val profile = directory.resolve(SessionRepository.PROFILE_FILE_NAME).readText()
        assertFalse("access-token-value" in profile)
        assertFalse("refresh-token-value" in profile)
        assertTrue("Cokeef" in profile)
        assertTrue("refresh-token-value" in vault.read().orEmpty())
    }

    @Test
    fun `logging out in one game logs out everywhere`() {
        val first = SessionRepository(directory, vault)
        val second = SessionRepository(directory, vault)
        first.save(signedIn)
        assertTrue(second.load().isSignedIn(Instant.ofEpochSecond(1_900_000_000)))

        first.clear()

        assertFalse(second.load().isSignedIn())
        assertNull(vault.read())
    }

    @Test
    fun `a renewal by another game is picked up`() {
        val first = SessionRepository(directory, vault)
        val second = SessionRepository(directory, vault)
        first.save(signedIn)
        second.load()

        first.save(signedIn.copy(elyAccessToken = "renewed", authSessionExpiresAt = Instant.ofEpochSecond(2_100_000_000)))

        assertEquals("renewed", second.load().elyAccessToken)
    }

    @Test
    fun `only one game holds the lock at a time`() {
        val first = SessionRepository(directory, vault)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread {
            first.tryWithLock {
                held.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(held.await(5, TimeUnit.SECONDS))

        // Same process, other thread: refused right away instead of waiting.
        assertNull(first.tryWithLock { "got it" })

        release.countDown()
        holder.join()
        assertEquals("got it", first.tryWithLock { "got it" })
    }

    @Test
    fun `a refresh can save while it holds the lock`() {
        val repository = SessionRepository(directory, vault)
        repository.save(signedIn)

        val result = repository.tryWithLock {
            repository.save(signedIn.copy(elyAccessToken = "renewed"))
            repository.load().elyAccessToken
        }

        assertEquals("renewed", result)
        // And the lock is free again afterwards.
        assertEquals("free", repository.tryWithLock { "free" })
    }

    @Test
    fun `the old per-instance file is removed and its tokens are not used`() {
        val legacy = directory.resolve("config").resolve(ClientSessionStore.LEGACY_FILE_NAME)
        Files.createDirectories(legacy.parent)
        Files.writeString(legacy, "ely_access_token=someone-else\nely_username=aelthi\n")

        assertTrue(ClientSessionStore.removeLegacyFile(legacy))

        assertFalse(Files.exists(legacy))
        assertFalse(SessionRepository(directory, vault).load().isSignedIn())
    }

    @Test
    fun `the folder is per user and outside the game`() {
        val env = mapOf("APPDATA" to "C:\\Users\\nik\\AppData\\Roaming", "XDG_CONFIG_HOME" to "/home/nik/.cfg")

        assertEquals(
            Paths.get("C:\\Users\\nik\\AppData\\Roaming", "ely4everyone"),
            SessionDirectory.resolve("Windows 11", env::get, "C:\\Users\\nik"),
        )
        assertEquals(
            Paths.get("/Users/nik", "Library", "Application Support", "ely4everyone"),
            SessionDirectory.resolve("Mac OS X", env::get, "/Users/nik"),
        )
        assertEquals(Paths.get("/home/nik/.cfg", "ely4everyone"), SessionDirectory.resolve("Linux", env::get, "/home/nik"))
        assertEquals(Paths.get("/home/nik", ".config", "ely4everyone"), SessionDirectory.resolve("Linux", { null }, "/home/nik"))
    }
}
