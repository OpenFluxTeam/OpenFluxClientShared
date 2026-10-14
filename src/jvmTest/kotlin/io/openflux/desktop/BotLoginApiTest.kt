package io.openflux.desktop

import com.sun.net.httpserver.HttpServer
import io.openflux.desktop.data.HttpBotApi
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSessionItem
import io.openflux.desktop.model.LoginPoll
import io.openflux.desktop.model.LoginStart
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BotLoginApiTest {
    private val servers = mutableListOf<HttpServer>()
    private class Seen(val path: String, val headers: Map<String, String>, val body: String) {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
    private val seen = mutableListOf<Seen>()

    @AfterTest
    fun stop() = servers.forEach { it.stop(0) }

    /** A server answering each path with (status, json) from [answer]; records the requests. */
    private fun server(answer: (path: String, body: String) -> Pair<Int, String>): String {
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        s.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen += Seen(ex.requestURI.path, ex.requestHeaders.mapValues { it.value.first() }, body)
            val (code, json) = answer(ex.requestURI.path, body)
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        servers += s
        return "http://127.0.0.1:${s.address.port}"
    }

    private val key = "ofk_testtesttesttest"

    @Test
    fun startLoginSendsTheKeyAndReadsTheLink() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to """{"nonce":"n123","deepLink":"https://t.me/openflux_bot?start=auth_n123","expiresAt":"1900000000"}""" }, key)
        val s = api.startLogin()
        assertEquals(LoginStart("n123", "https://t.me/openflux_bot?start=auth_n123", 1900000000), s)
        val r = seen.single()
        assertEquals("/api/v1/auth/app/start", r.path)
        assertEquals(key, r.header("X-App-Key"))
    }

    @Test
    fun aLinkThatIsNotTelegramsIsRefused() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to """{"nonce":"n1","deepLink":"https://evil.example/x","expiresAt":"1"}""" }, key)
        assertEquals("bad_response", assertFailsWith<BotException> { api.startLogin() }.code)
    }

    @Test
    fun pollReadsEveryStatus() = runBlocking {
        val answers = ArrayDeque(
            listOf(
                """{"status":"pending"}""",
                """{"status":"approved","auth":{"token":"ofs_tok","userId":"7","username":"ivan","role":"user","expiresAt":"1900000000","sessionId":"s1"}}""",
                """{"status":"denied"}""",
                """{"status":"expired"}""",
                """{"status":"unknown"}""",
            ),
        )
        val api = HttpBotApi(server { _, _ -> 200 to answers.removeFirst() }, key)
        assertEquals(LoginPoll.Pending, api.pollLogin("n"))
        assertEquals(LoginPoll.Approved(BotSession("ofs_tok", 7, "ivan", 1900000000)), api.pollLogin("n"))
        assertEquals(LoginPoll.Denied, api.pollLogin("n"))
        assertEquals(LoginPoll.Expired, api.pollLogin("n"))
        assertEquals(LoginPoll.Expired, api.pollLogin("n"), "an unknown nonce is as good as an expired one")
        assertTrue(seen.all { it.path == "/api/v1/auth/app/poll" && it.header("X-App-Key") == key && """"nonce":"n"""" in it.body })
    }

    @Test
    fun anApprovedAnswerWithoutATokenIsBroken() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to """{"status":"approved","auth":{"userId":"7"}}""" }, key)
        assertEquals("bad_response", assertFailsWith<BotException> { api.pollLogin("n") }.code)
    }

    @Test
    fun aDisabledAppIsNamed() = runBlocking {
        for (code in listOf(401, 403)) {
            val api = HttpBotApi(server { _, _ -> code to """{"code":16,"message":"app disabled"}""" }, key)
            assertEquals("app_disabled", assertFailsWith<BotException> { api.startLogin() }.code, "start $code")
            assertEquals("app_disabled", assertFailsWith<BotException> { api.pollLogin("n") }.code, "poll $code")
        }
    }

    @Test
    fun noKeyNoLogin() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to "{}" }, "")
        assertEquals("no_app_key", assertFailsWith<BotException> { api.startLogin() }.code)
        assertEquals("no_app_key", assertFailsWith<BotException> { api.pollLogin("n") }.code)
        assertTrue(seen.isEmpty(), "nothing leaves the device without a key")
    }

    @Test
    fun sessionsAreReadFromABareArrayOrAnObject() = runBlocking {
        val bare = HttpBotApi(server { _, _ -> 200 to """[{"sessionId":"s1","status":"running","connectToken":"tok1","clientUrl":"https://x"},{"sessionId":"s2","status":"creating"}]""" }, key)
        assertEquals(listOf(BotSessionItem("s1", "running", "tok1"), BotSessionItem("s2", "creating", "")), bare.sessions("ofs_tok"))
        val wrapped = HttpBotApi(server { _, _ -> 200 to """{"sessions":[{"sessionId":"s3","status":"stopped"}],"total":1}""" }, key)
        assertEquals(listOf(BotSessionItem("s3", "stopped", "")), wrapped.sessions("ofs_tok"))
        val empty = HttpBotApi(server { _, _ -> 200 to "[]" }, key)
        assertTrue(empty.sessions("ofs_tok").isEmpty())
        assertEquals("Bearer ofs_tok", seen.first().header("Authorization"))
    }

    @Test
    fun aCreatedSessionIsReadAndARefusalIsForbidden() = runBlocking {
        val ok = HttpBotApi(server { _, _ -> 200 to """{"session":{"sessionId":"s9","status":"creating"}}""" }, key)
        assertEquals(BotSessionItem("s9", "creating", ""), ok.createSession("ofs_tok"))
        val no = HttpBotApi(server { _, _ -> 403 to """{"code":7,"message":"no free slot for an automatic connection"}""" }, key)
        assertTrue(assertFailsWith<BotException> { no.createSession("ofs_tok") }.forbidden)
    }

    @Test
    fun secretsStayOutOfToString() {
        val s = LoginStart("secretnonce", "https://t.me/openflux_bot?start=auth_secretnonce", 5)
        assertFalse("secretnonce" in s.toString())
    }
}
