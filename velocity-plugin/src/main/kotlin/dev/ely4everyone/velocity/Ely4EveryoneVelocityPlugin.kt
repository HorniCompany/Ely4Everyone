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
        val username = event.username
        try {
            val client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build()
            val request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://authserver.ely.by/api/users/profiles/minecraft/$username"))
                .GET()
                .build()
            
            val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                logger.info("Ely4Everyone: {} is registered on Ely.by. Forcing online mode native authentication!", username)
                event.result = PreLoginEvent.PreLoginComponentResult.forceOnlineMode()
            } else {
                logger.info("Ely4Everyone: {} is not registered on Ely.by. Continuing as offline mode.", username)
            }
        } catch (e: Exception) {
            logger.warn("Ely4Everyone: Error checking Ely.by profile for {}", username, e)
        }
    }
}
