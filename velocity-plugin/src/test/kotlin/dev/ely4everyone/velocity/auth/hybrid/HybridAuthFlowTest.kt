package dev.ely4everyone.velocity.auth.hybrid

import dev.ely4everyone.shared.host.AuthAuthority
import dev.ely4everyone.shared.host.EmbeddedAuthHostConfig
import dev.ely4everyone.shared.host.EmbeddedAuthHttpServer
import dev.ely4everyone.shared.host.HybridAuthUpstream
import dev.ely4everyone.shared.host.HybridProfileResolver
import dev.ely4everyone.shared.host.HybridSessionVerifier
import dev.ely4everyone.shared.host.PendingPremiumLoginStore
import dev.ely4everyone.shared.host.RemoteGameProfile
import dev.ely4everyone.shared.host.UpstreamHttpResponse
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HybridAuthFlowTest {
    private val elyProfile = RemoteGameProfile(
        authority = AuthAuthority.ELY,
        username = "SameNick",
        uuid = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        rawJson = """{"name":"SameNick","id":"11111111111111111111111111111111"}""",
    )
    private val mojangProfile = RemoteGameProfile(
        authority = AuthAuthority.MOJANG,
        username = "SameNick",
        uuid = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        rawJson = """{"name":"SameNick","id":"22222222222222222222222222222222"}""",
    )

    @Test
    fun `resolver routes offline uuid to offline flow`() {
        val resolver = HybridProfileResolver(FakeHybridAuthUpstream())
        val username = "PirateUser"
        val offlineUuid = UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray(StandardCharsets.UTF_8))

        val decision = resolver.resolvePreLogin(username, offlineUuid)

        assertEquals(AuthAuthority.OFFLINE, decision.authority)
        assertEquals(false, decision.shouldForceOnline)
    }

    @Test
    fun `resolver no longer treats dotted usernames as bedrock`() {
        val resolver = HybridProfileResolver(FakeHybridAuthUpstream())

        val decision = resolver.resolvePreLogin(".BedrockLikeName", null)

        assertEquals(AuthAuthority.OFFLINE, decision.authority)
        assertEquals(false, decision.shouldForceOnline)
    }

    @Test
    fun `resolver chooses ely or mojang when collision matches client uuid`() {
        val resolver = HybridProfileResolver(
            FakeHybridAuthUpstream(
                elyProfiles = mapOf("SameNick" to elyProfile),
                mojangProfiles = mapOf("SameNick" to mojangProfile),
            ),
        )

        val elyDecision = resolver.resolvePreLogin("SameNick", elyProfile.uuid)
        val mojangDecision = resolver.resolvePreLogin("SameNick", mojangProfile.uuid)
        val ambiguousDecision = resolver.resolvePreLogin("SameNick", null)

        assertEquals(AuthAuthority.ELY, elyDecision.authority)
        assertEquals(AuthAuthority.MOJANG, mojangDecision.authority)
        assertNull(ambiguousDecision.authority)
        assertEquals(setOf(AuthAuthority.ELY, AuthAuthority.MOJANG), ambiguousDecision.allowedAuthorities)
        assertTrue(ambiguousDecision.shouldForceOnline)
    }

    @Test
    fun `resolver routes random uuid with premium nickname to offline flow`() {
        val resolver = HybridProfileResolver(
            FakeHybridAuthUpstream(
                elyProfiles = mapOf("SameNick" to elyProfile),
                mojangProfiles = mapOf("SameNick" to mojangProfile),
            ),
        )

        val decision = resolver.resolvePreLogin("SameNick", UUID.fromString("e2960f95-b092-4ca9-b7f3-eebc3cbb0ea9"))

        assertEquals(AuthAuthority.OFFLINE, decision.authority)
        assertEquals(false, decision.shouldForceOnline)
    }

    @Test
    fun `limboauth prefers pinned mojang profile over local ely session`() {
        val upstream = FakeHybridAuthUpstream(
            elyProfiles = mapOf("SameNick" to elyProfile),
            mojangProfiles = mapOf("SameNick" to mojangProfile),
        )
        val pendingStore = PendingPremiumLoginStore(ttlSeconds = 30)
        val authServer = createServer(upstream, pendingStore)
        try {
            authServer.registerPendingLogin(
                username = "SameNick",
                authority = AuthAuthority.MOJANG,
                expectedUuid = mojangProfile.uuid,
                clientUuid = mojangProfile.uuid,
                allowedAuthorities = setOf(AuthAuthority.MOJANG),
            )

            val response = httpClient().send(
                HttpRequest.newBuilder(URI.create("${authServer.localBaseUrl()}/api/v1/auth/limboauth/SameNick"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("22222222222222222222222222222222"))
        } finally {
            authServer.stop()
        }
    }

    @Test
    fun `hasJoined falls back to mojang when authority is unresolved`() {
        val upstream = FakeHybridAuthUpstream(
            elyProfiles = mapOf("SameNick" to elyProfile),
            mojangProfiles = mapOf("SameNick" to mojangProfile),
            hasJoinedResponses = mapOf(
                AuthAuthority.ELY to UpstreamHttpResponse(204, "application/json; charset=utf-8", ByteArray(0)),
                AuthAuthority.MOJANG to UpstreamHttpResponse(200, "application/json; charset=utf-8", mojangProfile.rawJson.toByteArray(StandardCharsets.UTF_8)),
            ),
        )
        val pendingStore = PendingPremiumLoginStore(ttlSeconds = 30)
        val authServer = createServer(upstream, pendingStore)
        try {
            authServer.registerPendingLogin(
                username = "SameNick",
                authority = null,
                expectedUuid = null,
                clientUuid = null,
                allowedAuthorities = setOf(AuthAuthority.ELY, AuthAuthority.MOJANG),
            )

            val response = httpClient().send(
                HttpRequest.newBuilder(
                    URI.create("${authServer.localBaseUrl()}/sessionserver/session/minecraft/hasJoined?username=SameNick&serverId=test"),
                ).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("22222222222222222222222222222222"))
            assertEquals(AuthAuthority.MOJANG, pendingStore.findAuthorityByUuid("22222222222222222222222222222222"))
        } finally {
            authServer.stop()
        }
    }

    @Test
    fun `profile route uses verified uuid authority`() {
        val upstream = FakeHybridAuthUpstream(
            profileResponses = mapOf(
                AuthAuthority.MOJANG to UpstreamHttpResponse(
                    200,
                    "application/json; charset=utf-8",
                    """{"name":"SameNick","id":"22222222222222222222222222222222","properties":[]}"""
                        .toByteArray(StandardCharsets.UTF_8),
                ),
            ),
        )
        val pendingStore = PendingPremiumLoginStore(ttlSeconds = 30)
        pendingStore.put(
            username = "SameNick",
            authority = AuthAuthority.MOJANG,
            expectedUuid = mojangProfile.uuid,
            clientUuid = mojangProfile.uuid,
            allowedAuthorities = setOf(AuthAuthority.MOJANG),
            now = Instant.now(),
        )
        pendingStore.markVerified("SameNick", AuthAuthority.MOJANG, mojangProfile.uuid)

        val authServer = createServer(upstream, pendingStore)
        try {
            val response = httpClient().send(
                HttpRequest.newBuilder(
                    URI.create("${authServer.localBaseUrl()}/sessionserver/session/minecraft/profile/22222222222222222222222222222222?unsigned=true"),
                ).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("SameNick"))
        } finally {
            authServer.stop()
        }
    }

    private fun createServer(
        upstream: FakeHybridAuthUpstream,
        pendingStore: PendingPremiumLoginStore,
    ): EmbeddedAuthHttpServer {
        val tempDir = Files.createTempDirectory("ely4everyone-hybrid-test")
        val resolver = HybridProfileResolver(upstream)
        val verifier = HybridSessionVerifier(upstream, pendingStore)
        return EmbeddedAuthHttpServer(
            config = EmbeddedAuthHostConfig(
                hostId = "test-host",
                displayName = "Test Host",
                trustedIssuer = "test",
                expectedAudience = "test-audience",
                ticketSigningKey = "test-key",
                bindHost = "127.0.0.1",
                bindPort = 0,
                publicBaseUrl = "http://127.0.0.1",
                enabled = true,
            ),
            logger = LoggerFactory.getLogger("HybridAuthFlowTest"),
            dataDirectory = tempDir,
            pendingPremiumLoginStore = pendingStore,
            profileResolver = resolver,
            sessionVerifier = verifier,
        ).also { it.start() }
    }

    private fun httpClient(): HttpClient = HttpClient.newHttpClient()
}

private class FakeHybridAuthUpstream(
    private val elyProfiles: Map<String, RemoteGameProfile> = emptyMap(),
    private val mojangProfiles: Map<String, RemoteGameProfile> = emptyMap(),
    private val hasJoinedResponses: Map<AuthAuthority, UpstreamHttpResponse> = emptyMap(),
    private val profileResponses: Map<AuthAuthority, UpstreamHttpResponse> = emptyMap(),
) : HybridAuthUpstream {
    override fun lookupElyProfile(username: String): RemoteGameProfile? = elyProfiles[username]

    override fun lookupMojangProfile(username: String): RemoteGameProfile? = mojangProfiles[username]

    override fun fetchElyMetadata(): UpstreamHttpResponse {
        return UpstreamHttpResponse(
            200,
            "application/json; charset=utf-8",
            """{"meta":{"serverName":"Ely.by","implementationName":"Account Ely.by adapter for the authlib-injector library","feature.no_mojang_namespace":true}}"""
                .toByteArray(StandardCharsets.UTF_8),
        )
    }

    override fun verifyHasJoined(
        authority: AuthAuthority,
        username: String,
        serverId: String?,
        ip: String?,
    ): UpstreamHttpResponse {
        return hasJoinedResponses[authority]
            ?: UpstreamHttpResponse(204, "application/json; charset=utf-8", ByteArray(0))
    }

    override fun fetchProfile(
        authority: AuthAuthority,
        uuidWithoutDashes: String,
        rawQuery: String?,
    ): UpstreamHttpResponse {
        return profileResponses[authority]
            ?: UpstreamHttpResponse(404, "application/json; charset=utf-8", ByteArray(0))
    }

    override fun submitJoin(
        authority: AuthAuthority,
        body: ByteArray,
        contentType: String,
    ): UpstreamHttpResponse {
        return UpstreamHttpResponse(204, contentType, ByteArray(0))
    }
}
