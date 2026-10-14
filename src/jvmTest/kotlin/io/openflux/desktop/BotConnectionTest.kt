package io.openflux.desktop

import io.openflux.app.testContainer
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSessionItem
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.LoginPoll
import io.openflux.desktop.model.LoginStart
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ShareConfig
import io.openflux.desktop.model.ShareTransport
import io.openflux.desktop.model.TelegramUser
import io.openflux.desktop.service.BotAccountManager
import io.openflux.desktop.service.BotApi
import io.openflux.desktop.service.BotConnection
import io.openflux.desktop.service.InMemoryBotRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotConnectionTest {
    private val session = BotSession("ofs_x", 7, "ivan", 1_900_000_000)

    private class Bot : BotApi {
        var sessions = mutableListOf<BotSessionItem>()
        var create: () -> BotSessionItem = { throw BotException("forbidden", "Нет доступа") }
        var created = 0
        val links = mutableMapOf<String, BotConnection>()
        var sessionsError: BotException? = null
        var signedOut = 0
        override suspend fun signIn(user: TelegramUser): BotSession = throw BotException("unsupported", "not used")
        override suspend fun subscription(token: String) = BotSubscription(planId = "own30", planName = "Start", status = "active", isActive = true)
        override suspend fun signOut(token: String) { signedOut++ }
        override suspend fun resolve(connectToken: String) = links[connectToken] ?: throw BotException("not_found", "Не найдено")
        override suspend fun sessions(token: String): List<BotSessionItem> = sessionsError?.let { throw it } ?: sessions.toList()
        override suspend fun createSession(token: String): BotSessionItem { created++; return create().also { sessions += it } }
    }

    private suspend fun link(name: String, key: String) = FakeShareLinkCodec().encode(
        ShareConfig(
            name = name, negotiate = true, secret = key, context = "https://example.com/doc",
            transports = listOf(ShareTransport(type = "mailru", url = "https://cloud.mail.ru/public/aaaa/bbbb", priority = 90), ShareTransport(type = "direct", dial = "203.0.113.5:443", priority = 50)),
        ),
    )

    private fun blockingLink(key: String) = runBlocking { link("x", key) }

    private fun env(api: Bot = Bot()) = run {
        val c = testContainer()
        val accounts = testAccounts(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        Triple(BotAccountManager(api, InMemoryBotRepository(), c.profiles, c.shareCodec, c.platform, c.connection, accounts), c, api)
    }

    private val tokenA = "AAAAAAAAAAAAAAAAAAAAAA"
    private val tokenB = "BBBBBBBBBBBBBBBBBBBBBB"

    @Test
    fun signingInBringsTheRunningConnectionAsAProfile() = runTest {
        val (m, c, api) = env()
        api.sessions += BotSessionItem("s1", "running", tokenA)
        api.links[tokenA] = BotConnection("OpenFlux #4", link("x", "ab".repeat(32)))
        m.completeLogin(session)
        val p = c.profiles.profiles.value.single()
        assertEquals(ProfileSource.Bot, p.source)
        assertEquals("OpenFlux #4", p.name)
        val st = m.state.value
        assertEquals(session, st.session)
        assertTrue(st.links.single().account)
        assertFalse(st.preparing)
        assertEquals(0, api.created, "a running connection is used, not doubled")
    }

    @Test
    fun withoutAConnectionOneIsMade() = runTest {
        val (m, c, api) = env()
        api.create = { BotSessionItem("s1", "running", tokenA).also { api.links[tokenA] = BotConnection("OpenFlux #5", blockingLink("cd".repeat(32))) } }
        m.completeLogin(session)
        assertEquals(1, api.created)
        assertEquals("OpenFlux #5", c.profiles.profiles.value.single().name)
    }

    @Test
    fun aConnectionBeingMadeAppearsWhenReady() = runTest {
        val (m, c, api) = env()
        api.create = { BotSessionItem("s1", "creating", "") }
        m.completeLogin(session)
        assertTrue(m.state.value.preparing)
        assertTrue(c.profiles.profiles.value.isEmpty(), "no profile before the link exists")
        assertTrue(m.state.value.links.none { it.gone })

        // Still being made: no second connection is asked for.
        m.refresh()
        assertEquals(1, api.created)
        assertTrue(m.state.value.preparing)

        api.sessions[0] = BotSessionItem("s1", "running", tokenA)
        api.links[tokenA] = BotConnection("OpenFlux #6", link("x", "ef".repeat(32)))
        m.refresh()
        assertFalse(m.state.value.preparing)
        assertEquals("OpenFlux #6", c.profiles.profiles.value.single().name)
        assertEquals(1, api.created)
    }

    @Test
    fun aLinkThatIsNotResolvableYetKeepsWaiting() = runTest {
        val (m, c, api) = env()
        api.sessions += BotSessionItem("s1", "running", tokenA) // no link in api.links yet
        m.completeLogin(session)
        assertTrue(m.state.value.preparing)
        assertNull(m.state.value.lastError)
        assertTrue(c.profiles.profiles.value.isEmpty())
    }

    @Test
    fun noFreeSlotSaysSo() = runTest {
        val (m, c, api) = env()
        m.completeLogin(session)
        assertEquals(BotAccountManager.NO_SLOT, m.state.value.connectionNote)
        assertFalse(m.state.value.preparing)
        assertNull(m.state.value.lastError)
        assertEquals(session, m.state.value.session, "still signed in")
        assertTrue(c.profiles.profiles.value.isEmpty())
        // The subscription is renewed in the bot: the next refresh makes the connection and the note goes.
        api.create = { BotSessionItem("s1", "running", tokenA).also { api.links[tokenA] = BotConnection("OpenFlux #7", blockingLink("ab".repeat(32))) } }
        m.refresh()
        assertNull(m.state.value.connectionNote)
        assertEquals(1, c.profiles.profiles.value.size)
    }

    @Test
    fun aReplacedConnectionIsReplaced() = runTest {
        val (m, c, api) = env()
        api.sessions += BotSessionItem("s1", "running", tokenA)
        api.links[tokenA] = BotConnection("OpenFlux #4", link("x", "ab".repeat(32)))
        m.completeLogin(session)
        val old = c.profiles.profiles.value.single()
        c.connection.connect(old, ConnectionMode.Client)

        // The bot stopped it and made another.
        api.links.remove(tokenA)
        api.sessions.clear()
        api.sessions += BotSessionItem("s2", "running", tokenB)
        api.links[tokenB] = BotConnection("OpenFlux #8", link("x", "cd".repeat(32)))
        m.refresh()

        val p = c.profiles.profiles.value.single()
        assertEquals("OpenFlux #8", p.name)
        assertEquals("cd".repeat(32), p.secret)
        assertFalse(c.connection.state.value is ConnectionState.Connected, "the tunnel through the old one stops")
        assertEquals(tokenB, m.state.value.links.single().token)
    }

    @Test
    fun signOutKeepsManualLinks() = runTest {
        val (m, c, api) = env()
        api.links[tokenB] = BotConnection("Ручная", link("x", "11".repeat(32)))
        m.addLink("https://link.openflux.dev/$tokenB")
        api.sessions += BotSessionItem("s1", "running", tokenA)
        api.links[tokenA] = BotConnection("Аккаунт", link("x", "22".repeat(32)))
        m.completeLogin(session)
        assertEquals(2, c.profiles.profiles.value.size)

        m.signOut()
        assertNull(m.state.value.session)
        assertEquals(listOf("Ручная"), c.profiles.profiles.value.map { it.name })
        assertEquals(listOf(tokenB), m.state.value.links.map { it.token })
        assertEquals(1, api.signedOut)
    }

    @Test
    fun anExpiredSessionEndsTheSignInButKeepsTheProfile() = runTest {
        val (m, c, api) = env()
        api.sessions += BotSessionItem("s1", "running", tokenA)
        api.links[tokenA] = BotConnection("Аккаунт", link("x", "22".repeat(32)))
        m.completeLogin(session)
        api.sessionsError = BotException("unauthorized", "Войдите через Telegram заново")
        m.refresh()
        assertNull(m.state.value.session)
        assertEquals(1, c.profiles.profiles.value.size, "the connection keeps working until the bot ends it")
    }

    @Test
    fun subscriptionFollowsTheBotAndSignOutAsksTheService() = runTest {
        val (m, _, api) = env()
        m.completeLogin(session)
        assertEquals("Start", m.state.value.subscription?.planName)
        m.signOut()
        assertNull(m.state.value.session)
        assertEquals(1, api.signedOut)
    }

    @Test
    fun loginCallsGoToTheApi() = runTest {
        val api = object : BotApi by Bot() {
            override suspend fun startLogin() = LoginStart("n", "https://t.me/openflux_bot?start=auth_n", 5)
            override suspend fun pollLogin(nonce: String) = LoginPoll.Pending
        }
        val c = testContainer()
        val m = BotAccountManager(api, InMemoryBotRepository(), c.profiles, c.shareCodec, c.platform, c.connection, testAccounts(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)))
        assertEquals("n", m.startLogin().nonce)
        assertEquals(LoginPoll.Pending, m.pollLogin("n"))
    }
}
