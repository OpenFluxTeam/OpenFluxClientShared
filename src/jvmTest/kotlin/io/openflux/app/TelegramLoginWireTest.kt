package io.openflux.app

import com.sun.net.httpserver.HttpServer
import io.openflux.app.account.LoginUi
import io.openflux.app.account.TelegramLoginModel
import io.openflux.desktop.FakeShareLinkCodec
import io.openflux.desktop.data.HttpBotApi
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ShareConfig
import io.openflux.desktop.model.ShareTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole sign-in over real HTTP: the real client and the state machine against a stand-in that answers like the
 * service (the contract of the owner's plan: start, three polls, the account's sessions, connect/resolve).
 */
class TelegramLoginWireTest {
    private class Seen(val method: String, val path: String, val headers: Map<String, String>, val body: String) {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    @Test
    fun signingInBringsTheConnectionAndSigningOutTakesItAway() = runBlocking {
        val key = "ofk_wiretestwiretest"
        val link = FakeShareLinkCodec().encode(
            ShareConfig(
                name = "Заглушка #1", negotiate = true, secret = "ab".repeat(32), context = "https://example.com/doc",
                transports = listOf(ShareTransport(type = "mailru", url = "https://cloud.mail.ru/public/aaaa/bbbb", priority = 90), ShareTransport(type = "direct", dial = "203.0.113.5:443", priority = 50)),
            ),
        )
        val seen = mutableListOf<Seen>()
        var polls = 0
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            synchronized(seen) { seen += Seen(ex.requestMethod, ex.requestURI.path, ex.requestHeaders.mapValues { it.value.first() }, body) }
            val (code, json) = when (ex.requestURI.path) {
                "/api/v1/auth/app/start" -> 200 to """{"nonce":"wirenonce","deepLink":"https://t.me/openflux_bot?start=auth_wirenonce","expiresAt":"${System.currentTimeMillis() / 1000 + 300}"}"""
                "/api/v1/auth/app/poll" -> 200 to if (++polls < 3) """{"status":"pending"}""" else """{"status":"approved","auth":{"token":"ofs_wire","userId":"7","username":"wire","expiresAt":"1900000000"}}"""
                "/api/v1/sessions" -> 200 to """[{"sessionId":"s1","status":"running","connectToken":"WireWireWireWireWireWire"}]"""
                "/api/v1/connect/resolve" -> 200 to """{"name":"Заглушка #1","link":${JsonPrimitive(link)}}"""
                "/api/v1/subscription" -> 200 to """{"planId":"own30","planName":"Заглушка","status":"active","daysLeft":20,"isActive":true}"""
                "/api/v1/auth/logout" -> 200 to "{}"
                else -> 404 to "{}"
            }
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val platform = TestPlatform().apply { clock = System.currentTimeMillis() }
            val c = testContainer(bot = HttpBotApi("http://127.0.0.1:${server.address.port}", key), platform = platform)
            val m = TelegramLoginModel(c, this, pollMs = 30)
            m.start()
            withTimeout(15_000) { while (m.ui != LoginUi.Done) delay(20) }

            assertEquals(listOf("https://t.me/openflux_bot?start=auth_wirenonce"), platform.opened)
            assertEquals("ofs_wire", c.bot.state.value.session?.token)
            assertEquals("Заглушка", c.bot.state.value.subscription?.planName)
            val p = c.profiles.profiles.value.single()
            assertEquals("Заглушка #1", p.name)
            assertEquals(ProfileSource.Bot, p.source)

            val calls = synchronized(seen) { seen.toList() }
            val paths = calls.map { it.path }
            assertEquals(listOf("/api/v1/auth/app/start", "/api/v1/auth/app/poll", "/api/v1/auth/app/poll", "/api/v1/auth/app/poll"), paths.take(4))
            assertTrue(calls.filter { it.path.startsWith("/api/v1/auth/app/") }.all { it.header("X-App-Key") == key }, "the key goes with every sign-in call")
            assertTrue(calls.filter { !it.path.startsWith("/api/v1/auth/app/") && it.path != "/api/v1/connect/resolve" }.all { it.header("Authorization") == "Bearer ofs_wire" }, "later calls carry the session")
            assertEquals(3, polls, "nobody polled after the approval")

            c.bot.signOut()
            assertNull(c.bot.state.value.session)
            assertTrue(c.profiles.profiles.value.isEmpty(), "the account's connection goes with the sign-out")
            assertTrue(synchronized(seen) { seen.any { it.path == "/api/v1/auth/logout" } })
        } finally {
            server.stop(0)
        }
    }
}
