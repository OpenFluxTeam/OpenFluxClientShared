package io.openflux.app

import io.openflux.app.account.LoginUi
import io.openflux.app.account.TelegramLoginModel
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSessionItem
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.model.LoginPoll
import io.openflux.desktop.model.LoginStart
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.service.BotApi
import io.openflux.desktop.service.BotConnection
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramLoginModelTest {
    private val link = "https://t.me/openflux_bot?start=auth_n1"
    private val approved = LoginPoll.Approved(BotSession("ofs_a", 7, "ivan", 1_900_000_000))

    /** Polls answer from [script] in order: a [LoginPoll] is returned, a [BotException] thrown. */
    private class Scripted(val script: ArrayDeque<Any>, val startError: BotException? = null) : BotApi {
        var starts = 0
        override suspend fun startLogin(): LoginStart {
            starts++
            startError?.let { throw it }
            return LoginStart("n1", "https://t.me/openflux_bot?start=auth_n1", 2_000)
        }
        override suspend fun pollLogin(nonce: String): LoginPoll = when (val x = script.removeFirst()) {
            is LoginPoll -> x
            is BotException -> throw x
            else -> error("bad script")
        }
        override suspend fun subscription(token: String) = BotSubscription()
        override suspend fun signOut(token: String) = Unit
        override suspend fun resolve(connectToken: String): BotConnection = throw BotException("not_found", "Не найдено")
        override suspend fun sessions(token: String) = emptyList<BotSessionItem>()
        override suspend fun createSession(token: String): BotSessionItem = throw BotException("forbidden", "Нет доступа")
    }

    private fun world(vararg script: Any, startError: BotException? = null, platform: TestPlatform = TestPlatform()): Triple<AppContainer, Scripted, TestPlatform> {
        val api = Scripted(ArrayDeque(script.toList()), startError)
        return Triple(testContainer(bot = api, platform = platform), api, platform)
    }

    @Test
    fun approvedAfterPendingPollsSignsIn() = runTest {
        val (c, api, platform) = world(LoginPoll.Pending, LoginPoll.Pending, approved)
        val m = TelegramLoginModel(c, this)
        m.start()
        runCurrent()
        assertIs<LoginUi.Waiting>(m.ui)
        assertEquals(listOf(link), platform.opened, "Telegram is opened with the link")
        advanceUntilIdle()
        assertEquals(LoginUi.Done, m.ui)
        assertEquals("ofs_a", c.bot.state.value.session?.token)
        assertTrue(api.script.isEmpty())
    }

    @Test
    fun approvedStopsPolling() = runTest {
        val (c, api, _) = world(approved, LoginPoll.Expired) // a late "expired" must never be asked for
        val m = TelegramLoginModel(c, this)
        m.start()
        advanceUntilIdle()
        assertEquals(LoginUi.Done, m.ui)
        assertEquals(1, api.script.size, "the second answer was never asked for")
        assertEquals("ofs_a", c.bot.state.value.session?.token)
    }

    @Test
    fun deniedAndExpiredStop() = runTest {
        val (c1, a1, _) = world(LoginPoll.Denied)
        val m1 = TelegramLoginModel(c1, this)
        m1.start(); advanceUntilIdle()
        assertEquals(LoginUi.Denied, m1.ui)
        assertNull(c1.bot.state.value.session)
        assertTrue(a1.script.isEmpty())

        val (c2, _, _) = world(LoginPoll.Pending, LoginPoll.Expired)
        val m2 = TelegramLoginModel(c2, this)
        m2.start(); advanceUntilIdle()
        assertEquals(LoginUi.Expired, m2.ui)
    }

    @Test
    fun networkHiccupsDoNotEndTheWait() = runTest {
        val (c, api, _) = world(BotException("network", "Бот OpenFlux не отвечает. Проверьте сеть."), BotException("rate_limited", "Слишком много запросов"), LoginPoll.Pending, approved)
        val m = TelegramLoginModel(c, this)
        m.start()
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        val hint = assertIs<LoginUi.Waiting>(m.ui).hint
        assertEquals("Бот OpenFlux не отвечает. Проверьте сеть.", hint)
        advanceUntilIdle()
        assertEquals(LoginUi.Done, m.ui)
        assertTrue(api.script.isEmpty())
    }

    @Test
    fun theFiveMinutesRunOutOnTheLocalClock() = runTest {
        val platform = TestPlatform()
        val (c, api, _) = world(LoginPoll.Pending, LoginPoll.Pending, platform = platform)
        val m = TelegramLoginModel(c, this)
        m.start(); runCurrent()
        advanceTimeBy(2_001); runCurrent()
        assertEquals(1, api.script.size, "the first question was asked")
        platform.clock += 400_000L // more than the five minutes a sign-in lives
        advanceTimeBy(2_000); runCurrent()
        assertEquals(LoginUi.Expired, m.ui)
        assertEquals(1, api.script.size, "nobody was asked after the time ran out")
    }

    @Test
    fun aFastClockDoesNotEndTheSignIn() = runTest {
        // The service says the sign-in ends at 2_000 s; this device thinks it is already 3_000 s.
        val platform = TestPlatform().apply { clock = 3_000_000L }
        val (c, _, _) = world(approved, platform = platform)
        val m = TelegramLoginModel(c, this)
        m.start(); advanceUntilIdle()
        assertEquals(LoginUi.Done, m.ui)
    }

    @Test
    fun startTwiceIsOneLogin() = runTest {
        val (c, api, _) = world(LoginPoll.Pending, LoginPoll.Pending, LoginPoll.Pending)
        val m = TelegramLoginModel(c, this)
        m.start(); m.start()
        runCurrent()
        m.start()
        runCurrent()
        assertEquals(1, api.starts)
        m.cancel()
    }

    @Test
    fun cancelStops() = runTest {
        val (c, api, _) = world(LoginPoll.Pending, LoginPoll.Pending)
        val m = TelegramLoginModel(c, this)
        m.start(); runCurrent()
        m.cancel()
        assertEquals(LoginUi.Idle, m.ui)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, api.script.size, "no poll after cancelling")
    }

    @Test
    fun aDisabledAppFails() = runTest {
        val (c, _, _) = world(startError = BotException("app_disabled", "Эта версия приложения больше не может войти. Обновите приложение"))
        val m = TelegramLoginModel(c, this)
        m.start(); advanceUntilIdle()
        assertEquals(LoginUi.Failed("Эта версия приложения больше не может войти. Обновите приложение"), m.ui)
        m.start(); advanceUntilIdle() // "Повторить"
        assertEquals(LoginUi.Failed("Эта версия приложения больше не может войти. Обновите приложение"), m.ui)
    }

    @Test
    fun comingBackAsksAtOnce() = runTest {
        val (c, api, _) = world(LoginPoll.Pending, approved)
        val m = TelegramLoginModel(c, this, pollMs = 60_000)
        m.start(); runCurrent()
        assertEquals(2, api.script.size, "the first poll waits its minute")
        m.pollNow(); runCurrent()
        assertEquals(1, api.script.size)
        m.pollNow(); runCurrent()
        assertEquals(LoginUi.Done, m.ui)
    }
}
