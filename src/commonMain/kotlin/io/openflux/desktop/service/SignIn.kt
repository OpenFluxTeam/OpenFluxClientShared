package io.openflux.desktop.service

import io.openflux.desktop.model.AuthAttempt
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.OpenFluxId

/**
 * What a platform gives the sign-in on the OpenFlux page: its Keycloak client, a way back from the browser and a way
 * to show the page (a Custom Tab on Android, the default browser on a computer; never a WebView).
 */
interface SignInSupport {
    /** This build's Keycloak client; blank: the build cannot sign in. */
    val clientId: String
    val issuer: String get() = OpenFluxId.ISSUER
    fun newAttempt(): AuthAttempt
    /** Gets the way back ready and gives the redirect_uri to send; drops returns of earlier attempts. BotException("no_return") when there is none. */
    suspend fun openReturn(): String
    /** Waits for the browser to come back on the address [openReturn] gave; the full address with its query. */
    suspend fun awaitReturn(): String
    fun openPage(url: String)
    /** Ends the way back (a computer stops listening); harmless twice. */
    fun closeReturn()
}

object NoSignIn : SignInSupport {
    private fun unsupported(): Nothing = throw BotException("unsupported", "Вход здесь не поддерживается")
    override val clientId = ""
    override fun newAttempt() = unsupported()
    override suspend fun openReturn() = unsupported()
    override suspend fun awaitReturn() = unsupported()
    override fun openPage(url: String) = Unit
    override fun closeReturn() = Unit
}
