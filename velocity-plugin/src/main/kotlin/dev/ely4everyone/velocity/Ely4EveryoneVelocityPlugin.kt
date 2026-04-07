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
        val clientUuid = event.uniqueId
        
        // Вектор 1 (Deep Think): UUID Fingerprinting
        val expectedOfflineUuid = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).toByteArray(Charsets.UTF_8))
        
        // Быстрый путь 1: Bedrock игроки (Geyser/Floodgate). Обычно у них префикс '.' или UUID версии 0.
        val isBedrock = username.startsWith(".") || (clientUuid != null && clientUuid.version() == 0)
        if (isBedrock) {
            logger.info("Ely4Everyone: {} идентифицирован как Bedrock. Пропускаем API и пускаем в Offline Mode!", username)
            event.result = PreLoginEvent.PreLoginComponentResult.forceOfflineMode()
            return
        }

        // Быстрый путь 2: Пиратские клиенты (UUID идеально совпадает с MD5-формулой Minecraft)
        if (clientUuid != null && clientUuid == expectedOfflineUuid) {
            logger.info("Ely4Everyone: {} отправляет оффлайн UUID ({}). Это 100% пират. Пропускаем Ely.by и пускаем в Offline Mode!", username, clientUuid)
            event.result = PreLoginEvent.PreLoginComponentResult.forceOfflineMode()
            return
        }
        
        if (clientUuid == null) {
            logger.info("Ely4Everyone: {} не прислал UUID (старый клиент). Обычная проверка по API.", username)
        } else {
            logger.info("Ely4Everyone: {} прислал уникальный UUID ({}). Похоже на Ely/Mojang аккаунт. Проверяем регистрацию.", username, clientUuid)
        }

        try {
            val client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build()
                
            // Шаг 1: Ищем профиль на Ely.by
            val elyRequest = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://authserver.ely.by/api/users/profiles/minecraft/$username"))
                .GET()
                .build()
            
            val elyResponse = client.send(elyRequest, java.net.http.HttpResponse.BodyHandlers.ofString())
            if (elyResponse.statusCode() == 200) {
                logger.info("Ely4Everyone: {} зарегистрирован на Ely.by. Force Online Mode!", username)
                event.result = PreLoginEvent.PreLoginComponentResult.forceOnlineMode()
                return
            } 
            
            // Шаг 2: Ищем профиль в Mojang
            val mojangRequest = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://api.mojang.com/users/profiles/minecraft/$username"))
                .GET()
                .build()
            
            val mojangResponse = client.send(mojangRequest, java.net.http.HttpResponse.BodyHandlers.ofString())
            if (mojangResponse.statusCode() == 200) {
                logger.info("Ely4Everyone: {} имеет лицензию Mojang. Force Online Mode!", username)
                event.result = PreLoginEvent.PreLoginComponentResult.forceOnlineMode()
                return
            }

            // Игрок не найден ни в одной базе! Он - уникальный пират (даже без UUIDv3), пустим его как пирата.
            logger.info("Ely4Everyone: {} не найден ни на Ely.by, ни в Mojang. Force Offline Mode.", username)
            event.result = PreLoginEvent.PreLoginComponentResult.forceOfflineMode()

        } catch (e: Exception) {
            logger.warn("Ely4Everyone: Ошибка API (Ely.by или Mojang) для {}. Fallback в Offline.", username, e)
            event.result = PreLoginEvent.PreLoginComponentResult.forceOfflineMode()
        }
    }
}
