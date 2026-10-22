package io.openflux.desktop.data

import java.io.File

/** Per-user application folders. */
object AppDirs {
    private val os = System.getProperty("os.name").lowercase()

    val config: File by lazy {
        val base = when {
            os.contains("win") -> System.getenv("APPDATA")?.let(::File) ?: File(System.getProperty("user.home"), "AppData/Roaming")
            os.contains("mac") -> File(System.getProperty("user.home"), "Library/Application Support")
            else -> System.getenv("XDG_CONFIG_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".config")
        }
        File(base, if (os.contains("win") || os.contains("mac")) "OpenFlux" else "openflux").apply { mkdirs() }
    }

    /**
     * Large per-computer files: the built-in browser, WinDivert, the browser's logs. On Windows not in
     * %LOCALAPPDATA%\OpenFlux: the app is installed there, and its installer removes that whole folder when it
     * upgrades (jpackage's MSI has util:RemoveFolderEx on the install folder), so a browser kept inside was
     * downloaded again after every update. Elsewhere the config folder, as before.
     */
    val local: File by lazy {
        (System.getenv("LOCALAPPDATA")?.let { File(it, "OpenFluxData") } ?: config).apply { mkdirs() }
    }

    /** Where those files were before [local]: the install folder on Windows; null elsewhere. */
    val legacyLocal: File? get() = System.getenv("LOCALAPPDATA")?.let { File(it, "OpenFlux") }

    /** Files the core needs while it runs (key, .conf, IPC socket). */
    val runtime: File by lazy { File(config, "runtime").apply { mkdirs() } }
}
