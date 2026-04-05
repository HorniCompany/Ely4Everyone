package dev.ely4everyone.mod.skin

import com.mojang.authlib.SignatureState
import com.mojang.authlib.minecraft.MinecraftProfileTexture
import com.mojang.authlib.minecraft.MinecraftProfileTextures
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Resolves player skins from Ely.by Skin System.
 *
 * Priority: Ely.by first → Mojang fallback (handled by caller).
 * Results are cached for [CACHE_TTL] to avoid spamming the API.
 */
object ElySkinResolver {
    private val logger = LoggerFactory.getLogger("Ely4Everyone/SkinResolver")

    private const val SKINSYSTEM_URL = "http://skinsystem.ely.by/textures/signed/"
    private val CACHE_TTL = Duration.ofMinutes(5)
    private val HTTP_TIMEOUT = Duration.ofSeconds(4)

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "ely4everyone-skin-resolver").apply { isDaemon = true }
    }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(HTTP_TIMEOUT)
        .executor(executor)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val cache = ConcurrentHashMap<String, CachedResult>()

    data class CachedResult(
        val textures: MinecraftProfileTextures?,
        val fetchedAt: Instant,
    ) {
        fun isExpired(now: Instant = Instant.now()): Boolean =
            Duration.between(fetchedAt, now) > CACHE_TTL
    }

    /**
     * Asynchronously resolve Ely.by skin textures for a player by username.
     * Returns null if no Ely.by skin exists (caller should fall back to Mojang).
     */
    fun resolve(username: String): CompletableFuture<MinecraftProfileTextures?> {
        val key = username.lowercase()

        // Check cache first
        val cached = cache[key]
        if (cached != null && !cached.isExpired()) {
            return CompletableFuture.completedFuture(cached.textures)
        }

        return fetchFromApi(username).thenApply { textures ->
            cache[key] = CachedResult(textures, Instant.now())
            textures
        }.exceptionally { ex ->
            logger.debug("Ely.by skin lookup failed for '{}': {}", username, ex.message)
            // Cache the failure too to avoid retrying immediately
            cache[key] = CachedResult(null, Instant.now())
            null
        }
    }

    /**
     * Get cached result without triggering a fetch.
     * Returns null if no cache entry exists or if it's expired.
     */
    fun getCached(username: String): CachedResult? {
        val cached = cache[username.lowercase()]
        return if (cached != null && !cached.isExpired()) cached else null
    }

    /**
     * Invalidate a specific cache entry.
     */
    fun invalidate(username: String) {
        cache.remove(username.lowercase())
    }

    /**
     * Clear the entire cache.
     */
    fun clearCache() {
        cache.clear()
    }

    /**
     * Returns cache size (for diagnostics/tests).
     */
    fun cacheSize(): Int = cache.size

    private fun fetchFromApi(username: String): CompletableFuture<MinecraftProfileTextures?> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$SKINSYSTEM_URL${username}"))
            .timeout(HTTP_TIMEOUT)
            .GET()
            .build()

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() != 200) {
                    return@thenApply null
                }
                parseResponse(response.body())
            }
    }

    /**
     * Parses the skinsystem.ely.by JSON response into MinecraftProfileTextures.
     *
     * Expected format:
     * ```json
     * {
     *   "id": "...",
     *   "name": "...",
     *   "properties": [
     *     { "name": "textures", "value": "base64...", "signature": "..." }
     *   ]
     * }
     * ```
     */
    internal fun parseResponse(json: String): MinecraftProfileTextures? {
        return runCatching {
            // Extract the textures property value (base64)
            val valueMatch = VALUE_REGEX.find(json) ?: return null
            val texturesValue = valueMatch.groupValues[1]

            // Extract optional signature
            val signatureMatch = SIGNATURE_REGEX.find(json)
            val signature = signatureMatch?.groupValues?.getOrNull(1)

            // Decode base64 textures value to get skin URL
            val decoded = String(Base64.getDecoder().decode(texturesValue), Charsets.UTF_8)

            val skinUrl = SKIN_URL_REGEX.find(decoded)
                ?.groupValues?.getOrNull(1)
                ?: return null

            // Extract model metadata (slim/default)
            val metadata = HashMap<String, String>()
            MODEL_REGEX.find(decoded)
                ?.groupValues?.getOrNull(1)
                ?.let { metadata["model"] = it }

            // Extract cape URL if present
            val capeUrl = CAPE_URL_REGEX.find(decoded)
                ?.groupValues?.getOrNull(1)

            val signatureState = if (signature.isNullOrBlank()) {
                SignatureState.UNSIGNED
            } else {
                SignatureState.SIGNED
            }

            MinecraftProfileTextures(
                MinecraftProfileTexture(skinUrl, metadata),
                capeUrl?.let { MinecraftProfileTexture(it, emptyMap()) },
                null,
                signatureState,
            )
        }.getOrNull()
    }

    private val VALUE_REGEX = Regex(""""value"\s*:\s*"([^"]+)"""")
    private val SIGNATURE_REGEX = Regex(""""signature"\s*:\s*"([^"]+)"""")
    private val SKIN_URL_REGEX = Regex(""""SKIN"\s*:\s*\{[^}]*"url"\s*:\s*"([^"]+)"""")
    private val MODEL_REGEX = Regex(""""metadata"\s*:\s*\{[^}]*"model"\s*:\s*"([^"]+)"""")
    private val CAPE_URL_REGEX = Regex(""""CAPE"\s*:\s*\{[^}]*"url"\s*:\s*"([^"]+)"""")
}
