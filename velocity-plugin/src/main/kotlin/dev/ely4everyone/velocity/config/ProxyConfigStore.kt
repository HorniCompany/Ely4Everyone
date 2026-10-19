package dev.ely4everyone.velocity.config

import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import kotlin.io.path.inputStream

object ProxyConfigStore {
    private const val FILE_NAME: String = "ely4everyone.properties"

    fun load(dataDirectory: Path): ProxyConfig {
        val configPath = dataDirectory.resolve(FILE_NAME)
        if (!Files.exists(configPath)) {
            return ProxyConfig()
        }

        val properties = Properties()
        configPath.inputStream().use(properties::load)
        return ProxyConfig.fromProperties(properties)
    }

    fun saveDefaultsIfMissing(dataDirectory: Path) {
        val configPath = dataDirectory.resolve(FILE_NAME)
        if (Files.exists(configPath)) {
            return
        }

        Files.createDirectories(dataDirectory)
        // A fresh install gets its own secret instead of the shared "change-me" placeholder.
        val defaults = ProxyConfig(ticketSigningKey = randomSigningKey())
        Files.newOutputStream(configPath).use { stream ->
            defaults.toProperties().store(stream, "Ely4Everyone Velocity config")
        }
    }

    private fun randomSigningKey(): String {
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
