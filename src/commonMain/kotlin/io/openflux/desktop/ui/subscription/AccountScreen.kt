package io.openflux.desktop.ui.subscription

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.openflux.desktop.model.SubscriptionKind
import io.openflux.desktop.model.SubscriptionState
import io.openflux.desktop.ui.Format
import io.openflux.desktop.ui.components.AppButton
import io.openflux.desktop.ui.components.AppCard
import io.openflux.desktop.ui.components.AppDialog
import io.openflux.desktop.ui.components.AppTextField
import io.openflux.desktop.ui.components.Banner
import io.openflux.desktop.ui.components.ButtonRow
import io.openflux.desktop.ui.components.ButtonStyle
import io.openflux.desktop.ui.components.HorizontalRule
import io.openflux.desktop.ui.components.SectionLabel
import io.openflux.desktop.ui.components.StatusBadge
import io.openflux.desktop.ui.components.Tone
import io.openflux.desktop.ui.theme.AppTheme

/** The signed-in screen: the state of the subscription, its locations, the devices and the account's actions. */
@Composable
fun AccountScreen(model: SubscriptionScreenModel, state: SubscriptionState, locations: List<String>, nowMillis: Long) {
    val refreshing by model.manager.refreshing.collectAsState()
    val look = state.look(nowMillis, model.platform.utcOffsetSeconds)
    val account = state.account
    Column(Modifier.widthIn(max = 640.dp)) {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.title.ifBlank { "OpenFlux" }, style = AppTheme.typography.sectionTitle, color = AppTheme.colors.text)
                    account?.let { Text(it.login, style = AppTheme.typography.bodySmall, color = AppTheme.colors.textSecondary) }
                }
                StatusBadge(look.badge, look.tone)
            }
            Spacer(Modifier.height(AppTheme.spacing.s))
            Text(look.line, style = AppTheme.typography.body, color = AppTheme.colors.text)
            account?.plan?.let {
                Text("Тариф «${it.name}», устройств: ${it.devices}", style = AppTheme.typography.bodySmall, color = AppTheme.colors.textSecondary)
            }
            state.refreshedText(nowMillis)?.let {
                Text(it, style = AppTheme.typography.caption, color = AppTheme.colors.textHint)
            }
            Spacer(Modifier.height(AppTheme.spacing.l))
            ButtonRow {
                if (state.kind == SubscriptionKind.Account) {
                    if (account?.trialAvailable == true && !state.active) {
                        AppButton("Начать пробный период", model::startTrial, enabled = model.busy == null)
                    }
                    AppButton(
                        "Продлить", model::openPlans,
                        style = if (look.attention) ButtonStyle.Primary else ButtonStyle.Secondary,
                    )
                } else if (state.support.isNotEmpty()) {
                    AppButton("Продлить", model::openSupport, style = if (look.attention) ButtonStyle.Primary else ButtonStyle.Secondary, leading = Icons.Rounded.OpenInNew)
                }
                AppButton(
                    if (refreshing) "Обновляю…" else "Обновить", model::refresh,
                    style = ButtonStyle.Secondary, leading = Icons.Rounded.Refresh, enabled = !refreshing,
                )
            }
        }

        state.lastError?.let {
            Spacer(Modifier.height(AppTheme.spacing.m))
            Banner("Не удалось обновить: $it Показан последний список.", Tone.Warning, icon = Icons.Rounded.Warning)
        }
        if (state.announce.isNotEmpty() && state.announce != state.announceSeen) {
            Spacer(Modifier.height(AppTheme.spacing.m))
            Banner(
                state.announce, Tone.Accent, icon = Icons.Rounded.Info,
                action = { AppButton("Закрыть", model.manager::dismissAnnounce, style = ButtonStyle.Ghost, leading = Icons.Rounded.Close) },
            )
        }
        model.notice?.let {
            Spacer(Modifier.height(AppTheme.spacing.m))
            Banner(it, Tone.Success, icon = Icons.Rounded.CheckCircle)
        }
        model.error?.let {
            Spacer(Modifier.height(AppTheme.spacing.m))
            Banner(it, Tone.Danger)
        }
        model.busy?.let {
            Spacer(Modifier.height(AppTheme.spacing.m))
            Banner(it, Tone.Accent, icon = Icons.Rounded.Info)
        }

        Spacer(Modifier.height(AppTheme.spacing.xxl))
        SectionLabel("Локации")
        Spacer(Modifier.height(AppTheme.spacing.s))
        AppCard {
            if (locations.isEmpty()) {
                Text(
                    if (state.active) "Список пока пуст. Нажмите «Обновить»." else "Локации появятся, когда подписка станет активной.",
                    style = AppTheme.typography.body, color = AppTheme.colors.textSecondary,
                )
            } else {
                Text(locations.joinToString(", "), style = AppTheme.typography.body, color = AppTheme.colors.text)
                Spacer(Modifier.height(AppTheme.spacing.xs))
                Text("Выбор и подключение: на вкладке «Главная».", style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary)
            }
        }

        if (state.kind == SubscriptionKind.Account && account != null) {
            Spacer(Modifier.height(AppTheme.spacing.xl))
            SectionLabel("Устройства: ${account.devices.size} из ${account.deviceLimit}")
            Spacer(Modifier.height(AppTheme.spacing.s))
            AppCard(padding = 0.dp) {
                account.devices.forEachIndexed { i, d ->
                    if (i > 0) HorizontalRule()
                    Row(Modifier.fillMaxWidth().padding(horizontal = AppTheme.spacing.l, vertical = AppTheme.spacing.m), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name + if (d.current || d.id == state.deviceId) " · это устройство" else "", style = AppTheme.typography.bodyStrong, color = AppTheme.colors.text)
                            // The service notes a device when it asks, which is not each minute: this one is here by definition.
                            val here = d.current || d.id == state.deviceId
                            if (here || d.lastSeen > 0) {
                                Text(
                                    if (here) "сейчас на связи" else "был в сети " + Format.ago(nowMillis - d.lastSeen * 1000),
                                    style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary,
                                )
                            }
                        }
                        if (!(d.current || d.id == state.deviceId)) {
                            AppButton("Убрать", { model.removeDevice(d.id) }, style = ButtonStyle.Ghost, enabled = model.busy == null)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(AppTheme.spacing.xl))
        SectionLabel(if (state.kind == SubscriptionKind.Account) "Аккаунт" else "Ключ подписки")
        Spacer(Modifier.height(AppTheme.spacing.s))
        ButtonRow {
            if (state.kind == SubscriptionKind.Account) {
                AppButton("Сменить пароль", model::openPasswordDialog, style = ButtonStyle.Secondary, leading = Icons.Rounded.Lock)
            }
            if (state.support.isNotEmpty()) {
                AppButton("Поддержка", model::openSupport, style = ButtonStyle.Secondary, leading = Icons.Rounded.OpenInNew)
            }
            AppButton(
                if (state.kind == SubscriptionKind.Account) "Выйти" else "Удалить подписку", model::signOut,
                style = ButtonStyle.Secondary, leading = Icons.Rounded.PowerSettingsNew, enabled = model.busy == null,
            )
        }
        if (state.kind == SubscriptionKind.Account) {
            Spacer(Modifier.height(AppTheme.spacing.m))
            AppButton("Удалить аккаунт", model::openDeleteDialog, style = ButtonStyle.Ghost, leading = Icons.Rounded.Delete)
        }
        Spacer(Modifier.height(AppTheme.spacing.xxl))
    }

    if (model.passwordDialog) PasswordDialog(model)
    if (model.deleteDialog) DeleteDialog(model)
    if (model.plansOpen) PlansDialog(model, nowMillis)
}

@Composable
private fun PasswordDialog(model: SubscriptionScreenModel) {
    AppDialog(
        title = "Сменить пароль", onDismiss = model::closeDialogs,
        primary = "Сменить", onPrimary = model::changePassword, primaryEnabled = model.busy == null,
    ) {
        AppTextField(model.recoveryInput, { model.recoveryInput = it }, label = "Текущий пароль", secret = true)
        Spacer(Modifier.height(AppTheme.spacing.m))
        AppTextField(model.password, { model.password = it }, label = "Новый пароль", secret = true, helper = "Не короче 10 символов")
        Spacer(Modifier.height(AppTheme.spacing.m))
        AppTextField(model.password2, { model.password2 = it }, label = "Новый пароль ещё раз", secret = true)
        Spacer(Modifier.height(AppTheme.spacing.s))
        Text("На других устройствах придётся войти заново.", style = AppTheme.typography.caption, color = AppTheme.colors.textHint)
        model.dialogError?.let {
            Spacer(Modifier.height(AppTheme.spacing.m))
            Banner(it, Tone.Danger)
        }
    }
}

@Composable
private fun DeleteDialog(model: SubscriptionScreenModel) {
    AppDialog(
        title = "Удалить аккаунт?", onDismiss = model::closeDialogs,
        primary = "Удалить навсегда", onPrimary = model::deleteAccount, primaryStyle = ButtonStyle.Danger, primaryEnabled = model.busy == null,
    ) {
        Text(
            "Аккаунт, его устройства и заказы будут удалены, подписка пропадёт. Это нельзя отменить. Для подтверждения введите пароль.",
            style = AppTheme.typography.body, color = AppTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(AppTheme.spacing.m))
        AppTextField(model.password, { model.password = it }, label = "Пароль", secret = true)
        model.dialogError?.let {
            Spacer(Modifier.height(AppTheme.spacing.m))
            Banner(it, Tone.Danger)
        }
    }
}

/** Plans for sale, then the order: how to pay and the wait for the owner to confirm it. */
@Composable
private fun PlansDialog(model: SubscriptionScreenModel, nowMillis: Long) {
    val plans = model.plans
    val order = model.order
    val offset = model.platform.utcOffsetSeconds
    val expires = model.manager.state.value?.expires ?: 0L
    AppDialog(
        title = if (model.paid) "Оплата получена" else "Продлить подписку",
        onDismiss = model::closePlans,
        primary = when {
            model.paid -> "Готово"
            order != null -> "Проверить оплату"
            else -> "Оформить заказ"
        },
        onPrimary = when {
            model.paid -> model::closePlans
            order != null -> model::checkOrder
            else -> model::checkout
        },
        primaryEnabled = model.busy == null && (order != null || model.selectedPlan != null),
        secondary = if (model.paid) null else if (order != null) "Закрыть" else "Отмена",
    ) {
        model.plansError?.let {
            Banner(it, Tone.Danger)
            Spacer(Modifier.height(AppTheme.spacing.m))
        }
        when {
            model.paid -> Banner(
                "Подписка продлена" + (if (expires > 0) " до ${Format.date(expires, offset)}" else "") + ". Спасибо!",
                Tone.Success, icon = Icons.Rounded.CheckCircle,
            )
            order != null -> {
                Text("Заказ оформлен.", style = AppTheme.typography.bodyStrong, color = AppTheme.colors.text)
                if (order.instructions.isNotBlank()) {
                    Spacer(Modifier.height(AppTheme.spacing.s))
                    Text(order.instructions, style = AppTheme.typography.body, color = AppTheme.colors.text)
                }
                Spacer(Modifier.height(AppTheme.spacing.m))
                Text(
                    "Как только оплату подтвердят, подписка продлится сама: окно можно не закрывать.",
                    style = AppTheme.typography.bodySmall, color = AppTheme.colors.textSecondary,
                )
                Spacer(Modifier.height(AppTheme.spacing.s))
                Text("Номер заказа: ${order.orderId}", style = AppTheme.typography.caption, color = AppTheme.colors.textHint)
            }
            plans == null -> if (model.plansError == null) Text("Загружаю тарифы…", style = AppTheme.typography.body, color = AppTheme.colors.textSecondary)
            plans.plans.isEmpty() -> Text("Платных тарифов пока нет.", style = AppTheme.typography.body, color = AppTheme.colors.textSecondary)
            else -> Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                plans.plans.forEach { p ->
                    val selected = p.id == model.selectedPlan
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(AppTheme.shapes.card)
                            .background(if (selected) AppTheme.colors.accentSoft else AppTheme.colors.surface)
                            .border(BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) AppTheme.colors.accent else AppTheme.colors.border), AppTheme.shapes.card)
                            .clickable { model.selectedPlan = p.id }
                            .padding(AppTheme.spacing.l),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = AppTheme.typography.bodyStrong, color = AppTheme.colors.text)
                            Text(
                                "${Format.days(p.days)} · устройств: ${p.devices}",
                                style = AppTheme.typography.bodySmall, color = AppTheme.colors.textSecondary,
                            )
                        }
                        if (p.price.isNotBlank()) Text(p.price, style = AppTheme.typography.bodyStrong, color = AppTheme.colors.text)
                    }
                }
            }
        }
        model.busy?.let {
            Spacer(Modifier.height(AppTheme.spacing.m))
            Text(it, style = AppTheme.typography.bodySmall, color = AppTheme.colors.textSecondary)
        }
    }
}
