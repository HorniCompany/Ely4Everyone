package dev.ely4everyone.velocity.hook

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.GameProfileRequestEvent
import com.velocitypowered.api.util.GameProfile
import dev.ely4everyone.velocity.config.ProxyConfig
import org.slf4j.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import com.google.gson.JsonParser
import java.time.Duration

class VelocitySkinsRestorerHook(
    private val config: ProxyConfig,
    private val logger: Logger
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    @Subscribe
    fun onGameProfileRequest(event: GameProfileRequestEvent) {
        if (!config.enableSkinsrestorerHook) return
        if (event.isOnlineMode) return // Mojang already gave the skin

        val username = event.username
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://skinsystem.ely.by/api/textures/$username"))
                .header("User-Agent", "Ely4Everyone/1.0")
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val json = JsonParser.parseString(response.body()).asJsonObject
                val value = json.get("value")?.asString
                val signature = json.get("signature")?.asString

                if (value != null && signature != null) {
                    val props = event.originalProfile.properties.toMutableList()
                    props.removeIf { it.name == "textures" }
                    props.add(GameProfile.Property("textures", value, signature))
                    
                    event.gameProfile = GameProfile(
                        event.originalProfile.id,
                        event.originalProfile.name,
                        props
                    )
                    logger.info("Injected Ely.by skin for offline player $username")
                }
            }
        } catch (e: Exception) {
            logger.debug("Failed to fetch Ely.by skin for $username: ${e.message}")
        }
    }
}
