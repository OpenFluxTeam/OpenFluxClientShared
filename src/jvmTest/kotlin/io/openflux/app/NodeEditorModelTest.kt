package io.openflux.app

import io.openflux.app.nav.Dest
import io.openflux.app.servers.NodeEditorModel
import io.openflux.app.servers.NodeVerifier
import io.openflux.app.servers.ServerLink
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.ExitAddress
import io.openflux.desktop.model.InstalledChannel
import io.openflux.desktop.model.NodeMode
import io.openflux.desktop.model.NodeTransport
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.SshTarget
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.service.AppContainer
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeEditorModelTest {
    private val doc = "https://docs.yandex.ru/edit/d/" + "a".repeat(20)
    private val server = FakeServer("a.example")
    private val fake = FakeNodeService(server)
    private val conn = ScriptedConnection().apply { address = { ExitAddress.Known("a.example") } }
    private val c: AppContainer = testContainer(node = fake, connection = conn)
    private val link = ServerLink(c)
    private val id = "a.example:22:root"

    private var yandexCookies = ""

    private suspend fun TestScope.editor(channel: String? = null): NodeEditorModel {
        link.connect(SshTarget("a.example", 22, "root", password = "pw", hostKey = "SHA256:a.example"))
        return NodeEditorModel(c, link, id, channel, this, NodeVerifier(c, now = { testScheduler.currentTime }), yandexCookies = { yandexCookies })
    }

    private fun installedNode(): InstalledChannel {
        val ch = InstalledChannel("of-x", "active", 8445, "ctx", listOf(NodeTransport("vyandex", doc)), "l4", "k".repeat(64))
        server.channels[ch.id] = ch
        return ch
    }

    @Test
    fun quickInstallMakesADirectNodeSavesItsProfileAndProvesIt() = runTest {
        val m = editor()
        m.install(); advanceUntilIdle()
        val made = server.channels.values.single()
        assertEquals(emptyList(), made.transports, "direct only")
        val p = c.profiles.profiles.value.single()
        assertEquals(ProfileSource.Node, p.source)
        assertEquals("Нода a.example", p.name)
        assertEquals(p.id, c.settings.settings.value.selectedProfileId)
        assertEquals(Dest.Node(id, made.id), m.done)
        assertNull(m.error)
        assertTrue(conn.state.value is ConnectionState.Connected, "the check connected through it")
    }

    @Test
    fun aPlanProblemIsShownAndNothingIsInstalled() = runTest {
        val m = editor()
        fake.failPlan = "на сервере нет systemd"
        m.install(); advanceUntilIdle()
        assertEquals("на сервере нет systemd", m.error)
        assertTrue(server.channels.isEmpty())
        assertTrue(c.profiles.profiles.value.isEmpty())
        assertNull(m.done)
    }

    @Test
    fun aWrongSudoPasswordIsSaidAtTheButton() = runTest {
        server.sudo = "password"; server.sudoPassword = "right"
        val m = editor()
        assertTrue(m.needsSudo)
        m.sudoInput = "wrong"
        m.install(); advanceUntilIdle()
        assertEquals("sudo не принял пароль", m.error)
        assertTrue(server.channels.isEmpty())
    }

    @Test
    fun aFailedCheckKeepsTheNodeAndOffersRemoval() = runTest {
        conn.address = { ExitAddress.Known("198.51.100.1") }
        val m = editor()
        m.install(); advanceUntilIdle()
        assertEquals("Запрос вышел с адреса 198.51.100.1, а не с адреса сервера", m.verifyFailed)
        assertEquals(1, server.channels.size)
        m.removeFailed(); advanceUntilIdle()
        assertTrue(server.channels.isEmpty())
        assertTrue(c.profiles.profiles.value.isEmpty())
    }

    @Test
    fun editingLoadsCountsAndDiscards() = runTest {
        installedNode()
        val m = editor("of-x")
        m.load(); advanceUntilIdle()
        assertEquals(true, m.draft.volga)
        m.edit { it.copy(mode = NodeMode.L3) }
        assertEquals(1, m.changes)
        m.discard()
        assertEquals(0, m.changes)
    }

    @Test
    fun applyChangesTheServerAndTheProfileHere() = runTest {
        installedNode()
        val m = editor("of-x")
        m.load(); advanceUntilIdle()
        val before = m.adopt()
        m.edit { it.copy(mode = NodeMode.L3, cups = true) }
        m.apply(); advanceUntilIdle()
        assertNull(m.error)
        val now = server.channels.getValue("of-x")
        assertEquals("l3", now.mode)
        assertEquals(listOf("vyandex", "cupsonline"), now.transports.map { it.type })
        val p = c.profiles.profiles.value.single()
        assertEquals(before.id, p.id, "the same profile, updated")
        assertTrue(p.carriers.any { it.type == TransportType.CUPSONLINE })
        assertEquals(0, m.changes)
    }

    @Test
    fun aFailedApplyKeepsTheDraft() = runTest {
        installedNode()
        val m = editor("of-x")
        m.load(); advanceUntilIdle()
        m.edit { it.copy(mode = NodeMode.L3) }
        fake.dropNext = true
        m.apply(); advanceUntilIdle()
        assertNotNull(m.error)
        assertEquals(1, m.changes)
        assertEquals("l4", server.channels.getValue("of-x").mode)
    }

    @Test
    fun aNewNameStaysOnThisDevice() = runTest {
        installedNode()
        val m = editor("of-x")
        m.load(); advanceUntilIdle()
        m.adopt()
        fake.log.clear()
        m.edit { it.copy(name = "Амстердам") }
        m.apply(); advanceUntilIdle()
        assertFalse(fake.log.any { it.endsWith("setTransports") })
        assertEquals("Амстердам", c.profiles.profiles.value.single().name)
        assertEquals(0, m.changes)
    }

    @Test
    fun removingDeletesTheNodeAndItsProfile() = runTest {
        installedNode()
        val m = editor("of-x")
        m.load(); advanceUntilIdle()
        m.adopt()
        var gone = false
        m.remove { gone = true }; advanceUntilIdle()
        assertTrue(gone)
        assertTrue(server.channels.isEmpty())
        assertTrue(c.profiles.profiles.value.isEmpty())
    }

    @Test
    fun theYandexSignInGoesToTheNode() = runTest {
        installedNode()
        val m = editor("of-x")
        m.load(); advanceUntilIdle()
        m.pushYandexSignIn(); advanceUntilIdle()
        assertEquals("Сначала войдите в Яндекс: Настройки → Сервисы", m.error)
        yandexCookies = "Session_id=s; yandex_login=ivan"
        m.pushYandexSignIn(); advanceUntilIdle()
        assertEquals(listOf("Session_id=s; yandex_login=ivan"), fake.cookiesSent)
        assertNull(m.error)
    }

    @Test
    fun aDocumentMadeHereTakesItsSignInToANewNode() = runTest {
        val m = editor()
        m.createYandexDocument(); advanceUntilIdle()
        m.install(); advanceUntilIdle()
        assertNull(m.error)
        assertEquals(listOf("Session_id=s; yandex_login=ivan"), fake.cookiesSent)
    }

    @Test
    fun theSignInGoesOnlyWithTheDocumentItMade() = runTest {
        installedNode()
        val m = editor("of-x")
        m.load(); advanceUntilIdle()
        m.createYandexDocument(); advanceUntilIdle()
        m.edit { it.copy(yandexDoc = "https://docs.yandex.ru/edit/d/" + "b".repeat(20)) }
        m.checkDocument(); advanceUntilIdle()
        m.apply(); advanceUntilIdle()
        assertNull(m.error)
        assertEquals(emptyList(), fake.cookiesSent, "another document goes without the sign-in made for the first")
    }

    @Test
    fun aNodeWithoutAProfileHereGetsOneFromTheServersKey() = runTest {
        installedNode()
        val m = editor("of-x")
        m.load(); advanceUntilIdle()
        assertNull(m.localProfile)
        val p = m.adopt()
        assertEquals(ProfileSource.Node, p.source)
        assertEquals(p, m.localProfile)
        assertEquals(p.id, m.adopt().id, "found again, not made twice")
    }
}
