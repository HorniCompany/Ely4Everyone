package dev.ely4everyone.shared.host

import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class AuthAuthority {
    ELY,
    MOJANG,
    OFFLINE,
    BEDROCK,
}

data class RemoteGameProfile(
    val authority: AuthAuthority,
    val username: String,
    val uuid: UUID,
    val rawJson: String,
)

data class HybridLookupResult(
    val elyProfile: RemoteGameProfile?,
    val mojangProfile: RemoteGameProfile?,
)

data class HybridPreLoginDecision(
    val authority: AuthAuthority?,
    val shouldForceOnline: Boolean,
    val expectedProfile: RemoteGameProfile? = null,
    val allowedAuthorities: Set<AuthAuthority> = emptySet(),
)

data class PendingPremiumLogin(
    val username: String,
    val authority: AuthAuthority?,
    val expectedUuid: UUID?,
    val clientUuid: UUID?,
    val allowedAuthorities: Set<AuthAuthority>,
    val expiresAtEpochSeconds: Long,
    val verifiedUuid: UUID? = null,
)

data class UpstreamHttpResponse(
    val statusCode: Int,
    val contentType: String,
    val body: ByteArray,
) {
    fun bodyAsString(): String = body.toString(StandardCharsets.UTF_8)
}

interface HybridAuthUpstream {
    fun lookupElyProfile(username: String): RemoteGameProfile?
    fun lookupMojangProfile(username: String): RemoteGameProfile?
    fun fetchElyMetadata(): UpstreamHttpResponse
    fun verifyHasJoined(
        authority: AuthAuthority,
        username: String,
        serverId: String?,
        ip: String?,
    ): UpstreamHttpResponse

    fun fetchProfile(
        authority: AuthAuthority,
        uuidWithoutDashes: String,
        rawQuery: String?,
    ): UpstreamHttpResponse

    fun submitJoin(
        authority: AuthAuthority,
        body: ByteArray,
        contentType: String,
    ): UpstreamHttpResponse
}

class HttpHybridAuthUpstream(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) : HybridAuthUpstream {
    override fun lookupElyProfile(username: String): RemoteGameProfile? {
        val response = send(
            HttpRequest.newBuilder(URI.create("https://authserver.ely.by/api/profiles/minecraft"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("[\"$username\"]"))
                .build(),
        )
        if (response.statusCode != 200) {
            return null
        }
        val body = response.bodyAsString().trim()
        val firstObject = JsonFieldReader.readFirstJsonObjectFromArray(body) ?: return null
        return JsonFieldReader.readProfile(firstObject, AuthAuthority.ELY)
    }

    override fun lookupMojangProfile(username: String): RemoteGameProfile? {
        val response = send(
            HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/$username"))
                .GET()
                .build(),
        )
        if (response.statusCode != 200) {
            return null
        }
        return JsonFieldReader.readProfile(response.bodyAsString(), AuthAuthority.MOJANG)
    }

    override fun fetchElyMetadata(): UpstreamHttpResponse {
        return send(
            HttpRequest.newBuilder(URI.create("https://account.ely.by/api/authlib-injector"))
                .GET()
                .build(),
        )
    }

    override fun verifyHasJoined(
        authority: AuthAuthority,
        username: String,
        serverId: String?,
        ip: String?,
    ): UpstreamHttpResponse {
        val query = buildString {
            append("username=").append(urlEncode(username))
            if (!serverId.isNullOrBlank()) {
                append("&serverId=").append(urlEncode(serverId))
            }
            if (!ip.isNullOrBlank()) {
                append("&ip=").append(urlEncode(ip))
            }
        }
        val target = when (authority) {
            AuthAuthority.ELY -> "https://authserver.ely.by/session/hasJoined?$query"
            AuthAuthority.MOJANG -> "https://sessionserver.mojang.com/session/minecraft/hasJoined?$query"
            else -> error("Unsupported authority for hasJoined: $authority")
        }
        return send(
            HttpRequest.newBuilder(URI.create(target))
                .GET()
                .build(),
        )
    }

    override fun fetchProfile(
        authority: AuthAuthority,
        uuidWithoutDashes: String,
        rawQuery: String?,
    ): UpstreamHttpResponse {
        val suffix = rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        val target = when (authority) {
            AuthAuthority.ELY -> "https://authserver.ely.by/session/profile/$uuidWithoutDashes$suffix"
            AuthAuthority.MOJANG -> "https://sessionserver.mojang.com/session/minecraft/profile/$uuidWithoutDashes$suffix"
            else -> error("Unsupported authority for profile fetch: $authority")
        }
        return send(
            HttpRequest.newBuilder(URI.create(target))
                .GET()
                .build(),
        )
    }

    override fun submitJoin(
        authority: AuthAuthority,
        body: ByteArray,
        contentType: String,
    ): UpstreamHttpResponse {
        val target = when (authority) {
            AuthAuthority.ELY -> "https://authserver.ely.by/session/join"
            AuthAuthority.MOJANG -> "https://sessionserver.mojang.com/session/minecraft/join"
            else -> error("Unsupported authority for join: $authority")
        }
        return send(
            HttpRequest.newBuilder(URI.create(target))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
        )
    }

    private fun send(request: HttpRequest): UpstreamHttpResponse {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        return UpstreamHttpResponse(
            statusCode = response.statusCode(),
            contentType = response.headers().firstValue("Content-Type").orElse("application/json; charset=utf-8"),
            body = response.body(),
        )
    }

    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
}

class HybridProfileResolver(
    private val upstream: HybridAuthUpstream,
) {
    fun lookup(username: String): HybridLookupResult {
        return HybridLookupResult(
            elyProfile = upstream.lookupElyProfile(username),
            mojangProfile = upstream.lookupMojangProfile(username),
        )
    }

    fun resolvePreLogin(username: String, clientUuid: UUID?): HybridPreLoginDecision {
        val expectedOfflineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:$username").toByteArray(StandardCharsets.UTF_8))
        if (clientUuid != null && clientUuid == expectedOfflineUuid) {
            return HybridPreLoginDecision(
                authority = AuthAuthority.OFFLINE,
                shouldForceOnline = false,
            )
        }

        val lookup = lookup(username)
        val availableAuthorities = buildSet {
            if (lookup.elyProfile != null) add(AuthAuthority.ELY)
            if (lookup.mojangProfile != null) add(AuthAuthority.MOJANG)
        }
        if (availableAuthorities.isEmpty()) {
            return HybridPreLoginDecision(
                authority = AuthAuthority.OFFLINE,
                shouldForceOnline = false,
            )
        }

        val exactMatch = when (clientUuid) {
            lookup.elyProfile?.uuid -> lookup.elyProfile
            lookup.mojangProfile?.uuid -> lookup.mojangProfile
            else -> null
        }
        if (exactMatch != null) {
            return HybridPreLoginDecision(
                authority = exactMatch.authority,
                shouldForceOnline = true,
                expectedProfile = exactMatch,
                allowedAuthorities = setOf(exactMatch.authority),
            )
        }

        // Some cracked launchers send a random UUIDv4 instead of the canonical
        // OfflinePlayer UUID. If it does not match a premium identity exactly,
        // we must not send the login into online verification.
        if (clientUuid != null) {
            return HybridPreLoginDecision(
                authority = AuthAuthority.OFFLINE,
                shouldForceOnline = false,
            )
        }

        val singleProfile = lookup.elyProfile ?: lookup.mojangProfile
        if (availableAuthorities.size == 1 && singleProfile != null) {
            return HybridPreLoginDecision(
                authority = singleProfile.authority,
                shouldForceOnline = true,
                expectedProfile = singleProfile,
                allowedAuthorities = setOf(singleProfile.authority),
            )
        }

        return HybridPreLoginDecision(
            authority = null,
            shouldForceOnline = true,
            expectedProfile = null,
            allowedAuthorities = availableAuthorities,
        )
    }
}

class PendingPremiumLoginStore(
    private val ttlSeconds: Long = 30,
) {
    private val pendingByUsername = ConcurrentHashMap<String, PendingPremiumLogin>()
    private val authorityByUuid = ConcurrentHashMap<String, AuthAuthority>()

    fun put(
        username: String,
        authority: AuthAuthority?,
        expectedUuid: UUID?,
        clientUuid: UUID?,
        allowedAuthorities: Set<AuthAuthority>,
        now: Instant = Instant.now(),
    ) {
        purgeExpired(now)
        pendingByUsername[normalizeUsername(username)] = PendingPremiumLogin(
            username = username,
            authority = authority,
            expectedUuid = expectedUuid,
            clientUuid = clientUuid,
            allowedAuthorities = allowedAuthorities,
            expiresAtEpochSeconds = now.plusSeconds(ttlSeconds).epochSecond,
        )
    }

    fun get(username: String, now: Instant = Instant.now()): PendingPremiumLogin? {
        purgeExpired(now)
        return pendingByUsername[normalizeUsername(username)]
    }

    fun markVerified(username: String, authority: AuthAuthority, verifiedUuid: UUID, now: Instant = Instant.now()): PendingPremiumLogin? {
        purgeExpired(now)
        val key = normalizeUsername(username)
        val current = pendingByUsername[key] ?: return null
        val updated = current.copy(
            authority = authority,
            verifiedUuid = verifiedUuid,
            expiresAtEpochSeconds = now.plusSeconds(ttlSeconds).epochSecond,
        )
        pendingByUsername[key] = updated
        authorityByUuid[normalizeUuid(verifiedUuid)] = authority
        return updated
    }

    fun findAuthorityByUuid(uuid: String, now: Instant = Instant.now()): AuthAuthority? {
        purgeExpired(now)
        return authorityByUuid[normalizeUuid(uuid)]
    }

    fun clear(username: String) {
        pendingByUsername.remove(normalizeUsername(username))
    }

    private fun purgeExpired(now: Instant) {
        val epoch = now.epochSecond
        val expired = pendingByUsername.entries
            .filter { it.value.expiresAtEpochSeconds <= epoch }
            .map { it.key }
        expired.forEach { key ->
            val removed = pendingByUsername.remove(key) ?: return@forEach
            removed.verifiedUuid?.let { authorityByUuid.remove(normalizeUuid(it)) }
        }
    }

    private fun normalizeUsername(username: String): String = username.lowercase()

    private fun normalizeUuid(uuid: UUID): String = normalizeUuid(uuid.toString())

    private fun normalizeUuid(uuid: String): String = uuid.replace("-", "").lowercase()
}

class HybridSessionVerifier(
    private val upstream: HybridAuthUpstream,
    private val pendingStore: PendingPremiumLoginStore,
) {
    fun fetchMetadataResponse(): UpstreamHttpResponse {
        val upstreamResponse = upstream.fetchElyMetadata()
        val patched = upstreamResponse.bodyAsString()
            .replace("\"feature.no_mojang_namespace\":true", "\"feature.no_mojang_namespace\":false")
            .replace("\"serverName\":\"Ely.by\"", "\"serverName\":\"Ely4Everyone Hybrid\"")
            .replace(
                "\"implementationName\":\"Account Ely.by adapter for the authlib-injector library\"",
                "\"implementationName\":\"Ely4Everyone hybrid auth-host for authlib-injector\"",
            )
        return UpstreamHttpResponse(
            statusCode = upstreamResponse.statusCode,
            contentType = upstreamResponse.contentType,
            body = patched.toByteArray(StandardCharsets.UTF_8),
        )
    }

    fun verifyHasJoined(
        username: String,
        serverId: String?,
        ip: String?,
        now: Instant = Instant.now(),
    ): UpstreamHttpResponse {
        val pending = pendingStore.get(username, now)
        val orderedAuthorities = selectAuthorities(pending)
        var lastFailure: UpstreamHttpResponse? = null

        for (authority in orderedAuthorities) {
            val response = upstream.verifyHasJoined(authority, username, serverId, ip)
            if (response.statusCode == 200) {
                val profile = JsonFieldReader.readProfile(response.bodyAsString(), authority)
                if (profile != null) {
                    pendingStore.markVerified(username, authority, profile.uuid, now)
                }
                return response
            }
            lastFailure = response
        }

        return lastFailure ?: UpstreamHttpResponse(
            statusCode = 204,
            contentType = "application/json; charset=utf-8",
            body = ByteArray(0),
        )
    }

    fun fetchProfile(
        uuidWithoutDashes: String,
        rawQuery: String?,
        now: Instant = Instant.now(),
    ): UpstreamHttpResponse {
        val pinnedAuthority = pendingStore.findAuthorityByUuid(uuidWithoutDashes, now)
        val orderedAuthorities = if (pinnedAuthority != null) {
            listOf(pinnedAuthority)
        } else {
            listOf(AuthAuthority.ELY, AuthAuthority.MOJANG)
        }

        var lastFailure: UpstreamHttpResponse? = null
        for (authority in orderedAuthorities) {
            val response = upstream.fetchProfile(authority, uuidWithoutDashes, rawQuery)
            if (response.statusCode == 200) {
                return response
            }
            lastFailure = response
        }
        return lastFailure ?: UpstreamHttpResponse(
            statusCode = 404,
            contentType = "application/json; charset=utf-8",
            body = ByteArray(0),
        )
    }

    fun submitJoin(body: ByteArray, contentType: String): UpstreamHttpResponse {
        val selectedProfile = JsonFieldReader.readString(body.toString(StandardCharsets.UTF_8), "selectedProfile")
        val pinnedAuthority = selectedProfile?.let { pendingStore.findAuthorityByUuid(it) }
        val orderedAuthorities = if (pinnedAuthority != null) {
            listOf(pinnedAuthority)
        } else {
            listOf(AuthAuthority.ELY, AuthAuthority.MOJANG)
        }

        var lastFailure: UpstreamHttpResponse? = null
        for (authority in orderedAuthorities) {
            val response = upstream.submitJoin(authority, body, contentType)
            if (response.statusCode in 200..299 || response.statusCode == 204) {
                return response
            }
            lastFailure = response
        }
        return lastFailure ?: UpstreamHttpResponse(
            statusCode = 403,
            contentType = "application/json; charset=utf-8",
            body = ByteArray(0),
        )
    }

    private fun selectAuthorities(pending: PendingPremiumLogin?): List<AuthAuthority> {
        if (pending?.authority != null) {
            return listOf(pending.authority)
        }
        val allowed = pending?.allowedAuthorities?.ifEmpty { setOf(AuthAuthority.ELY, AuthAuthority.MOJANG) }
            ?: setOf(AuthAuthority.ELY, AuthAuthority.MOJANG)
        return listOf(AuthAuthority.ELY, AuthAuthority.MOJANG).filter { it in allowed }
    }
}

fun extractRemoteAddress(value: InetAddress?): String? = value?.hostAddress
