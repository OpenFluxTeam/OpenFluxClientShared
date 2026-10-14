package io.openflux.app.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import io.openflux.app.components.Banner
import io.openflux.app.components.PrimaryButton
import io.openflux.app.components.ProgressLine
import io.openflux.app.components.ScreenScaffold
import io.openflux.app.components.SecondaryButton
import io.openflux.app.components.TextAction
import io.openflux.app.components.Tone
import io.openflux.app.shell.LocalNavigator
import io.openflux.app.shell.rememberScreenModel
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.LoginPoll
import io.openflux.desktop.model.LoginStart
import io.openflux.desktop.model.OpenFluxBot
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.service.LocalAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the sign-in screen shows. */
sealed interface LoginUi {
    data object Idle : LoginUi
    data object Starting : LoginUi
    /** In Telegram, waiting for the person; [hint] says why the last question failed (no network), if it did. */
    data class Waiting(val hint: String? = null) : LoginUi
    data object Preparing : LoginUi
    data object Denied : LoginUi
    data object Expired : LoginUi
    data class Failed(val message: String) : LoginUi
    data object Done : LoginUi
}

/**
 * Signing in through the bot: begins a sign-in, opens Telegram with its link and asks every [pollMs] whether the
 * person approved, until they did, refused, or the five minutes ran out. A failed question (no network, too many
 * requests) does not end the wait: the person may be in Telegram, and the app may be asleep.
 */
@Stable
class TelegramLoginModel(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val pollMs: Long = POLL_MS,
) {
    var ui by mutableStateOf<LoginUi>(LoginUi.Idle)
        private set
    private var job: Job? = null
    private var login: LoginStart? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Begins a sign-in; one at a time. */
    fun start() {
        if (job?.isActive == true) return
        ui = LoginUi.Starting
        job = scope.launch { run() }
    }

    /** Gives up: the nonce simply runs out on the service. */
    fun cancel() {
        job?.cancel()
        job = null
        login = null
        ui = LoginUi.Idle
    }

    /** Opens Telegram with the same sign-in again. */
    fun reopen() {
        login?.let { container.platform.openUrl(it.deepLink) }
    }

    /** The app came back to the front: ask at once. */
    fun pollNow() {
        wake.trySend(Unit)
    }

    private suspend fun run() {
        val begun = try {
            container.bot.startLogin()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BotException) {
            ui = LoginUi.Failed(e.message ?: GENERIC)
            return
        }
        login = begun
        container.platform.openUrl(begun.deepLink)
        ui = LoginUi.Waiting()
        val deadline = begun.expiresAt * 1000
        while (true) {
            withTimeoutOrNull(pollMs) { wake.receive() }
            if (deadline > 0 && container.platform.now() >= deadline) {
                ui = LoginUi.Expired
                return
            }
            val answer = try {
                container.bot.pollLogin(begun.nonce)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotException) {
                if (e.code == "network" || e.code == "rate_limited" || e.code.startsWith("http_")) {
                    ui = LoginUi.Waiting(hint = e.message)
                    continue
                }
                ui = LoginUi.Failed(e.message ?: GENERIC)
                return
            }
            when (answer) {
                LoginPoll.Pending -> ui = LoginUi.Waiting()
                LoginPoll.Denied -> { ui = LoginUi.Denied; return }
                LoginPoll.Expired -> { ui = LoginUi.Expired; return }
                is LoginPoll.Approved -> {
                    ui = LoginUi.Preparing
                    container.bot.completeLogin(answer.session)
                    ui = LoginUi.Done
                    return
                }
            }
        }
    }

    private companion object {
        const val POLL_MS = 2_000L
        const val GENERIC = "Не получилось войти"
    }
}

/** The sign-in screen's body for each state; the screen around it is [TelegramLoginScreen]. */
@Composable
fun TelegramLoginContent(ui: LoginUi, onStart: () -> Unit, onCancel: () -> Unit, onReopen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
        when (ui) {
            LoginUi.Idle -> {
                Text("Вход через Telegram", style = AppTheme.type.screenTitle, color = AppTheme.colors.text)
                Text(
                    "Подтвердите вход в боте @${OpenFluxBot.USERNAME}: подписка и подключение появятся здесь сами. Пароль не нужен.",
                    style = AppTheme.type.body, color = AppTheme.colors.textSecondary,
                )
                PrimaryButton("Войти через Telegram", onStart, Modifier.fillMaxWidth())
            }
            LoginUi.Starting -> ProgressLine("Готовлю вход…")
            is LoginUi.Waiting -> {
                Text("Откройте Telegram и подтвердите вход в боте", style = AppTheme.type.screenTitle, color = AppTheme.colors.text)
                Text(
                    "Telegram должен открыться сам. Если нет, нажмите кнопку ниже.",
                    style = AppTheme.type.body, color = AppTheme.colors.textSecondary,
                )
                ProgressLine("Жду подтверждения…")
                ui.hint?.let { Banner(it, Tone.Warning) }
                SecondaryButton("Открыть Telegram ещё раз", onReopen, Modifier.fillMaxWidth())
                TextAction("Отмена", onCancel)
            }
            LoginUi.Preparing -> ProgressLine("Готовлю подключение…")
            LoginUi.Denied -> {
                Banner("Вход отклонён в боте", Tone.Warning)
                PrimaryButton("Повторить", onStart, Modifier.fillMaxWidth())
            }
            LoginUi.Expired -> {
                Banner("Время на подтверждение вышло", Tone.Info)
                PrimaryButton("Повторить", onStart, Modifier.fillMaxWidth())
            }
            is LoginUi.Failed -> {
                Banner(ui.message, Tone.Danger)
                PrimaryButton("Повторить", onStart, Modifier.fillMaxWidth())
            }
            LoginUi.Done -> ProgressLine("Готово")
        }
    }
}

/** Signing in (spec 8.1): the account is the person's Telegram, confirmed in the bot. */
@Composable
fun TelegramLoginScreen() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val m = rememberScreenModel { TelegramLoginModel(container, it) }
    val window = LocalWindowInfo.current
    LaunchedEffect(m.ui) { if (m.ui == LoginUi.Done) navigator.back() }
    // Back from Telegram: ask at once instead of waiting out the interval.
    LaunchedEffect(window) { snapshotFlow { window.isWindowFocused }.filter { it }.collect { m.pollNow() } }
    ScreenScaffold("Вход", onBack = { m.cancel(); navigator.back() }) {
        TelegramLoginContent(m.ui, onStart = m::start, onCancel = m::cancel, onReopen = m::reopen)
    }
}
