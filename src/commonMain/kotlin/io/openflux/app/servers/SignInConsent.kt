package io.openflux.app.servers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.openflux.app.components.ConfirmDialog
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.service.SettingsRepository

/**
 * The person's yes before the app first hands a node their sign-in to a service (its cookies): the node then
 * acts as them in that service. Asked once per service and kept in the settings
 * ([io.openflux.desktop.model.AppSettings.nodeSignInConsent]); every way a sign-in leaves for a node the
 * first time (the wizard's install, «Передать вход … ноде» on the node and on the home screen) goes through
 * [withConsent]. The keeper only renews sign-ins already given, so it needs no ask of its own.
 */
@Stable
class SignInConsent(private val settings: SettingsRepository) {
    /** The service being asked about and what to do on a yes; null while nothing is asked. */
    var asking by mutableStateOf<Ask?>(null)
        private set

    class Ask(val kind: AccountKind, val proceed: () -> Unit)

    fun given(kind: AccountKind): Boolean = kind in settings.settings.value.nodeSignInConsent

    /** Runs [action] now if [kind]'s sign-in may go to nodes, else asks first and runs it on a yes. */
    fun withConsent(kind: AccountKind, action: () -> Unit) {
        if (given(kind)) action() else asking = Ask(kind, action)
    }

    fun accept() {
        val ask = asking ?: return
        settings.update { it.copy(nodeSignInConsent = it.nodeSignInConsent + ask.kind) }
        asking = null
        ask.proceed()
    }

    fun decline() {
        asking = null
    }
}

/** What a node gets with a sign-in and what it can do with it, in the words of the ask. */
fun signInConsentText(kind: AccountKind): String =
    "Нода получит вашу сессию ${kind.label} — куки входа, а не пароль. Пока сессия действует, нода работает " +
        "с документами ${kind.label} от вашего имени, а приложение передаёт ей новую сессию, когда старая сменится.\n\n" +
        "Передавайте вход только своему серверу или серверу, которому доверяете. Отозвать доступ: завершите " +
        "сеансы на других устройствах в настройках безопасности ${kind.label}."

/** The ask of [consent], while there is one. */
@Composable
fun SignInConsentDialog(consent: SignInConsent) {
    val ask = consent.asking ?: return
    ConfirmDialog(
        title = "Передать вход в ${ask.kind.label} ноде?",
        text = signInConsentText(ask.kind),
        confirm = "Передать",
        onConfirm = consent::accept,
        onDismiss = consent::decline,
    )
}
