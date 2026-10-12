package io.openflux.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.openflux.app.shell.OpenFluxApp2
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.ExitAddress
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.TrafficStats
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.ui.NoScrollbars
import io.openflux.desktop.ui.Shortcuts
import kotlin.test.Test
import kotlin.test.assertEquals

fun homeWorld(conn: ScriptedConnection = ScriptedConnection()): AppContainer {
    val c = testContainer(connection = conn)
    c.profiles.upsert(Profile(id = "a", name = "Амстердам", source = ProfileSource.Node, transport = TransportType.VYANDEX, value = "https://docs.yandex.ru/edit/d/" + "a".repeat(20), secret = "s".repeat(64), session = true, extras = listOf(io.openflux.desktop.model.ExtraTransport(TransportType.DIRECT, "203.0.113.7:8445"))))
    c.profiles.upsert(Profile(id = "b", name = "Франкфурт", source = ProfileSource.Link, transport = TransportType.CUPSONLINE, value = "rooms", secret = "s".repeat(64)))
    c.profiles.upsert(Profile(id = "c", name = "Хостинг · me.example", transport = TransportType.CUPSONLINE, value = "https://cups.online/r1", stream = true, phpNode = io.openflux.desktop.model.PhpNodeRef("https://me.example", "t")))
    return c
}

@OptIn(ExperimentalTestApi::class)
class HomeScreenTest {
    @Test
    fun theButtonConnectsTheChosenProfile() = runComposeUiTest {
        val conn = ScriptedConnection()
        val c = homeWorld(conn)
        setContent { OpenFluxApp2(c, NoScrollbars, Shortcuts()) }
        onNodeWithContentDescription("Подключить").performClick()
        assertEquals("a", (conn.state.value as ConnectionState.Connected).profile.id)
        onNodeWithText("Подключено").assertIsDisplayed()
        onNodeWithContentDescription("Отключить").performClick()
        assertEquals(ConnectionState.Idle, conn.state.value)
    }

    @Test
    fun aFailureSaysWhyAndOffersARetry() = runComposeUiTest {
        val conn = ScriptedConnection().apply { onConnect = { p, _ -> ConnectionState.Failed(p, "нода не ответила") } }
        val c = homeWorld(conn)
        setContent { OpenFluxApp2(c, NoScrollbars, Shortcuts()) }
        onNodeWithContentDescription("Подключить").performClick()
        onNodeWithText("нода не ответила").assertIsDisplayed()
        onNodeWithText("Повторить").assertIsDisplayed()
    }

    @Test
    fun theDetailsOpenOnRequest() = runComposeUiTest {
        val conn = ScriptedConnection()
        val c = homeWorld(conn)
        setContent { OpenFluxApp2(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Сведения").performClick()
        onNodeWithText("Шифрование").assertIsDisplayed()
        onNodeWithText("AES-256-GCM").assertIsDisplayed()
    }
}
