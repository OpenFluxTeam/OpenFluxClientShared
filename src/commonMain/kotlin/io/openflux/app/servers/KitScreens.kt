package io.openflux.app.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.openflux.app.components.Banner
import io.openflux.app.components.LocalToaster
import io.openflux.app.components.PrimaryButton
import io.openflux.app.components.QrView
import io.openflux.app.components.RowItem
import io.openflux.app.components.ScreenScaffold
import io.openflux.app.components.SecondaryButton
import io.openflux.app.components.Section
import io.openflux.app.components.Sheet
import io.openflux.app.components.Status
import io.openflux.app.components.StatusDot
import io.openflux.app.components.Tone
import io.openflux.app.components.ValueRow
import io.openflux.app.nav.Dest
import io.openflux.app.shell.LocalNavigator
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.KitDelivery
import io.openflux.desktop.model.NodeKit
import io.openflux.desktop.model.ProfileTransfer
import io.openflux.desktop.model.isActive
import io.openflux.desktop.service.LocalAppContainer

/**
 * Where a new node will live (the first thing to answer when there is no node yet): on a server the app reaches
 * over SSH, or somewhere it does not - a machine the person runs a command on, another device with the app, this
 * device. [Dest.NodeWhere.forProfile] is a profile that exists already and gets the node.
 */
@Composable
fun NodeWhereScreen(dest: Dest.NodeWhere) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val settings by container.settings.settings.collectAsState()
    ScreenScaffold("Где будет нода", onBack = { navigator.back() }) {
        Text(
            "Нода — программа, которая принимает ваше подключение и выпускает его в интернет. Она должна работать там, " +
                "куда у вас есть доступ, и всё время, пока вы подключены.",
            style = AppTheme.type.body, color = AppTheme.colors.textSecondary,
        )
        if (settings.knownServers.isNotEmpty()) {
            Section("Мои серверы") {
                for (s in settings.knownServers) {
                    RowItem(
                        s.host, subtitle = "SSH: приложение поставит и запустит ноду само",
                        trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = AppTheme.colors.textMuted) },
                        onClick = { navigator.open(Dest.NewNode(serverIdOf(s), forProfile = dest.forProfile)) },
                    )
                }
            }
        }
        Section(if (settings.knownServers.isEmpty()) "Сервер" else "Другой сервер") {
            RowItem(
                NodePlacement.Ssh.title, subtitle = NodePlacement.Ssh.about,
                trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = AppTheme.colors.textMuted) },
                onClick = { navigator.open(Dest.AddServer) },
            )
        }
        Section("Без SSH") {
            for (placement in listOf(NodePlacement.Manual, NodePlacement.Device, NodePlacement.Here)) {
                RowItem(
                    placement.title, subtitle = placement.about,
                    trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = AppTheme.colors.textMuted) },
                    onClick = { navigator.open(Dest.NewNode(placement.id ?: return@RowItem, forProfile = dest.forProfile)) },
                )
            }
        }
    }
}

/** The screen of a node without SSH: how it is started, and the ways to hand its profile on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KitNodePanel(m: NodeEditorModel, connected: Boolean, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val profile = m.localProfile
    val delivery = profile?.kitNode?.delivery ?: m.placement.delivery ?: return
    var transfer by remember { mutableStateOf<ProfileTransfer.Role?>(null) }
    val busy = m.busy != null
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
        StatusDot(Status.Off)
        Text("Ждёт запуска · " + NodePlacement.of(delivery).title, style = AppTheme.type.body, color = AppTheme.colors.textSecondary)
    }
    Text(
        when (delivery) {
            KitDelivery.Manual -> "Выполните команду на машине, где будет нода: скрипт скачает проверенный установщик, поставит и запустит ноду. " +
                "Если такая нода там уже есть, он только изменит её транспорты."
            KitDelivery.Device -> "Передайте профиль ноды на другое устройство с OpenFlux: оно станет нодой. Ему не нужны ни белый адрес, ни открытые порты."
            KitDelivery.Here -> "Это устройство станет нодой: запустите её на экране «Это устройство». Профиль для подключения можно отдать другим."
        },
        style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
        when (delivery) {
            KitDelivery.Manual -> PrimaryButton("Показать команду", m::showKit, enabled = !busy)
            KitDelivery.Device -> PrimaryButton("Передать на устройство", { transfer = ProfileTransfer.Role.Node }, enabled = !busy, leading = Icons.Rounded.Share)
            KitDelivery.Here -> PrimaryButton(
                "Запустить ноду здесь",
                {
                    profile?.let { p -> container.settings.update { it.copy(selectedProfileId = p.id) } }
                    navigator.open(Dest.ThisDevice)
                },
                enabled = !busy, leading = Icons.Rounded.PlayArrow,
            )
        }
        if (connected) SecondaryButton("Отключить", onDisconnect, leading = Icons.Rounded.Stop)
        else SecondaryButton("Проверить подключение", onConnect, leading = Icons.Rounded.PlayArrow, enabled = !busy)
        if (delivery != KitDelivery.Manual) SecondaryButton("Профиль для подключения", { transfer = ProfileTransfer.Role.Client }, enabled = !busy)
    }
    m.kitShown?.let { KitCommandSheet(it, onDismiss = m::hideKit) }
    transfer?.let { role ->
        val line = m.transferLine(role)
        if (line == null) transfer = null else TransferSheet(line, role, onDismiss = { transfer = null })
    }
}

/** The command that installs a node, with what to know before running it. */
@Composable
internal fun KitCommandSheet(kit: NodeKit, onDismiss: () -> Unit) {
    val platform = LocalAppContainer.current.platform
    val toaster = LocalToaster.current
    Sheet(onDismiss, "Команда для ноды") {
        Column(Modifier.padding(horizontal = AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
            Text(
                "Нужна машина с Linux, systemd и правами root. Скрипт скачает закреплённый установщик, проверит его SHA-256, " +
                    "поставит ноду и запустит её; если нода уже есть, изменит только её транспорты.",
                style = AppTheme.type.body, color = AppTheme.colors.textSecondary,
            )
            Text(
                "Надёжнее файлом: сохраните как openflux-node.sh, выполните  sudo sh openflux-node.sh  и удалите файл.",
                style = AppTheme.type.body, color = AppTheme.colors.text,
            )
            Banner(
                "В команде секреты: ключ ноды и токены аккаунтов. Никому её не показывайте. Вставленная в терминал, она остаётся в истории командной строки.",
                Tone.Warning,
            )
            ValueRow("Размер", "${(kit.script.length + 512) / 1024} КБ")
            if (platform.shareSupported) {
                PrimaryButton("Поделиться файлом", { if (!platform.shareText(kit.script)) { platform.setClipboardText(kit.script); toaster.show("Скрипт скопирован") } }, Modifier.fillMaxWidth(), leading = Icons.Rounded.Share)
            }
            SecondaryButton("Копировать как файл", { platform.setClipboardText(kit.script); toaster.show("Скрипт скопирован") }, Modifier.fillMaxWidth())
            SecondaryButton("Копировать для терминала", { platform.setClipboardText(kit.paste); toaster.show("Команда скопирована") }, Modifier.fillMaxWidth())
        }
    }
}

/** A node's profile as a QR and a line, for a device of the person's own. */
@Composable
internal fun TransferSheet(line: String, role: ProfileTransfer.Role, onDismiss: () -> Unit) {
    val platform = LocalAppContainer.current.platform
    val toaster = LocalToaster.current
    Sheet(onDismiss, if (role == ProfileTransfer.Role.Node) "Профиль ноды для другого устройства" else "Профиль для подключения с другого устройства") {
        Column(Modifier.padding(horizontal = AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
            Text(
                if (role == ProfileTransfer.Role.Node) {
                    "На другом устройстве откройте OpenFlux → ＋ → «Ключ, ссылка или файл»: отсканируйте QR или вставьте строку. " +
                        "Затем откройте «Это устройство» и запустите ноду."
                } else {
                    "На другом устройстве откройте OpenFlux → ＋ → «Ключ, ссылка или файл»: отсканируйте QR или вставьте строку. " +
                        "Профиль появится в списке, через него можно подключаться."
                },
                style = AppTheme.type.body, color = AppTheme.colors.textSecondary,
            )
            if (line.length <= ProfileTransfer.QR_LIMIT) QrView(line, platform)
            else Text("Профиль слишком длинный для QR: передайте строкой.", style = AppTheme.type.bodySmall, color = AppTheme.colors.textMuted)
            Banner("В строке секреты: ключ ноды и токен аккаунта. Передавайте её только на свои устройства.", Tone.Warning)
            if (platform.shareSupported) {
                PrimaryButton("Поделиться строкой", { if (!platform.shareText(line)) { platform.setClipboardText(line); toaster.show("Строка скопирована") } }, Modifier.fillMaxWidth(), leading = Icons.Rounded.Share)
                SecondaryButton("Копировать строку", { platform.setClipboardText(line); toaster.show("Строка скопирована") }, Modifier.fillMaxWidth())
            } else {
                PrimaryButton("Копировать строку", { platform.setClipboardText(line); toaster.show("Строка скопирована") }, Modifier.fillMaxWidth())
            }
        }
    }
}
