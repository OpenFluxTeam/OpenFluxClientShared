package io.openflux.desktop

import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.CoreConfig
import io.openflux.desktop.model.CorePaths
import io.openflux.desktop.model.ExtraTransport
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ScriptCarrierLookup
import io.openflux.desktop.model.TransportType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JS transports are the app's own: nothing gates them. "Включить легаси" only opens the first transports for choosing in
 * new connections and nodes; it never stops a connection through one.
 */
class LegacySwitchTest {
    private val paths = CorePaths("C:/rt/key", "C:/rt/p.conf", "C:/cfg/cookies/p.json", "C:/rt/ipc.sock")
    private val secret = "f".repeat(64)
    private val lookup = ScriptCarrierLookup("/p.flux", "aa", "demo")

    private val script = Profile(
        id = "s", name = "JS", transport = TransportType.SCRIPT, value = "https://example.org/doc", secret = secret,
        session = true, scriptId = "demo",
    )
    private val native = Profile(
        id = "n", name = "Native", transport = TransportType.VYANDEX, value = "https://disk.yandex.ru/i/one", secret = secret, session = true,
    )

    @Test
    fun legacyIsOffByDefaultAndSettingsSavedBeforeReadAsOffWhateverTheyHeldForTheOldSwitch() {
        assertFalse(AppSettings().legacy)
        val json = Json { ignoreUnknownKeys = true }
        // the old "Экспериментальные функции" is gone: it does not turn the legacy on
        val old = """{"theme":"System","mode":"Client","experimental":true}"""
        assertFalse(json.decodeFromString(AppSettings.serializer(), old).legacy)
        assertTrue(json.decodeFromString(AppSettings.serializer(), """{"legacy":true}""").legacy)
    }

    @Test
    fun onlyTheFirstTransportsAreLegacy() {
        val legacy = TransportType.entries.filter { it.legacy }.toSet()
        assertEquals(
            setOf(
                TransportType.VYANDEX, TransportType.YANDEX, TransportType.BOARDS, TransportType.MAILRU,
                TransportType.CUPSONLINE, TransportType.TELEMOST, TransportType.AVITO, TransportType.ONEME,
            ),
            legacy,
        )
        assertFalse(TransportType.DIRECT.legacy, "the node's own backup is no legacy")
        assertFalse(TransportType.SCRIPT.legacy)
    }

    @Test
    fun aProfileThroughAScriptConnectsWithoutAnySwitch() {
        assertTrue(CoreConfig.build(script, AppSettings(), paths) { lookup }.conf.orEmpty().contains("Type = script"))
        val mixed = native.copy(extras = listOf(ExtraTransport(TransportType.SCRIPT, "https://example.org/doc", scriptId = "demo", priority = 50)))
        assertTrue(CoreConfig.build(mixed, AppSettings(), paths) { lookup }.conf != null)
    }

    @Test
    fun nativeTransportsAreUntouchedWhateverTheSetting() {
        for (on in listOf(false, true)) {
            for (mode in ConnectionMode.entries) {
                val launch = CoreConfig.build(native, AppSettings(legacy = on, mode = mode), paths)
                assertFalse("Type = script" in launch.conf.orEmpty())
                assertTrue("Type = vyandex" in launch.conf.orEmpty(), "a legacy transport still connects with legacy off")
            }
        }
    }

    @Test
    fun anExtraThatIsNotAScriptNeedsNothing() {
        val extras = native.copy(extras = listOf(ExtraTransport(TransportType.VYANDEX, "https://disk.yandex.ru/i/two", priority = 50)))
        assertTrue(CoreConfig.build(extras, AppSettings(), paths).conf != null)
        assertContentEquals(listOf(TransportType.VYANDEX, TransportType.VYANDEX), extras.carriers.map { it.type })
    }
}
