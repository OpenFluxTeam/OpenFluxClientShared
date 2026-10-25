package io.openflux.desktop

import io.openflux.desktop.platform.windowsInstallerCommands
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The update installer must start on a machine where the shell's handler for
 * .msi/.exe is missing; these are the commands the app tries, in order.
 */
class InstallerLaunchTest {
    @Test
    fun anMsiGoesToMsiexecFirst() {
        val file = File("C:\\Users\\me\\Downloads\\OpenFlux 3.13.1.msi")
        val first = windowsInstallerCommands(file).first()
        assertTrue(first.first().endsWith("msiexec.exe", ignoreCase = true), "first command: $first")
        assertEquals(listOf("/i", file.absolutePath), first.drop(1))
    }

    @Test
    fun anExeStartsDirectlyFirst() {
        val file = File("C:\\tmp\\OpenFlux-3.13.1-setup.exe")
        assertEquals(listOf(file.absolutePath), windowsInstallerCommands(file).first())
    }

    @Test
    fun theFallbacksKeepASpacesPathAndDoubleASingleQuote() {
        val file = File("C:\\tmp\\O'Brien\\OpenFlux setup.msi")
        val commands = windowsInstallerCommands(file)
        val powershell = commands[1]
        assertEquals("powershell.exe", powershell.first())
        assertEquals(
            "Start-Process -FilePath '${file.absolutePath.replace("'", "''")}'",
            powershell.last(),
        )
        val cmd = commands[2]
        assertEquals(listOf("cmd.exe", "/c", "start", "", file.absolutePath), cmd)
    }
}
