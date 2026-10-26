package dev.ely4everyone.mod.session

import java.nio.file.Path
import java.nio.file.Paths

/**
 * The per-user folder that holds the Ely login, shared by every game instance of this user.
 *
 * It lives outside the instance on purpose: launchers export `config/` into modpacks, and on
 * 2026-10-26 a shared `.mrpack` carried someone's Ely tokens to another player that way.
 */
object SessionDirectory {
    /** Overrides the folder, for tests and for players who want the login somewhere else. */
    const val OVERRIDE_PROPERTY: String = "ely4everyone.sessionDir"

    private const val FOLDER_NAME: String = "ely4everyone"

    fun resolve(): Path {
        System.getProperty(OVERRIDE_PROPERTY)?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
        return resolve(
            osName = System.getProperty("os.name").orEmpty(),
            env = System::getenv,
            userHome = System.getProperty("user.home").orEmpty(),
        )
    }

    internal fun resolve(osName: String, env: (String) -> String?, userHome: String): Path {
        val os = osName.lowercase()
        return when {
            os.startsWith("windows") ->
                Paths.get(env("APPDATA")?.takeIf { it.isNotBlank() } ?: Paths.get(userHome, "AppData", "Roaming").toString(), FOLDER_NAME)
            os.startsWith("mac") ->
                Paths.get(userHome, "Library", "Application Support", FOLDER_NAME)
            else ->
                Paths.get(env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: Paths.get(userHome, ".config").toString(), FOLDER_NAME)
        }
    }
}
