package dev.ely4everyone.mod.session

import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Properties
import kotlin.io.path.inputStream

object ClientSessionStore {
    private const val FILE_NAME: String = "ely4everyone-session.properties"

    fun load(): ClientSessionState {
        val sessionPath = resolveSessionPath()
        if (!Files.exists(sessionPath)) {
            return ClientSessionState(relayBaseUrl = "http://127.0.0.1:18085")
        }

        val properties = Properties()
        sessionPath.inputStream().use(properties::load)
        return ClientSessionState(
            relayBaseUrl = properties.getProperty("relay_base_url", "http://127.0.0.1:18085"),
            authSessionToken = properties.getProperty("auth_session_token")?.ifBlank { null },
            elyAccessToken = properties.getProperty("ely_access_token")?.ifBlank { null },
            authSessionExpiresAt = properties.getProperty("auth_session_expires_at")
                ?.takeIf { it.isNotBlank() }
                ?.let { Instant.ofEpochSecond(it.toLong()) },
            elyUsername = properties.getProperty("ely_username")?.ifBlank { null },
            elyUuid = properties.getProperty("ely_uuid")?.ifBlank { null },
            elyTexturesValue = properties.getProperty("ely_textures_value")?.ifBlank { null },
            elyTexturesSignature = properties.getProperty("ely_textures_signature")?.ifBlank { null },
            elyRefreshToken = properties.getProperty("ely_refresh_token")?.ifBlank { null },
        )
    }

    fun save(state: ClientSessionState) {
        val sessionPath = resolveSessionPath()
        Files.createDirectories(sessionPath.parent)
        Files.newOutputStream(sessionPath).use { stream ->
            Properties().apply {
                setProperty("relay_base_url", state.relayBaseUrl)
                setProperty("auth_session_token", state.authSessionToken.orEmpty())
                setProperty("ely_access_token", state.elyAccessToken.orEmpty())
                setProperty("auth_session_expires_at", state.authSessionExpiresAt?.epochSecond?.toString().orEmpty())
                setProperty("ely_username", state.elyUsername.orEmpty())
                setProperty("ely_uuid", state.elyUuid.orEmpty())
                setProperty("ely_textures_value", state.elyTexturesValue.orEmpty())
                setProperty("ely_textures_signature", state.elyTexturesSignature.orEmpty())
                setProperty("ely_refresh_token", state.elyRefreshToken.orEmpty())
            }.store(stream, "Ely4Everyone client session")
        }
    }

    fun clear() {
        save(ClientSessionState(relayBaseUrl = "http://127.0.0.1:18085"))
    }

    fun saveDefaultsIfMissing() {
        val sessionPath = resolveSessionPath()
        if (Files.exists(sessionPath)) {
            return
        }

        Files.createDirectories(sessionPath.parent)
        Files.newOutputStream(sessionPath).use { stream ->
            Properties().apply {
                setProperty("relay_base_url", "http://127.0.0.1:18085")
                setProperty("auth_session_token", "")
                setProperty("ely_access_token", "")
                setProperty("auth_session_expires_at", "")
                setProperty("ely_username", "")
                setProperty("ely_uuid", "")
                setProperty("ely_textures_value", "")
                setProperty("ely_textures_signature", "")
                setProperty("ely_refresh_token", "")
            }.store(stream, "Ely4Everyone client session")
        }
    }

    private fun resolveSessionPath(): Path {
        return FabricLoader.getInstance().configDir.resolve(FILE_NAME)
    }
}
