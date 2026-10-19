package io.openflux.desktop

import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.CoreConfig
import io.openflux.desktop.model.CorePaths
import io.openflux.desktop.model.ExtraTransport
import io.openflux.desktop.model.KitDelivery
import io.openflux.desktop.model.KitNode
import io.openflux.desktop.model.KitScriptSides
import io.openflux.desktop.model.KitTransport
import io.openflux.desktop.model.NewChannel
import io.openflux.desktop.model.NodeKit
import io.openflux.desktop.model.NodeMode
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ProfileTransfer
import io.openflux.desktop.model.ScriptCarrierLookup
import io.openflux.desktop.model.ScriptSettingsCodec
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.model.installedKit
import io.openflux.desktop.model.nodeTransports
import io.openflux.desktop.model.ownerOfKit
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileTransferTest {
    private val key = "ab".repeat(32)
    private val authorKey = "cd".repeat(32)
    private val clientSide = mapOf("token" to "TOKEN-A", "uid" to "222", "chatId" to "9")
    private val nodeSide = mapOf("token" to "TOKEN-B", "uid" to "222")
    private val kit = NodeKit(
        script = "#!/bin/sh", paste = "", context = "ctx",
        transports = listOf(
            KitTransport("mailru", "https://cloud.mail.ru/public/aa/bb", 90),
            KitTransport("script", "", 60, "mx"),
        ),
    )

    private fun owner(delivery: KitDelivery = KitDelivery.Device) = Profile.ownerOfKit(
        id = "p1", name = "Дача", icon = "ic_terminal", createdAt = 5, channel = NewChannel("of-kit", key), kit = kit,
        delivery = delivery, mode = NodeMode.L4, autoUpdate = true, scripts = mapOf("mx" to KitScriptSides(clientSide, nodeSide)),
    )

    /** The text a person would see in the line: decoded, so a secret that is in it can be found. */
    private fun plain(line: String): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(line.removePrefix(ProfileTransfer.PREFIX)).decodeToString()

    @Test
    fun theOwnersProfileHoldsTheKeyTheCarriersAndBothSides() {
        val p = owner()
        assertEquals(ProfileSource.Node, p.source)
        assertTrue(p.session)
        assertEquals(key, p.secret)
        assertEquals("ctx", p.context)
        assertEquals(KitNode("of-kit", KitDelivery.Device, "l4", true), p.kitNode)
        // the best carrier is the main one, the script an extra with both sides
        assertEquals(TransportType.MAILRU, p.transport)
        val script = p.extras.single()
        assertEquals(TransportType.SCRIPT, script.type)
        assertEquals("mx", script.scriptId)
        assertEquals(clientSide, script.settings)
        assertEquals(nodeSide, script.nodeSettings)
        assertEquals(emptyList(), p.problems())
        assertTrue(p.carriers.none { it.type == TransportType.DIRECT }, "there is no address to dial")
    }

    @Test
    fun aMaxOnlyNodeHasTheScriptAsItsMainCarrier() {
        val only = Profile.ownerOfKit(
            "p", "N", "ic_terminal", 1, NewChannel("of-kit", key), NodeKit("s", transports = listOf(KitTransport("script", "", 60, "mx"))),
            KitDelivery.Manual, NodeMode.L4, true, mapOf("mx" to KitScriptSides(clientSide, nodeSide)),
        )
        assertEquals(TransportType.SCRIPT, only.transport)
        assertEquals("mx", only.scriptId)
        assertEquals(clientSide, only.settings)
        assertEquals(nodeSide, only.nodeSettings)
        assertTrue(only.extras.isEmpty())
        assertEquals(emptyList(), only.problems())
        assertFailsWith<IllegalArgumentException> {
            Profile.ownerOfKit("p", "N", "i", 1, NewChannel("c", key), NodeKit("s"), KitDelivery.Manual, NodeMode.L4, true, emptyMap())
        }
    }

    @Test
    fun theNodesTransportsCarryTheNodesSideOfEachScript() {
        val ts = owner().nodeTransports()
        assertEquals(listOf("mailru", "script"), ts.map { it.type })
        val s = ts.single { it.type == "script" }
        assertEquals("mx", s.script)
        assertEquals(nodeSide, ScriptSettingsCodec.decode(s.params), "the node's side, not the client's")
        // an ordinary script (one set of settings for both ends) goes with those
        val flat = owner().copy(extras = listOf(ExtraTransport(TransportType.SCRIPT, "v", scriptId = "other", settings = mapOf("a" to "b"))))
        assertEquals(mapOf("a" to "b"), ScriptSettingsCodec.decode(flat.nodeTransports().single { it.type == "script" }.params))
        assertEquals("v", flat.nodeTransports().single { it.type == "script" }.url)
        // and the node as the editor reads it back
        val info = owner().installedKit()
        assertNotNull(info)
        assertEquals("of-kit", info.id)
        assertEquals(key, info.key)
        assertNull(Profile(id = "x", name = "x").installedKit(), "a profile that is no kit node has none")
    }

    @Test
    fun theClientsCopyHasNothingOfTheNodesSide() {
        val line = ProfileTransfer.encode(owner(), ProfileTransfer.Role.Client, mapOf("mx" to authorKey, "unused" to "ff".repeat(32)))
        assertTrue(line.startsWith(ProfileTransfer.PREFIX))
        assertFalse(plain(line).contains("TOKEN-B"), "the node account's token must not travel to a client")
        assertTrue(plain(line).contains("TOKEN-A"))
        val got = ProfileTransfer.decode(line)
        assertEquals(ProfileTransfer.Role.Client, got.role)
        assertEquals(mapOf("mx" to authorKey), got.scriptKeys, "only the keys of scripts the carriers use")
        val p = got.profile
        assertEquals(ProfileSource.Link, p.source)
        assertNull(p.kitNode)
        assertEquals(key, p.secret)
        assertEquals("ctx", p.context)
        assertEquals("", p.id, "the receiving device gives it its own id")
        val script = p.extras.single()
        assertEquals(clientSide, script.settings)
        assertTrue(script.nodeSettings.isEmpty())
    }

    @Test
    fun theNodesCopyHasNothingOfTheClientsSideAndRunsWithTheNodesOwn() {
        val line = ProfileTransfer.encode(owner(), ProfileTransfer.Role.Node, mapOf("mx" to authorKey))
        assertFalse(plain(line).contains("TOKEN-A"), "the client account's token must not travel to the node")
        assertTrue(plain(line).contains("TOKEN-B"))
        val got = ProfileTransfer.decode(line)
        assertEquals(ProfileTransfer.Role.Node, got.role)
        val p = got.profile
        assertEquals(ProfileSource.ThisNode, p.source)
        assertNull(p.kitNode)
        val script = p.extras.single()
        assertEquals(nodeSide, script.settings, "the node's side is the carrier's plain settings there")
        assertTrue(script.nodeSettings.isEmpty())
        assertNull(p.exitProblem(), "this device may run it as the exit")
    }

    @Test
    fun theNodeDeviceRunsTheNodesSideAsTheExitAndTheOwnerRunsItsOwnAsAClient() {
        val settingsExit = AppSettings(mode = ConnectionMode.Exit)
        val settingsClient = AppSettings(mode = ConnectionMode.Client)
        val paths = CorePaths(keyFile = "/k", confFile = "/c", cookieStore = "/s", ipcSocket = null)
        val lookup = ScriptCarrierLookup("/mx.flux", authorKey, "mx")
        fun conf(p: Profile, s: AppSettings) = CoreConfig.build(p, s, paths) { lookup }.conf.orEmpty()

        // the node's device: the profile it was handed, run as the exit, carries the node's token
        val received = ProfileTransfer.decode(ProfileTransfer.encode(owner(), ProfileTransfer.Role.Node, mapOf("mx" to authorKey))).profile.copy(id = "n1", name = "Нода")
        val exitConf = conf(received, settingsExit)
        assertTrue("Role = exit" in exitConf)
        assertTrue("Params = ${ScriptSettingsCodec.encode(nodeSide)}" in exitConf, exitConf)
        assertFalse(ScriptSettingsCodec.encode(clientSide) in exitConf)

        // the owner's own profile as a client carries its own token; as the exit of a node it made HERE it carries the node's
        val here = owner(KitDelivery.Here)
        assertTrue("Params = ${ScriptSettingsCodec.encode(clientSide)}" in conf(here, settingsClient))
        val ownExit = conf(here, settingsExit)
        assertTrue("Params = ${ScriptSettingsCodec.encode(nodeSide)}" in ownExit, ownExit)
        // a node made elsewhere is a client's profile here, not an exit
        assertFailsWith<IllegalArgumentException> { conf(owner(KitDelivery.Device), settingsExit) }
    }

    @Test
    fun aLineThatIsNotOursOrBrokenIsRefusedWithWords() {
        fun refused(text: String): String = assertFailsWith<IllegalArgumentException> { ProfileTransfer.decode(text) }.message.orEmpty()
        assertTrue("не профиль" in refused("openflux://abc"))
        assertTrue("повреждён" in refused(ProfileTransfer.PREFIX + "###"))
        val good = ProfileTransfer.encode(owner(), ProfileTransfer.Role.Client)
        assertTrue("повреждён" in refused(good.dropLast(40)), "a truncated line")
        assertTrue("новой версией" in refused("openflux-profile:9:abc"))
        assertFalse(ProfileTransfer.isTransfer("openflux://abc"))
        assertTrue(ProfileTransfer.isTransfer("  " + good + "\n"))
        // a profile without a key is no node's
        val keyless = ProfileTransfer.encode(owner().copy(secret = ""), ProfileTransfer.Role.Client)
        assertTrue("ключа" in refused(keyless))
        // whitespace around it, as pasted
        assertEquals(ProfileTransfer.Role.Client, ProfileTransfer.decode("\n $good \n").role)
    }

    @Test
    fun aRoleTheSenderForgotIsDecidedByTheReceiversRule() {
        // a node line whose client side was not stripped by the sender cannot carry it into a node profile
        val sloppy = ProfileTransfer.PREFIX + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(
            """{"v":1,"role":"node","profile":{"id":"x","name":"N","transport":"script","scriptId":"mx","settings":{"token":"TOKEN-A"},"nodeSettings":{"token":"TOKEN-B"},"secret":"$key","session":true}}""".encodeToByteArray(),
        )
        val p = ProfileTransfer.decode(sloppy).profile
        assertEquals(mapOf("token" to "TOKEN-B"), p.settings)
        assertTrue(p.nodeSettings.isEmpty())
        assertEquals("", p.id)
    }

    @Test
    fun aMaxProfileFitsInAQr() {
        val line = ProfileTransfer.encode(owner(), ProfileTransfer.Role.Node, mapOf("mx" to authorKey))
        assertTrue(line.length < ProfileTransfer.QR_LIMIT, "${line.length} bytes")
        // with a long real-world token and a few more carriers still inside
        val fat = owner().copy(
            extras = owner().extras + listOf(
                ExtraTransport(TransportType.TELEMOST, "https://telemost.yandex.ru/j/88936358186517", priority = 85),
                ExtraTransport(TransportType.CUPSONLINE, "A".repeat(200), priority = 70),
            ),
        ).let { p -> p.copy(extras = p.extras.map { if (it.type == TransportType.SCRIPT) it.copy(settings = it.settings + ("token" to "x".repeat(300)), nodeSettings = it.nodeSettings + ("token" to "y".repeat(300))) else it }) }
        assertTrue(ProfileTransfer.encode(fat, ProfileTransfer.Role.Client).length < ProfileTransfer.QR_LIMIT)
    }
}
