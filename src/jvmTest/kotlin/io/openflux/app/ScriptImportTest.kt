package io.openflux.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Dest
import io.openflux.app.platform.NoScrollbars
import io.openflux.app.platform.Shortcuts
import io.openflux.app.shell.OpenFluxApp
import io.openflux.desktop.model.InstalledScript
import io.openflux.desktop.model.ScriptSource
import io.openflux.desktop.service.InMemoryScriptRepository
import io.openflux.desktop.service.ScriptRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/** Importing a JS transport from a file: a third-party author's key is asked for after the check, and adding it is asked. */
@OptIn(ExperimentalTestApi::class)
class ScriptImportTest {
    private val authorKey = "ab".repeat(32)

    /** Records what the sheet asked to install, with which key. */
    private class Scripts(private val base: InMemoryScriptRepository = InMemoryScriptRepository()) : ScriptRepository by base {
        val keys = mutableListOf<String>()
        override fun install(data: ByteArray, sig: ByteArray, pubkeyHex: String, source: ScriptSource, origin: String, now: Long): InstalledScript {
            keys += pubkeyHex
            return InstalledScript(id = "boards", name = "boards", pubkeyHex = pubkeyHex, fingerprint = "ff", fileName = "boards.flux").also(base::upsert)
        }
    }

    /** The core's answer: the package is signed by [authorKey] only. */
    private fun report(key: String, bareJs: Boolean = false, sig: ByteArray = ByteArray(0)): String {
        val signature = when {
            bareJs && sig.isEmpty() -> "unverified"
            key == authorKey -> "valid"
            else -> "invalid"
        }
        return """{"ok":true,"name":"boards","version":"1.0","signature":"$signature","fingerprint":"${"cd".repeat(32)}"}"""
    }

    private fun setUp(path: String, bytes: ByteArray): Pair<io.openflux.desktop.service.AppContainer, Scripts> {
        val platform = TestPlatform().apply {
            picked = path
            files[path] = bytes
            inspect = { data, sig, key -> report(key, bareJs = path.endsWith(".js"), sig = sig) }
        }
        val scripts = Scripts()
        val c = testContainer(platform = platform, scripts = scripts)
        c.settings.update { it.copy(experimental = true, navState = AppNavigator().apply { open(Dest.Scripts) }.snapshot().encode()) }
        return c to scripts
    }

    @Test
    fun aThirdPartyPackageAsksForTheAuthorsKeyThenWhetherToAddIt() = runComposeUiTest {
        val (c, scripts) = setUp("C:/t/boards.flux", "PK-package".encodeToByteArray())
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Импортировать").performClick()
        onNodeWithText("Из файла…").performClick()
        waitForIdle()
        // Checked against OpenFlux's key first: not it, so the author's key is asked for, nothing is refused yet.
        onNodeWithText("Подписано не ключом OpenFlux. Введите открытый ключ автора, чтобы проверить подпись").assertIsDisplayed()
        onNode(hasSetTextAction() and hasText("Ключ автора (hex)")).performTextInput(authorKey)
        onNodeWithText("Проверить с этим ключом").performClick()
        waitForIdle()
        onNodeWithText("Добавить транспорт").assertIsDisplayed()
        onNodeWithText("Не добавлять").assertIsDisplayed()
        onNodeWithText("Добавить транспорт").performClick()
        waitForIdle()
        assertEquals(listOf(authorKey), scripts.keys)
    }

    @Test
    fun saidNoNothingIsInstalled() = runComposeUiTest {
        val (c, scripts) = setUp("C:/t/boards.flux", "PK-package".encodeToByteArray())
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Импортировать").performClick()
        onNode(hasSetTextAction() and hasText("Ключ автора (hex); пусто — ключ OpenFlux")).performTextInput(authorKey)
        onNodeWithText("Из файла…").performClick()
        waitForIdle()
        onNodeWithText("Не добавлять").performClick()
        waitForIdle()
        assertEquals(emptyList(), scripts.keys)
        onNodeWithText("Импортировать").assertIsDisplayed()
    }

    @Test
    fun aBareScriptTakesItsSignatureFromBesideIt() = runComposeUiTest {
        val (c, _) = setUp("C:/t/boards.js", "info()".encodeToByteArray())
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Импортировать").performClick()
        onNodeWithText("Из файла…").performClick()
        waitForIdle()
        onNodeWithText("Рядом с файлом нет подписи boards.js.sig: без неё транспорт не установить. Положите .sig рядом или возьмите пакет .flux").assertIsDisplayed()
    }
}
