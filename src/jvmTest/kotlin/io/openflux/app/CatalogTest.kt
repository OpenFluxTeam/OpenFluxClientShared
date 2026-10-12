package io.openflux.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import io.openflux.app.components.Banner
import io.openflux.app.components.Chip
import io.openflux.app.components.Collapsible
import io.openflux.app.components.ConfirmDialog
import io.openflux.app.components.DangerButton
import io.openflux.app.components.Field
import io.openflux.app.components.IconButton2
import io.openflux.app.components.PrimaryButton
import io.openflux.app.components.RowItem
import io.openflux.app.components.ScreenScaffold
import io.openflux.app.components.SecondaryButton
import io.openflux.app.components.SecretField
import io.openflux.app.components.Section
import io.openflux.app.components.Segmented
import io.openflux.app.components.Sheet
import io.openflux.app.components.Status
import io.openflux.app.components.StatusDot
import io.openflux.app.components.TextButton2
import io.openflux.app.components.Toggle
import io.openflux.app.components.Tone
import io.openflux.app.components.ValueRow
import io.openflux.app.shell.AddMenu
import io.openflux.app.shell.OpenFluxApp2
import io.openflux.app.theme.AppTheme2
import io.openflux.desktop.ui.NoScrollbars
import io.openflux.desktop.ui.Shortcuts
import kotlin.test.Test

/** Every component in one screen, for review by eye (SHOTS=<dir>). */
@Composable
fun ComponentsCatalog() {
    ScreenScaffold("Компоненты", onBack = {}, actions = { IconButton2(Icons.Rounded.Add, "Добавить · Ctrl+N", {}) }) {
        Section("Строки") {
            RowItem("Амстердам", subtitle = "Мои серверы · direct", selected = true, leading = { StatusDot(Status.On) })
            RowItem("Франкфурт", subtitle = "Подписка", leading = { StatusDot(Status.Off) }, trailing = { IconButton2(Icons.Rounded.MoreVert, "Ещё", {}) }, onClick = {})
            RowItem("root@203.0.113.7", leading = { Icon(Icons.Rounded.Dns, null, tint = AppTheme2.colors.textSecondary) }, onClick = {})
            RowItem("Нода с ошибкой", subtitle = "Не отвечает с 12:40", leading = { StatusDot(Status.Error) })
            RowItem("Выключено", enabled = false)
        }
        Section(null) {
            ValueRow("Адрес", "203.0.113.7:8445", mono = true, onCopy = {})
            ValueRow("Шифрование", "AES-256-GCM")
        }
        Section("Поля") {
            Toggle("Автообновление ядра", checked = true, onCheckedChange = {})
            Toggle("Весь трафик", checked = false, onCheckedChange = {}, subtitle = "Через VPN")
        }
        Field("203.0.113.7", {}, "Адрес")
        Field("абв", {}, "Порт", error = "Порт — число от 1 до 65535")
        SecretField("hunter2", {}, "Пароль")
        Segmented(listOf("L4", "L3"), "L4", {}, label = { it })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme2.spacing.s)) {
            Chip("Весь трафик", selected = true, onClick = {})
            Chip("Системный прокси", selected = false, onClick = {})
        }
        Banner("Нет связи с сервером", Tone.Danger, action = "Повторить", onAction = {}, secondAction = "Журнал", onSecondAction = {})
        Banner("Сессия Яндекса истекла", Tone.Warning, action = "Войти заново", onAction = {})
        Banner("Нода установлена", Tone.Success)
        Banner("Применится при переподключении", Tone.Info)
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme2.spacing.s), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Применить", {})
            SecondaryButton("Отменить", {})
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme2.spacing.s), verticalAlignment = Alignment.CenterVertically) {
            DangerButton("Удалить", {})
            TextButton2("Подробнее", {})
            PrimaryButton("Нельзя", {}, enabled = false)
        }
        Section(null) {
            Collapsible("Дополнительно", initiallyOpen = true) {
                ValueRow("Порт", "8445", mono = true)
            }
        }
    }
}

class CatalogTest {
    @Test fun catalogPhone() = Shots.render("components") { ComponentsCatalog() }
    @Test fun catalogSmall() = Shots.render("components", Shots.SMALL) { ComponentsCatalog() }
    @Test fun catalogWide() = Shots.render("components", Shots.WIDE) { ComponentsCatalog() }
    @Test fun sheetPhone() = Shots.render("sheet") { Sheet(onDismiss = {}, title = "Подключение") { RowItem("Амстердам", selected = true); RowItem("Франкфурт") } }
    @Test fun confirmDialog() = Shots.render("confirm") { ConfirmDialog("Удалить ноду?", "Нода of-abc будет удалена с сервера.", "Удалить", {}, {}, danger = true) }
    @Test fun shellPhone() = Shots.render("shell") { OpenFluxApp2(testContainer(), NoScrollbars, Shortcuts()) }
    @Test fun shellSmall() = Shots.render("shell", Shots.SMALL) { OpenFluxApp2(testContainer(), NoScrollbars, Shortcuts()) }
    @Test fun shellWide() = Shots.render("shell", Shots.WIDE) { OpenFluxApp2(testContainer(), NoScrollbars, Shortcuts()) }
    @Test fun addMenu() = Shots.render("add-menu") { AddMenu(cameraScan = true, onChoice = {}, onDismiss = {}) }
}
