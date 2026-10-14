package io.openflux.desktop.data

import io.openflux.desktop.model.OpenFluxBot
import java.net.URI

/**
 * What the build says about itself to the OpenFlux service: its key (X-App-Key; an identifier and a switch for the
 * service, not a secret) and, for development, a local stand-in for the service's address.
 */
object AppKeys {
    private const val RESOURCE = "/openflux-appkey.txt"
    private val SHAPE = Regex("^ofk_[A-Za-z0-9_-]{8,128}$")

    /** The key if [text] has a key's shape, else blank. */
    fun valid(text: String?): String = text?.trim()?.takeIf(SHAPE::matches).orEmpty()

    /** The desktop build's key: -Dopenflux.appKey, then OPENFLUX_APP_KEY, then the resource the build wrote; blank when none. */
    fun desktop(): String =
        valid(System.getProperty("openflux.appKey"))
            .ifEmpty { valid(System.getenv("OPENFLUX_APP_KEY")) }
            .ifEmpty { valid(AppKeys::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().decodeToString() }) }

    /** The service's address; OPENFLUX_API_BASE moves it, but only to this machine (a stand-in for tests). */
    fun apiBase(): String = pickBase(System.getenv("OPENFLUX_API_BASE"))

    internal fun pickBase(override: String?): String {
        val uri = runCatching { URI(override.orEmpty().trim()) }.getOrNull() ?: return OpenFluxBot.API
        val local = uri.scheme == "http" && (uri.host == "127.0.0.1" || uri.host == "localhost")
        return if (local) override!!.trim().trimEnd('/') else OpenFluxBot.API
    }
}
