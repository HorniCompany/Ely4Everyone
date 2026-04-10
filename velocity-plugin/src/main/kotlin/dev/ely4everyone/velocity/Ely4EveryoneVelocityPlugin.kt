package dev.ely4everyone.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import dev.ely4everyone.shared.host.EmbeddedAuthHttpServer
import dev.ely4everyone.shared.host.HttpHybridAuthUpstream
import dev.ely4everyone.shared.host.HybridProfileResolver
import dev.ely4everyone.shared.host.HybridSessionVerifier
import dev.ely4everyone.shared.host.PendingPremiumLoginStore
import dev.ely4everyone.velocity.config.ProxyConfigStore
import org.slf4j.Logger
import java.nio.file.Path

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
    private lateinit var config: dev.ely4everyone.velocity.config.ProxyConfig
    private lateinit var pendingPremiumLoginStore: PendingPremiumLoginStore
    private lateinit var profileResolver: HybridProfileResolver
    private lateinit var sessionVerifier: HybridSessionVerifier
    private lateinit var floodgateBedrockDetector: FloodgateBedrockDetector
    private var embeddedAuthHttpServer: EmbeddedAuthHttpServer? = null

    companion object {
        val LOGIN_CHANNEL: MinecraftChannelIdentifier = MinecraftChannelIdentifier.from("ely4everyone:login")
    }

    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        ProxyConfigStore.saveDefaultsIfMissing(dataDirectory)
        config = ProxyConfigStore.load(dataDirectory)

        val upstream = HttpHybridAuthUpstream()
        pendingPremiumLoginStore = PendingPremiumLoginStore(ttlSeconds = config.issuedTicketTtlSeconds)
        profileResolver = HybridProfileResolver(upstream)
        sessionVerifier = HybridSessionVerifier(upstream, pendingPremiumLoginStore)
        floodgateBedrockDetector = FloodgateBedrockDetector(logger = logger).also { it.register() }

        server.channelRegistrar.register(LOGIN_CHANNEL)
        embeddedAuthHttpServer = EmbeddedAuthHttpServer(
            config = config.toEmbeddedAuthHostConfig("velocity-embedded"),
            logger = logger,
            dataDirectory = dataDirectory,
            pendingPremiumLoginStore = pendingPremiumLoginStore,
            profileResolver = profileResolver,
            sessionVerifier = sessionVerifier,
        ).also { authServer ->
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
        floodgateBedrockDetector.unregister()
        embeddedAuthHttpServer?.stop()
        embeddedAuthHttpServer = null
    }

    @Subscribe
    fun onPreLogin(event: PreLoginEvent) {
        val username = event.username
        val clientUuid = event.uniqueId

        val floodgatePlayer = floodgateBedrockDetector.findPlayer(username)
        if (floodgatePlayer != null) {
            pendingPremiumLoginStore.clear(username)
            logger.info(
                "Ely4Everyone: {} Bedrock Floodgate passthrough (javaUsername={}, correctUsername={}, linked={}, bedrockUuid={})",
                username,
                floodgatePlayer.javaUsername,
                floodgatePlayer.correctUsername,
                floodgatePlayer.linked,
                floodgatePlayer.bedrockUuid,
            )
            return
        }

        val decision = profileResolver.resolvePreLogin(username, clientUuid)

        if (!decision.shouldForceOnline) {
            pendingPremiumLoginStore.clear(username)
            event.result = PreLoginEvent.PreLoginComponentResult.forceOfflineMode()
            logger.info(
                "Ely4Everyone: {} routed to OFFLINE flow (reason={}, uuid={})",
                username,
                decision.authority ?: "UNKNOWN",
                clientUuid,
            )
            return
        }

        embeddedAuthHttpServer?.registerPendingLogin(
            username = username,
            authority = decision.authority,
            expectedUuid = decision.expectedProfile?.uuid,
            clientUuid = clientUuid,
            allowedAuthorities = decision.allowedAuthorities,
        )
        event.result = PreLoginEvent.PreLoginComponentResult.forceOnlineMode()

        if (decision.authority != null && decision.expectedProfile != null) {
            logger.info(
                "Ely4Everyone: {} routed to {} premium flow (clientUuid={}, expectedUuid={})",
                username,
                decision.authority,
                clientUuid,
                decision.expectedProfile.uuid,
            )
        } else {
            logger.info(
                "Ely4Everyone: {} routed to dual premium candidate flow (clientUuid={}, authorities={})",
                username,
                clientUuid,
                decision.allowedAuthorities.joinToString(","),
            )
        }
    }
}
