package dev.ely4everyone.mod.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real login against account.ely.by, for manual checks only. Skipped unless the property
 * `ely4everyone.liveLogin` points to a file: the confirm link is written there, a human opens it
 * and allows the login. Tokens are never printed.
 *
 * ./gradlew :mod:test --tests '*ElyDirectLoginLiveTest*' -Dely4everyone.liveLogin=<file>
 */
class ElyDirectLoginLiveTest {
    @Test
    fun `device code login against the real Ely`() {
        val outFile = System.getProperty("ely4everyone.liveLogin")?.takeIf { it.isNotBlank() }?.let(::File) ?: return

        val code = ElyDirectLogin.requestDeviceCode()
        outFile.writeText("${code.verificationUriComplete}\n")
        println("[live] confirm at ${code.verificationUriComplete} (code ${code.userCode})")

        val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000
        var interval = code.intervalSeconds * 1000
        var tokens: ElyTokens? = null
        while (tokens == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(interval)
            when (val poll = ElyDirectLogin.pollDeviceToken(code.deviceCode)) {
                DeviceTokenPoll.Pending -> Unit
                DeviceTokenPoll.SlowDown -> interval += 5_000
                is DeviceTokenPoll.Failed -> error("Ely refused: ${poll.error}")
                is DeviceTokenPoll.Granted -> tokens = poll.tokens
            }
        }
        val granted = tokens ?: error("nobody confirmed the code in time")
        println("[live] token ok, refresh token present: ${granted.refreshToken != null}, expires_in=${granted.expiresInSeconds}")

        val profile = ElyDirectLogin.fetchProfile(granted.accessToken)
        println("[live] profile ${profile.username} ${profile.uuid}, textures=${profile.texturesValue != null}, signed=${profile.texturesSignature != null}")
        assertTrue(profile.username.isNotBlank())

        val refreshed = ElyDirectLogin.refresh(granted.refreshToken ?: error("no refresh token"))
        println("[live] refresh ok, new token differs: ${refreshed.accessToken != granted.accessToken}")
        outFile.appendText("OK ${profile.username}\n")
    }
}
