package io.openflux.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Dest
import io.openflux.app.platform.NoScrollbars
import io.openflux.app.platform.Shortcuts
import io.openflux.app.shell.OpenFluxApp
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSessionItem
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.model.LoginPoll
import io.openflux.desktop.model.LoginStart
import io.openflux.desktop.model.TelegramUser
import io.openflux.desktop.service.BotApi
import io.openflux.desktop.service.BotConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The sign-in screen driven the way a person does it, over a bot that answers at once. */
@OptIn(ExperimentalTestApi::class)
class TelegramLoginScreenTest {
    private class Bot(val answer: LoginPoll) : BotApi {
        override suspend fun signIn(user: TelegramUser): BotSession = throw BotException("unsupported", "not used")
        override suspend fun startLogin() = LoginStart("n1", "https://t.me/openflux_bot?start=auth_n1", System.currentTimeMillis() / 1000 + 300)
        override suspend fun pollLogin(nonce: String) = answer
        override suspend fun subscription(token: String) = BotSubscription()
        override suspend fun signOut(token: String) = Unit
        override suspend fun resolve(connectToken: String): BotConnection = throw BotException("not_found", "Не найдено")
        override suspend fun sessions(token: String) = emptyList<BotSessionItem>()
        override suspend fun createSession(token: String): BotSessionItem = throw BotException("forbidden", "Нет доступа")
    }

    private fun open(c: io.openflux.desktop.service.AppContainer) =
        c.settings.update { it.copy(navState = AppNavigator().apply { open(Dest.Auth) }.snapshot().encode()) }

    @Test
    fun theButtonOpensTelegramAndAnApprovalSignsIn() = runComposeUiTest {
        val platform = TestPlatform().apply { clock = System.currentTimeMillis() }
        val c = testContainer(bot = Bot(LoginPoll.Approved(BotSession("ofs_ui", 7, "ivan", 1_900_000_000))), platform = platform)
        open(c)
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Вход через Telegram").assertIsDisplayed()
        onNodeWithText("Войти через Telegram").performClick()
        waitUntil(timeoutMillis = 10_000) { c.bot.state.value.session != null }
        assertEquals("ofs_ui", c.bot.state.value.session?.token)
        assertEquals(listOf("https://t.me/openflux_bot?start=auth_n1"), platform.opened)
    }

    @Test
    fun aRefusalInTheBotIsSaidAndCanBeRetried() = runComposeUiTest {
        val platform = TestPlatform().apply { clock = System.currentTimeMillis() }
        val c = testContainer(bot = Bot(LoginPoll.Denied), platform = platform)
        open(c)
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Войти через Telegram").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Вход отклонён в боте")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Повторить").assertIsDisplayed()
        assertNull(c.bot.state.value.session)
    }
}
