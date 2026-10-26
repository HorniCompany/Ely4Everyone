package dev.ely4everyone.mod.session

import java.io.StringReader
import java.io.StringWriter
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.Properties
import java.util.concurrent.locks.ReentrantLock

/**
 * Stores [ClientSessionState] split in two: tokens in [vault], everything else in `session.properties`
 * inside [directory]. Several games of the same user share it; a file lock keeps their writes apart.
 */
class SessionRepository(
    private val directory: Path,
    private val vault: SecretVault,
) {
    private val profileFile: Path = directory.resolve(PROFILE_FILE_NAME)
    private val lockFile: Path = directory.resolve(LOCK_FILE_NAME)

    // FileLock is per process; this keeps threads of the same game from tripping over it.
    private val threadLock = ReentrantLock()

    @Volatile
    private var cache: Cached? = null

    val vaultDescription: String
        get() = vault.description

    /** Cheap to call every frame: the vault is read again only after the profile file changed. */
    fun load(): ClientSessionState {
        val stamp = stampOf(profileFile) ?: return emptyState().also { cache = null }
        cache?.takeIf { it.stamp == stamp }?.let { return it.state }

        val profile = readProperties(profileFile) ?: return emptyState()
        val secrets = vault.read()?.let(::parseProperties) ?: Properties()
        val state = ClientSessionState(
            relayBaseUrl = profile.getProperty("relay_base_url", DEFAULT_RELAY_BASE_URL),
            authSessionToken = secrets.getProperty("auth_session_token")?.ifBlank { null },
            elyAccessToken = secrets.getProperty("ely_access_token")?.ifBlank { null },
            authSessionExpiresAt = profile.getProperty("auth_session_expires_at")
                ?.takeIf { it.isNotBlank() }
                ?.toLongOrNull()
                ?.let(Instant::ofEpochSecond),
            elyUsername = profile.getProperty("ely_username")?.ifBlank { null },
            elyUuid = profile.getProperty("ely_uuid")?.ifBlank { null },
            elyTexturesValue = profile.getProperty("ely_textures_value")?.ifBlank { null },
            elyTexturesSignature = profile.getProperty("ely_textures_signature")?.ifBlank { null },
            elyRefreshToken = secrets.getProperty("ely_refresh_token")?.ifBlank { null },
        )
        cache = Cached(stamp, state)
        return state
    }

    fun save(state: ClientSessionState) = withLock {
        val secrets = Properties().apply {
            setProperty("auth_session_token", state.authSessionToken.orEmpty())
            setProperty("ely_access_token", state.elyAccessToken.orEmpty())
            setProperty("ely_refresh_token", state.elyRefreshToken.orEmpty())
        }
        // Tokens first: a game that sees the new profile file must also find the new tokens.
        vault.write(toText(secrets))
        val profile = Properties().apply {
            setProperty("relay_base_url", state.relayBaseUrl)
            setProperty("auth_session_expires_at", state.authSessionExpiresAt?.epochSecond?.toString().orEmpty())
            setProperty("ely_username", state.elyUsername.orEmpty())
            setProperty("ely_uuid", state.elyUuid.orEmpty())
            setProperty("ely_textures_value", state.elyTexturesValue.orEmpty())
            setProperty("ely_textures_signature", state.elyTexturesSignature.orEmpty())
        }
        writeAtomically(profileFile, toText(profile))
        cache = null
    }

    fun clear() = withLock {
        vault.delete()
        Files.deleteIfExists(profileFile)
        cache = null
    }

    fun <T> tryWithLock(block: () -> T): T? {
        // Already inside a locked block (a refresh saving its result): the file lock is ours.
        if (threadLock.isHeldByCurrentThread) return block()
        if (!threadLock.tryLock()) return null
        try {
            Files.createDirectories(directory)
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                val lock: FileLock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                } ?: return null
                lock.use { return block() }
            }
        } finally {
            threadLock.unlock()
        }
    }

    private fun <T> withLock(block: () -> T): T {
        if (threadLock.isHeldByCurrentThread) return block()
        threadLock.lock()
        try {
            Files.createDirectories(directory)
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { return block() }
            }
        } finally {
            threadLock.unlock()
        }
    }

    private fun emptyState() = ClientSessionState(relayBaseUrl = DEFAULT_RELAY_BASE_URL)

    private data class Stamp(val modified: FileTime, val size: Long)

    private data class Cached(val stamp: Stamp, val state: ClientSessionState)

    private fun stampOf(file: Path): Stamp? = try {
        Stamp(Files.getLastModifiedTime(file), Files.size(file))
    } catch (_: NoSuchFileException) {
        null
    }

    private fun readProperties(file: Path): Properties? = try {
        parseProperties(Files.readString(file, StandardCharsets.UTF_8))
    } catch (_: NoSuchFileException) {
        null
    }

    private fun parseProperties(text: String) = Properties().apply { load(StringReader(text)) }

    private fun toText(properties: Properties): String =
        StringWriter().also { properties.store(it, "Ely4Everyone login, shared by all game instances of this user") }.toString()

    private fun writeAtomically(file: Path, text: String) {
        Files.createDirectories(file.parent)
        val temp = Files.createTempFile(file.parent, "session", ".tmp")
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        const val PROFILE_FILE_NAME: String = "session.properties"
        const val LOCK_FILE_NAME: String = "session.lock"
        const val DEFAULT_RELAY_BASE_URL: String = "http://127.0.0.1:18085"
    }
}
