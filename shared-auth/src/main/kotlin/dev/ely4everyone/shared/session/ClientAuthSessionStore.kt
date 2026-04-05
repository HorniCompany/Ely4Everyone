package dev.ely4everyone.shared.session

import dev.ely4everyone.shared.auth.AuthProfileProperty
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ClientAuthSession(
    val sessionToken: String,
    val username: String,
    val uuid: String,
    val elyAccessToken: String,
    val createdAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val properties: List<AuthProfileProperty>,
) : Serializable

class ClientAuthSessionStore(
    private val ttlSeconds: Long,
    private val storagePath: Path? = null,
) {
    private val sessions = ConcurrentHashMap<String, ClientAuthSession>()

    init {
        loadFromDisk()
    }

    fun create(
        username: String,
        uuid: String,
        elyAccessToken: String,
        properties: List<AuthProfileProperty>,
        now: Instant = Instant.now(),
    ): ClientAuthSession {
        purgeExpired(now)
        val session = ClientAuthSession(
            sessionToken = UUID.randomUUID().toString(),
            username = username,
            uuid = uuid,
            elyAccessToken = elyAccessToken,
            createdAtEpochSeconds = now.epochSecond,
            expiresAtEpochSeconds = now.plusSeconds(ttlSeconds).epochSecond,
            properties = properties,
        )
        sessions[session.sessionToken] = session
        saveToDisk()
        return session
    }

    fun get(sessionToken: String, now: Instant = Instant.now()): ClientAuthSession? {
        purgeExpired(now)
        return sessions[sessionToken]
    }

    fun latest(now: Instant = Instant.now()): ClientAuthSession? {
        purgeExpired(now)
        return sessions.values.maxByOrNull { it.createdAtEpochSeconds }
    }

    private fun purgeExpired(now: Instant) {
        val epoch = now.epochSecond
        val changed = sessions.entries.removeIf { (_, session) -> session.expiresAtEpochSeconds <= epoch }
        if (changed) {
            saveToDisk()
        }
    }

    private fun loadFromDisk() {
        val path = storagePath ?: return
        if (!Files.exists(path)) {
            return
        }
        runCatching {
            ObjectInputStream(Files.newInputStream(path)).use { input ->
                @Suppress("UNCHECKED_CAST")
                val restored = input.readObject() as? List<ClientAuthSession> ?: emptyList()
                restored.forEach { sessions[it.sessionToken] = it }
            }
        }
    }

    private fun saveToDisk() {
        val path = storagePath ?: return
        Files.createDirectories(path.parent)
        ObjectOutputStream(Files.newOutputStream(path)).use { output ->
            output.writeObject(sessions.values.toList())
        }
    }
}
