package io.openflux.desktop.ui.nodes

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import io.openflux.desktop.ui.components.appClickable
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.model.editableNode
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.managed
import io.openflux.desktop.model.NodeTransports
import io.openflux.desktop.model.PhpHosts
import io.openflux.desktop.model.profile
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.desktop.ui.LocalScrollbars
import io.openflux.desktop.ui.LocalTouchUi
import io.openflux.desktop.ui.components.AppButton
import io.openflux.desktop.ui.components.AppCard
import io.openflux.desktop.ui.components.AppIconButton
import io.openflux.desktop.ui.components.AppIcons
import io.openflux.desktop.ui.components.AppMenu
import io.openflux.desktop.ui.components.ButtonRow
import io.openflux.desktop.ui.components.ButtonStyle
import io.openflux.desktop.ui.components.EmptyState
import io.openflux.desktop.ui.components.HorizontalRule
import io.openflux.desktop.ui.components.IconBubble
import io.openflux.desktop.ui.components.KeyValueRow
import io.openflux.desktop.ui.components.LocalToaster
import io.openflux.desktop.ui.components.MenuAction
import io.openflux.desktop.ui.components.PageHeader
import io.openflux.desktop.ui.components.SectionLabel
import io.openflux.desktop.ui.components.StatusBadge
import io.openflux.desktop.ui.components.TextAction
import io.openflux.desktop.ui.components.Tone
import io.openflux.desktop.ui.connections.ProfileRow
import io.openflux.desktop.ui.look
import io.openflux.desktop.ui.nav.AppActions
import io.openflux.desktop.ui.nav.DetailScreen
import io.openflux.desktop.ui.nav.Dest
import io.openflux.desktop.ui.nav.LocalActions
import io.openflux.desktop.ui.nav.LocalModels
import io.openflux.desktop.ui.node.PhpWizardPane
import io.openflux.desktop.ui.profiles.ProfilesScreenModel
import io.openflux.desktop.ui.theme.AppTheme

/**
 * "Ноды": what the person runs. Their servers (open one to see and change the nodes on it) and the nodes they run on
 * an ordinary PHP hosting. A server's screen is where nodes are made, changed, shared and connected to.
 */
@Composable
fun NodesList(selectedId: String?, split: Boolean, modifier: Modifier = Modifier) {
    val models = LocalModels.current
    val actions = LocalActions.current
    val profilesModel = models.profiles
    val container = LocalAppContainer.current
    val profiles by profilesModel.profiles.profiles.collectAsState()
    val settings by container.settings.settings.collectAsState()
    val state by profilesModel.connection.state.collectAsState()
    val touch = LocalTouchUi.current
    val servers = settings.knownServers
    val hosting = profiles.filter { it.phpNode != null && it.ownNode }
    var addMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scrollbars = LocalScrollbars.current

    if (servers.isEmpty() && hosting.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                title = "Своих серверов пока нет",
                message = "Введите доступ к своему серверу (VDS с Linux): OpenFlux покажет ноды на нём, поможет поставить новые, " +
                    "поменять транспорты, поделиться ссылкой и подключить ноду себе. Без сервера можно поднять ноду на обычном PHP-хостинге.",
                resource = AppIcons.Public,
            ) {
                ButtonRow(alignment = Alignment.CenterHorizontally) {
                    AppButton("Добавить сервер", actions::addServer, leading = Icons.Rounded.Dns)
                    AppButton("Без сервера", actions::createHosting, style = ButtonStyle.Secondary, leading = Icons.Rounded.Cloud)
                }
                Spacer(Modifier.height(AppTheme.spacing.s))
                TextAction("Есть ссылка на ноду: добавить подключение", actions::addConnection)
            }
        }
        return
    }
    Column(modifier.padding(top = AppTheme.spacing.xl)) {
        PageHeader("Ноды", "${servers.size} ${serverPlural(servers.size)}", Modifier.padding(horizontal = AppTheme.spacing.xl)) {
            Box {
                AppButton("Добавить", { addMenu = true }, leading = Icons.Rounded.Add)
                AppMenu(addMenu, { addMenu = false }, listOf(
                    MenuAction("Сервер (VDS)", actions::addServer, Icons.Rounded.Dns),
                    MenuAction("Без сервера: на PHP-хостинге", actions::createHosting, Icons.Rounded.Cloud),
                ))
            }
        }
        Spacer(Modifier.height(AppTheme.spacing.m))
        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.m),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (servers.isNotEmpty()) {
                    item(key = "servers") { GroupTitle("Серверы") }
                    items(servers, key = { serverId(it) }) { server ->
                        val id = serverId(server)
                        val mine = profiles.count { it.ownNode && it.serverHost == server.host }
                        ServerRow(
                            server = server,
                            subtitle = "${server.user}@${server.host}:${server.port}" + if (mine > 0) " · профилей на устройстве: $mine" else "",
                            selected = id == selectedId,
                            onClick = { actions.openServer(id, replace = split) },
                            onForget = { container.settings.update { st -> st.copy(knownServers = st.knownServers.filterNot { it == server }) } },
                        )
                    }
                }
                if (hosting.isNotEmpty()) {
                    item(key = "hosting") { GroupTitle("Без сервера: на PHP-хостинге") }
                    items(hosting, key = { it.id }) { node ->
                        val running = state.isActive && state.profile?.id == node.id
                        ProfileRow(
                            profile = node,
                            selected = node.id == selectedId,
                            runningLook = if (running) state.look() else null,
                            onClick = { actions.openNode(node.id, replace = split) },
                            onDoubleClick = { profilesModel.connect(node) },
                            menu = { nodeActions(profilesModel, actions, node, running) },
                            onToggle = { if (running) profilesModel.connection.disconnect() else profilesModel.connect(node) },
                            onEdit = { actions.openNode(node.id) },
                            touch = touch,
                        )
                    }
                }
                item { Spacer(Modifier.height(AppTheme.spacing.l)) }
            }
            scrollbars.Vertical(listState, Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text.uppercase(), style = AppTheme.typography.label, color = AppTheme.colors.textSecondary,
        modifier = Modifier.padding(start = AppTheme.spacing.m, top = AppTheme.spacing.m, bottom = AppTheme.spacing.xs),
    )
}

/** One of the person's servers in the list: the address and the way into its screen. */
@Composable
private fun ServerRow(server: io.openflux.desktop.model.KnownServer, subtitle: String, selected: Boolean, onClick: () -> Unit, onForget: () -> Unit) {
    val colors = AppTheme.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(AppTheme.dimens.listRow)
            .clip(AppTheme.shapes.card)
            .background(if (selected) colors.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .appClickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, onClick = onClick)
            .padding(horizontal = AppTheme.spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(Icons.Rounded.Dns)
        Spacer(Modifier.width(AppTheme.spacing.m))
        Column(Modifier.weight(1f)) {
            Text(server.host, style = AppTheme.typography.bodyStrong, color = colors.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(subtitle, style = AppTheme.typography.caption, color = colors.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Box {
            AppIconButton("Ещё", { menu = true }, icon = Icons.Rounded.MoreVert)
            AppMenu(menu, { menu = false }, listOf(MenuAction("Забыть сервер", onForget, Icons.Rounded.Delete, danger = true)))
        }
    }
}

private fun serverPlural(n: Int): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> "сервер"
        mod10 in 2..4 && mod100 !in 12..14 -> "сервера"
        else -> "серверов"
    }
}

private fun nodeActions(model: ProfilesScreenModel, actions: AppActions, node: Profile, running: Boolean): List<MenuAction> = buildList {
    add(
        if (running) MenuAction("Отключить", model.connection::disconnect, Icons.Rounded.Stop)
        else MenuAction("Подключить", { model.connect(node) }, Icons.Rounded.PlayArrow),
    )
    add(MenuAction("QR и ссылка", { model.shareFor = node }, Icons.Rounded.QrCode2, enabled = node.transport.shareable))
    add(MenuAction("Параметры профиля", { actions.editProfile(node) }, Icons.Rounded.Edit))
    add(MenuAction("Убрать из приложения", { model.deleteFor = node }, Icons.Rounded.Delete, danger = true))
}

// ---- one node ----

/** A node of the person's own: its server, how it is reached, who it can be given to, what to do with it. */
@Composable
fun NodeScreen(id: String) {
    val models = LocalModels.current
    val actions = LocalActions.current
    val toaster = LocalToaster.current
    val model = models.profiles
    val nodes = models.nodes
    val node = model.profiles.profiles.collectAsState().value.firstOrNull { it.id == id }
    val state by model.connection.state.collectAsState()
    if (node == null) {
        LaunchedEffect(Unit) { actions.back() }
        return
    }
    val running = state.isActive && state.profile?.id == node.id
    val host = node.serverHost
    val onHosting = node.phpNode != null
    DetailScreen(
        title = node.name,
        subtitle = if (onHosting) "Нода на PHP-хостинге" else host.ifEmpty { node.summary },
        leading = { IconBubble(if (onHosting) Icons.Rounded.Cloud else Icons.Rounded.Dns, size = 48.dp) },
        actions = { if (running) StatusBadge(state.look().title, state.look().tone) },
        maxWidth = 800.dp,
    ) {
        ButtonRow {
            if (running) AppButton("Отключить", model.connection::disconnect, style = ButtonStyle.Danger, leading = Icons.Rounded.Stop)
            else AppButton("Подключить", { model.connect(node) }, leading = Icons.Rounded.PlayArrow)
            if (node.serverHost.isNotEmpty() && !node.managed) {
                AppButton("Управлять сервером", { actions.openServerOf(node) }, style = ButtonStyle.Secondary, leading = Icons.Rounded.Dns)
            }
            if (node.transport.shareable) {
                AppButton("QR и ссылка", { model.shareFor = node }, style = ButtonStyle.Secondary, leading = Icons.Rounded.QrCode2)
            }
        }

        Spacer(Modifier.height(AppTheme.spacing.xl))
        SectionLabel(if (onHosting) "Хостинг" else "Сервер")
        Spacer(Modifier.height(AppTheme.spacing.s))
        AppCard(padding = 0.dp) {
            val direct = node.carriers.firstOrNull { it.type == TransportType.DIRECT }?.value.orEmpty()
            if (onHosting) {
                KeyValueRow("Сайт", node.phpNode?.siteUrl.orEmpty())
            } else {
                KeyValueRow("Адрес", direct.ifEmpty { "нет прямого адреса" }) {
                    if (direct.isNotEmpty()) AppIconButton("Копировать", { model.copy(direct); toaster.show("Скопировано") }, icon = Icons.Rounded.ContentCopy)
                }
            }
            HorizontalRule()
            KeyValueRow("Транспорты", node.carriers.joinToString(", ") { it.type.shortLabel })
            HorizontalRule()
            KeyValueRow("Режим", if (node.stream) "Без ключа" else if (node.session) "Session: несколько транспортов" else "Обычный")
            if (node.createdAt > 0) {
                HorizontalRule()
                KeyValueRow("Создана", io.openflux.desktop.ui.Format.date(node.createdAt / 1000, model.platform.utcOffsetSeconds))
            }
        }

        node.phpNode?.let { php ->
            HostingControls(node, php.siteUrl, php.token)
        }

        if (!onHosting && host.isNotEmpty()) {
            Spacer(Modifier.height(AppTheme.spacing.xl))
            SectionLabel("Сервер и пользователи")
            Spacer(Modifier.height(AppTheme.spacing.s))
            AppCard {
                Text(
                    "У каждого пользователя свой канал: свой документ и свой ключ. Все ноды этого сервера, их изменение, " +
                        "ссылки и новые пользователи: на экране сервера.",
                    style = AppTheme.typography.body, color = AppTheme.colors.textSecondary,
                )
                Spacer(Modifier.height(AppTheme.spacing.m))
                AppButton("Открыть сервер", { actions.openServerOf(node) }, style = ButtonStyle.Secondary, leading = Icons.Rounded.Dns)
            }
        }

        Spacer(Modifier.height(AppTheme.spacing.xl))
        ButtonRow {
            AppButton("Параметры профиля", { actions.editProfile(node) }, style = ButtonStyle.Ghost, leading = Icons.Rounded.Edit)
            AppButton("Убрать из приложения", { model.deleteFor = node }, style = ButtonStyle.Ghost, leading = Icons.Rounded.Delete)
        }
        Spacer(Modifier.height(AppTheme.spacing.xs))
        Text(
            "Убрать — значит забыть профиль на этом устройстве: сервер и канал на нём остаются как есть.",
            style = AppTheme.typography.caption, color = AppTheme.colors.textHint,
        )
    }
}

/** The node on a PHP hosting: what it says now, and the buttons for when something went wrong. */
@Composable
private fun HostingControls(profile: Profile, siteUrl: String, token: String) {
    val models = LocalModels.current
    val nodes = models.nodes
    val toaster = LocalToaster.current
    val model = models.profiles
    Spacer(Modifier.height(AppTheme.spacing.xl))
    SectionLabel("Нода на хостинге")
    Spacer(Modifier.height(AppTheme.spacing.s))
    AppCard(padding = 0.dp) {
        if (token.isNotEmpty()) {
            KeyValueRow("Ключ доступа", PhpHosts.maskToken(token)) {
                AppIconButton("Копировать ключ", { model.copy(token); toaster.show("Ключ доступа скопирован") }, icon = Icons.Rounded.ContentCopy)
            }
        } else {
            KeyValueRow("Сайт", siteUrl)
        }
    }
    // What the node says now (running, which generation serves): asked once when the screen opens.
    LaunchedEffect(profile.id) { nodes.forgetNodeMessage(); nodes.refreshNode(profile) }
    Spacer(Modifier.height(AppTheme.spacing.m))
    ButtonRow {
        AppButton(if (nodes.nodeBusy) "Подождите…" else "Запустить ноду", { nodes.startNode(profile) },
            style = ButtonStyle.Secondary, leading = Icons.Rounded.PlayArrow, enabled = !nodes.nodeBusy)
        AppButton("Остановить ноду", { nodes.stopNode(profile) }, style = ButtonStyle.Ghost, leading = Icons.Rounded.Stop, enabled = !nodes.nodeBusy)
    }
    Spacer(Modifier.height(AppTheme.spacing.s))
    ButtonRow {
        AppButton("Панель ноды", { nodes.openNodePanel(profile) }, style = ButtonStyle.Secondary, leading = Icons.Rounded.OpenInNew, enabled = !nodes.nodeBusy)
        AppButton("Обновить состояние", { nodes.refreshNode(profile) }, style = ButtonStyle.Ghost, leading = Icons.Rounded.Refresh, enabled = !nodes.nodeBusy)
    }
    nodes.nodeMessage?.let {
        Spacer(Modifier.height(AppTheme.spacing.s))
        Text(it, style = AppTheme.typography.bodySmall, color = AppTheme.colors.textSecondary)
    }
    Spacer(Modifier.height(AppTheme.spacing.s))
    Text(
        "Нода запускается сама, когда вы подключаетесь и хостинг доступен напрямую. Кнопки нужны, если что-то пошло не так.",
        style = AppTheme.typography.caption, color = AppTheme.colors.textHint,
    )
}

// ---- a node without a server ----

/** The wizard that makes a node on a PHP hosting. Done, it becomes the node's screen. */
@Composable
fun CreateHostingScreen() {
    val models = LocalModels.current
    val actions = LocalActions.current
    val nav = io.openflux.desktop.ui.nav.LocalNav.current
    PhpWizardPane(models.nodes.hostingWizard(nav.top), onClose = actions::back, onSaved = actions::nodeMade)
}
