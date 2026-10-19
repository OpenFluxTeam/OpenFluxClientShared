package io.openflux.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Dest
import io.openflux.app.servers.ServerLink
import io.openflux.app.shell.OpenFluxApp
import io.openflux.desktop.model.InstalledChannel
import io.openflux.desktop.model.NodeTransport
import io.openflux.desktop.model.SshTarget
import io.openflux.app.platform.NoBrowserViews
import io.openflux.app.platform.NoScrollbars
import io.openflux.app.platform.Shortcuts
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class NodeScreenTest {
    @Test
    fun aChangeIsCountedAndApplied() = runComposeUiTest {
        val server = FakeServer("a.example")
        server.channels["of-x"] = InstalledChannel("of-x", "active", 8445, "c", listOf(NodeTransport("vyandex", "https://docs.yandex.ru/edit/d/" + "a".repeat(20))), "l4", "k".repeat(64))
        val c = testContainer(node = FakeNodeService(server))
        val link = ServerLink(c)
        runBlocking { link.connect(SshTarget("a.example", 22, "root", password = "pw", hostKey = "SHA256:a.example")) }
        c.settings.update { it.copy(navState = AppNavigator().apply { open(Dest.Node("a.example:22:root", "of-x")) }.snapshot().encode()) }
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts(), NoBrowserViews, link) }
        // The node is read from the server first: a switch touched before that is lost to the loaded draft.
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Подключить себе")).fetchSemanticsNodes().isNotEmpty() }
        // The legacy transports sit below the JS ones now: scroll to the switch before touching it.
        onNodeWithText("Cups.online").performScrollTo().performClick()
        onNodeWithText("Изменено: 1").assertIsDisplayed()
        onNodeWithText("Применить").performClick()
        waitUntil(timeoutMillis = 5_000) { server.channels.getValue("of-x").transports.size == 2 }
        assertEquals(listOf("vyandex", "cupsonline"), server.channels.getValue("of-x").transports.map { it.type })
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Изменено: 1")).fetchSemanticsNodes().isEmpty() }
    }
}
