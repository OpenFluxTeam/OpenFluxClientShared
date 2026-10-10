package io.openflux.desktop.ui.subscription

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.openflux.desktop.model.DeviceInfo
import io.openflux.desktop.model.OrderInfo
import io.openflux.desktop.model.PlansResult
import io.openflux.desktop.model.SubscriptionException
import io.openflux.desktop.model.SubscriptionInvite
import io.openflux.desktop.model.SubscriptionKind
import io.openflux.desktop.model.ShareLinkCodec
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.service.KeyPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The screens of signing in: what the person sees while there is no account on this device. */
enum class AuthStep { Add, Choose, Register, Login, Recover, Devices }

/** What a pasted text turned out to be. */
sealed interface KeyState {
    data object Empty : KeyState
    data object Checking : KeyState
    class Subscription(val preview: KeyPreview) : KeyState
    /** A profile link: the Profiles screen imports it. */
    class ProfileLink(val link: String) : KeyState
    class Invalid(val message: String) : KeyState
}

/**
 * The subscription screen's state: a key pasted in, the sign-in forms, the
 * account's actions, and buying a plan. The service's words (its errors are
 * already in Russian) go to the person as they are.
 */
@Stable
class SubscriptionScreenModel(private val container: AppContainer) : ScreenModel {
    val manager = container.subscription
    val platform = container.platform

    // ---- signing in ----

    var step by mutableStateOf(AuthStep.Add)
        private set
    var login by mutableStateOf("")
    var password by mutableStateOf("")
    var password2 by mutableStateOf("")
    var recoveryInput by mutableStateOf("")
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)

    /** The recovery key, shown once after registering or recovering; the screen stays until the person has it. */
    var recoveryKey by mutableStateOf<String?>(null)
        private set

    /** The devices of an account at its limit, to pick one to replace. */
    var deviceChoices by mutableStateOf<List<DeviceInfo>>(emptyList())
        private set

    fun go(next: AuthStep) {
        step = next
        error = null
        notice = null
        password = ""
        password2 = ""
        if (next != AuthStep.Recover) recoveryInput = ""
    }

    fun register() {
        val problem = checkLogin() ?: checkNewPassword(password, password2)
        if (problem != null) { error = problem; return }
        call("Создаю аккаунт…") {
            val auth = manager.register(login, password)
            recoveryKey = auth.recoveryKey.ifEmpty { null }
            clearForm()
        }
    }

    fun signIn(replaceDeviceId: String? = null) {
        if (login.isBlank() || password.isEmpty()) { error = "Введите логин и пароль."; return }
        call("Вхожу…", onFailure = { e ->
            if (e.code == "device_limit") {
                deviceChoices = e.devices
                step = AuthStep.Devices
                error = null
                true
            } else false
        }) {
            manager.login(login, password, replaceDeviceId)
            clearForm()
        }
    }

    fun recover() {
        val problem = checkLogin() ?: if (recoveryInput.isBlank()) "Введите ключ восстановления." else checkNewPassword(password, password2)
        if (problem != null) { error = problem; return }
        call("Восстанавливаю доступ…", onFailure = { e ->
            if (e.code == "device_limit") { deviceChoices = e.devices; step = AuthStep.Devices; true } else false
        }) {
            val auth = manager.recover(login, recoveryInput, password)
            recoveryKey = auth.recoveryKey.ifEmpty { null }
            clearForm()
        }
    }

    /** The person has written the recovery key down. */
    fun recoveryKeySaved() {
        recoveryKey = null
        step = AuthStep.Add
    }

    fun copyRecoveryKey() {
        recoveryKey?.let { platform.setClipboardText(it) }
    }

    private fun clearForm() {
        login = ""; password = ""; password2 = ""; recoveryInput = ""; deviceChoices = emptyList()
        step = AuthStep.Add
    }

    private fun checkLogin(): String? = when {
        login.trim().length < 3 -> "Логин: не короче 3 символов (почта или имя)."
        login.trim().any { it.isWhitespace() } -> "В логине не должно быть пробелов."
        else -> null
    }

    /** What the service will also check, said before the request. */
    private fun checkNewPassword(p: String, again: String): String? = when {
        p.length < MIN_PASSWORD -> "Пароль не короче $MIN_PASSWORD символов."
        p != again -> "Пароли не совпадают."
        p.equals(login.trim(), ignoreCase = true) -> "Пароль не должен совпадать с логином."
        else -> null
    }

    // ---- a key pasted in ----

    var keyText by mutableStateOf("")
        private set
    var keyState by mutableStateOf<KeyState>(KeyState.Empty)
        private set
    private var keyJob: Job? = null

    /** Called as the text changes: a subscription key is looked up, a profile link is handed on. */
    fun keyChanged(text: String) {
        keyText = text
        keyJob?.cancel()
        val t = text.trim()
        keyState = when {
            t.isEmpty() -> KeyState.Empty
            t.startsWith(ShareLinkCodec.PREFIX) -> KeyState.ProfileLink(t)
            SubscriptionInvite.looksLike(t) -> KeyState.Checking
            else -> KeyState.Invalid("Это не ключ подписки и не ссылка openflux://.")
        }
        if (keyState !is KeyState.Checking) return
        keyJob = screenModelScope.launch {
            delay(250) // typing, not pasting: wait for the end of it
            keyState = try {
                KeyState.Subscription(manager.previewKey(t))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubscriptionException) {
                KeyState.Invalid(e.message ?: "Не удалось проверить ключ.")
            } catch (e: Exception) {
                KeyState.Invalid("Не удалось проверить ключ.")
            }
        }
    }

    fun pasteKey() {
        val text = platform.clipboardText().orEmpty().trim()
        if (text.isEmpty()) { keyState = KeyState.Invalid("В буфере обмена пусто."); keyText = ""; return }
        keyChanged(text)
    }

    /** Text from a file or a QR code goes the same way as a pasted key. */
    fun keyFromFile() {
        screenModelScope.launch {
            val path = platform.pickFile("Файл с настройками", listOf("txt", "json", "conf", "openflux", "key")) ?: return@launch
            val text = platform.readTextFile(path)?.trim()
            if (text.isNullOrEmpty()) keyState = KeyState.Invalid("Файл пустой или слишком большой.") else keyChanged(firstLine(text))
        }
    }

    fun keyFromQr() {
        screenModelScope.launch {
            val text = try {
                if (platform.cameraScanSupported) platform.scanQr()
                else platform.pickFile("QR-код OpenFlux", listOf("png", "jpg", "jpeg", "bmp", "gif"))?.let { platform.qrFromFile(it) }
                    ?: platform.qrFromClipboardImage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (text.isNullOrBlank()) keyState = KeyState.Invalid("QR-код не найден.") else keyChanged(text.trim())
        }
    }

    private fun firstLine(text: String) = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: text

    fun addKey() {
        val s = keyState as? KeyState.Subscription ?: return
        call("Подключаю подписку…") {
            manager.addKey(s.preview)
            keyChanged("")
        }
    }

    fun clearKey() = keyChanged("")

    // ---- the account ----

    fun refresh() {
        screenModelScope.launch {
            if (!manager.refresh()) notice = null
        }
    }

    fun startTrial() = call("Включаю пробный период…") { manager.startTrial() }

    fun signOut() = call("Выхожу…") { manager.signOut() }

    var removeDeviceError by mutableStateOf<String?>(null)
        private set

    fun removeDevice(id: String) = call("Убираю устройство…") { manager.removeDevice(id) }

    var passwordDialog by mutableStateOf(false)
    var deleteDialog by mutableStateOf(false)

    /** The dialogs' own error: the page's banner is behind the dialog. */
    var dialogError by mutableStateOf<String?>(null)
        private set

    fun openPasswordDialog() { dialogError = null; password = ""; password2 = ""; recoveryInput = ""; passwordDialog = true }
    fun openDeleteDialog() { dialogError = null; password = ""; deleteDialog = true }
    fun closeDialogs() { passwordDialog = false; deleteDialog = false; password = ""; password2 = ""; recoveryInput = "" }

    /** [recoveryInput] holds the old password in the change dialog. */
    fun changePassword() {
        val problem = if (recoveryInput.isEmpty()) "Введите текущий пароль." else checkNewPassword(password, password2)
        if (problem != null) { dialogError = problem; return }
        screenModelScope.launch {
            busy = "Меняю пароль…"
            try {
                manager.changePassword(recoveryInput, password)
                closeDialogs()
                notice = "Пароль изменён. На других устройствах войдите заново."
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubscriptionException) {
                dialogError = e.message
            } finally {
                busy = null
            }
        }
    }

    fun deleteAccount() {
        if (password.isEmpty()) { dialogError = "Введите пароль."; return }
        screenModelScope.launch {
            busy = "Удаляю аккаунт…"
            try {
                manager.deleteAccount(password)
                closeDialogs()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubscriptionException) {
                dialogError = e.message
            } finally {
                busy = null
            }
        }
    }

    fun openSupport() {
        val url = manager.state.value?.support.orEmpty()
        if (url.startsWith("https://") || url.startsWith("http://")) platform.openUrl(url)
    }

    // ---- buying ----

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

    fun openPlans() {
        plansOpen = true
        order = null
        paid = false
        plansError = null
        selectedPlan = null
        screenModelScope.launch {
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
        screenModelScope.launch {
            busy = "Оформляю заказ…"
            plansError = null
            try {
                val o = manager.checkout(id)
                order = o
                if (o.paymentUrl.startsWith("https://")) platform.openUrl(o.paymentUrl)
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

    /** Asks about the order until it is paid, while the dialog is open. */
    private fun poll(orderId: String) {
        pollJob?.cancel()
        pollJob = screenModelScope.launch {
            while (plansOpen && !paid) {
                delay(POLL_MS)
                checkOrderNow(orderId)
            }
        }
    }

    fun checkOrder() {
        val id = order?.orderId ?: return
        screenModelScope.launch { checkOrderNow(id) }
    }

    private suspend fun checkOrderNow(orderId: String) {
        try {
            val o = manager.order(orderId)
            order = o
            if (o.paid) paid = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: SubscriptionException) {
            plansError = e.message
        }
    }

    // ---- shared ----

    val isAccount: Boolean get() = manager.state.value?.kind == SubscriptionKind.Account

    private fun call(label: String, onFailure: (SubscriptionException) -> Boolean = { false }, block: suspend () -> Unit) {
        screenModelScope.launch {
            busy = label
            error = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubscriptionException) {
                if (!onFailure(e)) error = e.message
            } catch (e: Exception) {
                error = "Что-то пошло не так: ${e.message ?: e::class.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    private companion object {
        const val MIN_PASSWORD = 10
        const val POLL_MS = 8_000L
    }
}
