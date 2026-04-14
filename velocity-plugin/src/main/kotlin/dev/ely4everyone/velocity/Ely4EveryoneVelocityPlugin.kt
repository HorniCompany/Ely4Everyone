package dev.ely4everyone.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.event.player.GameProfileRequestEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import com.velocitypowered.api.util.GameProfile
import dev.ely4everyone.shared.host.EmbeddedAuthHttpServer
import dev.ely4everyone.shared.host.AuthAuthority
import dev.ely4everyone.shared.host.HttpHybridAuthUpstream
import dev.ely4everyone.shared.host.HybridProfileResolver
import dev.ely4everyone.shared.host.HybridSessionVerifier
import dev.ely4everyone.shared.host.PendingPremiumLoginStore
import dev.ely4everyone.velocity.config.ProxyConfigStore
import org.slf4j.Logger
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class GameLoginIdentity(
    val accountType: String,
    val authProvider: String,
    val registrationOrigin: String,
    val username: String,
    val uuid: String?,
)

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
    private val gameIdentitiesByUsername = ConcurrentHashMap<String, GameLoginIdentity>()

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
            rememberIdentity(
                username,
                GameLoginIdentity(
                    accountType = "bedrock",
                    authProvider = "bedrock",
                    registrationOrigin = "game_bedrock",
                    username = floodgatePlayer.correctUsername,
                    uuid = floodgatePlayer.bedrockUuid.toString(),
                ),
            )
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
            rememberIdentity(
                username,
                GameLoginIdentity(
                    accountType = "cracked",
                    authProvider = "cracked",
                    registrationOrigin = "game_cracked",
                    username = username,
                    uuid = clientUuid?.toString(),
                ),
            )
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
        decision.expectedProfile?.let { profile ->
            rememberIdentity(username, identityForAuthority(profile.authority, profile.username, profile.uuid))
        }
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

    @Subscribe
    fun onGameProfileRequest(event: GameProfileRequestEvent) {
        val identity = gameIdentitiesByUsername[normalize(event.username)] ?: return
        if (identity.accountType == "cracked" || identity.uuid == null) {
            return
        }

        val expectedUuid = runCatching { UUID.fromString(identity.uuid) }.getOrNull() ?: return
        if (event.gameProfile.id == expectedUuid) {
            return
        }

        event.gameProfile = GameProfile(
            expectedUuid,
            event.gameProfile.name,
            event.gameProfile.properties,
        )

        logger.info(
            "Ely4Everyone: forced forwarded profile for {} to {} uuid {}",
            event.username,
            identity.accountType,
            expectedUuid,
        )
    }

    fun resolveGameIdentity(username: String, uuid: UUID?, onlineMode: Boolean): GameLoginIdentity {
        val floodgatePlayer = floodgateBedrockDetector.findPlayer(username)
        if (floodgatePlayer != null) {
            return GameLoginIdentity(
                accountType = "bedrock",
                authProvider = "bedrock",
                registrationOrigin = "game_bedrock",
                username = floodgatePlayer.correctUsername,
                uuid = floodgatePlayer.bedrockUuid.toString(),
            ).also { rememberIdentity(username, it) }
        }

        val remembered = gameIdentitiesByUsername[normalize(username)]
        if (remembered != null) {
            return remembered
        }

        if (!onlineMode) {
            return GameLoginIdentity(
                accountType = "cracked",
                authProvider = "cracked",
                registrationOrigin = "game_cracked",
                username = username,
                uuid = uuid?.toString(),
            ).also { rememberIdentity(username, it) }
        }

        val verifiedAuthority = uuid?.let { pendingPremiumLoginStore.findAuthorityByUuid(it.toString()) }
        val pendingAuthority = pendingPremiumLoginStore.get(username)?.authority
        val authority = verifiedAuthority ?: pendingAuthority

        if (authority == AuthAuthority.ELY || authority == AuthAuthority.MOJANG) {
            return identityForAuthority(authority, username, uuid).also { rememberIdentity(username, it) }
        }

        return gameIdentitiesByUsername[normalize(username)] ?: GameLoginIdentity(
            accountType = "java",
            authProvider = "microsoft",
            registrationOrigin = "game_java",
            username = username,
            uuid = uuid?.toString(),
        ).also { rememberIdentity(username, it) }
    }

    private fun identityForAuthority(authority: AuthAuthority, username: String, uuid: UUID?): GameLoginIdentity {
        return when (authority) {
            AuthAuthority.ELY -> GameLoginIdentity(
                accountType = "ely",
                authProvider = "elyby",
                registrationOrigin = "game_ely",
                username = username,
                uuid = uuid?.toString(),
            )
            AuthAuthority.MOJANG -> GameLoginIdentity(
                accountType = "java",
                authProvider = "microsoft",
                registrationOrigin = "game_java",
                username = username,
                uuid = uuid?.toString(),
            )
            AuthAuthority.BEDROCK -> GameLoginIdentity(
                accountType = "bedrock",
                authProvider = "bedrock",
                registrationOrigin = "game_bedrock",
                username = username,
                uuid = uuid?.toString(),
            )
            AuthAuthority.OFFLINE -> GameLoginIdentity(
                accountType = "cracked",
                authProvider = "cracked",
                registrationOrigin = "game_cracked",
                username = username,
                uuid = uuid?.toString(),
            )
        }
    }

    private fun rememberIdentity(username: String, identity: GameLoginIdentity) {
        gameIdentitiesByUsername[normalize(username)] = identity
        gameIdentitiesByUsername[normalize(identity.username)] = identity
    }

    private fun normalize(username: String): String = username.lowercase()
}
