package dev.ely4everyone.velocity.auth.http

import dev.ely4everyone.shared.host.ElyAccountInfo
import dev.ely4everyone.shared.host.ElyOAuthGateway
import dev.ely4everyone.shared.host.ElyTexturesProfile
import dev.ely4everyone.shared.host.ElyTokenResponse
import dev.ely4everyone.shared.host.EmbeddedAuthHostConfig
import dev.ely4everyone.shared.host.EmbeddedAuthHttpServer
import dev.ely4everyone.shared.oauth.LoopbackLogin
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LoopbackLoginFlowTest {
    private val loopback = "http://127.0.0.1:54321/callback"

    // Redirects must be inspected, never followed.
    private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private var server: EmbeddedAuthHttpServer? = null

    @AfterTest
    fun stopServer() {
        server?.stop()
    }

    @Test
    fun `loopback login hands the tokens only to the client holding the verifier`() {
        val base = startServer()
        val state = LoopbackLogin.newSecret()
        val verifier = LoopbackLogin.newSecret()

        val start = get("$base/api/v1/auth/start?" + form("state" to state, "code_challenge" to LoopbackLogin.challengeFor(verifier), "code_challenge_method" to "S256", "client_redirect_uri" to loopback))
        assertEquals(302, start.statusCode())
        assertTrue(location(start).startsWith("https://ely.example/authorize?state="))

        // A known state cannot be started again, so it cannot be taken over.
        val restart = get("$base/api/v1/auth/start?" + form("state" to state, "code_challenge" to LoopbackLogin.challengeFor(LoopbackLogin.newSecret()), "client_redirect_uri" to loopback))
        assertEquals(409, restart.statusCode())

        val callback = get("$base/oauth/callback?" + form("state" to state, "code" to FakeElyOAuth.GOOD_CODE))
        assertEquals(302, callback.statusCode())
        val sentBack = URI(location(callback))
        assertEquals(loopback, "${sentBack.scheme}://${sentBack.authority}${sentBack.path}")
        val query = parse(sentBack.rawQuery)
        assertEquals(state, query["state"])
        val handoffCode = assertNotNull(query["code"])
        assertFalse(location(callback).contains("ely-access-token"), "tokens must not travel through the browser")

        // Replaying the Ely callback neither mints a second session nor breaks the first.
        assertEquals(400, get("$base/oauth/callback?" + form("state" to state, "code" to FakeElyOAuth.GOOD_CODE)).statusCode())

        // Polling, the old result channel, never hands out tokens for a loopback login.
        val poll = get("$base/api/v1/auth/poll?" + form("state" to state))
        assertFalse(poll.body().contains("auth_session_token"))

        assertEquals(400, exchange(base, state, handoffCode, LoopbackLogin.newSecret()).statusCode())
        assertEquals(400, exchange(base, state, LoopbackLogin.newSecret(), verifier).statusCode())
        assertEquals(405, get("$base/api/v1/auth/exchange?" + form("state" to state, "code" to handoffCode, "code_verifier" to verifier)).statusCode())

        val redeemed = exchange(base, state, handoffCode, verifier)
        assertEquals(200, redeemed.statusCode())
        assertTrue(redeemed.body().contains("status=completed"))
        assertTrue(redeemed.body().contains("ely_access_token=ely-access-token"))
        assertTrue(redeemed.body().contains("username=Cokeef"))

        // One-time: the same code and verifier do not work twice.
        assertEquals(400, exchange(base, state, handoffCode, verifier).statusCode())
    }

    @Test
    fun `a crafted login link gives its author nothing`() {
        val base = startServer()
        // The attacker controls the link, so the attacker picks the state and the verifier.
        val state = LoopbackLogin.newSecret()
        val attackerVerifier = LoopbackLogin.newSecret()
        get("$base/api/v1/auth/start?" + form("state" to state, "code_challenge" to LoopbackLogin.challengeFor(attackerVerifier), "client_redirect_uri" to loopback))

        // The victim approves: the code goes to the victim's own 127.0.0.1, not to the attacker.
        assertEquals(302, get("$base/oauth/callback?" + form("state" to state, "code" to FakeElyOAuth.GOOD_CODE)).statusCode())

        assertFalse(get("$base/api/v1/auth/poll?" + form("state" to state)).body().contains("auth_session_token"))
        assertEquals(400, exchange(base, state, LoopbackLogin.newSecret(), attackerVerifier).statusCode())
    }

    @Test
    fun `start refuses callback addresses off the player's machine`() {
        val base = startServer()
        listOf(
            "http://evil.example:54321/callback",
            "http://localhost:54321/callback",
            "https://127.0.0.1:54321/callback",
            "http://127.0.0.1:54321/callback?next=x",
        ).forEach { redirect ->
            val response = get("$base/api/v1/auth/start?" + form("state" to LoopbackLogin.newSecret(), "code_challenge" to LoopbackLogin.challengeFor(LoopbackLogin.newSecret()), "client_redirect_uri" to redirect))
            assertEquals(400, response.statusCode(), "accepted $redirect")
        }
    }

    @Test
    fun `an ely error is sent back to the mod's loopback address`() {
        val base = startServer()
        val state = LoopbackLogin.newSecret()
        get("$base/api/v1/auth/start?" + form("state" to state, "code_challenge" to LoopbackLogin.challengeFor(LoopbackLogin.newSecret()), "client_redirect_uri" to loopback))

        val callback = get("$base/oauth/callback?" + form("state" to state, "error" to "access_denied"))
        assertEquals(302, callback.statusCode())
        assertEquals("access_denied", parse(URI(location(callback)).rawQuery)["error"])
    }

    @Test
    fun `old polling mods are told to update by default`() {
        val base = startServer(allowLegacyPollLogin = false)
        val state = "0b1c8f5e-1f9a-4c55-a1d2-3e4f5a6b7c8d"

        val start = get("$base/api/v1/auth/start?" + form("state" to state, "client_redirect_uri" to loopback))
        assertEquals(400, start.statusCode())
        assertTrue(start.body().contains("href=\"https://modrinth.com/mod/ely4everyone\""))

        // What 0.1.0-alpha shows in game: one line, coloured with § codes, with the download address.
        val poll = get("$base/api/v1/auth/poll?" + form("state" to state))
        assertTrue(poll.body().startsWith("status=failed\nerror=§eмод §eустарел."))
        assertTrue(poll.body().contains("§bmodrinth.com/mod/ely4everyone"))
        assertEquals(2, poll.body().lines().size)
    }

    @Test
    fun `refused old-mod logins do not use up login capacity`() {
        val base = startServer(allowLegacyPollLogin = false)
        repeat(50) { index ->
            assertEquals(400, get("$base/api/v1/auth/start?" + form("state" to "spam-$index")).statusCode())
        }
        // A spammed state is not reserved either, so a real login with it still starts.
        val state = LoopbackLogin.newSecret()
        val start = get("$base/api/v1/auth/start?" + form("state" to state, "code_challenge" to LoopbackLogin.challengeFor(LoopbackLogin.newSecret()), "client_redirect_uri" to loopback))
        assertEquals(302, start.statusCode())
    }

    @Test
    fun `session tokens are refused in the url`() {
        val base = startServer(allowLegacyPollLogin = false)

        val refresh = http.send(
            HttpRequest.newBuilder(URI.create("$base/api/v1/auth/refresh?session_token=leaked")).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(400, refresh.statusCode())
        assertTrue(refresh.body().contains("missing_session_token"))

        val ticket = get("$base/api/v1/auth/issue-ticket?" + form("session_token" to "leaked", "nonce" to "n"))
        assertEquals(400, ticket.statusCode())
        assertTrue(ticket.body().contains("missing_session_token"))
    }

    @Test
    fun `the migration flag keeps old mods working, with a one-shot poll`() {
        val base = startServer(allowLegacyPollLogin = true)
        val state = "0b1c8f5e-1f9a-4c55-a1d2-3e4f5a6b7c8d"

        assertEquals(302, get("$base/api/v1/auth/start?" + form("state" to state)).statusCode())
        assertEquals(200, get("$base/oauth/callback?" + form("state" to state, "code" to FakeElyOAuth.GOOD_CODE)).statusCode())

        val first = get("$base/api/v1/auth/poll?" + form("state" to state))
        assertTrue(first.body().contains("auth_session_token="))
        val second = get("$base/api/v1/auth/poll?" + form("state" to state))
        assertEquals(404, second.statusCode())
        assertFalse(second.body().contains("auth_session_token"))
    }

    private fun startServer(allowLegacyPollLogin: Boolean = false): String {
        val authServer = EmbeddedAuthHttpServer(
            config = EmbeddedAuthHostConfig(
                hostId = "test-host",
                displayName = "Test Host",
                trustedIssuer = "test",
                expectedAudience = "test-audience",
                ticketSigningKey = "test-key",
                bindHost = "127.0.0.1",
                bindPort = 0,
                publicBaseUrl = "http://127.0.0.1",
                clientId = "test-client",
                clientSecret = "test-secret",
                allowLegacyPollLogin = allowLegacyPollLogin,
            ),
            logger = LoggerFactory.getLogger("LoopbackLoginFlowTest"),
            dataDirectory = Files.createTempDirectory("ely4everyone-loopback-test"),
            oauthClient = FakeElyOAuth(),
        ).also { it.start() }
        server = authServer
        return assertNotNull(authServer.localBaseUrl())
    }

    private fun get(url: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun exchange(base: String, state: String, code: String, verifier: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("$base/api/v1/auth/exchange"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form("state" to state, "code" to code, "code_verifier" to verifier)))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun location(response: HttpResponse<String>): String =
        assertNotNull(response.headers().firstValue("Location").orElse(null))

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (key, value) -> key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8) }

    private fun parse(rawQuery: String?): Map<String, String> =
        rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate { part ->
            val (key, value) = part.split('=', limit = 2)
            key to URLDecoder.decode(value, StandardCharsets.UTF_8)
        }
}

private class FakeElyOAuth : ElyOAuthGateway {
    companion object {
        const val GOOD_CODE: String = "ely-code"
    }

    override fun buildAuthorizationUri(state: String): URI =
        URI.create("https://ely.example/authorize?state=" + URLEncoder.encode(state, StandardCharsets.UTF_8))

    override fun exchangeCode(code: String): ElyTokenResponse {
        check(code == GOOD_CODE) { "Failed to exchange Ely authorization code: invalid_grant" }
        return ElyTokenResponse(accessToken = "ely-access-token", expiresInSeconds = 3600, refreshToken = "ely-refresh-token")
    }

    override fun refreshToken(refreshToken: String): ElyTokenResponse =
        ElyTokenResponse(accessToken = "ely-access-token-2", expiresInSeconds = 3600, refreshToken = refreshToken)

    override fun fetchAccountInfo(accessToken: String): ElyAccountInfo =
        ElyAccountInfo(uuid = "11111111-1111-1111-1111-111111111111", username = "Cokeef")

    override fun fetchTexturesProfile(uuid: String): ElyTexturesProfile = ElyTexturesProfile(emptyList())
}
