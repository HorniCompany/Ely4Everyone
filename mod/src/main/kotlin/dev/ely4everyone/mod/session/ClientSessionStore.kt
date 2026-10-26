package dev.ely4everyone.mod.session

import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * The Ely login of this OS user, shared by every game instance with the mod.
 *
 * Tokens go to [SecretVaults] (Windows Credential Manager or an owner-only file), the rest
 * (nick, UUID, skin) to a file in [SessionDirectory]. Nothing stays in the instance's `config/`.
 */
object ClientSessionStore {
    private val logger = LoggerFactory.getLogger("Ely4Everyone/SessionStore")

    /** Where versions up to 0.2.0 kept the whole login, tokens included, inside the instance. */
    internal const val LEGACY_FILE_NAME: String = "ely4everyone-session.properties"

    private val repository: SessionRepository by lazy {
        val directory = SessionDirectory.resolve()
        SessionRepository(directory, SecretVaults.forCurrentOs(directory)).also {
            logger.info("Ely login is stored per user: {} + {}", directory, it.vaultDescription)
        }
    }

    fun load(): ClientSessionState = repository.load()

    fun save(state: ClientSessionState) = repository.save(state)

    /** Logs out of Ely in every instance of this user. */
    fun clear() = repository.clear()

    /**
     * Runs [block] while no other game of this user can change the login, or returns null right away
     * if another one is busy (for example renewing the tokens right now).
     */
    fun <T> tryWithLock(block: () -> T): T? = repository.tryWithLock(block)

    /**
     * Removes the login file older versions kept in this instance's `config/`. Its tokens are not
     * moved over: the file may have arrived inside someone else's modpack, so the player logs in once more.
     */
    fun removeLegacyInstanceFile() {
        val legacyFile = runCatching { FabricLoader.getInstance().configDir.resolve(LEGACY_FILE_NAME) }.getOrNull() ?: return
        removeLegacyFile(legacyFile)
    }

    internal fun removeLegacyFile(legacyFile: Path): Boolean {
        val removed = runCatching { java.nio.file.Files.deleteIfExists(legacyFile) }
            .onFailure { logger.warn("Could not remove the old login file {}: {}", legacyFile, it.toString()) }
            .getOrDefault(false)
        if (removed) {
            logger.info("Removed the old per-instance login file {}; log in to Ely.by once more, it now works in every instance.", legacyFile)
        }
        return removed
    }
}
