package dev.ely4everyone.mod.identity

import dev.ely4everyone.mod.Ely4EveryoneClientMod
import dev.ely4everyone.mod.session.ClientSessionStore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

object ElyAuthlibBridge {
    private val logger = LoggerFactory.getLogger("${Ely4EveryoneClientMod.MOD_ID}/authlib")
    private val httpClient: HttpClient = HttpClient.newHttpClient()
    private const val ELY_SESSION_JOIN_URL: String = "https://authserver.ely.by/session/join"

    fun hasUsableElySession(): Boolean {
        return ClientSessionStore.load().hasUsableElyAccessToken()
    }

    fun tryJoinServerSession(serverId: String): Component? {
        val client = Minecraft.getInstance()
        val session = client.user ?: return null
        val uuid = session.profileId ?: return null
        val accessToken = session.accessToken

        if (accessToken.isBlank()) {
            return Component.translatable(
                "disconnect.loginFailedInfo",
                Component.translatable("disconnect.loginFailedInfo.invalidSession"),
            )
        }

        return try {
            logger.info("Joining server session through Ely authserver. uuid={}, serverId={}", uuid, serverId)
            val selectedProfile = uuid.toString().replace("-", "")
            val body = """
                {"accessToken":"$accessToken","selectedProfile":"$selectedProfile","serverId":"$serverId"}
            """.trimIndent()
            val request = HttpRequest.newBuilder(URI.create(ELY_SESSION_JOIN_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (response.statusCode() in 200..299) {
                null
            } else {
                logger.warn(
                    "Ely joinServerSession failed. status={}, body={}",
                    response.statusCode(),
                    response.body(),
                )
                Component.translatable("disconnect.loginFailedInfo", "Ely session join failed: HTTP ${response.statusCode()}")
            }
        } catch (exception: Exception) {
            logger.warn("Ely joinServerSession failed.", exception)
            Component.translatable("disconnect.loginFailedInfo", exception.message ?: "Authentication error")
        }
    }
}
