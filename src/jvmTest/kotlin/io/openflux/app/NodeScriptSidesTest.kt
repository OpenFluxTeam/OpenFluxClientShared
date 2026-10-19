package io.openflux.app

import io.openflux.app.nav.Dest
import io.openflux.app.servers.NodeDraft
import io.openflux.app.servers.NodeEditorModel
import io.openflux.app.servers.NodeScript
import io.openflux.app.servers.NodeVerifier
import io.openflux.app.servers.ServerLink
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.ExitAddress
import io.openflux.desktop.model.ExtraTransport
import io.openflux.desktop.model.InstalledChannel
import io.openflux.desktop.model.InstalledScript
import io.openflux.desktop.model.NodeTransport
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ScriptParam
import io.openflux.desktop.model.ScriptSettingsCodec
import io.openflux.desktop.model.SshTarget
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.service.InMemoryScriptRepository
import io.openflux.desktop.service.ScriptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A script whose two ends are set up together by its own wizard (MAX: two accounts): the client's side lives in the
 * person's profile, the node's on the node, and neither ever lands in the other's place.
 */
class NodeScriptSidesTest {
    private val server = FakeServer("a.example")
    private val fake = FakeNodeService(server)
    private val conn = ScriptedConnection().apply { address = { ExitAddress.Known("a.example") } }
    private class Scripts(private val inner: InMemoryScriptRepository = InMemoryScriptRepository()) : ScriptRepository by inner {
        val bytes = mutableMapOf<String, ByteArray>()
        override val dirPath = "/scripts"
        override fun packageBytes(id: String) = bytes[id]?.let { it to ByteArray(0) }
    }
    private val scripts = Scripts()
    private val platform = TestPlatform()
    private val host = FakeSettingsPageHost()
    private val c: AppContainer = testContainer(node = fake, connection = conn, scripts = scripts, platform = platform, settingsPageHost = host)
    private val authorKey = "ab".repeat(32)
    private val link = ServerLink(c)
    private val id = "a.example:22:root"

    private val clientSide = mapOf("token" to "TOKEN-A", "uid" to "222", "chatId" to "9")
    private val nodeSide = mapOf("token" to "TOKEN-B", "uid" to "222")
    private val wizardAnswer = """{"client":{"token":"TOKEN-A","uid":"222","chatId":"9"},"node":{"token":"TOKEN-B","uid":"222"}}"""

    /** [scoped]: the params say they are settings, as the script declares them; false is an older install, where the first param reads as the profile's input. */
    private fun installWizardScript(scoped: Boolean = true) {
        val scope = if (scoped) "settings" else ""
        scripts.upsert(
            InstalledScript(
                id = "mx", name = "MAX", pubkeyHex = authorKey, fingerprint = "f", fileName = "mx.flux", setupPage = true,
                params = listOf(
                    ScriptParam("token", "Токен", type = "secret", required = true, scope = scope),
                    ScriptParam("uid", scope = scope), ScriptParam("chatId", scope = scope),
                ),
            ),
        )
        scripts.bytes["mx"] = "flux-mx".encodeToByteArray()
    }

    private suspend fun TestScope.editor(channel: String? = null, forProfile: Profile? = null): NodeEditorModel {
        link.connect(SshTarget("a.example", 22, "root", password = "pw", hostKey = "SHA256:a.example"))
        return NodeEditorModel(c, link, id, channel, this, NodeVerifier(c, now = { testScheduler.currentTime }), forProfile = forProfile)
    }

    /** The service runs its work off the test's thread: wait for it for real. */
    private suspend fun awaitReal(what: String, cond: () -> Boolean) = withContext(Dispatchers.Default) {
        repeat(300) { if (cond()) return@withContext; delay(10) }
        error("timed out waiting for $what")
    }

    private suspend fun runWizard(m: NodeEditorModel, answer: String = wizardAnswer) {
        m.setupScript("mx")
        awaitReal("the wizard page") { host.opened.isNotEmpty() && host.submit != null }
        host.submit!!(answer)
    }

    @Test
    fun theWizardsTwoSidesGoToTheirOwnPlaces() = runTest {
        installWizardScript()
        val m = editor()
        m.edit { it.copy(scripts = listOf(NodeScript("mx", wizard = true))) }
        runWizard(m)
        val s = m.draft.scripts.single()
        assertEquals(clientSide, s.settings)
        assertEquals(nodeSide, s.nodeSettings)
        assertEquals(listOf(Triple("/scripts/mx.flux", authorKey, "{}")), platform.setupStarts, "the wizard runs the installed file, pinned to its key")

        m.install(); advanceUntilIdle()
        assertNull(m.error)
        // the server gets the node's side only
        val sent = server.channels.values.single().transports.single { it.type == "script" }
        assertEquals(nodeSide, ScriptSettingsCodec.decode(sent.params))
        assertEquals("flux-mx", Base64.decode(sent.pkg).decodeToString())
        // the person's profile keeps the client's side only: no node token anywhere
        val p = c.profiles.profiles.value.single()
        val extra = p.extras.single { it.type == TransportType.SCRIPT }
        assertEquals(clientSide, extra.settings)
        assertTrue(extra.nodeSettings.isEmpty(), "the node's side stays on the server")
        assertFalse(p.toString().contains("TOKEN-B"), "the node account's token is not in the profile")
    }

    /** An install from before the script declared scopes reads its first param (the token) as the profile's input; a blank field must not wipe the wizard's token. */
    @Test
    fun aBlankProfileFieldDoesNotWipeWhatTheWizardSaved() = runTest {
        installWizardScript(scoped = false)
        assertEquals("token", scripts.byId("mx")?.primaryParam?.key, "the old rule")
        val m = editor()
        runWizard(m)
        m.install(); advanceUntilIdle()
        assertNull(m.error)
        val sent = server.channels.values.single().transports.single { it.type == "script" }
        assertEquals("TOKEN-B", ScriptSettingsCodec.decode(sent.params)["token"])
        assertEquals(mapOf("token" to "x", "a" to "b"), ScriptSettingsCodec.withPrimary(mapOf("a" to "b"), "token", "x"))
        assertEquals(mapOf("token" to "kept"), ScriptSettingsCodec.withPrimary(mapOf("token" to "kept"), "token", ""))
        assertEquals(mapOf("token" to "typed"), ScriptSettingsCodec.withPrimary(mapOf("token" to "kept"), "token", "typed"), "a typed value still wins")
    }

    @Test
    fun aWizardScriptIsNotInstalledBeforeItsWizardHasRun() = runTest {
        installWizardScript()
        val m = editor()
        m.edit { it.copy(scripts = listOf(NodeScript("mx", wizard = true))) }
        assertTrue(m.draft.problem().orEmpty().contains("мастер"), m.draft.problem())
        m.install(); advanceUntilIdle()
        assertTrue(m.error.orEmpty().contains("мастер"), m.error)
        assertTrue(server.channels.isEmpty())
    }

    @Test
    fun aFlatAnswerMeansBothEndsUseTheSameSettings() = runTest {
        installWizardScript()
        val m = editor()
        runWizard(m, """{"token":"T","room":"r"}""")
        val s = m.draft.scripts.single()
        assertEquals(mapOf("token" to "T", "room" to "r"), s.settings)
        assertNull(s.nodeSettings)
        assertEquals(s.settings, s.forNode)
    }

    @Test
    fun anAppliedChangeKeepsTheClientsSideAndSendsTheNodes() = runTest {
        installWizardScript()
        val ch = InstalledChannel(
            "of-s", "active", 8445, "ctx",
            listOf(NodeTransport("mailru", "https://cloud.mail.ru/public/aa/bb"), NodeTransport("script", "", script = "mx", params = ScriptSettingsCodec.encode(nodeSide))),
            "l4", "k".repeat(64),
        )
        server.channels[ch.id] = ch
        // this device made the node: its profile holds the client's side
        c.profiles.upsert(
            Profile(
                id = "p-mine", name = "MAX нода", transport = TransportType.DIRECT, value = "a.example:8445", session = true, secret = "k".repeat(64),
                source = ProfileSource.Node,
                extras = listOf(
                    ExtraTransport(TransportType.MAILRU, "https://cloud.mail.ru/public/aa/bb", priority = 90),
                    ExtraTransport(TransportType.SCRIPT, "", priority = 60, scriptId = "mx", settings = clientSide),
                ),
            ),
        )
        val m = editor(ch.id)
        m.load(); advanceUntilIdle()
        val s = m.draft.scripts.single()
        assertEquals(clientSide, s.settings, "the client's side comes from the profile here")
        assertEquals(nodeSide, s.nodeSettings, "the node's from the server")

        m.edit { it.copy(cups = true) }
        m.apply(); advanceUntilIdle()
        assertNull(m.error)
        val sent = server.channels.getValue("of-s").transports.single { it.type == "script" }
        assertEquals(nodeSide, ScriptSettingsCodec.decode(sent.params), "the node keeps its side")
        val p = c.profiles.profiles.value.single()
        assertEquals("p-mine", p.id)
        assertEquals(clientSide, p.extras.single { it.type == TransportType.SCRIPT }.settings, "the profile keeps its own side, not the node's")
        assertTrue(p.carriers.any { it.type == TransportType.CUPSONLINE }, "the other transport came with it: both live in one profile")
        assertTrue(p.carriers.any { it.type == TransportType.MAILRU })
    }

    @Test
    fun changingOnlyTheClientsSideTouchesNoServer() = runTest {
        installWizardScript()
        val ch = InstalledChannel("of-s", "active", 8445, "ctx", listOf(NodeTransport("script", "", script = "mx", params = ScriptSettingsCodec.encode(nodeSide))), "l4", "k".repeat(64))
        server.channels[ch.id] = ch
        val m = editor(ch.id)
        m.load(); advanceUntilIdle()
        fake.log.clear()
        m.edit { d -> d.copy(scripts = d.scripts.map { it.copy(settings = clientSide) }) }
        assertEquals(1, m.changes)
        m.apply(); advanceUntilIdle()
        assertFalse(fake.log.any { it.endsWith("setTransports") }, "the client's side is kept here, the server has nothing to learn")
    }

    @Test
    fun anotherDeviceStartsFromWhatTheNodeSaysAboutItselfWithoutItsSecrets() = runTest {
        installWizardScript()
        val ch = InstalledChannel("of-s", "active", 8445, "ctx", listOf(NodeTransport("script", "", script = "mx", params = ScriptSettingsCodec.encode(nodeSide))), "l4", "k".repeat(64))
        server.channels[ch.id] = ch
        val m = editor(ch.id)
        m.load(); advanceUntilIdle()
        val s = m.draft.scripts.single()
        assertEquals(mapOf("uid" to "222"), s.settings, "the node's user id is public: the person signs only their own account in")
        assertEquals(nodeSide, s.nodeSettings)
        val p = m.adopt()
        assertEquals(mapOf("uid" to "222"), p.extras.single { it.type == TransportType.SCRIPT }.settings, "no node token in the profile made from the server")
    }

    @Test
    fun aClientOnlyRunOfTheWizardKeepsTheNodesSide() = runTest {
        installWizardScript()
        val ch = InstalledChannel("of-s", "active", 8445, "ctx", listOf(NodeTransport("script", "", script = "mx", params = ScriptSettingsCodec.encode(nodeSide))), "l4", "k".repeat(64))
        server.channels[ch.id] = ch
        val m = editor(ch.id)
        m.load(); advanceUntilIdle()
        runWizard(m, """{"client":{"token":"MINE","uid":"222","chatId":"5"}}""")
        val s = m.draft.scripts.single()
        assertEquals(mapOf("token" to "MINE", "uid" to "222", "chatId" to "5"), s.settings)
        assertEquals(nodeSide, s.nodeSettings, "the node was not asked again")
        assertTrue(platform.setupStarts.single().third.contains("\"uid\":\"222\""), "the wizard is told whom to call: it asks for one account")
    }

    @Test
    fun aNodeMadeForAnExistingProfileKeepsThatProfile() = runTest {
        installWizardScript()
        val old = Profile(
            id = "p-old", name = "Мой MAX", icon = "ic_key", transport = TransportType.SCRIPT, scriptId = "mx", settings = clientSide,
            createdAt = 77, source = ProfileSource.Manual,
        )
        c.profiles.upsert(old)
        val m = editor(forProfile = old)
        assertEquals(listOf(NodeScript("mx", "", clientSide, null, wizard = true)), m.draft.scripts, "what the profile had is the start")
        assertEquals("Мой MAX", m.draft.name)
        runWizard(m)
        m.install(); advanceUntilIdle()
        assertNull(m.error)
        val p = c.profiles.profiles.value.single()
        assertEquals("p-old", p.id, "the profile is kept, not made again")
        assertEquals("Мой MAX", p.name)
        assertEquals("ic_key", p.icon)
        assertEquals(77, p.createdAt)
        assertEquals(ProfileSource.Node, p.source)
        assertTrue(p.session && p.secret.length == 64, "it is a Session profile with the new node's key now")
        assertEquals(clientSide, p.extras.single { it.type == TransportType.SCRIPT }.settings)
        assertEquals(Dest.Node(id, server.channels.keys.single()), m.done)
    }

    @Test
    fun theNodeEditorsDraftSeesWhichEdgeIsWhich() {
        val split = NodeScript("mx", "", clientSide, nodeSide, wizard = true)
        assertTrue(split.split)
        assertEquals(nodeSide, split.forNode)
        val flat = NodeScript("mx", "", clientSide)
        assertFalse(flat.split)
        assertEquals(clientSide, flat.forNode)
        val d = NodeDraft(scripts = listOf(split))
        assertEquals(nodeSide, ScriptSettingsCodec.decode(d.toTransports().single().params))
        // the same draft with only the client's side changed is no change for the server
        val other = d.copy(scripts = listOf(split.copy(settings = mapOf("token" to "OTHER"))))
        assertEquals(1, other.changes(d))
        assertFalse(other.serverChanged(d))
        // a changed node side is
        assertTrue(d.copy(scripts = listOf(split.copy(nodeSettings = mapOf("token" to "OTHER")))).serverChanged(d))
        // and for an ordinary script, whose one set of settings both ends use, any change is
        assertTrue(NodeDraft(scripts = listOf(flat.copy(settings = mapOf("a" to "b")))).serverChanged(NodeDraft(scripts = listOf(flat))))
    }

    @Test
    fun aDraftFromAProfileMapsItsCarriers() {
        val p = Profile(
            id = "p", name = "N", transport = TransportType.MAILRU, value = "https://cloud.mail.ru/public/aa/bb", session = true, secret = "k".repeat(32),
            extras = listOf(
                ExtraTransport(TransportType.DIRECT, "h:1"),
                ExtraTransport(TransportType.SCRIPT, "", scriptId = "mx", settings = clientSide, nodeSettings = nodeSide),
                ExtraTransport(TransportType.CUPSONLINE, "rooms-code"),
            ),
        )
        val d = NodeDraft.fromProfile(p, wizardScripts = setOf("mx"))
        assertTrue(d.mailru && d.mailruLink.endsWith("/aa/bb"))
        assertTrue(d.cups && d.cupsRooms == "rooms-code")
        assertEquals(listOf(NodeScript("mx", "", clientSide, nodeSide, wizard = true)), d.scripts)
        assertFalse(d.volga || d.telemost || d.avito, "direct is the node's own, it is not a draft transport")
    }
}
