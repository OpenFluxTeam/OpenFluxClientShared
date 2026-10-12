package io.openflux.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Dest
import io.openflux.app.shell.OpenFluxApp2
import io.openflux.desktop.model.LogLevel
import io.openflux.desktop.ui.NoScrollbars
import io.openflux.desktop.ui.Shortcuts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalTestApi::class)
class SettingsScreensTest {
    @Test
    fun signingUpShowsTheRecoveryKeyThenTheSubscription() = runComposeUiTest {
        val c = testContainer(subscriptions = FakeSubApi())
        c.settings.update { it.copy(navState = AppNavigator().apply { open(Dest.Auth) }.snapshot().encode()) }
        setContent { OpenFluxApp2(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Регистрация").performClick()
        onNode(hasSetTextAction() and hasText("Логин")).performTextInput("ivan")
        onNode(hasSetTextAction() and hasText("Пароль")).performTextInput("longenough1")
        onNode(hasSetTextAction() and hasText("Пароль ещё раз")).performTextInput("longenough1")
        onNodeWithText("Создать аккаунт").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Я сохранил ключ в надёжном месте")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Я сохранил ключ в надёжном месте").performClick()
        onNodeWithText("Продолжить").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Подписка")).fetchSemanticsNodes().isNotEmpty() }
        assertNotNull(c.subscription.state.value)
    }

    @Test
    fun theJournalFilters() = runComposeUiTest {
        val c = testContainer()
        c.nodeWizard.note("ошибка входа по SSH", LogLevel.Error)
        c.settings.update { it.copy(navState = AppNavigator().apply { open(Dest.Logs) }.snapshot().encode()) }
        setContent { OpenFluxApp2(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Журнал пуст").assertIsDisplayed()
    }

    @Test
    fun tenTapsOnTheVersion() = runComposeUiTest {
        val c = testContainer()
        c.settings.update { it.copy(navState = AppNavigator().apply { open(Dest.SettingsPage("about")) }.snapshot().encode()) }
        setContent { OpenFluxApp2(c, NoScrollbars, Shortcuts()) }
        repeat(10) { onNodeWithText("Версия приложения").performClick() }
        waitForIdle()
        assertEquals(true, c.settings.settings.value.developerMode)
    }

    @Test
    fun shots() {
        val c = homeWorld()
        val pages = listOf(
            "settings" to Dest.Settings, "settings-connection" to Dest.SettingsPage("connection"), "settings-vpn" to Dest.SettingsPage("vpn"),
            "settings-services" to Dest.SettingsPage("services"), "settings-core" to Dest.SettingsPage("core"), "settings-look" to Dest.SettingsPage("look"),
            "settings-experimental" to Dest.SettingsPage("experimental"), "settings-about" to Dest.SettingsPage("about"),
            "journal" to Dest.Logs, "auth" to Dest.Auth, "account-none" to Dest.Account,
        )
        for ((name, dest) in pages) {
            c.settings.update { it.copy(navState = AppNavigator().apply { open(dest) }.snapshot().encode()) }
            Shots.render(name) { OpenFluxApp2(c, NoScrollbars, Shortcuts()) }
        }
        c.settings.update { it.copy(navState = AppNavigator().apply { open(Dest.SettingsPage("services")) }.snapshot().encode()) }
        Shots.render("settings", Shots.WIDE) { OpenFluxApp2(c, NoScrollbars, Shortcuts()) }
    }
}
