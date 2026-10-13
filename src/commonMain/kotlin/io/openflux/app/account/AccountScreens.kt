package io.openflux.app.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.openflux.app.components.Banner
import io.openflux.app.components.ConfirmDialog
import io.openflux.app.components.Field
import io.openflux.app.components.LocalToaster
import io.openflux.app.components.PrimaryButton
import io.openflux.app.components.ProgressLine
import io.openflux.app.components.RowItem
import io.openflux.app.components.ScreenScaffold
import io.openflux.app.components.SecondaryButton
import io.openflux.app.components.SecretField
import io.openflux.app.components.Section
import io.openflux.app.components.Segmented
import io.openflux.app.components.Sheet
import io.openflux.app.components.TextAction
import io.openflux.app.components.Toggle
import io.openflux.app.components.Tone
import io.openflux.app.components.ValueRow
import io.openflux.app.home.look
import io.openflux.app.nav.Dest
import io.openflux.app.shell.LocalNavigator
import io.openflux.app.shell.rememberScreenModel
import io.openflux.app.text.Fmt
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.SubscriptionKind
import io.openflux.desktop.service.LocalAppContainer

/** Signing in to the OpenFlux account (spec 8.1). */
@Composable
fun AuthScreen() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val toaster = LocalToaster.current
    val m = rememberScreenModel { AuthModel(container, it) }
    val sub by container.subscription.state.collectAsState()
    LaunchedEffect(m.done) { if (m.done) navigator.replaceTop(Dest.Account) }
    LaunchedEffect(Unit) { if (sub?.signedIn == true && m.recoveryKey == null) navigator.replaceTop(Dest.Account) }
    ScreenScaffold("Аккаунт OpenFlux", onBack = { navigator.back() }) {
        val key = m.recoveryKey
        if (key != null) {
            var saved by rememberSaveable { mutableStateOf(false) }
            Banner("Аккаунт готов. Сохраните ключ восстановления: без него забытый пароль не вернуть", Tone.Success)
            Section(null) {
                ValueRow("Ключ", key.chunked(4).joinToString(" "), mono = true, onCopy = { m.copyRecoveryKey(); toaster.show("Ключ скопирован") })
            }
            Section(null) { Toggle("Я сохранил ключ в надёжном месте", saved, { saved = it }) }
            PrimaryButton("Продолжить", m::recoveryKeySaved, Modifier.fillMaxWidth(), enabled = saved)
            return@ScreenScaffold
        }
        when (m.step) {
            AuthStep.Devices -> {
                Text("На аккаунте уже все устройства. Замените одно из них этим.", style = AppTheme.type.body, color = AppTheme.colors.textSecondary)
                Section(null) {
                    for (d in m.devices) {
                        RowItem(
                            d.name.ifBlank { "Устройство" },
                            subtitle = if (d.lastSeen > 0) "было в сети ${Fmt.ago(container.platform.now() - d.lastSeen * 1000)}" else null,
                            trailing = { TextAction("Заменить", { m.signIn(replaceDeviceId = d.id) }, enabled = m.busy == null) },
                        )
                    }
                }
                TextAction("Назад ко входу", { m.go(AuthStep.Login) })
            }
            AuthStep.Recover -> {
                Text("Восстановление доступа по ключу, выданному при регистрации.", style = AppTheme.type.body, color = AppTheme.colors.textSecondary)
                Field(m.login, { m.login = it }, "Логин")
                Field(m.recoveryInput, { m.recoveryInput = it }, "Ключ восстановления", mono = true)
                SecretField(m.password, { m.password = it }, "Новый пароль")
                SecretField(m.password2, { m.password2 = it }, "Новый пароль ещё раз")
                Submit(m.busy, "Задать новый пароль", m::recover)
                TextAction("Назад ко входу", { m.go(AuthStep.Login) })
            }
            else -> {
                Segmented(listOf(AuthStep.Login, AuthStep.Register), m.step, m::go, label = { if (it == AuthStep.Login) "Вход" else "Регистрация" })
                Field(m.login, { m.login = it }, "Логин", placeholder = "Почта или имя")
                SecretField(m.password, { m.password = it }, "Пароль")
                if (m.step == AuthStep.Register) {
                    SecretField(m.password2, { m.password2 = it }, "Пароль ещё раз")
                    Text("Не короче $MIN_PASSWORD символов", style = AppTheme.type.bodySmall, color = AppTheme.colors.textMuted)
                    Submit(m.busy, "Создать аккаунт", m::register)
                } else {
                    Submit(m.busy, "Войти") { m.signIn() }
                    TextAction("Забыли пароль?", { m.go(AuthStep.Recover) })
                }
            }
        }
        m.error?.let { Banner(it, Tone.Danger) }
    }
}

@Composable
private fun Submit(busy: String?, label: String, onClick: () -> Unit) {
    if (busy != null) ProgressLine(busy) else PrimaryButton(label, onClick, Modifier.fillMaxWidth())
}

/** The subscription (spec 8.2). */
@Composable
fun AccountScreen() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val toaster = LocalToaster.current
    val m = rememberScreenModel { SubscriptionModel(container, it) }
    val state by container.subscription.state.collectAsState()
    var passwordOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var signOutAsk by remember { mutableStateOf(false) }
    LaunchedEffect(m.notice) { m.notice?.let { toaster.show(it); m.clearNotice() } }
    ScreenScaffold("Подписка", onBack = { navigator.back() }, actions = { if (state != null) TextAction("Обновить", m::refresh, enabled = m.busy == null) }) {
        val s = state
        if (s == null) {
            Text("На этом устройстве нет подписки.", style = AppTheme.type.body, color = AppTheme.colors.textSecondary)
            PrimaryButton("Войти или создать аккаунт", { navigator.replaceTop(Dest.Auth) }, Modifier.fillMaxWidth())
            SecondaryButton("У меня ключ подписки", { navigator.replaceTop(Dest.AddKey) }, Modifier.fillMaxWidth())
            return@ScreenScaffold
        }
        val platform = container.platform
        val look = s.look(platform.now(), platform.utcOffsetSeconds)
        val account = s.account
        Section(null) {
            ValueRow("Подписка", s.title.ifBlank { "OpenFlux" })
            if (account != null) ValueRow("Логин", account.login)
            ValueRow("Состояние", look.badge)
            ValueRow("Срок", look.line.removeSuffix("."))
            account?.plan?.let { ValueRow("Тариф", it.name) }
            if (account != null) ValueRow("Устройства", "${account.devices.size} из ${account.deviceLimit}")
        }
        s.lastError?.let { Banner("Не удалось обновить: $it", Tone.Warning) }
        if (s.announce.isNotBlank() && s.announce != s.announceSeen) {
            Banner(s.announce, Tone.Info, action = "Закрыть", onAction = container.subscription::dismissAnnounce)
        }
        when {
            s.kind == SubscriptionKind.Account && account?.trialAvailable == true && !s.active ->
                PrimaryButton("Начать пробный период", m::startTrial, Modifier.fillMaxWidth(), enabled = m.busy == null)
            s.kind == SubscriptionKind.Account -> PrimaryButton(if (look.attention) "Продлить" else "Продлить заранее", m::openPlans, Modifier.fillMaxWidth(), enabled = m.busy == null)
            s.support.isNotBlank() -> PrimaryButton("Продлить через поддержку", m::openSupport, Modifier.fillMaxWidth())
        }
        m.busy?.let { ProgressLine(it) }
        m.error?.let { Banner(it, Tone.Danger) }
        if (account != null && account.devices.isNotEmpty()) {
            Section("Устройства") {
                for (d in account.devices) {
                    RowItem(
                        d.name.ifBlank { "Устройство" },
                        subtitle = if (d.current) "это устройство" else if (d.lastSeen > 0) "было в сети ${Fmt.ago(platform.now() - d.lastSeen * 1000)}" else null,
                        trailing = if (d.current) null else ({ TextAction("Убрать", { m.removeDevice(d.id) }, enabled = m.busy == null) }),
                    )
                }
            }
        }
        Section(null) {
            if (s.support.isNotBlank()) RowItem("Поддержка", onClick = m::openSupport)
            if (s.kind == SubscriptionKind.Account) RowItem("Сменить пароль", onClick = { m.clearDialogError(); passwordOpen = true })
            RowItem(if (s.kind == SubscriptionKind.Account) "Выйти" else "Убрать подписку с устройства", onClick = { signOutAsk = true })
            if (s.kind == SubscriptionKind.Account) RowItem("Удалить аккаунт", onClick = { m.clearDialogError(); deleteOpen = true })
        }
    }
    if (signOutAsk) {
        ConfirmDialog(
            title = if (state?.kind == SubscriptionKind.Account) "Выйти из аккаунта?" else "Убрать подписку?",
            text = "Локации подписки пропадут с этого устройства.",
            confirm = if (state?.kind == SubscriptionKind.Account) "Выйти" else "Убрать",
            onConfirm = { signOutAsk = false; m.signOut() },
            onDismiss = { signOutAsk = false },
        )
    }
    if (passwordOpen) PasswordSheet(m) { passwordOpen = false }
    if (deleteOpen) DeleteSheet(m) { deleteOpen = false }
    if (m.plansOpen) PlansSheet(m)
}

@Composable
private fun PasswordSheet(m: SubscriptionModel, onClose: () -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    Sheet(onDismiss = onClose, title = "Смена пароля") {
        Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
            SecretField(old, { old = it }, "Текущий пароль")
            SecretField(new, { new = it }, "Новый пароль")
            SecretField(again, { again = it }, "Новый пароль ещё раз")
            Text("На других устройствах придётся войти заново.", style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary)
            m.dialogError?.let { Banner(it, Tone.Danger) }
            Submit(m.busy, "Сменить") { m.changePassword(old, new, again, onDone = onClose) }
        }
    }
}

@Composable
private fun DeleteSheet(m: SubscriptionModel, onClose: () -> Unit) {
    var password by remember { mutableStateOf("") }
    Sheet(onDismiss = onClose, title = "Удалить аккаунт?") {
        Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
            Text("Аккаунт, подписка и устройства удалятся навсегда. Вернуть их не получится.", style = AppTheme.type.body, color = AppTheme.colors.text)
            SecretField(password, { password = it }, "Пароль")
            m.dialogError?.let { Banner(it, Tone.Danger) }
            if (m.busy != null) ProgressLine(m.busy!!)
            else io.openflux.app.components.DangerButton("Удалить навсегда", { m.deleteAccount(password, onDone = onClose) }, Modifier.fillMaxWidth())
        }
    }
}

/** Renewing: a plan, the order and how to pay, then "paid" (spec 8.2). */
@Composable
private fun PlansSheet(m: SubscriptionModel) {
    Sheet(onDismiss = m::closePlans, title = "Продление") {
        Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
            val plans = m.plans
            val order = m.order
            when {
                m.paid -> {
                    Banner("Оплата получена. Подписка продлена", Tone.Success)
                    PrimaryButton("Готово", m::closePlans, Modifier.fillMaxWidth())
                }
                order != null -> {
                    Text(order.instructions.ifBlank { plans?.payment?.instructions.orEmpty() }.ifBlank { "Оплатите заказ ${order.orderId} по инструкции сервиса." }, style = AppTheme.type.body, color = AppTheme.colors.text)
                    ProgressLine("Жду подтверждения оплаты…")
                    SecondaryButton("Проверить оплату", m::checkOrder, Modifier.fillMaxWidth())
                }
                plans == null && m.plansError == null -> ProgressLine("Загружаю тарифы…")
                plans != null && plans.plans.isEmpty() -> Text("Платных тарифов пока нет.", style = AppTheme.type.body, color = AppTheme.colors.textSecondary)
                plans != null -> {
                    Section(null) {
                        for (p in plans.plans) {
                            RowItem(
                                p.name,
                                subtitle = "${Fmt.days(p.days)} · устройств: ${p.devices}" + if (p.price.isNotBlank()) " · ${p.price}" else "",
                                selected = m.selectedPlan == p.id,
                                onClick = { m.selectedPlan = p.id },
                            )
                        }
                    }
                    Submit(m.busy, "Оформить заказ", m::checkout)
                }
            }
            m.plansError?.let { Banner(it, Tone.Danger) }
        }
    }
}
