package dev.ely4everyone.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.event.player.GameProfileRequestEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.LoginPhaseConnection
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import com.velocitypowered.api.util.GameProfile
import dev.ely4everyone.velocity.auth.ReplayProtection
import dev.ely4everyone.velocity.auth.TrustedLoginRegistry
import dev.ely4everyone.velocity.auth.VelocityProfilePropertyAdapter
import dev.ely4everyone.shared.host.EmbeddedAuthHttpServer
import dev.ely4everyone.shared.host.IssuedLoginTicketRecord
import dev.ely4everyone.shared.protocol.AuthProtocolCodec
import dev.ely4everyone.shared.protocol.LoginChallenge
import dev.ely4everyone.shared.ticket.AuthTicketCodec
import dev.ely4everyone.velocity.config.ProxyConfigStore
import org.slf4j.Logger
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

@Plugin(
    id = "ely4everyone",
    name = "Ely4Everyone",
    version = "0.1.0-SNAPSHOT",
    description = "Trusted Ely identity flow for Velocity networks.",
    authors = ["cokeef"],
)
class Ely4EveryoneVelocityPlugin @Inject constructor(
    private val server: ProxyServer,
    private val logger: Logger,
    @param:DataDirectory private val dataDirectory: Path,
) {
    private val replayProtection = ReplayProtection()
    private val trustedLoginRegistry = TrustedLoginRegistry()
    private lateinit var config: dev.ely4everyone.velocity.config.ProxyConfig
    private var embeddedAuthHttpServer: EmbeddedAuthHttpServer? = null

    companion object {
        val LOGIN_CHANNEL: MinecraftChannelIdentifier = MinecraftChannelIdentifier.from("ely4everyone:login")
    }

    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        ProxyConfigStore.saveDefaultsIfMissing(dataDirectory)
        config = ProxyConfigStore.load(dataDirectory)
        server.channelRegistrar.register(LOGIN_CHANNEL)
        embeddedAuthHttpServer = EmbeddedAuthHttpServer(config.toEmbeddedAuthHostConfig("velocity-embedded"), logger, dataDirectory).also { authServer ->
            authServer.start()
        }

        logger.info(
            "Ely4Everyone Velocity plugin initialized. issuer={}, audience={}, loginChannel={}, embeddedAuthEnabled={}, publicBaseUrl={}",
            config.trustedIssuer,
            config.expectedAudience,
            LOGIN_CHANNEL.id,
            config.embeddedAuthEnabled,
            config.publicBaseUrl,
        )
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        embeddedAuthHttpServer?.stop()
        embeddedAuthHttpServer = null
    }

    @Subscribe
    fun onPreLogin(event: PreLoginEvent) {
        val loginConnection = event.connection as? LoginPhaseConnection ?: return
        val username = event.username
        val challenge = LoginChallenge(
            version = "v2",
            nonce = UUID.randomUUID().toString(),
            audience = config.expectedAudience,
            hostId = "velocity-embedded",
        )

        logger.info("Sending Ely4Everyone login challenge to {} with nonce {}", username, challenge.nonce)
        loginConnection.sendLoginPluginMessage(
            LOGIN_CHANNEL,
            AuthProtocolCodec.encodeChallenge(challenge),
        ) { response ->
            try {
                logger.info("Received raw Ely4Everyone login response callback from {}.", username)
                handleLoginResponse(username, challenge, response)
            } catch (exception: Exception) {
                logger.warn("Failed to process Ely4Everyone login response for {}", username, exception)
            }
        }
    }

    @Subscribe
    fun onGameProfileRequest(event: GameProfileRequestEvent) {
        val trustedLogin = trustedLoginRegistry.take(event.username) ?: return
        val adaptedProperties = VelocityProfilePropertyAdapter.adapt(trustedLogin.properties)
        val replacementProfile = GameProfile(
            trustedLogin.uuid,
            trustedLogin.ticket.username,
            adaptedProperties.map { property ->
                GameProfile.Property(property.name, property.value, property.signature)
            },
        )

        event.gameProfile = replacementProfile
        logger.info(
            "Applied Ely trusted profile for {} -> {} ({}) with {} profile properties",
            event.username,
            trustedLogin.ticket.username,
            trustedLogin.ticket.subject,
            adaptedProperties.size,
        )
    }

    private fun handleLoginResponse(username: String, challenge: LoginChallenge, response: ByteArray?): dev.ely4everyone.velocity.auth.TrustedLogin? {
        val parsed = AuthProtocolCodec.decodeResponse(response)
        if (parsed.ticket.isNullOrBlank()) {
            logger.info("No Ely ticket received for {}. Falling back to cracked flow.", username)
            return null
        }

        logger.info("Received Ely ticket response from {}.", username)

        val verified = AuthTicketCodec.verify(
            token = parsed.ticket,
            trustedIssuer = config.trustedIssuer,
            expectedAudience = config.expectedAudience,
            signingKey = config.ticketSigningKey,
            now = Instant.now(),
        )

        if (verified == null) {
            logger.info("Rejected Ely ticket for {} due to failed verification.", username)
            return null
        }

        if (verified.nonce != challenge.nonce) {
            logger.info(
                "Rejected Ely ticket for {} because nonce mismatch. expected={}, actual={}",
                username,
                challenge.nonce,
                verified.nonce,
            )
            return null
        }

        if (!replayProtection.tryAccept(verified.ticketId, verified.expiresAtEpochSeconds)) {
            logger.info("Rejected Ely ticket for {} due to replay protection.", username)
            return null
        }

        val issuedRecord: IssuedLoginTicketRecord = embeddedAuthHttpServer?.consumeIssuedTicketRecord(verified.ticketId)
            ?: run {
                logger.info("Rejected Ely ticket for {} because issued ticket metadata was not found.", username)
                return null
            }

        val uuid = runCatching { UUID.fromString(verified.subject) }.getOrNull()
        if (uuid == null) {
            logger.info("Rejected Ely ticket for {} because subject is not a UUID: {}", username, verified.subject)
            return null
        }

        val trustedLogin = dev.ely4everyone.velocity.auth.TrustedLogin(
            uuid = uuid,
            ticket = verified,
            properties = issuedRecord.properties,
        )

        trustedLoginRegistry.put(username, trustedLogin)
        logger.info("Accepted Ely ticket for {} as trusted Ely user {}", username, verified.username)
        return trustedLogin
    }
}
