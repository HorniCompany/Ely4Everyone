package dev.ely4everyone.mod.host

import dev.ely4everyone.shared.protocol.AuthProtocolCodec
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture

object AuthHostDiscoveryService {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(350))
        .build()
    private val defaultPorts: List<Int> = listOf(18085, 19085)

    fun discoverAsync(): CompletableFuture<List<DiscoveredAuthHost>> {
        val candidates = buildCandidateBaseUrls(localIpv4Addresses(), defaultPorts)
        val futures = candidates.map { baseUrl ->
            CompletableFuture.supplyAsync { fetchDiscovery(baseUrl) }
        }
        return CompletableFuture.allOf(*futures.toTypedArray()).thenApply {
            futures.mapNotNull { runCatching { it.get() }.getOrNull() }
                .distinctBy { it.id }
                .sortedBy { it.displayName.lowercase() }
        }
    }

    fun buildCandidateBaseUrls(ipv4Addresses: List<String>, ports: List<Int>): List<String> {
        val candidates = linkedSetOf<String>()
        ipv4Addresses.forEach { ipv4 ->
            ports.forEach { port ->
                candidates += "http://$ipv4:$port"
            }
            if (ipv4 != "127.0.0.1") {
                val parts = ipv4.split('.')
                if (parts.size == 4) {
                    val prefix = parts.take(3).joinToString(".")
                    (1..254).forEach { host ->
                        ports.forEach { port ->
                            candidates += "http://$prefix.$host:$port"
                        }
                    }
                }
            }
        }
        return candidates.toList()
    }

    fun parseDiscoveryPayload(payload: String): DiscoveredAuthHost? {
        return runCatching {
            val document = AuthProtocolCodec.decodeDiscovery(payload.toByteArray(StandardCharsets.UTF_8))
            if (document.hostId.isBlank() || document.publicBaseUrl.isBlank()) {
                null
            } else {
                DiscoveredAuthHost(
                    id = document.hostId,
                    displayName = document.displayName,
                    baseUrl = document.publicBaseUrl,
                    issuer = document.issuer,
                    audience = document.audience,
                )
            }
        }.getOrNull()
    }

    private fun fetchDiscovery(baseUrl: String): DiscoveredAuthHost? {
        val request = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/api/v2/discovery"))
            .timeout(Duration.ofMillis(500))
            .GET()
            .build()
        val response = runCatching {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        }.getOrNull() ?: return null
        if (response.statusCode() !in 200..299) {
            return null
        }
        return parseDiscoveryPayload(response.body())
    }

    private fun localIpv4Addresses(): List<String> {
        val loopback = mutableListOf("127.0.0.1")
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
        interfaces.asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .filter { !it.isNullOrBlank() }
            .forEach { loopback += it }
        return loopback.distinct()
    }
}
