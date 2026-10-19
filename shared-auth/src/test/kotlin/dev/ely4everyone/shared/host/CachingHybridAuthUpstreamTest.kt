package dev.ely4everyone.shared.host

import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CachingHybridAuthUpstreamTest {
    private val profile = RemoteGameProfile(
        authority = AuthAuthority.ELY,
        username = "Cokeef",
        uuid = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        rawJson = """{"name":"Cokeef","id":"11111111111111111111111111111111"}""",
    )

    @Test
    fun `reuses a found profile until it expires, ignoring name case`() {
        val clock = MutableClock()
        val delegate = CountingUpstream(elyProfiles = mapOf("cokeef" to profile))
        val cache = CachingHybridAuthUpstream(delegate, profileTtl = Duration.ofMinutes(10), clock = clock)

        assertEquals(profile, cache.lookupElyProfile("Cokeef"))
        assertEquals(profile, cache.lookupElyProfile("COKEEF"))
        assertEquals(1, delegate.elyLookups)

        clock.advance(Duration.ofMinutes(11))
        cache.lookupElyProfile("Cokeef")
        assertEquals(2, delegate.elyLookups)
    }

    @Test
    fun `remembers missing profiles for the shorter negative ttl`() {
        val clock = MutableClock()
        val delegate = CountingUpstream()
        val cache = CachingHybridAuthUpstream(delegate, missingProfileTtl = Duration.ofMinutes(1), clock = clock)

        assertNull(cache.lookupMojangProfile("Nobody"))
        assertNull(cache.lookupMojangProfile("Nobody"))
        assertEquals(1, delegate.mojangLookups)

        clock.advance(Duration.ofSeconds(61))
        cache.lookupMojangProfile("Nobody")
        assertEquals(2, delegate.mojangLookups)
    }

    @Test
    fun `caches only successful metadata responses`() {
        val delegate = CountingUpstream(metadataStatus = 500)
        val cache = CachingHybridAuthUpstream(delegate, clock = MutableClock())

        cache.fetchElyMetadata()
        cache.fetchElyMetadata()
        assertEquals(2, delegate.metadataFetches)

        delegate.metadataStatus = 200
        cache.fetchElyMetadata()
        cache.fetchElyMetadata()
        assertEquals(3, delegate.metadataFetches)
    }

    @Test
    fun `stays bounded when flooded with unique names`() {
        val delegate = CountingUpstream()
        val cache = CachingHybridAuthUpstream(delegate, maxProfileEntries = 3, clock = MutableClock())

        repeat(10) { index -> cache.lookupElyProfile("Bot$index") }
        // The newest name survives the reset and is still served from cache.
        cache.lookupElyProfile("Bot9")
        assertEquals(10, delegate.elyLookups)
    }
}

private class MutableClock(private var now: Instant = Instant.parse("2026-10-18T00:00:00Z")) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneOffset = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?): Clock = this
}

private class CountingUpstream(
    private val elyProfiles: Map<String, RemoteGameProfile> = emptyMap(),
    var metadataStatus: Int = 200,
) : HybridAuthUpstream {
    var elyLookups = 0
    var mojangLookups = 0
    var metadataFetches = 0

    override fun lookupElyProfile(username: String): RemoteGameProfile? {
        elyLookups++
        return elyProfiles[username.lowercase()]
    }

    override fun lookupMojangProfile(username: String): RemoteGameProfile? {
        mojangLookups++
        return null
    }

    override fun fetchElyMetadata(): UpstreamHttpResponse {
        metadataFetches++
        return UpstreamHttpResponse(metadataStatus, "application/json", "{}".toByteArray(StandardCharsets.UTF_8))
    }

    override fun verifyHasJoined(authority: AuthAuthority, username: String, serverId: String?, ip: String?) =
        UpstreamHttpResponse(204, "application/json", ByteArray(0))

    override fun fetchProfile(authority: AuthAuthority, uuidWithoutDashes: String, rawQuery: String?) =
        UpstreamHttpResponse(404, "application/json", ByteArray(0))

    override fun submitJoin(authority: AuthAuthority, body: ByteArray, contentType: String) =
        UpstreamHttpResponse(204, contentType, ByteArray(0))
}
