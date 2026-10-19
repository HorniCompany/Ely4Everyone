package dev.ely4everyone.mod.auth

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalAuthCallbackServerTest {
    private val http: HttpClient = HttpClient.newHttpClient()

    @Test
    fun `keeps only the first callback with the expected state`() {
        val server = LocalAuthCallbackServer.start("expected-state")
        try {
            assertEquals("127.0.0.1", server.callbackUri.host)

            val foreign = get(server.callbackUri, "state=other-state&code=foreign-code")
            assertEquals(400, foreign.statusCode())
            assertNull(server.consumeResult())

            val ours = get(server.callbackUri, "state=expected-state&code=handoff-code")
            assertEquals(200, ours.statusCode())
            assertEquals("handoff-code", server.consumeResult()?.code)

            get(server.callbackUri, "state=expected-state&code=second-code")
            assertNull(server.consumeResult())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `escapes the error it shows in the browser`() {
        val server = LocalAuthCallbackServer.start("expected-state")
        try {
            val response = get(server.callbackUri, "state=expected-state&error=%3Cscript%3Ealert(1)%3C%2Fscript%3E")
            assertFalse(response.body().contains("<script>"))
            assertTrue(response.body().contains("&lt;script&gt;"))
            assertEquals("<script>alert(1)</script>", server.consumeResult()?.error)
        } finally {
            server.stop()
        }
    }

    private fun get(callbackUri: URI, query: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create("$callbackUri?$query")).GET().build(), HttpResponse.BodyHandlers.ofString())
}
