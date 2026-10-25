package io.openflux.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Dest
import io.openflux.app.shell.OpenFluxApp
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.LogLevel
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.SplitTunnelMode
import io.openflux.desktop.service.InstalledApp
import io.openflux.desktop.service.PlatformKind
import io.openflux.app.platform.NoScrollbars
import io.openflux.app.platform.Shortcuts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalTestApi::class)
class SettingsScreensTest {
    @Test
    fun theJournalFiltersAndCopiesWhatItShows() = runComposeUiTest {
        val c = testContainer()
        (c.connection as TestConnection).logs.value = listOf(
            io.openflux.desktop.model.LogLine(1, 1, "соединение установлено", LogLevel.Info),
            io.openflux.desktop.model.LogLine(2, 2, "ошибка входа по SSH", LogLevel.Error),
        )
        c.settings.update { it.copy(navState = AppNavigator().apply { open(Dest.Logs) }.snapshot().encode()) }
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Поиск в журнале").assertIsDisplayed()
        onNodeWithText("соединение установлено").assertIsDisplayed()
        onNodeWithText("Ошибки").performClick()
        assertEquals(0, onAllNodes(hasText("соединение установлено")).fetchSemanticsNodes().size)
        onNodeWithText("ошибка входа по SSH").assertIsDisplayed()
        onNodeWithContentDescription("Ещё").performClick()
        onNodeWithText("Копировать показанное").performClick()
        assertEquals("ошибка входа по SSH", (c.platform as TestPlatform).clipboard)
    }

    @Test
    fun tenTapsOnTheVersion() = runComposeUiTest {
        val c = testContainer()
        c.settings.update { it.copy(navState = AppNavigator().apply { open(Dest.SettingsPage("about")) }.snapshot().encode()) }
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        repeat(10) { onNodeWithText("Версия приложения").performClick() }
        waitForIdle()
        assertEquals(true, c.settings.settings.value.developerMode)
    }

    private fun androidPlatform(vararg installed: InstalledApp) = TestPlatform(
        kind = PlatformKind.Android,
        fullTunnelSupported = true,
        splitTunnelSupported = true,
    ).apply { apps = installed.toList() }

    private fun activeClient(c: io.openflux.desktop.service.AppContainer) {
        val profile = Profile(id = "p1", name = "Тест")
        (c.connection as TestConnection).state.value = ConnectionState.Connected(profile, ConnectionMode.Client, 0)
    }

    @Test
    fun vpnPageOffersSplitTunnelOnlyWhenItCanWork() = runComposeUiTest {
        val c = testContainer(platform = androidPlatform())
        c.settings.update { it.copy(fullTunnel = true, mode = ConnectionMode.Client) }
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Настройки").performClick()
        onNodeWithText("VPN").performClick()
        onNodeWithText("Все, кроме выбранных").assertIsDisplayed()
        onNodeWithText("Все, кроме выбранных").performClick()
        waitForIdle()
        assertEquals(SplitTunnelMode.Exclude, c.settings.settings.value.splitTunnelMode)
    }

    @Test
    fun aSplitModeChangeReconnectsTheActiveVpn() = runComposeUiTest {
        val c = testContainer(platform = androidPlatform())
        val conn = c.connection as TestConnection
        c.settings.update {
            it.copy(
                fullTunnel = true,
                mode = ConnectionMode.Client,
                splitTunnelMode = SplitTunnelMode.Off,
                splitTunnelPackages = listOf("com.a"),
            )
        }
        activeClient(c)
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Настройки").performClick()
        onNodeWithText("VPN").performClick()
        conn.connects = 0
        onNodeWithText("Все, кроме выбранных").performClick()
        waitForIdle()
        assertEquals(1, conn.connects, "a valid split filter applies at once")
    }

    @Test
    fun anEmptyIncludeIsSavedWithAWarningButDoesNotReconnect() = runComposeUiTest {
        val c = testContainer(platform = androidPlatform())
        val conn = c.connection as TestConnection
        c.settings.update {
            it.copy(
                fullTunnel = true,
                mode = ConnectionMode.Client,
                splitTunnelMode = SplitTunnelMode.Off,
                splitTunnelPackages = emptyList(),
            )
        }
        activeClient(c)
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Настройки").performClick()
        onNodeWithText("VPN").performClick()
        conn.connects = 0
        onNodeWithText("Только выбранные").performClick()
        waitForIdle()
        assertEquals(SplitTunnelMode.Include, c.settings.settings.value.splitTunnelMode)
        assertEquals(0, conn.connects, "an empty allowlist is not a VPN to restart")
        onNodeWithText("Выберите хотя бы одно приложение: с пустым списком VPN не запустится.").assertIsDisplayed()
    }

    @Test
    fun savingAppsReconnectsTheActiveVpn() = runComposeUiTest {
        val c = testContainer(
            platform = androidPlatform(
                InstalledApp("com.a", "Альфа", system = false),
                InstalledApp("com.b", "Бета", system = false),
            ),
        )
        val conn = c.connection as TestConnection
        c.settings.update {
            it.copy(
                fullTunnel = true,
                mode = ConnectionMode.Client,
                splitTunnelMode = SplitTunnelMode.Exclude,
                splitTunnelPackages = listOf("com.a"),
            )
        }
        activeClient(c)
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Настройки").performClick()
        onNodeWithText("VPN").performClick()
        onNodeWithText("Приложения").performClick()
        waitForIdle()
        onNodeWithText("Бета").performClick()
        onNodeWithText("Сохранить").performClick()
        waitForIdle()
        assertEquals(listOf("com.a", "com.b"), c.settings.settings.value.splitTunnelPackages)
        assertEquals(1, conn.connects, "the saved filter applies at once")
    }

    @Test
    fun anEmptyIncludeInThePickerCannotBeSaved() = runComposeUiTest {
        val c = testContainer(platform = androidPlatform(InstalledApp("com.a", "Альфа", system = false)))
        val conn = c.connection as TestConnection
        c.settings.update {
            it.copy(
                fullTunnel = true,
                mode = ConnectionMode.Client,
                splitTunnelMode = SplitTunnelMode.Include,
                splitTunnelPackages = listOf("com.a"),
            )
        }
        activeClient(c)
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Настройки").performClick()
        onNodeWithText("VPN").performClick()
        onNodeWithText("Приложения").performClick()
        waitForIdle()
        onNodeWithText("Альфа").performClick()
        waitForIdle()
        onNodeWithText("Выберите хотя бы одно приложение").assertIsDisplayed()
        onNodeWithText("Сохранить").assertIsNotEnabled()
        assertEquals(listOf("com.a"), c.settings.settings.value.splitTunnelPackages)
        assertEquals(0, conn.connects)
    }

    @Test
    fun russianFilterShowsAndSelectsRussianApps() = runComposeUiTest {
        val c = testContainer(
            platform = androidPlatform(
                InstalledApp("ru.ozon.app.android", "Ozon", system = false),
                InstalledApp("com.wildberries.ru", "Wildberries", system = false),
                InstalledApp("com.google.android.youtube", "YouTube", system = false),
            ),
        )
        c.settings.update {
            it.copy(
                fullTunnel = true,
                mode = ConnectionMode.Client,
                splitTunnelMode = SplitTunnelMode.Exclude,
            )
        }
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Настройки").performClick()
        onNodeWithText("VPN").performClick()
        onNodeWithText("Приложения").performClick()
        waitForIdle()
        onNodeWithText("Российские").performClick()
        waitForIdle()
        onNodeWithText("Выбрано: 2").assertIsDisplayed()
        onNodeWithText("Ozon").assertIsDisplayed()
        onNodeWithText("Wildberries").assertIsDisplayed()
        onNodeWithText("YouTube").assertDoesNotExist()
        onNodeWithText("Сохранить").assertIsEnabled()
        onNodeWithText("Сохранить").performClick()
        waitForIdle()
        assertEquals(listOf("com.wildberries.ru", "ru.ozon.app.android"), c.settings.settings.value.splitTunnelPackages)
    }

    @Test
    fun aRussianAppRemovedByHandStaysRemovedWhenTheFilterIsReselected() = runComposeUiTest {
        val c = testContainer(
            platform = androidPlatform(
                InstalledApp("ru.ozon.app.android", "Ozon", system = false),
                InstalledApp("com.wildberries.ru", "Wildberries", system = false),
            ),
        )
        c.settings.update {
            it.copy(
                fullTunnel = true,
                mode = ConnectionMode.Client,
                splitTunnelMode = SplitTunnelMode.Exclude,
            )
        }
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Настройки").performClick()
        onNodeWithText("VPN").performClick()
        onNodeWithText("Приложения").performClick()
        waitForIdle()
        onNodeWithText("Российские").performClick()
        waitForIdle()
        onNodeWithText("Ozon").performClick()
        waitForIdle()
        onNodeWithText("Выбрано: 1").assertIsDisplayed()
        onNodeWithText("Все").performClick()
        onNodeWithText("Российские").performClick()
        waitForIdle()
        onNodeWithText("Выбрано: 1").assertIsDisplayed()
        onNodeWithText("Сохранить").performClick()
        waitForIdle()
        assertEquals(listOf("com.wildberries.ru"), c.settings.settings.value.splitTunnelPackages)
    }

    @Test
    fun aReleaseCanBeSkipped() = runComposeUiTest {
        val p = TestPlatform().apply {
            appVersion = "2.0.0"
            releases = """[{"tag_name":"v2.1.0","draft":false,"prerelease":false,"body":"","html_url":"https://x","assets":[]}]"""
        }
        val c = testContainer(platform = p)
        kotlinx.coroutines.runBlocking { c.updates.check(manual = false) }
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Вышла версия 2.1.0").assertIsDisplayed()
        onNodeWithContentDescription("Ещё").performClick()
        onNodeWithText("Пропустить эту версию").performClick()
        waitForIdle()
        assertEquals("v2.1.0", c.settings.settings.value.skippedUpdate)
        assertEquals(0, onAllNodes(hasText("Вышла версия 2.1.0")).fetchSemanticsNodes().size)
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
            Shots.render(name) { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        }
        c.settings.update { it.copy(navState = AppNavigator().apply { open(Dest.SettingsPage("services")) }.snapshot().encode()) }
        Shots.render("settings", Shots.WIDE) { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
    }
}
