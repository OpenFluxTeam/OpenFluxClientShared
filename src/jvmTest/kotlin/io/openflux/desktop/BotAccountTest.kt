package io.openflux.desktop

import com.sun.net.httpserver.HttpServer
import io.openflux.app.FakeSubApi
import io.openflux.app.testContainer
import io.openflux.desktop.data.HttpBotApi
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.OpenFluxBot
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ShareConfig
import io.openflux.desktop.model.ShareTransport
import io.openflux.desktop.model.TelegramUser
import io.openflux.desktop.model.managed
import io.openflux.desktop.service.AccountException
import io.openflux.desktop.service.BotAccountManager
import io.openflux.desktop.service.BotApi
import io.openflux.desktop.service.BotConnection
import io.openflux.desktop.service.InMemoryBotRepository

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotAccountTest {
    private val token = "AbCdEfGhIjKlMnOpQrStUv"

    // ---- links and Telegram's answer ----

    @Test
    fun shortLinksAreRecognised() {
        for (t in listOf("https://link.openflux.dev/$token", "link.openflux.dev/$token/", " https://link.openflux.dev/$token?x=1 ", "http://link.openflux.dev/$token#a")) {
            assertEquals(token, OpenFluxBot.connectToken(t), t)
        }
        for (t in listOf("https://link.openflux.dev/", "https://evil.example/$token", "https://link.openflux.dev.evil/$token", "https://link.openflux.dev/a/b", "openflux://v1/x", "https://link.openflux.dev/short")) {
            assertNull(OpenFluxBot.connectToken(t), t)
        }
    }

    @Test
    fun theSignInPageIsTheBots() {
        val u = OpenFluxBot.signInUrl
        assertTrue(u.startsWith("https://oauth.telegram.org/auth?bot_id=8950210830&origin=https%3A%2F%2Fopenflux.dev"), u)
        assertTrue("return_to=https%3A%2F%2Fopenflux.dev%2Fapp-login" in u, u)
    }

    private val userJson = """{"id":123456789,"first_name":"Иван","username":"ivan","photo_url":"https://t.me/i/u.jpg","auth_date":1800000000,"hash":"abc123"}"""

    @Test
    fun telegramsAnswerIsReadInEveryBase64() {
        val std = Base64.getEncoder().encodeToString(userJson.toByteArray())
        val url = Base64.getUrlEncoder().withoutPadding().encodeToString(userJson.toByteArray())
        for (enc in listOf(std, url, std.replace("=", "%3D"))) {
            val u = assertNotNull(OpenFluxBot.authResult("${OpenFluxBot.RETURN_TO}#tgAuthResult=$enc"), enc)
            assertEquals(TelegramUser(123456789, "Иван", "", "ivan", "https://t.me/i/u.jpg", 1800000000, "abc123"), u)
        }
        assertNull(OpenFluxBot.authResult("${OpenFluxBot.RETURN_TO}#tgAuthResult=false"), "refused")
        assertNull(OpenFluxBot.authResult(OpenFluxBot.RETURN_TO), "nothing")
        assertNull(OpenFluxBot.authResult("${OpenFluxBot.RETURN_TO}#tgAuthResult=" + Base64.getEncoder().encodeToString("""{"id":1}""".toByteArray())), "unsigned")
    }

    // ---- the manager ----

    private class FakeBot : BotApi {
        val links = mutableMapOf<String, BotConnection>()
        var subscription = BotSubscription(planId = "own30", planName = "Start", status = "active", expiresAt = 1_900_000_000, daysLeft = 20, isActive = true)
        val signedIn = mutableListOf<TelegramUser>()
        var signedOut = 0
        override suspend fun signIn(user: TelegramUser): BotSession { signedIn += user; return BotSession("ofs_x", user.id, user.username, 1_900_000_000) }
        override suspend fun subscription(token: String) = if (token == "ofs_x") subscription else throw BotException("unauthorized", "Войдите")
        override suspend fun signOut(token: String) { signedOut++ }
        override suspend fun resolve(connectToken: String) = links[connectToken] ?: throw BotException("not_found", "Не найдено")
    }

    private suspend fun link(name: String, key: String) = FakeShareLinkCodec().encode(
        ShareConfig(
            name = name, negotiate = true, secret = key, context = "https://example.com/doc",
            transports = listOf(ShareTransport(type = "mailru", url = "https://cloud.mail.ru/public/aaaa/bbbb", priority = 90), ShareTransport(type = "direct", dial = "203.0.113.5:443", priority = 50)),
        ),
    )

    private fun env(api: FakeBot = FakeBot(), browser: FakeBrowser = FakeBrowser()) = run {
        val c = testContainer(subscriptions = FakeSubApi())
        val accounts = testAccounts(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), browser = browser)
        Triple(BotAccountManager(api, InMemoryBotRepository(), c.profiles, c.shareCodec, c.platform, c.connection, accounts), c, api)
    }

    @Test
    fun aLinkFromTheBotBecomesAManagedProfileThatFollowsIt() = runTest {
        val (m, c, api) = env()
        api.links[token] = BotConnection("Рабочий ноутбук #19", link("x", "ab".repeat(32)))
        val p = m.addLink("https://link.openflux.dev/$token")
        assertEquals(ProfileSource.Bot, p.source)
        assertTrue(p.managed, "read-only like a subscription's")
        assertEquals("Рабочий ноутбук #19", p.name)
        assertEquals(listOf(p.id), c.profiles.profiles.value.map { it.id })

        // The bot changes the connection's key: the profile follows, same id.
        api.links[token] = BotConnection("Рабочий ноутбук #19", link("x", "cd".repeat(32)))
        m.refresh()
        assertEquals("cd".repeat(32), c.profiles.profiles.value.single().secret)
        assertEquals(p.id, c.profiles.profiles.value.single().id)

        // Adding the same link again updates, not doubles.
        m.addLink("link.openflux.dev/$token")
        assertEquals(1, c.profiles.profiles.value.size)
        assertEquals(1, m.state.value.links.size)
    }

    @Test
    fun aConnectionTheBotNoLongerServesIsMarkedAndStopped() = runTest {
        val (m, c, api) = env()
        api.links[token] = BotConnection("OpenFlux #3", link("x", "ab".repeat(32)))
        val p = m.addLink("https://link.openflux.dev/$token")
        c.connection.connect(p, ConnectionMode.Client)
        api.links.clear()
        m.refresh()
        assertTrue(m.state.value.links.single().gone)
        assertFalse(c.connection.state.value is ConnectionState.Connected, "the tunnel through it stops")
        assertNotNull(c.profiles.profiles.value.firstOrNull { it.id == p.id }, "the profile stays, with the way to a new link")

        m.removeLink(token)
        assertTrue(c.profiles.profiles.value.isEmpty())
        assertTrue(m.state.value.links.isEmpty())
    }

    @Test
    fun aLinkThatEndedIsNotAdded() = runTest {
        val (m, c, _) = env()
        val e = assertFailsWith<BotException> { m.addLink("https://link.openflux.dev/$token") }
        assertEquals(BotAccountManager.GONE, e.message)
        assertTrue(c.profiles.profiles.value.isEmpty())
    }

    @Test
    fun signingInThroughTelegramRegistersAndShowsTheSubscription() = runTest {
        val browser = FakeBrowser()
        val enc = Base64.getUrlEncoder().withoutPadding().encodeToString(userJson.toByteArray())
        // Telegram sends the browser back with the user in the fragment, and the page there clears it at once.
        browser.onOpen = { url -> visited = mutableListOf(url, "${OpenFluxBot.RETURN_TO}#tgAuthResult=$enc", "https://t.me/openflux_bot"); this.url = "https://t.me/openflux_bot" }
        val (m, _, api) = env(browser = browser)
        val s = m.signIn()
        assertEquals(123456789, s.userId)
        assertEquals("ivan", api.signedIn.single().username)
        assertEquals(OpenFluxBot.signInUrl, browser.opened.single().first)
        assertTrue(browser.closed, "the browser closes after")
        assertEquals("Start", m.state.value.subscription?.planName)

        api.subscription = api.subscription.copy(planName = "Pro")
        m.refresh()
        assertEquals("Pro", m.state.value.subscription?.planName)
        m.signOut()
        assertNull(m.state.value.session)
        assertEquals(1, api.signedOut)
    }

    @Test
    fun aClosedSignInWindowCancels() = runTest {
        val browser = FakeBrowser()
        browser.onOpen = { close() }
        val (m, _, api) = env(browser = browser)
        assertTrue(assertFailsWith<AccountException> { m.signIn() }.cancelled)
        assertTrue(api.signedIn.isEmpty())
    }

    @Test
    fun anExpiredSessionSignsOutQuietly() = runTest {
        val browser = FakeBrowser()
        val enc = Base64.getUrlEncoder().withoutPadding().encodeToString(userJson.toByteArray())
        browser.onOpen = { url = "${OpenFluxBot.RETURN_TO}#tgAuthResult=$enc" }
        val api = object : BotApi by FakeBot() {
            override suspend fun subscription(token: String): BotSubscription = throw BotException("unauthorized", "Войдите через Telegram заново")
            override suspend fun signIn(user: TelegramUser) = BotSession("ofs_old", user.id)
        }
        val c = testContainer()
        val m = BotAccountManager(api, InMemoryBotRepository(), c.profiles, c.shareCodec, c.platform, c.connection, testAccounts(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), browser = browser))
        m.signIn()
        assertNull(m.state.value.session, "a session the bot no longer takes is dropped")
    }

    // ---- the HTTP client ----

    private val servers = mutableListOf<HttpServer>()

    @AfterTest
    fun stop() = servers.forEach { it.stop(0) }

    private val seen = mutableListOf<Triple<String, Map<String, String>, String>>()

    private fun server(): String {
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        s.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen += Triple(ex.requestURI.path, ex.requestHeaders.mapValues { it.value.first() }, body)
            val auth = ex.requestHeaders.getFirst("Authorization")
            val (code, json) = when (ex.requestURI.path) {
                "/api/v1/auth/telegram" -> 200 to """{"token":"ofs_tok","userId":"123456789","username":"ivan","role":"user","expiresAt":"1900000000","sessionId":"s1"}"""
                "/api/v1/subscription" -> if (auth == "Bearer ofs_tok") 200 to """{"planId":"own30","planName":"Start","status":"active","expiresAt":"1900000000","daysLeft":20,"maxSessions":1,"trafficUsedBytes":"1073741824","trafficLimitBytes":"32212254720","isActive":true}""" else 401 to """{"code":16,"message":"unauthorized"}"""
                "/api/v1/connect/resolve" -> if (body.contains(token)) 200 to """{"name":"OpenFlux #3","link":"openflux://v1/x"}""" else 404 to """{"code":5,"message":"connection link not found"}"""
                "/api/v1/auth/logout" -> 200 to "{}"
                else -> 404 to "{}"
            }
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        servers += s
        return "http://127.0.0.1:${s.address.port}"
    }

    @Test
    fun theHttpClientSpeaksTheGatewaysJson() = runBlocking {
        val api = HttpBotApi(server())
        val session = api.signIn(TelegramUser(123456789, "Иван", username = "ivan", authDate = 1800000000, hash = "abc"))
        assertEquals(BotSession("ofs_tok", 123456789, "ivan", 1900000000), session)
        val (_, headers, body) = seen.single { it.first == "/api/v1/auth/telegram" }
        assertEquals("bearer", headers["X-session-transport"] ?: headers["X-Session-Transport"])
        assertTrue(""""id":123456789""" in body && """"auth_date":1800000000""" in body && """"hash":"abc"""" in body, body)

        val sub = api.subscription("ofs_tok")
        assertEquals(1073741824, sub.trafficUsedBytes, "int64 as a string")
        assertEquals(1900000000, sub.expiresAt)
        assertTrue(sub.isActive)
        assertTrue(assertFailsWith<BotException> { api.subscription("stale") }.unauthorized)

        assertEquals(BotConnection("OpenFlux #3", "openflux://v1/x"), api.resolve(token))
        assertTrue(assertFailsWith<BotException> { api.resolve("unknownunknownunknown") }.notFound)
        api.signOut("ofs_tok")
    }
}
