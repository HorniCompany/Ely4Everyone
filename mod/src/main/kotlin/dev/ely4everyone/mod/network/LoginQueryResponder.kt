package dev.ely4everyone.mod.network

import dev.ely4everyone.mod.Ely4EveryoneClientMod
import dev.ely4everyone.mod.auth.AuthHostClient
import dev.ely4everyone.mod.auth.AuthPollResult
import dev.ely4everyone.mod.session.ClientSessionState
import dev.ely4everyone.mod.session.ClientSessionStore
import dev.ely4everyone.shared.protocol.AuthProtocolCodec
import dev.ely4everyone.shared.protocol.LoginResponse
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientLoginNetworkHandler
import net.minecraft.network.PacketByteBuf
import io.netty.channel.ChannelFutureListener
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

object LoginQueryResponder {
    private val logger = LoggerFactory.getLogger("${Ely4EveryoneClientMod.MOD_ID}/login")

    fun register() {
        ClientLoginNetworking.registerGlobalReceiver(LoginChannel.ID, ::handleLoginQuery)
    }

    private fun handleLoginQuery(
        client: MinecraftClient,
        handler: ClientLoginNetworkHandler,
        buf: PacketByteBuf,
        callbacksConsumer: Consumer<ChannelFutureListener>,
    ): CompletableFuture<PacketByteBuf?> {
        val payload = ByteArray(buf.readableBytes())
        buf.readBytes(payload)
        val challenge = AuthProtocolCodec.decodeChallenge(payload)
        val sessionState = ClientSessionStore.load()

        logger.info(
            "Received Ely4Everyone login challenge. version={}, nonce={}, audience={}, hasSession={}",
            challenge.version,
            challenge.nonce,
            challenge.audience,
            sessionState.hasUsableAuthSession(),
        )

        if (challenge.version != "v2") {
            logger.warn("Unsupported Ely4Everyone login challenge version: {}", challenge.version)
            return CompletableFuture.completedFuture(null)
        }

        val nonce = challenge.nonce
        return CompletableFuture.supplyAsync {
            if (nonce.isNullOrBlank()) {
                logger.info("Challenge nonce is missing. Returning empty response.")
                return@supplyAsync null
            }

            val resolvedSession = ensureUsableSession(sessionState)
            val authSessionToken = resolvedSession?.authSessionToken

            if (resolvedSession == null || authSessionToken.isNullOrBlank()) {
                logger.info("No usable Ely auth session for login challenge. Returning empty response.")
                return@supplyAsync null
            }

            val issuedTicket = AuthHostClient.issueLoginTicketAsync(
                relayBaseUrl = resolvedSession.relayBaseUrl,
                authSessionToken = authSessionToken,
                nonce = nonce,
                audience = challenge.audience,
            ).join()

            if (issuedTicket.ticket.isNullOrBlank()) {
                logger.info("Auth host did not issue login ticket: {}", issuedTicket.error ?: "unknown error")
                emptyResponse()
            } else {
                logger.info("Issued challenge-bound Ely login ticket for nonce {}.", nonce)
                val responsePayload = AuthProtocolCodec.encodeResponse(LoginResponse(ticket = issuedTicket.ticket))
                PacketByteBufs.create().apply {
                    writeBytes(responsePayload)
                }
            }
        }
    }

    private fun emptyResponse(): PacketByteBuf? {
        // According to Fabric API, returning null signals that the client doesn't understand the channel.
        // We reply with an empty packet to explicitly say we understood but failed to provide a ticket.
        val responsePayload = AuthProtocolCodec.encodeResponse(LoginResponse(ticket = null))
        return PacketByteBufs.create().apply {
            writeBytes(responsePayload)
        }
    }

    private fun ensureUsableSession(sessionState: ClientSessionState): ClientSessionState? {
        if (sessionState.hasUsableAuthSession()) {
            return sessionState
        }

        logger.info("Local Ely auth session is empty or expired. Trying to pull latest session from auth host.")
        val latestSession: AuthPollResult = runCatching {
            AuthHostClient.latestSession(sessionState.relayBaseUrl)
        }.getOrElse { exception ->
            logger.warn("Failed to pull latest Ely auth session from auth host.", exception)
            return null
        }

        if (latestSession.status != "completed" ||
            latestSession.authSessionToken.isNullOrBlank() ||
            latestSession.username.isNullOrBlank() ||
            latestSession.uuid.isNullOrBlank() ||
            latestSession.expiresAtEpochSeconds == null
        ) {
            logger.info("Auth host did not return a usable latest Ely session. status={}", latestSession.status)
            return null
        }

        val resolvedSession = ClientSessionState(
            relayBaseUrl = sessionState.relayBaseUrl,
            authSessionToken = latestSession.authSessionToken,
            authSessionExpiresAt = Instant.ofEpochSecond(latestSession.expiresAtEpochSeconds),
            elyUsername = latestSession.username,
            elyUuid = latestSession.uuid,
        )
        ClientSessionStore.save(resolvedSession)
        logger.info("Pulled latest Ely auth session for {} ({}) from auth host.", latestSession.username, latestSession.uuid)
        return resolvedSession
    }
}
