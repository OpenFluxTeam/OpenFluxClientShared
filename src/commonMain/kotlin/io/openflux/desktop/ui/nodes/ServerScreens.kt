package io.openflux.desktop.ui.nodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.openflux.desktop.model.InstalledChannel
import io.openflux.desktop.model.NodeMode
import io.openflux.desktop.model.NodeTransports
import io.openflux.desktop.ui.components.AppButton
import io.openflux.desktop.ui.components.AppCard
import io.openflux.desktop.ui.components.AppDialog
import io.openflux.desktop.ui.components.Banner
import io.openflux.desktop.ui.components.ButtonRow
import io.openflux.desktop.ui.components.ButtonStyle
import io.openflux.desktop.ui.components.HorizontalRule
import io.openflux.desktop.ui.components.KeyValueRow
import io.openflux.desktop.ui.components.LocalToaster
import io.openflux.desktop.ui.components.SectionLabel
import io.openflux.desktop.ui.components.StatusBadge
import io.openflux.desktop.ui.components.Tone
import io.openflux.desktop.ui.nav.DetailScreen
import io.openflux.desktop.ui.nav.Dest
import io.openflux.desktop.ui.nav.LocalActions
import io.openflux.desktop.ui.nav.LocalModels
import io.openflux.desktop.ui.node.HostKeyDialog
import io.openflux.desktop.ui.node.NodeEditorResult
import io.openflux.desktop.ui.node.NodeWizardModel
import io.openflux.desktop.ui.node.NodeWizardPane
import io.openflux.desktop.ui.node.ServerStep
import io.openflux.desktop.ui.node.TransportsEditor
import io.openflux.desktop.ui.node.VerifyStep
import io.openflux.desktop.ui.node.WizardStep
import io.openflux.desktop.ui.theme.AppTheme
import kotlinx.coroutines.launch

/** What the session is doing or has to say: the banners under a server screen's title. */
@Composable
private fun SessionStatus(m: NodeWizardModel) {
    val busy = m.busy
    if (busy != null && m.documentProgress == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = AppTheme.colors.accent)
        Spacer(Modifier.height(AppTheme.spacing.s))
        Banner(busy, Tone.Accent, icon = Icons.Rounded.Info)
        Spacer(Modifier.height(AppTheme.spacing.m))
    }
    m.error?.let {
        Banner(it, Tone.Danger, icon = Icons.Rounded.ErrorOutline)
        Spacer(Modifier.height(AppTheme.spacing.m))
    }
    m.notice?.let {
        Banner(it, Tone.Success, icon = Icons.Rounded.CheckCircle)
        Spacer(Modifier.height(AppTheme.spacing.m))
    }
}

/**
 * One of the person's servers: the way in (address, login, password or key), then everything on it. The nodes
 * (channels) of the server are listed, each can be changed, shared, connected to or deleted, and a new one added.
 */
@Composable
fun ServerScreen(dest: Dest.Server) {
    val models = LocalModels.current
    val actions = LocalActions.current
    val m = models.nodes.session(dest.id)
    val connected = m.step == WizardStep.Manage && m.probe != null
    DetailScreen(
        title = if (m.host.isBlank()) "Новый сервер" else "${m.user.trim()}@${m.host.trim()}",
        subtitle = if (connected) "Ноды на этом сервере" else "Доступ к серверу нужен только на время работы с ним: OpenFlux его не хранит.",
        maxWidth = 800.dp,
    ) {
        SessionStatus(m)
        if (!connected) ServerStep(m, button = "Подключиться к серверу") else ServerNodes(dest, m)
    }
    HostKeyDialog(m)
}

@Composable
private fun ServerNodes(dest: Dest.Server, m: NodeWizardModel) {
    val models = LocalModels.current
    val actions = LocalActions.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var deleteFor by remember { mutableStateOf<InstalledChannel?>(null) }
    val idle = m.busy == null
    val probe = m.probe

    AppCard(padding = 0.dp) {
        KeyValueRow("Система", listOfNotNull(probe?.os, probe?.arch).filter { it.isNotBlank() }.joinToString(", ").ifEmpty { "неизвестно" })
        HorizontalRule()
        KeyValueRow("Ядро OpenFlux", probe?.core?.ifBlank { null } ?: "не установлено")
        HorizontalRule()
        KeyValueRow("Автообновление ядра", if (probe?.autoupdate == true) "включено" else "выключено")
        HorizontalRule()
        KeyValueRow("Нод на сервере", m.channels.size.toString())
    }
    Spacer(Modifier.height(AppTheme.spacing.s))
    ButtonRow {
        AppButton("Добавить ноду", { actions.createChannel(dest.id) }, leading = Icons.Rounded.Add, enabled = idle)
        AppButton("Обновить список", m::refreshChannels, style = ButtonStyle.Ghost, leading = Icons.Rounded.Refresh, enabled = idle)
        AppButton("Закрыть доступ", m::disconnect, style = ButtonStyle.Ghost, leading = Icons.Rounded.LinkOff, enabled = idle)
    }

    Spacer(Modifier.height(AppTheme.spacing.xl))
    SectionLabel("Ноды")
    Spacer(Modifier.height(AppTheme.spacing.s))
    if (m.channels.isEmpty()) {
        AppCard {
            Text(
                "На сервере пока нет нод OpenFlux. Добавьте первую: мастер поставит ядро, настроит транспорты и проверит канал.",
                style = AppTheme.typography.body, color = AppTheme.colors.textSecondary,
            )
        }
    }
    m.channels.forEach { info ->
        val local = m.profileOf(info)
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(info.id, style = AppTheme.typography.sectionTitle, color = AppTheme.colors.text)
                    Text(
                        "порт ${info.port} · ${info.nodeMode.label}", style = AppTheme.typography.bodySmall, color = AppTheme.colors.textSecondary,
                    )
                }
                StatusBadge(if (info.running) "работает" else info.state.ifBlank { "неизвестно" }, if (info.running) Tone.Success else Tone.Warning)
            }
            Spacer(Modifier.height(AppTheme.spacing.s))
            Text(
                NodeTransports.describe(info.transportTypes),
                style = AppTheme.typography.body, color = AppTheme.colors.text,
            )
            Text(
                if (local != null) "Профиль на этом устройстве: ${local.name}" else "Профиля на этом устройстве нет: его можно создать кнопкой «Подключить себе».",
                style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary,
            )
            Spacer(Modifier.height(AppTheme.spacing.m))
            ButtonRow {
                AppButton("Изменить", { m.openChannel(info); actions.editChannel(dest.id, info.id) }, style = ButtonStyle.Secondary, leading = Icons.Rounded.Edit, enabled = idle)
                AppButton("Подключить себе", {
                    scope.launch {
                        runCatching { m.adopt(info) }
                            .onSuccess { profile ->
                                models.profiles.connect(profile)
                                toaster.show("Подключаюсь через «${profile.name}»", Tone.Success)
                            }
                            .onFailure { toaster.show(it.message ?: "Не удалось подключиться", Tone.Danger) }
                    }
                }, style = ButtonStyle.Secondary, leading = Icons.Rounded.PlayArrow, enabled = idle)
                AppButton("Поделиться", {
                    scope.launch {
                        runCatching { m.adopt(info) }
                            .onSuccess { profile -> models.profiles.shareFor = profile }
                            .onFailure { toaster.show(it.message ?: "Не удалось собрать ссылку", Tone.Danger) }
                    }
                }, style = ButtonStyle.Secondary, leading = Icons.Rounded.Share, enabled = idle)
                AppButton("Удалить", { deleteFor = info }, style = ButtonStyle.Ghost, leading = Icons.Rounded.Delete, enabled = idle)
            }
        }
        Spacer(Modifier.height(AppTheme.spacing.m))
    }
    Text(
        "У каждого пользователя свой канал: свой документ и свой ключ. «Поделиться» отдаёт QR и ссылку канала, «Подключить себе» добавляет его в «Подключения».",
        style = AppTheme.typography.caption, color = AppTheme.colors.textHint,
    )

    deleteFor?.let { info ->
        AppDialog(
            title = "Удалить ноду ${info.id}?",
            onDismiss = { deleteFor = null },
            primary = "Удалить с сервера",
            primaryStyle = ButtonStyle.Danger,
            onPrimary = { deleteFor = null; m.deleteChannel(info.id) },
        ) {
            Text(
                "Канал остановится и пропадёт с сервера вместе с ключом и настройками. Ссылки, которые вы раздавали, перестанут работать. " +
                    "Профили на устройствах останутся в списке, но подключаться через эту ноду не смогут.",
                style = AppTheme.typography.body, color = AppTheme.colors.text,
            )
        }
    }
}

/** Changing an installed node: its transports and mode, applied to the server and checked. */
@Composable
fun ChannelEditScreen(dest: Dest.ChannelEdit) {
    val models = LocalModels.current
    val actions = LocalActions.current
    val m = models.nodes.session(dest.server)
    // The node this screen is about is open in the session; a rebuilt screen opens it again.
    val info = m.channels.firstOrNull { it.id == dest.channel }
    LaunchedEffect(dest, m.editing?.id) {
        if (m.editing?.id != dest.channel && info != null && m.busy == null) m.openChannel(info)
    }
    val editing = m.editing
    val done = m.step == WizardStep.Done
    val verifying = m.step == WizardStep.Verify
    val idle = m.busy == null
    DetailScreen(
        title = "Нода ${dest.channel}",
        subtitle = "${m.user.trim()}@${m.host.trim()}, порт ${editing?.port ?: info?.port ?: ""}",
        actions = {
            if (editing != null && !done && !verifying && (!m.needsYandexDocument || m.documentUrl.isNotEmpty())) {
                AppButton(if (idle) "Применить на сервере" else "Применяю…", m::next, enabled = idle)
            }
        },
        maxWidth = 720.dp,
    ) {
        SessionStatus(m)
        if (editing == null) {
            Text("Читаю ноду…", style = AppTheme.typography.body, color = AppTheme.colors.textSecondary)
            return@DetailScreen
        }
        if (done) {
            NodeEditorResult(m, onShowQr = {}, onSaved = { actions.back() }, hideProfile = true)
            Spacer(Modifier.height(AppTheme.spacing.l))
            AppButton("Готово", actions::back)
            return@DetailScreen
        }
        Banner(
            "Ключ, порт и контекст шифрования остаются прежними: выданные ссылки продолжат работать. " +
                if (m.editingHasProfile) "Профиль на этом устройстве обновится вместе с нодой." else "Профиля этой ноды на устройстве нет: изменения применятся на сервере.",
            Tone.Accent, icon = Icons.Rounded.Info,
        )
        Spacer(Modifier.height(AppTheme.spacing.l))
        TransportsEditor(m)
        if (verifying) {
            Spacer(Modifier.height(AppTheme.spacing.l))
            VerifyStep(m)
        }
    }
    HostKeyDialog(m)
}

/** Making a new node on a connected server: documents, plan, install, check. Done, the server's list is read again. */
@Composable
fun CreateChannelScreen(dest: Dest.CreateChannel) {
    val models = LocalModels.current
    val actions = LocalActions.current
    val m = models.nodes.session(dest.server)
    // A new node is started once; leaving the screen (any way) takes the session back to the server's list.
    LaunchedEffect(dest) { if (m.step == WizardStep.Manage && m.channel == null) m.startCreate() }
    if (m.step == WizardStep.Manage || m.step == WizardStep.Server) {
        DetailScreen(title = "Новая нода", maxWidth = 720.dp) { SessionStatus(m) }
        return
    }
    NodeWizardPane(m, onClose = actions::back, onSaved = { actions.back() })
}
