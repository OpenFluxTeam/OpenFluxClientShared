package io.openflux.app.account

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.openflux.desktop.model.DeviceInfo
import io.openflux.desktop.model.OrderInfo
import io.openflux.desktop.model.PlansResult
import io.openflux.desktop.model.SubscriptionException
import io.openflux.desktop.model.SubscriptionKind
import io.openflux.desktop.service.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val MIN_PASSWORD = 10

/** The screens of signing in (spec 8.1). */
enum class AuthStep { Login, Register, Recover, Devices }

private fun loginProblem(login: String): String? = when {
    login.trim().length < 3 -> "Логин: не короче 3 символов (почта или имя)"
    login.trim().any { it.isWhitespace() } -> "В логине не должно быть пробелов"
    else -> null
}

/** What the service will also check, said before the request. */
fun newPasswordProblem(login: String, p: String, again: String): String? = when {
    p.length < MIN_PASSWORD -> "Пароль: не короче $MIN_PASSWORD символов"
    p != again -> "Пароли не совпадают"
    p.equals(login.trim(), ignoreCase = true) -> "Пароль не должен совпадать с логином"
    else -> null
}

/**
 * Signing in to the OpenFlux account (spec 8.1): log in, register, recover with the recovery key, replace a device
 * when the account is full. After registering or recovering, the recovery key is shown once and the screen stays
 * until the person has saved it ([done] turns true then).
 */
@Stable
class AuthModel(private val container: AppContainer, private val scope: CoroutineScope) {
    private val manager = container.subscription
    var step by mutableStateOf(AuthStep.Login)
    var login by mutableStateOf("")
    var password by mutableStateOf("")
    var password2 by mutableStateOf("")
    var recoveryInput by mutableStateOf("")
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var recoveryKey by mutableStateOf<String?>(null)
        private set
    var devices by mutableStateOf<List<DeviceInfo>>(emptyList())
        private set
    var done by mutableStateOf(false)
        private set

    fun go(next: AuthStep) {
        step = next
        error = null
        password = ""
        password2 = ""
        if (next != AuthStep.Recover) recoveryInput = ""
    }

    fun register() {
        val problem = loginProblem(login) ?: newPasswordProblem(login, password, password2)
        if (problem != null) { error = problem; return }
        call("Создаю аккаунт…") {
            val auth = manager.register(login, password)
            finish(auth.recoveryKey)
        }
    }

    fun signIn(replaceDeviceId: String? = null) {
        if (login.isBlank() || password.isEmpty()) { error = "Введите логин и пароль"; return }
        call("Вхожу…") {
            manager.login(login, password, replaceDeviceId)
            finish("")
        }
    }

    fun recover() {
        val problem = loginProblem(login) ?: (if (recoveryInput.isBlank()) "Введите ключ восстановления" else newPasswordProblem(login, password, password2))
        if (problem != null) { error = problem; return }
        call("Восстанавливаю доступ…") {
            val auth = manager.recover(login, recoveryInput.trim(), password)
            finish(auth.recoveryKey)
        }
    }

    fun recoveryKeySaved() {
        recoveryKey = null
        done = true
    }

    fun copyRecoveryKey() {
        recoveryKey?.let(container.platform::setClipboardText)
    }

    private fun finish(key: String) {
        login = ""; password = ""; password2 = ""; recoveryInput = ""; devices = emptyList()
        if (key.isNotEmpty()) recoveryKey = key else done = true
    }

    private fun call(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubscriptionException) {
                if (e.code == "device_limit") {
                    devices = e.devices
                    step = AuthStep.Devices
                } else {
                    error = e.message
                }
            } catch (e: Exception) {
                error = e.message ?: "Не получилось"
            } finally {
                busy = null
            }
        }
    }
}

/**
 * The subscription (spec 8.2): renew (plans → order → payment → paid), a trial, the devices, the password, signing
 * out and deleting the account. The service's own words (already Russian) reach the person as they are.
 */
@Stable
class SubscriptionModel(private val container: AppContainer, private val scope: CoroutineScope) {
    private val manager = container.subscription
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    /** The password and delete dialogs' own error: the page's banner is behind the dialog. */
    var dialogError by mutableStateOf<String?>(null)
        private set

    var plansOpen by mutableStateOf(false)
        private set
    var plans by mutableStateOf<PlansResult?>(null)
        private set
    var plansError by mutableStateOf<String?>(null)
        private set
    var selectedPlan by mutableStateOf<String?>(null)
    var order by mutableStateOf<OrderInfo?>(null)
        private set
    var paid by mutableStateOf(false)
        private set
    private var pollJob: Job? = null

    val isAccount: Boolean get() = manager.state.value?.kind == SubscriptionKind.Account

    fun clearNotice() { notice = null }
    fun clearDialogError() { dialogError = null }

    fun refresh() = call("Обновляю…") { manager.refresh() }
    fun startTrial() = call("Включаю пробный период…") { manager.startTrial() }
    fun signOut() = call("Выхожу…") { manager.signOut() }
    fun removeDevice(id: String) = call("Убираю устройство…") { manager.removeDevice(id) }

    /** Opens the order's payment page again: the person closed it, or paid on another device. */
    fun reopenPayment() {
        val url = manager.state.value?.pendingOrder?.paymentUrl.orEmpty()
        if (url.startsWith("https://")) container.platform.openUrl(url)
    }

    // ---- the OpenFlux bot ----

    fun botSignIn() = call("Вход через Telegram…") {
        try {
            container.bot.signIn()
            notice = "Вы вошли в бота OpenFlux"
        } catch (e: io.openflux.desktop.service.AccountException) {
            if (!e.cancelled) throw e
        }
    }

    fun botSignOut() = call("Выхожу из бота…") { container.bot.signOut() }
    fun botRefresh() = call("Обновляю данные бота…") { container.bot.refresh() }
    fun removeBotLink(token: String) = call("Убираю подключение…") { container.bot.removeLink(token) }
    fun openBot() = container.platform.openUrl(io.openflux.desktop.model.OpenFluxBot.CHAT_URL)

    fun openSupport() {
        val url = manager.state.value?.support.orEmpty()
        if (url.startsWith("https://") || url.startsWith("http://")) container.platform.openUrl(url)
    }

    fun changePassword(old: String, new: String, again: String, onDone: () -> Unit = {}) {
        val login = manager.state.value?.account?.login.orEmpty()
        val problem = if (old.isEmpty()) "Введите текущий пароль" else newPasswordProblem(login, new, again)
        if (problem != null) { dialogError = problem; return }
        dialog("Меняю пароль…") {
            manager.changePassword(old, new)
            notice = "Пароль изменён. На других устройствах войдите заново"
            onDone()
        }
    }

    fun deleteAccount(password: String, onDone: () -> Unit = {}) {
        if (password.isEmpty()) { dialogError = "Введите пароль"; return }
        dialog("Удаляю аккаунт…") {
            manager.deleteAccount(password)
            onDone()
        }
    }

    fun openPlans() {
        plansOpen = true
        order = null
        paid = false
        plansError = null
        selectedPlan = null
        scope.launch {
            try {
                plans = manager.plans().also { p -> selectedPlan = p.plans.firstOrNull()?.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubscriptionException) {
                plansError = e.message
            }
        }
    }

    fun closePlans() {
        plansOpen = false
        pollJob?.cancel()
    }

    fun checkout() {
        val id = selectedPlan ?: return
        if (busy != null) return
        busy = "Оформляю заказ…"
        plansError = null
        scope.launch {
            try {
                val o = manager.checkout(id)
                order = o
                if (o.paymentUrl.startsWith("https://")) container.platform.openUrl(o.paymentUrl)
                poll(o.orderId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubscriptionException) {
                plansError = e.message
            } finally {
                busy = null
            }
        }
    }

    /** Asks about the order on screen, or the one kept from before the app was closed. */
    fun checkOrder() {
        val id = order?.orderId ?: manager.state.value?.pendingOrder?.orderId ?: return
        scope.launch { checkOrderNow(id) }
    }

    /** Asks about the order until it is paid, while the plans are open. */
    private fun poll(orderId: String) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (plansOpen && !paid) {
                delay(POLL_MS)
                checkOrderNow(orderId)
            }
        }
    }

    private suspend fun checkOrderNow(orderId: String) {
        try {
            val o = manager.order(orderId)
            if (plansOpen) order = o
            if (o.paid) paid = true
            // Asked from the card, the sheet is not there to say it.
            if (!plansOpen) notice = when {
                o.paid -> "Оплата получена. Подписка продлена"
                o.closed -> "Заказ ${o.orderId} не оплачен. Оформите новый"
                else -> "Оплата ещё не пришла"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SubscriptionException) {
            if (plansOpen) plansError = e.message else error = e.message
        }
    }

    private fun call(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubscriptionException) {
                error = e.message
            } catch (e: io.openflux.desktop.model.BotException) {
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: "Не получилось"
            } finally {
                busy = null
            }
        }
    }

    private fun dialog(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        dialogError = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubscriptionException) {
                dialogError = e.message
            } finally {
                busy = null
            }
        }
    }

    private companion object {
        const val POLL_MS = 8_000L
    }
}
