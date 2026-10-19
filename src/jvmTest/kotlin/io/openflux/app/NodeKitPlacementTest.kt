package io.openflux.app

import io.openflux.app.nav.Dest
import io.openflux.app.servers.NodeEditorModel
import io.openflux.app.servers.NodePlacement
import io.openflux.app.servers.NodeScript
import io.openflux.app.servers.NodeVerifier
import io.openflux.app.servers.ServerLink
import io.openflux.desktop.model.ExitAddress
import io.openflux.desktop.model.InstalledScript
import io.openflux.desktop.model.KitDelivery
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ProfileTransfer
import io.openflux.desktop.model.ScriptParam
import io.openflux.desktop.model.ScriptSettingsCodec
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.service.InMemoryScriptRepository
import io.openflux.desktop.service.ScriptRepository
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A node that is not on an SSH server: made from a kit, its owner's profile the only record of it. */
class NodeKitPlacementTest {
    private val server = FakeServer("a.example")
    private val fake = FakeNodeService(server)
    private val conn = ScriptedConnection().apply { address = { ExitAddress.Known("a.example") } }
    private class Scripts(private val inner: InMemoryScriptRepository = InMemoryScriptRepository()) : ScriptRepository by inner {
        val bytes = mutableMapOf<String, ByteArray>()
        override val dirPath = "/scripts"
        override fun packageBytes(id: String) = bytes[id]?.let { it to ByteArray(0) }
    }
    private val scripts = Scripts()
    private val c: AppContainer = testContainer(node = fake, connection = conn, scripts = scripts)
    private val link = ServerLink(c)
    private val authorKey = "ab".repeat(32)
    private val doc = "https://cloud.mail.ru/public/aaaa/bbbb"
    private val clientSide = mapOf("token" to "TOKEN-A", "uid" to "222", "chatId" to "9")
    private val nodeSide = mapOf("token" to "TOKEN-B", "uid" to "222")

    private fun installWizardScript() {
        scripts.upsert(
            InstalledScript(
                id = "mx", name = "MAX", pubkeyHex = authorKey, fingerprint = "f", fileName = "mx.flux", setupPage = true,
                params = listOf(ScriptParam("token", "Токен", type = "secret", required = true, scope = "settings"), ScriptParam("uid", scope = "settings"), ScriptParam("chatId", scope = "settings")),
            ),
        )
        scripts.bytes["mx"] = "flux-mx".encodeToByteArray()
    }

    private fun TestScope.editor(placement: NodePlacement, channel: String? = null, forProfile: Profile? = null) =
        NodeEditorModel(c, link, placement.id ?: error("a placement without a server"), channel, this, NodeVerifier(c, now = { testScheduler.currentTime }), forProfile = forProfile)

    private fun maxDraft(m: NodeEditorModel) = m.edit {
        it.copy(mailru = true, mailruLink = doc, scripts = listOf(NodeScript("mx", "", clientSide, nodeSide, wizard = true)))
    }

    @Test
    fun theKitPlacementsAreNamedAndMapped() {
        assertEquals(NodePlacement.Ssh, NodePlacement.of("203.0.113.7:22:root"))
        assertEquals(NodePlacement.Manual, NodePlacement.of("kit:manual"))
        assertEquals(NodePlacement.Device, NodePlacement.of("kit:device"))
        assertEquals(NodePlacement.Here, NodePlacement.of("kit:here"))
        assertEquals(NodePlacement.Device, NodePlacement.of(KitDelivery.Device))
        assertTrue(NodePlacement.Manual.kit && !NodePlacement.Ssh.kit)
        assertEquals(KitDelivery.Here, NodePlacement.Here.delivery)
        assertNull(NodePlacement.Ssh.delivery)
    }

    @Test
    fun aNodeWithoutSshIsMadeOfAKitAndItsOwnersProfile() = runTest {
        installWizardScript()
        val m = editor(NodePlacement.Manual)
        assertTrue(m.kit && m.creating && !m.needsSudo)
        maxDraft(m)
        m.install(); advanceUntilIdle()
        assertNull(m.error)
        assertTrue(fake.log.none { it.contains("connect") || it.contains("plan") || it.contains("apply") }, "nothing connected to a server: ${fake.log}")
        val (ch, sent) = fake.kits.single()
        assertEquals(64, ch.key.length)
        // the node gets its side of the script, and this device's package with the author's key
        val script = sent.single { it.type == "script" }
        assertEquals(nodeSide, ScriptSettingsCodec.decode(script.params))
        assertEquals("flux-mx", Base64.decode(script.pkg).decodeToString())
        assertEquals(authorKey, script.pubkey)

        val p = c.profiles.profiles.value.single()
        assertEquals(ProfileSource.Node, p.source)
        assertTrue(p.session)
        assertEquals(ch.key, p.secret)
        assertEquals(KitDelivery.Manual, p.kitNode?.delivery)
        assertEquals(ch.id, p.kitNode?.channel)
        assertEquals(TransportType.MAILRU, p.transport, "the best carrier is the main one")
        val extra = p.extras.single()
        assertEquals("mx", extra.scriptId)
        assertEquals(clientSide, extra.settings)
        assertEquals(nodeSide, extra.nodeSettings, "the owner keeps both sides: it can make the kit again")
        assertEquals(p.id, c.settings.settings.value.selectedProfileId)
        assertEquals(Dest.Node("kit:manual", ch.id), m.done)
    }

    @Test
    fun aMaxOnlyNodeNeedsNoDirectAddress() = runTest {
        installWizardScript()
        val m = editor(NodePlacement.Device)
        m.edit { it.copy(scripts = listOf(NodeScript("mx", "", clientSide, nodeSide, wizard = true))) }
        m.install(); advanceUntilIdle()
        assertNull(m.error)
        val p = c.profiles.profiles.value.single()
        assertEquals(TransportType.SCRIPT, p.transport)
        assertEquals("mx", p.scriptId)
        assertTrue(p.carriers.none { it.type == TransportType.DIRECT })
        assertEquals(emptyList(), p.problems())
        assertEquals(KitDelivery.Device, p.kitNode?.delivery)
    }

    @Test
    fun aNodeWithoutATransportIsNotMade() = runTest {
        val m = editor(NodePlacement.Manual)
        assertTrue(m.installProblem.orEmpty().contains("транспорт"), "the button says why it cannot run")
        m.install(); advanceUntilIdle()
        assertTrue(m.error.orEmpty().contains("транспорт"), m.error)
        assertTrue(c.profiles.profiles.value.isEmpty())
        assertTrue(fake.kits.isEmpty(), "nothing was asked of the core")
    }

    @Test
    fun aFailedKitLeavesNoProfile() = runTest {
        installWizardScript()
        fake.failKit = "пакет не читается"
        val m = editor(NodePlacement.Manual)
        maxDraft(m)
        m.install(); advanceUntilIdle()
        assertEquals("пакет не читается", m.error)
        assertTrue(c.profiles.profiles.value.isEmpty())
        assertNull(m.done)
    }

    @Test
    fun theNodeIsReadBackFromItsProfileAndChangedWithTheSameKeyAndContext() = runTest {
        installWizardScript()
        val first = editor(NodePlacement.Manual)
        maxDraft(first)
        first.install(); advanceUntilIdle()
        val before = c.profiles.profiles.value.single()
        val channel = before.kitNode!!.channel

        val m = editor(NodePlacement.Manual, channel)
        m.load(); advanceUntilIdle()
        assertNull(m.error)
        assertTrue(m.draft.mailru)
        val s = m.draft.scripts.single()
        assertEquals(clientSide, s.settings)
        assertEquals(nodeSide, s.nodeSettings)
        assertEquals(0, m.changes)

        fake.kits.clear()
        m.edit { it.copy(cups = true, cupsRooms = "rooms-code") }
        assertEquals(1, m.changes)
        m.apply(); advanceUntilIdle()
        assertNull(m.error)
        val after = c.profiles.profiles.value.single()
        assertEquals(before.id, after.id, "the same profile, changed")
        assertEquals(before.secret, after.secret, "the key stays: the node keeps its clients")
        assertEquals(before.context, after.context, "the context stays, as set-transports keeps it")
        assertTrue(after.carriers.any { it.type == TransportType.CUPSONLINE })
        assertTrue(after.carriers.any { it.type == TransportType.MAILRU })
        val script = after.extras.single { it.type == TransportType.SCRIPT }
        assertEquals(clientSide, script.settings)
        assertEquals(nodeSide, script.nodeSettings, "the other transport joined; nothing of the script's two sides was lost")
        assertEquals(before.kitNode, after.kitNode)
        assertTrue(m.notice.orEmpty().contains("команду"), m.notice)
        assertEquals(before.secret, fake.kits.single().first.key)
        assertEquals(0, m.changes)
    }

    @Test
    fun changingOnlyThisDevicesSideTouchesNoKit() = runTest {
        installWizardScript()
        val first = editor(NodePlacement.Manual)
        maxDraft(first)
        first.install(); advanceUntilIdle()
        val m = editor(NodePlacement.Manual, c.profiles.profiles.value.single().kitNode!!.channel)
        m.load(); advanceUntilIdle()
        fake.kits.clear()
        m.edit { d -> d.copy(scripts = d.scripts.map { it.copy(settings = it.settings + ("chatId" to "10")) }) }
        m.apply(); advanceUntilIdle()
        assertTrue(fake.kits.isEmpty(), "the client's side is this device's: the node's kit does not change")
        assertEquals("Сохранено", m.notice)
    }

    @Test
    fun theCommandIsRenderedFromTheProfileWithThisDevicesPackage() = runTest {
        installWizardScript()
        val first = editor(NodePlacement.Manual)
        maxDraft(first)
        first.install(); advanceUntilIdle()
        val m = editor(NodePlacement.Manual, c.profiles.profiles.value.single().kitNode!!.channel)
        m.load(); advanceUntilIdle()
        fake.kits.clear()
        m.showKit(); advanceUntilIdle()
        assertNull(m.error)
        val kit = m.kitShown
        assertNotNull(kit)
        assertTrue(kit.script.startsWith("#!/bin/sh"))
        val sent = fake.kits.single().second.single { it.type == "script" }
        assertEquals(nodeSide, ScriptSettingsCodec.decode(sent.params), "the node's side")
        assertEquals("flux-mx", Base64.decode(sent.pkg).decodeToString())
        m.hideKit()
        assertNull(m.kitShown)
    }

    @Test
    fun theTransferLinesKeepEachEndToItsOwnSide() = runTest {
        installWizardScript()
        val m0 = editor(NodePlacement.Device)
        maxDraft(m0)
        m0.install(); advanceUntilIdle()
        val m = editor(NodePlacement.Device, c.profiles.profiles.value.single().kitNode!!.channel)
        m.load(); advanceUntilIdle()
        val node = assertNotNull(m.transferLine(ProfileTransfer.Role.Node))
        val client = assertNotNull(m.transferLine(ProfileTransfer.Role.Client))
        val nodeText = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(node.removePrefix(ProfileTransfer.PREFIX)).decodeToString()
        val clientText = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(client.removePrefix(ProfileTransfer.PREFIX)).decodeToString()
        assertTrue("TOKEN-B" in nodeText && "TOKEN-A" !in nodeText)
        assertTrue("TOKEN-A" in clientText && "TOKEN-B" !in clientText)
        assertTrue(nodeText.contains(authorKey), "the node's device is told which author's script this is")
        assertEquals(ProfileSource.ThisNode, ProfileTransfer.decode(node).profile.source)
    }

    @Test
    fun aNodeMadeForAnExistingProfileKeepsItsKeyWhenANodeCanUseIt() = runTest {
        installWizardScript()
        val key = "cd".repeat(32)
        val old = Profile(id = "mine", name = "Мой MAX", icon = "ic_key", transport = TransportType.SCRIPT, scriptId = "mx", settings = clientSide, secret = key, session = true, createdAt = 9)
        c.profiles.upsert(old)
        val m = editor(NodePlacement.Manual, forProfile = old)
        m.edit { d -> d.copy(scripts = d.scripts.map { it.copy(nodeSettings = nodeSide) }) }
        m.install(); advanceUntilIdle()
        assertNull(m.error)
        val p = c.profiles.profiles.value.single()
        assertEquals("mine", p.id)
        assertEquals(key, p.secret, "a 64-hex key is the channel's: the profile's other devices stay connected")
        assertEquals("Мой MAX", p.name)
        assertEquals(9, p.createdAt)
        // a key a node cannot use is replaced
        val odd = old.copy(id = "odd", secret = "not-hex-but-long-enough")
        c.profiles.upsert(odd)
        val m2 = editor(NodePlacement.Manual, forProfile = odd)
        m2.edit { d -> d.copy(scripts = d.scripts.map { it.copy(nodeSettings = nodeSide) }) }
        m2.install(); advanceUntilIdle()
        val p2 = c.profiles.profiles.value.single { it.id == "odd" }
        assertEquals(64, p2.secret.length)
        assertFalse(p2.secret == "not-hex-but-long-enough")
    }

    @Test
    fun removingAKitNodeTakesOnlyItsProfile() = runTest {
        installWizardScript()
        val first = editor(NodePlacement.Manual)
        maxDraft(first)
        first.install(); advanceUntilIdle()
        c.profiles.upsert(Profile(id = "other", name = "Другой", value = "https://docs.yandex.ru/edit/d/" + "a".repeat(20)))
        val m = editor(NodePlacement.Manual, c.profiles.profiles.value.first { it.kitNode != null }.kitNode!!.channel)
        m.load(); advanceUntilIdle()
        var gone = false
        m.remove { gone = true }; advanceUntilIdle()
        assertTrue(gone)
        assertEquals(listOf("other"), c.profiles.profiles.value.map { it.id })
        assertTrue(fake.log.none { it.endsWith("remove") }, "no server was asked")
    }

    @Test
    fun aKitNodeWhoseProfileIsGoneSaysSo() = runTest {
        val m = editor(NodePlacement.Manual, "of-gone")
        m.load(); advanceUntilIdle()
        assertTrue(m.error.orEmpty().contains("удалён"), m.error)
    }

    @Test
    fun signInsCannotBePushedToANodeWithoutSsh() = runTest {
        installWizardScript()
        val first = editor(NodePlacement.Manual)
        maxDraft(first)
        first.install(); advanceUntilIdle()
        val m = editor(NodePlacement.Manual, c.profiles.profiles.value.single().kitNode!!.channel)
        m.load(); advanceUntilIdle()
        m.pushYandexSignIn()
        assertTrue(m.error.orEmpty().contains("SSH"), m.error)
    }
}
