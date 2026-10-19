package dev.ely4everyone.mod.auth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.ely4everyone.shared.oauth.LoopbackLogin
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Receives the end of a browser login on 127.0.0.1. The auth host redirects the player's own
 * browser here, which is what ties the login to this machine. Only the first callback carrying
 * the expected state is kept.
 */
class LocalAuthCallbackServer private constructor(
    private val expectedState: String,
    private val server: HttpServer,
    private val executor: ExecutorService,
    val callbackUri: URI,
) {
    @Volatile
    private var result: LocalAuthCallbackPayload? = null

    @Volatile
    private var delivered: Boolean = false

    fun consumeResult(): LocalAuthCallbackPayload? {
        val current = result
        result = null
        return current
    }

    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        fun start(expectedState: String): LocalAuthCallbackServer {
            val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
            // Daemon thread, so a forgotten server never keeps the game process alive.
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "ely4everyone-login-callback").apply { isDaemon = true }
            }
            server.executor = executor
            val instance = LocalAuthCallbackServer(
                expectedState = expectedState,
                server = server,
                executor = executor,
                callbackUri = URI.create("http://127.0.0.1:${server.address.port}${LoopbackLogin.CALLBACK_PATH}"),
            )
            server.createContext(LoopbackLogin.CALLBACK_PATH) { exchange ->
                try {
                    instance.handleCallback(exchange)
                } finally {
                    exchange.close()
                }
            }
            server.start()
            return instance
        }
    }

    private fun handleCallback(exchange: HttpExchange) {
        val values = parseQuery(exchange.requestURI.rawQuery)
        val state = values["state"].orEmpty()
        if (!LoopbackLogin.constantTimeEquals(state, expectedState)) {
            // Not our login (a stale tab or someone else's link): do not let it disturb this one.
            respond(exchange, 400, "Этот вход не относится к текущей попытке. Начни вход заново из Minecraft.")
            return
        }
        synchronized(this) {
            if (delivered) {
                respond(exchange, 200, "Вход уже обработан. Можно вернуться в Minecraft.")
                return
            }
            delivered = true
            result = LocalAuthCallbackPayload(
                state = state,
                code = values["code"]?.takeIf { it.isNotBlank() },
                error = values["error"]?.takeIf { it.isNotBlank() },
            )
        }
        if (values["error"].isNullOrBlank()) {
            respond(exchange, 200, "Почти готово! Вернись в Minecraft, вход завершится сам.")
        } else {
            respond(exchange, 200, "Вход не удался: ${values["error"]}. Вернись в Minecraft и попробуй ещё раз.")
        }
    }

    private fun respond(exchange: HttpExchange, statusCode: Int, message: String) {
        val body = """
            <!DOCTYPE html>
            <html lang="ru">
            <head><meta charset="utf-8"><title>Ely4Everyone</title></head>
            <body style="font-family: sans-serif; margin: 2rem;">
                <h1>Ely4Everyone</h1>
                <p>${escapeHtml(message)}</p>
            </body>
            </html>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        // The page shows the result of a login; nothing about it should be cached or framed.
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.responseHeaders.add("Referrer-Policy", "no-referrer")
        exchange.sendResponseHeaders(statusCode, body.size.toLong())
        exchange.responseBody.write(body)
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) {
            return emptyMap()
        }

        val result = LinkedHashMap<String, String>()
        val parts = rawQuery.split('&')
        for (part in parts) {
            if (part.isBlank()) continue
            val separator = part.indexOf('=')
            if (separator < 0) {
                result[decode(part)] = ""
            } else {
                result[decode(part.substring(0, separator))] = decode(part.substring(separator + 1))
            }
        }
        return result
    }

    private fun decode(value: String): String {
        return runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrDefault("")
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
