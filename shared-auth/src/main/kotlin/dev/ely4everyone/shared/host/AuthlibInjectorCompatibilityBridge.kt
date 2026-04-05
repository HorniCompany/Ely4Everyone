package dev.ely4everyone.shared.host

import com.sun.net.httpserver.HttpExchange
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.logging.Logger

object AuthlibInjectorCompatibilityBridge {
    private val logger = Logger.getLogger("ely4everyone.authlib-compat")
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun joinTarget(): URI = URI.create("https://authserver.ely.by/session/join")

    fun hasJoinedTarget(rawQuery: String?): URI {
        val suffix = rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        return URI.create("https://authserver.ely.by/session/hasJoined$suffix")
    }

    fun profileTarget(uuidPath: String, rawQuery: String?): URI {
        val suffix = rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        return URI.create("https://authserver.ely.by/session/profile/$uuidPath$suffix")
    }

    fun batchProfilesTarget(): URI = URI.create("https://authserver.ely.by/api/profiles/minecraft")

    fun proxyJsonPost(exchange: HttpExchange, target: URI) {
        logger.info("authlib-compat proxy POST ${exchange.requestURI} -> $target")
        proxy(
            exchange = exchange,
            target = target,
            method = "POST",
            body = exchange.requestBody.readBytes(),
            contentType = exchange.requestHeaders.getFirst("Content-Type") ?: "application/json",
        )
    }

    fun proxyGet(exchange: HttpExchange, target: URI) {
        logger.info("authlib-compat proxy GET ${exchange.requestURI} -> $target")
        proxy(
            exchange = exchange,
            target = target,
            method = "GET",
            body = null,
            contentType = null,
        )
    }

    private fun proxy(
        exchange: HttpExchange,
        target: URI,
        method: String,
        body: ByteArray?,
        contentType: String?,
    ) {
        val builder = HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(15))

        if (contentType != null) {
            builder.header("Content-Type", contentType)
        }

        val request = when (method) {
            "POST" -> builder.POST(HttpRequest.BodyPublishers.ofByteArray(body ?: ByteArray(0))).build()
            else -> builder.GET().build()
        }

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        logger.info("authlib-compat response ${exchange.requestURI} <- $target (${response.statusCode()})")
        val responseContentType = response.headers().firstValue("Content-Type").orElse("application/json; charset=utf-8")
        exchange.responseHeaders.add("Content-Type", responseContentType)
        exchange.sendResponseHeaders(response.statusCode(), response.body().size.toLong())
        exchange.responseBody.use { output ->
            output.write(response.body())
        }
    }
}
