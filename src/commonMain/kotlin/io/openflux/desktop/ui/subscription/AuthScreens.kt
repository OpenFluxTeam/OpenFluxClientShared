package io.openflux.desktop.ui.subscription

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.openflux.desktop.ui.Format
import io.openflux.desktop.ui.components.AppButton
import io.openflux.desktop.ui.components.AppCard
import io.openflux.desktop.ui.components.AppIconButton
import io.openflux.desktop.ui.components.AppIcons
import io.openflux.desktop.ui.components.AppSwitch
import io.openflux.desktop.ui.components.AppTextField
import io.openflux.desktop.ui.components.Banner
import io.openflux.desktop.ui.components.ButtonRow
import io.openflux.desktop.ui.components.ButtonStyle
import io.openflux.desktop.ui.components.HorizontalRule
import io.openflux.desktop.ui.components.TextAction
import io.openflux.desktop.ui.components.Tone
import io.openflux.desktop.ui.theme.AppTheme

/** The shell of a sign-in screen: a back arrow, a title and a line about it. */
@Composable
private fun AuthFrame(title: String, subtitle: String, onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(Modifier.widthIn(max = 460.dp)) {
        if (onBack != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconButton("Назад", onBack, resource = AppIcons.Back)
            }
            Spacer(Modifier.height(AppTheme.spacing.s))
        }
        Text(title, style = AppTheme.typography.pageTitle, color = AppTheme.colors.text)
        Spacer(Modifier.height(AppTheme.spacing.xs))
        Text(subtitle, style = AppTheme.typography.body, color = AppTheme.colors.textSecondary)
        Spacer(Modifier.height(AppTheme.spacing.xl))
        content()
    }
}

/** What an account gives and how to start: create one or sign in. */
@Composable
fun AuthChoose(model: SubscriptionScreenModel, onBack: () -> Unit) {
    AuthFrame("Аккаунт OpenFlux", "Войдите, и список локаций появится сам: ничего настраивать не нужно.", onBack = onBack) {
        AppCard {
            Benefit("Пробный период бесплатно", "Попробуйте сервис, прежде чем платить.")
            HorizontalRule()
            Benefit("Одна кнопка", "Локации, ключи и документы обновляются сами.")
            HorizontalRule()
            Benefit("Несколько устройств", "Тот же аккаунт на телефоне и на компьютере.")
        }
        Spacer(Modifier.height(AppTheme.spacing.xl))
        ButtonRow {
            AppButton("Создать аккаунт", { model.go(AuthStep.Register) })
            AppButton("Войти", { model.go(AuthStep.Login) }, style = ButtonStyle.Secondary)
        }
        Spacer(Modifier.height(AppTheme.spacing.m))
        TextAction("Забыли пароль?", { model.go(AuthStep.Recover) })
    }
}

@Composable
private fun Benefit(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = AppTheme.spacing.s)) {
        Text(title, style = AppTheme.typography.bodyStrong, color = AppTheme.colors.text)
        Text(text, style = AppTheme.typography.bodySmall, color = AppTheme.colors.textSecondary)
    }
}

@Composable
fun AuthRegister(model: SubscriptionScreenModel) {
    val idle = model.busy == null
    AuthFrame("Создать аккаунт", "Логин и пароль. Почта не нужна: пароль можно будет вернуть по ключу восстановления.", onBack = { model.go(AuthStep.Choose) }) {
        AppTextField(model.login, { model.login = it; model.error = null }, label = "Логин", placeholder = "почта или имя", enabled = idle)
        Spacer(Modifier.height(AppTheme.spacing.m))
        AppTextField(model.password, { model.password = it; model.error = null }, label = "Пароль", secret = true, enabled = idle, helper = "Не короче 10 символов")
        Spacer(Modifier.height(AppTheme.spacing.m))
        AppTextField(model.password2, { model.password2 = it; model.error = null }, label = "Пароль ещё раз", secret = true, enabled = idle)
        FormStatus(model)
        Spacer(Modifier.height(AppTheme.spacing.xl))
        ButtonRow {
            AppButton(if (idle) "Создать аккаунт" else "Подождите…", model::register, enabled = idle)
        }
        Spacer(Modifier.height(AppTheme.spacing.m))
        TextAction("Уже есть аккаунт? Войти", { model.go(AuthStep.Login) })
    }
}

@Composable
fun AuthLogin(model: SubscriptionScreenModel) {
    val idle = model.busy == null
    AuthFrame("Войти", "Логин и пароль вашего аккаунта OpenFlux.", onBack = { model.go(AuthStep.Choose) }) {
        AppTextField(model.login, { model.login = it; model.error = null }, label = "Логин", enabled = idle)
        Spacer(Modifier.height(AppTheme.spacing.m))
        AppTextField(model.password, { model.password = it; model.error = null }, label = "Пароль", secret = true, enabled = idle)
        FormStatus(model)
        Spacer(Modifier.height(AppTheme.spacing.xl))
        ButtonRow {
            AppButton(if (idle) "Войти" else "Подождите…", { model.signIn() }, enabled = idle)
        }
        Spacer(Modifier.height(AppTheme.spacing.m))
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.l)) {
            TextAction("Забыли пароль?", { model.go(AuthStep.Recover) })
            TextAction("Создать аккаунт", { model.go(AuthStep.Register) })
        }
    }
}

@Composable
fun AuthRecover(model: SubscriptionScreenModel) {
    val idle = model.busy == null
    AuthFrame(
        "Восстановить доступ", "Ключ восстановления вам показали при создании аккаунта. С ним можно задать новый пароль.",
        onBack = { model.go(AuthStep.Choose) },
    ) {
        AppTextField(model.login, { model.login = it; model.error = null }, label = "Логин", enabled = idle)
        Spacer(Modifier.height(AppTheme.spacing.m))
        AppTextField(
            model.recoveryInput, { model.recoveryInput = it; model.error = null }, label = "Ключ восстановления", placeholder = "XXXX-XXXX-XXXX-…",
            monospace = true, enabled = idle,
        )
        Spacer(Modifier.height(AppTheme.spacing.m))
        AppTextField(model.password, { model.password = it; model.error = null }, label = "Новый пароль", secret = true, enabled = idle, helper = "Не короче 10 символов")
        Spacer(Modifier.height(AppTheme.spacing.m))
        AppTextField(model.password2, { model.password2 = it; model.error = null }, label = "Новый пароль ещё раз", secret = true, enabled = idle)
        FormStatus(model)
        Spacer(Modifier.height(AppTheme.spacing.m))
        Text(
            "Потеряли и ключ? Напишите в поддержку: владелец сервиса сбросит пароль.",
            style = AppTheme.typography.caption, color = AppTheme.colors.textHint,
        )
        Spacer(Modifier.height(AppTheme.spacing.l))
        ButtonRow {
            AppButton(if (idle) "Задать новый пароль" else "Подождите…", model::recover, enabled = idle)
        }
    }
}

/** An account at its device limit: pick the device that gives way. */
@Composable
fun AuthDevices(model: SubscriptionScreenModel, nowMillis: Long) {
    AuthFrame(
        "Слишком много устройств", "В тарифе предел устройств. Выберите, какое отключить, и этот компьютер войдёт вместо него.",
        onBack = { model.go(AuthStep.Login) },
    ) {
        AppCard(padding = 0.dp) {
            model.deviceChoices.forEachIndexed { i, d ->
                if (i > 0) HorizontalRule()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = AppTheme.spacing.l, vertical = AppTheme.spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = AppTheme.typography.bodyStrong, color = AppTheme.colors.text)
                        Text(
                            if (d.lastSeen > 0) "был в сети " + Format.ago(nowMillis - d.lastSeen * 1000) else "ещё не был в сети",
                            style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary,
                        )
                    }
                    AppButton("Заменить", { model.signIn(replaceDeviceId = d.id) }, style = ButtonStyle.Secondary, enabled = model.busy == null)
                }
            }
        }
        FormStatus(model)
    }
}

/**
 * The recovery key, shown once. The screen stays until the person says they
 * have it: without it a forgotten password cannot be given back.
 */
@Composable
fun RecoveryKeyScreen(model: SubscriptionScreenModel, key: String, trialDays: String?) {
    var saved by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 460.dp)) {
        Banner(
            "Аккаунт создан." + (trialDays?.let { " Пробный период: $it." } ?: ""),
            Tone.Success, icon = Icons.Rounded.CheckCircle,
        )
        Spacer(Modifier.height(AppTheme.spacing.xl))
        Text("Запишите ключ восстановления", style = AppTheme.typography.pageTitle, color = AppTheme.colors.text)
        Spacer(Modifier.height(AppTheme.spacing.xs))
        Text(
            "Он показывается один раз. Если забудете пароль, только с этим ключом его можно будет вернуть.",
            style = AppTheme.typography.body, color = AppTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(AppTheme.spacing.l))
        AppCard {
            SelectionContainer {
                Text(
                    // Four groups to a line: the key never breaks inside a group.
                    key.split('-').chunked(4).joinToString("\n") { it.joinToString("-") },
                    style = AppTheme.typography.sectionTitle, color = AppTheme.colors.text,
                    fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(AppTheme.spacing.m))
            ButtonRow(alignment = Alignment.CenterHorizontally) {
                AppButton("Скопировать", model::copyRecoveryKey, style = ButtonStyle.Secondary, leading = Icons.Rounded.ContentCopy)
            }
        }
        Spacer(Modifier.height(AppTheme.spacing.l))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
            AppSwitch(saved, { saved = it })
            Text("Я сохранил ключ в надёжном месте", style = AppTheme.typography.body, color = AppTheme.colors.text)
        }
        Spacer(Modifier.height(AppTheme.spacing.xl))
        ButtonRow {
            AppButton("Продолжить", model::recoveryKeySaved, enabled = saved)
        }
    }
}

/** The error or progress line under a form. */
@Composable
private fun FormStatus(model: SubscriptionScreenModel) {
    val error = model.error
    val busy = model.busy
    if (error != null) {
        Spacer(Modifier.height(AppTheme.spacing.m))
        Banner(error, Tone.Danger, icon = Icons.Rounded.Warning)
    } else if (busy != null) {
        Spacer(Modifier.height(AppTheme.spacing.m))
        Banner(busy, Tone.Accent, icon = Icons.Rounded.Info)
    }
}
