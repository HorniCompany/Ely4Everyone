package dev.ely4everyone.velocity

import org.geysermc.floodgate.api.FloodgateApi
import org.geysermc.floodgate.api.InstanceHolder
import org.geysermc.floodgate.api.handshake.HandshakeHandlers
import org.geysermc.floodgate.api.player.FloodgatePlayer
import org.slf4j.Logger
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class FloodgateBedrockSession(
    val javaUsername: String,
    val correctUsername: String,
    val linked: Boolean,
    val bedrockUuid: UUID,
    val expiresAtEpochSeconds: Long,
)

interface FloodgateAccess {
    fun currentPlayers(): Collection<FloodgatePlayer>
    fun handshakeHandlers(): HandshakeHandlers?
}

object ApiFloodgateAccess : FloodgateAccess {
    override fun currentPlayers(): Collection<FloodgatePlayer> {
        return runCatching { FloodgateApi.getInstance().players }
            .getOrElse { emptyList() }
    }

    override fun handshakeHandlers(): HandshakeHandlers? {
        return runCatching { InstanceHolder.getHandshakeHandlers() }.getOrNull()
    }
}

class FloodgateHandshakeStore(
    private val ttlSeconds: Long = 30,
) {
    private val sessionsByUsername = ConcurrentHashMap<String, FloodgateBedrockSession>()

    fun put(session: FloodgateBedrockSession, now: Instant = Instant.now()) {
        purgeExpired(now)
        candidateKeys(session.javaUsername, session.correctUsername).forEach { key ->
            sessionsByUsername[key] = session
        }
    }

    fun get(username: String, now: Instant = Instant.now()): FloodgateBedrockSession? {
        purgeExpired(now)
        return sessionsByUsername[normalize(username)]
    }

    fun clear(username: String) {
        candidateKeys(username, username).forEach(sessionsByUsername::remove)
    }

    private fun purgeExpired(now: Instant) {
        val epoch = now.epochSecond
        sessionsByUsername.entries.removeIf { it.value.expiresAtEpochSeconds <= epoch }
    }

    private fun candidateKeys(primary: String, secondary: String): Set<String> {
        return buildSet {
            add(normalize(primary))
            add(normalize(secondary))
            strippedPrefix(primary)?.let { add(normalize(it)) }
            strippedPrefix(secondary)?.let { add(normalize(it)) }
        }
    }

    private fun strippedPrefix(value: String): String? {
        if (value.isEmpty()) return null
        return if (!value[0].isLetterOrDigit() && value.length > 1) value.substring(1) else null
    }

    private fun normalize(value: String): String = value.lowercase()
}

class FloodgateBedrockDetector(
    private val logger: Logger,
    private val floodgateAccess: FloodgateAccess = ApiFloodgateAccess,
    private val handshakeStore: FloodgateHandshakeStore = FloodgateHandshakeStore(),
) {
    private var handlerId: Int = -1

    fun register() {
        val handlers = floodgateAccess.handshakeHandlers() ?: return
        if (handlerId != -1) return

        handlerId = handlers.addHandshakeHandler { data ->
            if (!data.isFloodgatePlayer) {
                return@addHandshakeHandler
            }
            val session = FloodgateBedrockSession(
                javaUsername = data.javaUsername,
                correctUsername = data.correctUsername,
                linked = data.linkedPlayer != null,
                bedrockUuid = data.correctUniqueId,
                expiresAtEpochSeconds = Instant.now().plusSeconds(30).epochSecond,
            )
            handshakeStore.put(session)
            logger.info(
                "Ely4Everyone: registered Floodgate handshake marker (javaUsername={}, correctUsername={}, linked={}, bedrockUuid={})",
                session.javaUsername,
                session.correctUsername,
                session.linked,
                session.bedrockUuid,
            )
        }
    }

    fun unregister() {
        if (handlerId == -1) return
        floodgateAccess.handshakeHandlers()?.removeHandshakeHandler(handlerId)
        handlerId = -1
    }

    fun findPlayer(username: String): FloodgateBedrockSession? {
        val fromHandshake = handshakeStore.get(username)
        if (fromHandshake != null) {
            return fromHandshake
        }

        val onlinePlayer = floodgateAccess.currentPlayers().firstOrNull { player ->
            player.javaUsername.equals(username, ignoreCase = true) ||
                player.correctUsername.equals(username, ignoreCase = true) ||
                player.username.equals(username, ignoreCase = true)
        } ?: return null

        val session = FloodgateBedrockSession(
            javaUsername = onlinePlayer.javaUsername,
            correctUsername = onlinePlayer.correctUsername,
            linked = onlinePlayer.isLinked,
            bedrockUuid = onlinePlayer.correctUniqueId,
            expiresAtEpochSeconds = Instant.now().plusSeconds(30).epochSecond,
        )
        handshakeStore.put(session)
        return session
    }
}
