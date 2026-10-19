package io.openflux.app

import io.openflux.app.add.AddKeyModel
import io.openflux.app.add.KeyResult
import io.openflux.app.nav.Dest
import io.openflux.desktop.FakeShareLinkCodec
import io.openflux.desktop.model.DocProfile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ShareConfig
import io.openflux.desktop.model.ShareTransport
import io.openflux.desktop.model.SubStatus
import io.openflux.desktop.model.SubscriptionDocument
import io.openflux.desktop.model.SubscriptionKind
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AddKeyModelTest {
    private val api = FakeSubApi()
    private val platform = TestPlatform()
    private val c = testContainer(platform = platform, subscriptions = api)
    private val key = "openflux://sub?u=https%3A%2F%2Fsub.example%2Fsub%2Fabc"

    private suspend fun link(name: String) = FakeShareLinkCodec().encode(
        ShareConfig(name = name, secret = "s".repeat(64), transports = listOf(ShareTransport(type = "direct", dial = "203.0.113.7:8445", priority = 50))),
    )

    @Test
    fun aProfileLinkIsShownAndAdded() = runTest {
        val m = AddKeyModel(c, this)
        m.onText(link("Нода друга")); advanceUntilIdle()
        val r = assertIs<KeyResult.Profile>(m.result)
        assertEquals("Нода друга", r.config.name)
        val dest = m.add(); advanceUntilIdle()
        val p = c.profiles.profiles.value.single()
        assertEquals(ProfileSource.Link, p.source)
        assertEquals(Dest.Profile(p.id), dest)
        assertEquals(p.id, c.settings.settings.value.selectedProfileId)
    }

    @Test
    fun aSubscriptionKeyIsPreviewedAndAdded() = runTest {
        api.document = SubscriptionDocument(title = "Мой VPN", status = SubStatus.ACTIVE, profiles = listOf(DocProfile("1", "Амстердам", "x")))
        val m = AddKeyModel(c, this)
        m.onText(key); advanceUntilIdle()
        val r = assertIs<KeyResult.Subscription>(m.result)
        assertEquals("Мой VPN", r.preview.document.title)
        assertEquals(Dest.Account, m.add())
        advanceUntilIdle()
        assertEquals(SubscriptionKind.Key, c.subscription.state.value?.kind)
    }

    @Test
    fun anEndedKeyCannotBeAdded() = runTest {
        api.document = SubscriptionDocument(status = SubStatus.REVOKED)
        val m = AddKeyModel(c, this)
        m.onText(key); advanceUntilIdle()
        val r = assertIs<KeyResult.Subscription>(m.result)
        assertEquals(false, r.canAdd)
    }

    @Test
    fun anythingElseIsSaidPlainly() = runTest {
        val m = AddKeyModel(c, this)
        m.onText("привет"); advanceUntilIdle()
        assertEquals("Это не ключ подписки, не ссылка openflux:// и не ссылка из бота", (m.result as KeyResult.Invalid).message)
        m.onText(""); advanceUntilIdle()
        assertIs<KeyResult.Empty>(m.result)
    }

    @Test
    fun aPasteTakesTheClipboardAndTheFirstLineOfSeveral() = runTest {
        val m = AddKeyModel(c, this)
        m.paste()
        assertEquals("В буфере обмена пусто", (m.result as KeyResult.Invalid).message)
        platform.clipboard = "\n  " + link("Первая") + "\n" + link("Вторая")
        m.paste(); advanceUntilIdle()
        assertEquals("Первая", (m.result as KeyResult.Profile).config.name)
    }

    @Test
    fun textFromOutsideIsTakenOnce() = runTest {
        c.pendingKey.value = key
        val m = AddKeyModel(c, this)
        advanceUntilIdle()
        assertEquals(key, m.text)
        assertEquals(null, c.pendingKey.value)
    }

    // ---- a profile from another device of the person ----

    private val authorKey = "ab".repeat(32)
    private val clientSide = mapOf("token" to "TOKEN-A", "uid" to "222", "chatId" to "9")
    private val nodeSide = mapOf("token" to "TOKEN-B", "uid" to "222")

    private fun ownerProfile() = io.openflux.desktop.model.Profile(
        id = "owner", name = "Дача", transport = io.openflux.desktop.model.TransportType.SCRIPT, scriptId = "mx",
        settings = clientSide, nodeSettings = nodeSide, secret = "cd".repeat(32), session = true, source = ProfileSource.Node,
    )

    private fun installMx(key: String = authorKey) = (c.scripts as io.openflux.desktop.service.InMemoryScriptRepository).upsert(
        io.openflux.desktop.model.InstalledScript(id = "mx", name = "MAX", pubkeyHex = key, fingerprint = "f", fileName = "mx.flux", setupPage = true),
    )

    private fun line(role: io.openflux.desktop.model.ProfileTransfer.Role, key: String = authorKey) =
        io.openflux.desktop.model.ProfileTransfer.encode(ownerProfile(), role, mapOf("mx" to key))

    @Test
    fun aClientProfileFromAnotherDeviceIsAddedForConnecting() = runTest {
        installMx()
        c.settings.update { it.copy(experimental = true) }
        val m = AddKeyModel(c, this)
        m.onText(line(io.openflux.desktop.model.ProfileTransfer.Role.Client)); advanceUntilIdle()
        val r = assertIs<KeyResult.Transfer>(m.result)
        assertEquals(null, r.note)
        val dest = m.add(); advanceUntilIdle()
        val p = c.profiles.profiles.value.single()
        assertEquals(Dest.Profile(p.id), dest)
        assertEquals(ProfileSource.Link, p.source)
        assertEquals(clientSide, p.carriers.single().settings, "its own account's side")
        assertTrue(p.carriers.single().nodeSettings.isEmpty(), "nothing of the node's")
        assertEquals(p.id, c.settings.settings.value.selectedProfileId)
    }

    @Test
    fun aNodeProfileFromAnotherDeviceIsAddedToBeRunNotConnectedThrough() = runTest {
        installMx()
        val m = AddKeyModel(c, this)
        m.onText(line(io.openflux.desktop.model.ProfileTransfer.Role.Node)); advanceUntilIdle()
        val r = assertIs<KeyResult.Transfer>(m.result)
        assertEquals(io.openflux.desktop.model.ProfileTransfer.Role.Node, r.received.role)
        assertEquals(Dest.ThisDevice, m.add())
        val p = c.profiles.profiles.value.single()
        assertEquals(ProfileSource.ThisNode, p.source)
        assertEquals(nodeSide, p.carriers.single().settings, "the node's side, as the plain settings it runs with")
        assertEquals(null, p.exitProblem())
        assertEquals(p.id, c.settings.settings.value.selectedProfileId, "the node screen starts with it chosen")
    }

    @Test
    fun aProfileNeedsItsScriptHereUnderTheSameAuthor() = runTest {
        val m = AddKeyModel(c, this)
        m.onText(line(io.openflux.desktop.model.ProfileTransfer.Role.Client)); advanceUntilIdle()
        assertTrue((m.result as KeyResult.Invalid).message.contains("Нужен JS-транспорт"), "not installed here")
        installMx(key = "cd".repeat(32))
        m.onText(line(io.openflux.desktop.model.ProfileTransfer.Role.Client)); advanceUntilIdle()
        assertTrue((m.result as KeyResult.Invalid).message.contains("другим автором"), "another author's script is not the code the secrets are meant for")
        assertTrue(c.profiles.profiles.value.isEmpty())
    }

    @Test
    fun withoutExperimentalFeaturesTheProfileIsAddedWithAWarning() = runTest {
        installMx()
        val m = AddKeyModel(c, this)
        m.onText(line(io.openflux.desktop.model.ProfileTransfer.Role.Client)); advanceUntilIdle()
        assertTrue(assertIs<KeyResult.Transfer>(m.result).note.orEmpty().contains("Экспериментальные"))
    }

    @Test
    fun aDamagedTransferLineIsSaidPlainly() = runTest {
        installMx()
        val m = AddKeyModel(c, this)
        m.onText(line(io.openflux.desktop.model.ProfileTransfer.Role.Client).dropLast(30)); advanceUntilIdle()
        assertTrue((m.result as KeyResult.Invalid).message.contains("повреждён"))
    }
}
