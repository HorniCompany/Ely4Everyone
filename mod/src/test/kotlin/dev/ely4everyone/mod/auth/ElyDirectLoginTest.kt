package dev.ely4everyone.mod.auth

import dev.ely4everyone.mod.session.ClientSessionState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElyDirectLoginTest {
    @Test
    fun `device code response is parsed and the page link is forced to https`() {
        // Shape of a real answer from account.ely.by (2026-10-20): the link comes as plain http.
        val code = ElyDirectLogin.parseDeviceCode(
            """{"device_code":"dev-123","user_code":"ZKVQBGWK","verification_uri":"http://account.ely.by/code","interval":5,"expires_in":600}""",
        )

        assertEquals("dev-123", code.deviceCode)
        assertEquals("ZKVQBGWK", code.userCode)
        assertEquals("https://account.ely.by/code", code.verificationUri)
        assertEquals("https://account.ely.by/code?user_code=ZKVQBGWK", code.verificationUriComplete)
        assertEquals(5, code.intervalSeconds)
        assertEquals(600, code.expiresInSeconds)
    }

    @Test
    fun `device code falls back to safe defaults`() {
        val code = ElyDirectLogin.parseDeviceCode(
            """{"device_code":"d","user_code":"AB CD","verification_uri":"ftp://evil.example/code","interval":0}""",
        )

        assertEquals("https://account.ely.by/code", code.verificationUri)
        assertEquals("https://account.ely.by/code?user_code=AB+CD", code.verificationUriComplete)
        assertEquals(1, code.intervalSeconds)
        assertEquals(600, code.expiresInSeconds)
    }

    @Test
    fun `token poll understands every device code answer`() {
        assertEquals(DeviceTokenPoll.Pending, ElyDirectLogin.parseDeviceTokenPoll(400, """{"error":"authorization_pending"}"""))
        assertEquals(DeviceTokenPoll.SlowDown, ElyDirectLogin.parseDeviceTokenPoll(400, """{"error":"slow_down"}"""))
        assertIs<DeviceTokenPoll.Failed>(ElyDirectLogin.parseDeviceTokenPoll(400, """{"error":"access_denied"}"""))
        assertIs<DeviceTokenPoll.Failed>(ElyDirectLogin.parseDeviceTokenPoll(400, """{"error":"expired_token"}"""))
        assertIs<DeviceTokenPoll.Failed>(ElyDirectLogin.parseDeviceTokenPoll(401, """{"error":"invalid_client","message":"Client authentication failed"}"""))
        assertIs<DeviceTokenPoll.Failed>(ElyDirectLogin.parseDeviceTokenPoll(200, """{"token_type":"Bearer"}"""))

        val granted = ElyDirectLogin.parseDeviceTokenPoll(
            200,
            """{"token_type":"Bearer","access_token":"acc","refresh_token":"ref","expires_in":355021223}""",
        )
        assertIs<DeviceTokenPoll.Granted>(granted)
        assertEquals("acc", granted.tokens.accessToken)
        assertEquals("ref", granted.tokens.refreshToken)
        assertEquals(355021223, granted.tokens.expiresInSeconds)
    }

    @Test
    fun `direct and relay sessions are told apart`() {
        val now = Instant.parse("2026-10-21T00:00:00Z")
        val direct = ClientSessionState(
            relayBaseUrl = "",
            elyAccessToken = "acc",
            elyRefreshToken = "ref",
            authSessionExpiresAt = now.plusSeconds(3600),
            elyUsername = "Cokeef",
            elyUuid = "ed9f5370-0000-0000-0000-000000000000",
        )
        assertTrue(direct.isDirectLogin)
        assertTrue(direct.isSignedIn(now))
        assertFalse(direct.hasUsableAuthSession(now))

        val relay = direct.copy(relayBaseUrl = "https://horni.cc/auth/ely4everyone", authSessionToken = "session", elyRefreshToken = null)
        assertFalse(relay.isDirectLogin)
        assertTrue(relay.isSignedIn(now))

        assertFalse(direct.isSignedIn(now.plusSeconds(7200)))
        assertNull(ClientSessionState(relayBaseUrl = "").elyRefreshToken)
        assertFalse(ClientSessionState(relayBaseUrl = "").isSignedIn(now))
    }
}
