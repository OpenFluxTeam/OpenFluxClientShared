package io.openflux.app

import io.openflux.desktop.FakeShareLinkCodec
import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.ExitAddress
import io.openflux.desktop.model.LogLevel
import io.openflux.desktop.model.LogLine
import io.openflux.desktop.model.NewChannel
import io.openflux.desktop.model.NodeCoreSource
import io.openflux.desktop.model.NodeMode
import io.openflux.desktop.model.NodePlan
import io.openflux.desktop.model.NodeTransport
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ServerProbe
import io.openflux.desktop.model.SshTarget
import io.openflux.desktop.model.TrafficStats
import io.openflux.desktop.model.YandexDocument
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.model.CaptchaPrompt
import io.openflux.desktop.service.ConnectionService
import io.openflux.desktop.service.NodeWizardService
import io.openflux.desktop.service.PlatformServices
import io.openflux.desktop.service.ProfileRepository
import io.openflux.desktop.service.SettingsRepository
import io.openflux.desktop.testAccounts
import io.openflux.desktop.ui.BrowserPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** An app over in-memory fakes, for the new UI's tests: nothing touches the network, the disk or a server. */
fun testContainer(
    node: NodeWizardService = NoServer(),
    platform: TestPlatform = TestPlatform(),
    connection: ConnectionService = TestConnection(),
): AppContainer = AppContainer(
    TestProfiles(), TestSettings(), connection, platform, FakeShareLinkCodec(), node,
    testAccounts(CoroutineScope(Dispatchers.Unconfined)),
)

class TestSettings : SettingsRepository {
    override val settings = MutableStateFlow(AppSettings(autoUpdate = false))
    override fun update(transform: (AppSettings) -> AppSettings) { settings.value = transform(settings.value) }
}

class TestProfiles : ProfileRepository {
    override val profiles = MutableStateFlow<List<Profile>>(emptyList())
    private var next = 0
    override fun upsert(profile: Profile) { profiles.value = profiles.value.filterNot { it.id == profile.id } + profile }
    override fun delete(id: String) { profiles.value = profiles.value.filterNot { it.id == id } }
    override fun newId() = "p${next++}"
}

class TestConnection : ConnectionService {
    override val state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val traffic = MutableStateFlow(TrafficStats())
    override val exitAddress = MutableStateFlow<ExitAddress>(ExitAddress.Unknown)
    override val logs: StateFlow<List<LogLine>> = MutableStateFlow(emptyList())
    override val captcha: StateFlow<CaptchaPrompt?> = MutableStateFlow(null)
    override val exitShareLink: StateFlow<String?> = MutableStateFlow(null)
    override val socksAddress: StateFlow<String?> = MutableStateFlow(null)
    override val captchaPage: StateFlow<BrowserPage?> = MutableStateFlow(null)
    override fun connect(profile: Profile, mode: ConnectionMode?) { state.value = ConnectionState.Connected(profile, ConnectionMode.Client, 0) }
    override fun disconnect() { state.value = ConnectionState.Idle }
    override fun refreshExitAddress() = Unit
    override fun clearLogs() = Unit
    override fun openCaptcha() = Unit
    override fun submitCaptcha() = Unit
    override fun dismissCaptcha() = Unit
    override fun shutdown() = Unit
}

class TestPlatform : PlatformServices {
    override val appVersion = "test"
    override val coreVersion = "test"
    override val clientRepo = "test"
    override val systemProxySupported = false
    override val fullTunnelSupported = false
    override val elevated = false
    override fun restartElevated() = false
    override fun clipboardText(): String? = clipboard
    override fun setClipboardText(text: String) { clipboard = text }
    override fun qrFromClipboardImage(): String? = null
    override fun qrFromFile(path: String): String? = null
    override suspend fun pickFile(title: String, extensions: List<String>): String? = null
    override fun readTextFile(path: String, maxBytes: Int): String? = null
    override fun qrMatrix(text: String): List<BooleanArray> = emptyList()
    override fun openUrl(url: String) = Unit
    override fun newSecret() = "00".repeat(32)
    /** The clock; tests move it. */
    var clock = 1_000_000L
    var clipboard: String? = null
    override fun now() = clock
    override suspend fun latestRelease(): String? = null
}

/** No server in these tests: any call is a test defect. */
class NoServer : NodeWizardService {
    private fun no(): Nothing = error("no server in this test")
    override suspend fun connect(target: SshTarget, source: NodeCoreSource): ServerProbe = no()
    override suspend fun newChannel(): NewChannel = no()
    override suspend fun plan(channel: String, transports: List<NodeTransport>, withCookies: Boolean, autoUpdate: Boolean, mode: NodeMode): NodePlan = no()
    override suspend fun apply(channel: NewChannel, transports: List<NodeTransport>, port: Int, autoUpdate: Boolean, sudoPassword: String, cookieHeader: String, mode: NodeMode) = no()
    override suspend fun remove(channel: String, sudoPassword: String) = no()
    override suspend fun checkDocument(documentUrl: String) = no()
    override suspend fun createCupsRooms(): String = no()
    override suspend fun shareLink(name: String, key: String, host: String, port: Int, transports: List<NodeTransport>): String = no()
    override suspend fun resolve(host: String): Set<String> = no()
    override val documentPage: StateFlow<BrowserPage?> = MutableStateFlow(null)
    override val logs: StateFlow<List<LogLine>> = MutableStateFlow(emptyList())
    override fun clearLogs() = Unit
    override fun note(text: String, level: LogLevel) = Unit
    override suspend fun createDocument(fileName: String, onStep: (String) -> Unit): YandexDocument = no()
    override fun cancelDocument() = Unit
    override fun close() = Unit
}
