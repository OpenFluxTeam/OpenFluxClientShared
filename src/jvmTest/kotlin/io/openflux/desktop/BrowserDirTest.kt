package io.openflux.desktop

import io.openflux.desktop.web.BuiltInBrowser
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The built-in browser lives out of the install folder an update removes, and one left there is moved, not downloaded. */
class BrowserDirTest {
    private fun runtime(dir: File) {
        dir.mkdirs()
        File(dir, "install.lock").writeText("")
        File(dir, "libcef.dll").writeText("cef")
    }

    @Test
    fun aRuntimeInTheInstallFolderIsMovedOut() {
        val root = Files.createTempDirectory("browserdir").toFile()
        val legacy = File(root, "OpenFlux")
        runtime(File(legacy, "browser"))
        val dir = BuiltInBrowser.browserDir(File(root, "OpenFluxData"), legacy)
        assertEquals(File(root, "OpenFluxData/browser"), dir)
        assertTrue(BuiltInBrowser.runtimeInstalled(dir))
        assertFalse(File(legacy, "browser").exists())
        root.deleteRecursively()
    }

    @Test
    fun anInstalledRuntimeIsLeftAsItIs() {
        val root = Files.createTempDirectory("browserdir").toFile()
        val legacy = File(root, "OpenFlux")
        runtime(File(legacy, "browser"))
        runtime(File(root, "OpenFluxData/browser"))
        File(root, "OpenFluxData/browser/mine").writeText("x")
        val dir = BuiltInBrowser.browserDir(File(root, "OpenFluxData"), legacy)
        assertTrue(File(dir, "mine").exists())
        assertTrue(File(legacy, "browser/libcef.dll").exists())
        root.deleteRecursively()
    }

    @Test
    fun nothingToMoveGivesTheNewFolder() {
        val root = Files.createTempDirectory("browserdir").toFile()
        val dir = BuiltInBrowser.browserDir(File(root, "OpenFluxData"), null)
        assertEquals(File(root, "OpenFluxData/browser"), dir)
        assertFalse(BuiltInBrowser.runtimeInstalled(dir))
        root.deleteRecursively()
    }
}
