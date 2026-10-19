package io.openflux.app

import io.openflux.app.home.HomeBanner
import io.openflux.app.home.HomeModel
import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.ExtraTransport
import io.openflux.desktop.model.InstalledScript
import io.openflux.desktop.model.PhpNodeRef
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.model.needsNode
import io.openflux.desktop.service.InMemoryScriptRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A script transport whose two ends are set up together has nothing to connect to until a node is made. */
class NeedsNodeTest {
    private val wizard = InstalledScript(
        id = "mx", name = "MAX", pubkeyHex = "ab".repeat(32), fingerprint = "f", fileName = "mx.flux", setupPage = true,
        params = listOf(
            io.openflux.desktop.model.ScriptParam("token", "Токен", type = "secret", required = true, scope = "settings"),
            io.openflux.desktop.model.ScriptParam("uid", scope = "settings"),
        ),
    )
    private val plain = InstalledScript(id = "doc", name = "Doc", pubkeyHex = "ab".repeat(32), fingerprint = "f", fileName = "doc.flux")
    private val scripts = listOf(wizard, plain)

    private fun profile(
        script: String = "mx", secret: String = "", source: ProfileSource = ProfileSource.Manual, stream: Boolean = false,
        main: TransportType = TransportType.SCRIPT, settings: Map<String, String> = emptyMap(),
    ) = Profile(
        id = "p", name = "Мой MAX", transport = main, scriptId = if (main == TransportType.SCRIPT) script else "", secret = secret,
        source = source, stream = stream, settings = settings,
    )

    @Test
    fun aHandMadeProfileOfAWizardScriptThatIsNotSetUpNeedsANode() {
        assertTrue(profile().needsNode(scripts))
        // the profile editor makes a key for every profile, so a key says nothing: the script's own settings do
        assertTrue(profile(secret = "k".repeat(64)).needsNode(scripts), "a generated key is no node")
        assertTrue(profile(settings = mapOf("uid" to "222")).needsNode(scripts), "a required parameter (the token) is still missing")
        // it is in the extras too, when MAX is one of several carriers
        val many = Profile(
            id = "p", name = "N", transport = TransportType.MAILRU, value = "https://cloud.mail.ru/public/aa/bb", session = true,
            extras = listOf(ExtraTransport(TransportType.SCRIPT, scriptId = "mx")),
        )
        assertTrue(many.needsNode(scripts))
    }

    @Test
    fun anythingThatIsSetUpHandedOverOrNotAWizardScriptDoesNot() {
        assertFalse(profile(settings = mapOf("token" to "T")).needsNode(scripts), "filled in by the person: they run the node")
        assertFalse(profile(source = ProfileSource.Node).needsNode(scripts), "a node's own profile")
        assertFalse(profile(source = ProfileSource.Link).needsNode(scripts), "handed over by a node's owner")
        assertFalse(profile(script = "doc").needsNode(scripts), "a script without a wizard of its own")
        assertFalse(profile(script = "gone").needsNode(scripts), "a script that is not installed says so elsewhere")
        assertFalse(profile(main = TransportType.VYANDEX).needsNode(scripts))
        assertFalse(profile(stream = true).needsNode(scripts))
        assertFalse(profile().needsNode(emptyList()))
    }

    @Test
    fun aScriptThatDeclaresNothingRequiredIsSetUpOnceItHasAnySettings() {
        val loose = wizard.copy(params = emptyList())
        assertTrue(profile().needsNode(listOf(loose)))
        assertFalse(profile(settings = mapOf("a" to "b")).needsNode(listOf(loose)))
        // the profile's own input counts for the parameter it stands for
        val primary = wizard.copy(params = listOf(io.openflux.desktop.model.ScriptParam("room", scope = "profile", required = true)))
        assertTrue(profile().needsNode(listOf(primary)))
        assertFalse(profile().copy(value = "room-1").needsNode(listOf(primary)))
    }

    @Test
    fun homeAsksForANodeBeforeAnythingElseAndKnowsANodesOwnProfile() {
        val repo = InMemoryScriptRepository().also { it.upsert(wizard) }
        val c = testContainer(scripts = repo)
        val m = HomeModel(c)
        val settings = AppSettings()
        val none = emptyMap<io.openflux.desktop.model.AccountKind, io.openflux.desktop.model.AuthStatus>()
        val p = profile()
        assertEquals(HomeBanner.NeedsNode(p), m.banner(ConnectionState.Idle, settings, p, none, null, 0))
        // a refused connect says the same thing with a way out, not just the failure
        assertEquals(HomeBanner.NeedsNode(p), m.banner(ConnectionState.Failed(p, "Этому подключению нужна нода"), settings, p, none, null, 0))

        val node = profile(source = ProfileSource.ThisNode, secret = "k".repeat(64), settings = mapOf("token" to "T"))
        assertEquals(HomeBanner.NodeProfile(node), m.banner(ConnectionState.Idle, settings, node, none, null, 0))
        assertNull(m.banner(ConnectionState.Idle, settings, profile(settings = mapOf("token" to "T")), none, null, 0))
        assertIs<HomeBanner.ThisDevice>(
            m.banner(ConnectionState.Connected(node, ConnectionMode.Exit, 0), settings.copy(mode = ConnectionMode.Exit), node, none, null, 0),
            "while it runs as the node, that is what Home says",
        )
    }
}
