package dev.ely4everyone.shared.host

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches the upstream calls that are safe to reuse between logins:
 * username -> profile lookups (including "not found") and the authlib-injector metadata.
 *
 * Ely.by has no published rate limit, but runs fail2ban against "especially annoying clients".
 * Every proxied player, bot handshake and public batch lookup used to hit ely.by and Mojang
 * from the proxy IP without any reuse, which is exactly what gets an IP banned.
 *
 * Session calls (join / hasJoined / profile) are never cached: they are per-login by design.
 * Exceptions are not cached either, so a network blip is retried on the next request.
 */
class CachingHybridAuthUpstream(
    private val delegate: HybridAuthUpstream,
    private val profileTtl: Duration = Duration.ofMinutes(10),
    // Short negative TTL: a player who has just registered on Ely.by should not wait long.
    private val missingProfileTtl: Duration = Duration.ofMinutes(1),
    private val metadataTtl: Duration = Duration.ofHours(1),
    private val maxProfileEntries: Int = 10_000,
    private val clock: Clock = Clock.systemUTC(),
) : HybridAuthUpstream by delegate {
    private class Entry<T>(val value: T, val expiresAt: Instant)

    private val elyProfiles = ConcurrentHashMap<String, Entry<RemoteGameProfile?>>()
    private val mojangProfiles = ConcurrentHashMap<String, Entry<RemoteGameProfile?>>()

    @Volatile
    private var metadata: Entry<UpstreamHttpResponse>? = null

    override fun lookupElyProfile(username: String): RemoteGameProfile? =
        cachedLookup(elyProfiles, username) { delegate.lookupElyProfile(username) }

    override fun lookupMojangProfile(username: String): RemoteGameProfile? =
        cachedLookup(mojangProfiles, username) { delegate.lookupMojangProfile(username) }

    override fun fetchElyMetadata(): UpstreamHttpResponse {
        val now = clock.instant()
        metadata?.takeIf { it.expiresAt.isAfter(now) }?.let { return it.value }

        val response = delegate.fetchElyMetadata()
        // Only a successful document is worth keeping; errors are passed through as-is.
        if (response.statusCode == 200) {
            metadata = Entry(response, now.plus(metadataTtl))
        }
        return response
    }

    private fun cachedLookup(
        cache: ConcurrentHashMap<String, Entry<RemoteGameProfile?>>,
        username: String,
        load: () -> RemoteGameProfile?,
    ): RemoteGameProfile? {
        val key = username.lowercase()
        val now = clock.instant()
        cache[key]?.takeIf { it.expiresAt.isAfter(now) }?.let { return it.value }

        val profile = load()
        val ttl = if (profile != null) profileTtl else missingProfileTtl
        if (cache.size >= maxProfileEntries) {
            cache.entries.removeIf { !it.value.expiresAt.isAfter(now) }
            // Still full of live entries (flood of unique names): start over rather than grow unbounded.
            if (cache.size >= maxProfileEntries) {
                cache.clear()
            }
        }
        cache[key] = Entry(profile, now.plus(ttl))
        return profile
    }
}
