package io.openflux.desktop.data

import io.openflux.desktop.model.OpenFluxId
import io.openflux.desktop.service.SignInSupport
import kotlinx.coroutines.channels.Channel

/**
 * The way back on Android: the sign-in page returns to [redirectUri] (the app's own scheme), the system hands the
 * address to the activity, and the activity gives it to [deliver].
 */
class RedirectSignIn(
    override val clientId: String,
    private val redirectUri: String,
    override val issuer: String = OpenFluxId.ISSUER,
    private val open: (String) -> Unit,
) : SignInSupport {
    private val arrivals = Channel<String>(Channel.CONFLATED)

    override fun newAttempt() = Pkce.newAttempt()

    override suspend fun openReturn(): String {
        while (arrivals.tryReceive().isSuccess) Unit
        return redirectUri
    }

    override suspend fun awaitReturn(): String = arrivals.receive()

    override fun openPage(url: String) = open(url)

    override fun closeReturn() = Unit

    /** The activity got [url]: true when it is the sign-in's way back (then nothing else should take it). */
    fun deliver(url: String): Boolean {
        val mine = url == redirectUri || url.startsWith("$redirectUri?") || url.startsWith("$redirectUri#")
        if (mine) arrivals.trySend(url)
        return mine
    }
}
