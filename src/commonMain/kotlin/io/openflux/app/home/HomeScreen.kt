package io.openflux.app.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.openflux.app.components.Banner
import io.openflux.app.components.Chip
import io.openflux.app.components.Collapsible
import io.openflux.app.components.ConfirmDialog
import io.openflux.app.components.ConnectButton
import io.openflux.app.components.ConnectLook
import io.openflux.app.components.Field
import io.openflux.app.components.IconButton2
import io.openflux.app.components.LocalToaster2
import io.openflux.app.components.RowItem
import io.openflux.app.components.ScreenScaffold
import io.openflux.app.components.SecondaryButton
import io.openflux.app.components.Section
import io.openflux.app.components.Sheet
import io.openflux.app.components.Status
import io.openflux.app.components.StatusDot
import io.openflux.app.components.TextButton2
import io.openflux.app.components.Tone
import io.openflux.app.components.ValueRow
import io.openflux.app.nav.Dest
import io.openflux.app.profiles.ProfileActions
import io.openflux.app.servers.isOwnNode
import io.openflux.app.servers.serverIdOf
import io.openflux.app.servers.title
import io.openflux.app.shell.LocalNavigator
import io.openflux.app.shell.LocalOpenAdd
import io.openflux.app.text.Fmt
import io.openflux.app.theme.AppTheme2
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.ExitAddress
import io.openflux.desktop.model.KnownServer
import io.openflux.desktop.model.NetworkKind
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.model.editableNode
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.managed
import io.openflux.desktop.model.profile
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.desktop.service.PlatformKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A profile's transports in a few words, in the new interface's names. */
fun profileLine(p: Profile): String {
    val names = p.carriers.map { it.type.title }.distinct().joinToString(", ")
    return if (p.stream) "Без сервера · $names" else names
}

/** The person's server a node profile dials, if it is in their list. */
fun serverOf(p: Profile, known: List<KnownServer>): String? {
    val dial = p.carriers.firstOrNull { it.type == TransportType.DIRECT }?.value?.trim() ?: return null
    val host = dial.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
    return known.firstOrNull { it.host.equals(host, ignoreCase = true) }?.let(::serverIdOf)
}

private fun ConnectionState.look(): ConnectLook = when (this) {
    is ConnectionState.Connected -> ConnectLook.On
    is ConnectionState.Failed -> ConnectLook.Error
    ConnectionState.Idle -> ConnectLook.Off
    else -> ConnectLook.Busy
}

private fun ConnectionState.title(): String = when (this) {
    ConnectionState.Idle -> "Отключено"
    is ConnectionState.Connecting -> if (mode == ConnectionMode.Exit) "Запуск ноды…" else "Подключаюсь…"
    is ConnectionState.Connected -> if (mode == ConnectionMode.Exit) "Нода работает" else "Подключено"
    is ConnectionState.Reconnecting -> "Связь прервалась, восстанавливаю…"
    is ConnectionState.Disconnecting -> "Отключаюсь…"
    is ConnectionState.Failed -> "Не подключено"
}

/** Home (spec 6): the button, what it connects, a few switches, one banner, and the details on request. */
@Composable
fun HomeRoot() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val m = remember(container) { HomeModel(container) }
    val profiles by container.profiles.profiles.collectAsState()
    val settings by container.settings.settings.collectAsState()
    val state by container.connection.state.collectAsState()
    val subscription by container.subscription.state.collectAsState()
    var drawer by rememberSaveable { mutableStateOf(false) }
    val chosen = m.selected(profiles)
    ScreenScaffold(null, scroll = false) {
        if (profiles.isEmpty()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = AppTheme2.spacing.s), verticalArrangement = Arrangement.spacedBy(AppTheme2.spacing.l)) {
                StartPanel(hasSubscription = subscription != null)
            }
            return@ScreenScaffold
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = AppTheme2.spacing.s),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppTheme2.spacing.l),
                ) {
                    Main(m, chosen, state, onPick = { drawer = true }, pickable = !wide)
                }
                if (wide) {
                    Box(Modifier.width(1.dp).fillMaxHeight())
                    Column(
                        Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = AppTheme2.spacing.l, top = AppTheme2.spacing.s),
                        verticalArrangement = Arrangement.spacedBy(AppTheme2.spacing.m),
                    ) {
                        ConnectionList(m, profiles, chosen, onChosen = {})
                    }
                }
            }
        }
    }
    if (drawer) {
        Sheet(onDismiss = { drawer = false }, title = "Подключение") {
            Column(Modifier.padding(horizontal = AppTheme2.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme2.spacing.m)) {
                ConnectionList(m, profiles, chosen, onChosen = { drawer = false })
            }
        }
    }
}

/** No connections yet (spec 6): the three ways to start. */
@Composable
private fun StartPanel(hasSubscription: Boolean) {
    val navigator = LocalNavigator.current
    if (hasSubscription) {
        Banner("В подписке пока нет локаций", Tone.Info, action = "Открыть подписку", onAction = { navigator.open(Dest.Account) })
        return
    }
    Text("Приступим", style = AppTheme2.type.screenTitle, color = AppTheme2.colors.text)
    Text("Все способы — под кнопкой ＋", style = AppTheme2.type.body, color = AppTheme2.colors.textSecondary)
    Section(null) {
        RowItem("Аккаунт OpenFlux", subtitle = "Войти или создать: локации появятся сами", onClick = { navigator.open(Dest.Auth) })
        RowItem("Ключ или ссылка", subtitle = "От друга или из сервиса", onClick = { navigator.open(Dest.AddKey) })
        RowItem("Свой сервер", subtitle = "VDS по SSH: нода поставится сама", onClick = { navigator.open(Dest.AddServer) })
    }
}

@Composable
private fun Main(m: HomeModel, chosen: Profile?, state: ConnectionState, onPick: () -> Unit, pickable: Boolean) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val toaster = LocalToaster2.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    val traffic by container.connection.traffic.collectAsState()
    val status by container.accounts.status.collectAsState()
    val subscription by container.subscription.state.collectAsState()
    val platform = container.platform
    val exitMode = settings.mode == ConnectionMode.Exit && state.isActive
    val now by produceState(platform.now()) { while (true) { delay(1_000); value = platform.now() } }

    ConnectButton(
        state.look(),
        label = if (state.isActive) "Отключить" else "Подключить",
        onClick = m::toggle,
        shortcut = "Ctrl+Enter",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(state.title(), style = AppTheme2.type.title, color = AppTheme2.colors.text, textAlign = TextAlign.Center)
        val connected = state as? ConnectionState.Connected
        if (connected != null) {
            Text(
                Fmt.duration(now - connected.since) + "   ↓ " + Fmt.speed(traffic.downBytesPerSec) + "   ↑ " + Fmt.speed(traffic.upBytesPerSec),
                style = AppTheme2.type.mono, color = AppTheme2.colors.textSecondary,
            )
        }
    }
    if (chosen != null) {
        Section(null) {
            RowItem(
                chosen.name,
                subtitle = profileLine(chosen),
                leading = { StatusDot(if (state.isActive && state.profile?.id == chosen.id) Status.On else Status.Off) },
                trailing = if (pickable) ({ Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = AppTheme2.colors.textSecondary) }) else null,
                onClick = if (pickable) onPick else null,
            )
        }
    }
    if (!exitMode && (platform.fullTunnelSupported || (platform.systemProxySupported && !settings.fullTunnel))) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme2.spacing.s, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
            if (platform.fullTunnelSupported) {
                Chip(if (platform.kind == PlatformKind.Android) "VPN: весь трафик" else "Весь трафик", settings.fullTunnel, { m.setFullTunnel(!settings.fullTunnel) })
            }
            if (platform.systemProxySupported && !settings.fullTunnel) {
                Chip("Системный прокси", settings.systemProxy, { m.setSystemProxy(!settings.systemProxy) })
            }
        }
    }
    when (val b = m.banner(state, settings, chosen, status, subscription, now)) {
        is HomeBanner.Failed -> Banner(b.message, Tone.Danger, action = "Повторить", onAction = m::toggle, secondAction = "Журнал", onSecondAction = { navigator.open(Dest.Logs) })
        HomeBanner.NeedsAdmin -> Banner(
            "Для «Весь трафик» нужны права администратора", Tone.Warning, action = "Перезапустить",
            onAction = { if (!platform.restartElevated()) toaster.show("Не удалось перезапустить: разрешите запуск в окне Windows") },
        )
        is HomeBanner.Account -> Banner(
            if (b.needsCheck) "${b.kind.label} просит пройти проверку" else "Вход в ${b.kind.label} истёк", Tone.Warning,
            action = "Войти заново",
            onAction = { scope.launch { runCatching { container.accounts.signIn(b.kind) }.onFailure { toaster.show(it.message ?: "Вход не выполнен") } } },
        )
        is HomeBanner.Subscription -> Banner(b.look.line, Tone.Warning, action = "Продлить", onAction = { navigator.open(Dest.Account) })
        HomeBanner.ThisDevice -> Banner("Это устройство работает как нода", Tone.Info, action = "Открыть", onAction = { navigator.open(Dest.ThisDevice) })
        null -> Unit
    }
    if (chosen != null && !exitMode) Details(m, chosen, state)
}

/** The connection's facts, folded (spec 6, item 5). */
@Composable
private fun Details(m: HomeModel, p: Profile, state: ConnectionState) {
    val container = LocalAppContainer.current
    val toaster = LocalToaster2.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    val traffic by container.connection.traffic.collectAsState()
    val socks by container.connection.socksAddress.collectAsState()
    val address by container.connection.exitAddress.collectAsState()
    val android = container.platform.kind == PlatformKind.Android
    fun copy(what: String, text: String) { container.platform.setClipboardText(text); toaster.show("$what скопирован") }
    Section(null) {
        Collapsible("Сведения") {
            val on = state is ConnectionState.Connected && state.profile?.id == p.id
            if (on && traffic.activeCarriers.isNotEmpty()) {
                ValueRow("Сейчас через", traffic.activeCarriers.mapNotNull { TransportType.fromCli(it)?.title }.distinct().joinToString(", "))
            }
            ValueRow("Шифрование", if (p.secret.isNotEmpty()) "AES-256-GCM" else "нет ключа")
            if (p.bonding && p.session) {
                ValueRow("Бондинг", p.carriers.joinToString(" + ") { c -> c.type.title + if (c.network != NetworkKind.Default) " · " + c.network.label else "" })
            }
            if (!settings.fullTunnel) {
                val socksAddr = socks ?: "127.0.0.1:${settings.socksPort}"
                ValueRow("SOCKS5", socksAddr, mono = true, onCopy = { copy("Адрес", socksAddr) })
                if (!android) {
                    val http = "127.0.0.1:${settings.socksPort + 1}"
                    ValueRow("HTTP-прокси", http, mono = true, onCopy = { copy("Адрес", http) })
                }
            }
            val ip = when (val a = address) {
                ExitAddress.Unknown -> "—"
                ExitAddress.Checking -> "проверяю…"
                is ExitAddress.Known -> a.ip
                is ExitAddress.Unavailable -> "не удалось: ${a.reason}"
                is ExitAddress.NotCheckable -> a.reason
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ValueRow("Внешний IP", ip, Modifier.weight(1f))
                IconButton2(Icons.Rounded.Refresh, "Проверить ещё раз", container.connection::refreshExitAddress, enabled = on)
            }
            if (on) ValueRow("За сессию", Fmt.bytes(traffic.totalDown + traffic.totalUp))
            if (on && !traffic.live) ValueRow("Статистика", "ядро не сообщает")
            for (kind in m.pushable(p, state)) {
                RowItem("Передать вход ${kind.label} ноде", onClick = {
                    scope.launch {
                        runCatching { container.connection.pushAccountToExit(kind) }
                            .onSuccess { toaster.show("Вход ${kind.label} передан ноде") }
                            .onFailure { toaster.show(it.message ?: "Не удалось передать вход") }
                    }
                })
            }
        }
    }
}

/** Everything one can connect to, grouped (spec 6.1); the drawer's and the wide window's list. */
@Composable
fun ConnectionList(m: HomeModel, profiles: List<Profile>, chosen: Profile?, onChosen: () -> Unit) {
    val container = LocalAppContainer.current
    val openAdd = LocalOpenAdd.current
    val navigator = LocalNavigator.current
    val state by container.connection.state.collectAsState()
    val subscription by container.subscription.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val touch = container.platform.kind == PlatformKind.Android
    val shown = if (query.isBlank()) profiles else profiles.filter { p ->
        p.name.contains(query.trim(), ignoreCase = true) || p.carriers.any { it.value.contains(query.trim(), ignoreCase = true) }
    }
    if (profiles.size > 4) Field(query, { query = it }, "Поиск по названию или адресу")
    if (shown.isEmpty()) Text("Ничего не найдено", style = AppTheme2.type.body, color = AppTheme2.colors.textSecondary)
    for ((title, list) in m.groups(shown)) {
        Section(title) {
            if (title == "Подписка") {
                subscription?.let { sub ->
                    val look = sub.look2(container.platform.now(), container.platform.utcOffsetSeconds)
                    RowItem(
                        sub.title.ifBlank { "Подписка" }, subtitle = look.line,
                        trailing = { TextButton2(if (look.attention) "Продлить" else "Открыть", { navigator.open(Dest.Account) }) },
                    )
                }
            }
            for (p in list) {
                val running = state.isActive && state.profile?.id == p.id
                ProfileRow(p, selected = p.id == chosen?.id, running = running, touch = touch, onChoose = { m.choose(p); onChosen() }, onChosen = onChosen)
            }
        }
    }
    SecondaryButton("Добавить", openAdd, Modifier.fillMaxWidth(), leading = Icons.Rounded.Add)
}

@Composable
private fun ProfileRow(p: Profile, selected: Boolean, running: Boolean, touch: Boolean, onChoose: () -> Unit, onChosen: () -> Unit) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val toaster = LocalToaster2.current
    val actions = remember(container) { ProfileActions(container) }
    val settings by container.settings.settings.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var deleteAsk by remember { mutableStateOf(false) }
    RowItem(
        p.name,
        subtitle = profileLine(p),
        leading = { StatusDot(if (running) Status.On else Status.Off) },
        selected = selected,
        onClick = onChoose,
        onDoubleClick = if (touch) null else ({ actions.connect(p); onChosen() }),
        onLongClick = if (touch) ({ menu = true }) else null,
        trailing = {
            Box {
                IconButton2(Icons.Rounded.MoreVert, "Действия", { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    fun go(d: Dest) { menu = false; onChosen(); navigator.open(d) }
                    DropdownMenuItem({ Text("Сведения") }, { go(Dest.Profile(p.id)) })
                    if (!p.managed) DropdownMenuItem({ Text("Изменить") }, { go(Dest.ProfileEdit(p.id)) })
                    if (actions.canShare(p)) DropdownMenuItem({ Text("Поделиться") }, { go(Dest.Give(p.id)) })
                    if (p.phpNode != null) DropdownMenuItem({ Text("Открыть ноду") }, { go(Dest.Hosting(p.id)) })
                    else if (p.isOwnNode) serverOf(p, settings.knownServers)?.let { id -> DropdownMenuItem({ Text("Открыть сервер") }, { go(Dest.Server(id)) }) }
                    if (!p.isOwnNode && p.editableNode) DropdownMenuItem({ Text("Это моя нода") }, {
                        menu = false; actions.markOwn(p); toaster.show("«${p.name}» теперь в «Мои серверы»")
                    })
                    if (!p.managed) DropdownMenuItem({ Text("Дублировать") }, { menu = false; actions.duplicate(p)?.let { toaster.show("Копия: ${it.name}") } })
                    if (!p.managed) DropdownMenuItem({ Text("Удалить", color = AppTheme2.colors.danger) }, { menu = false; deleteAsk = true })
                }
            }
        },
    )
    if (deleteAsk) {
        ConfirmDialog(
            title = "Удалить «${p.name}»?",
            text = if (p.isOwnNode) "Профиль пропадёт с этого устройства. Сама нода продолжит работать." else "Профиль пропадёт с этого устройства.",
            confirm = "Удалить",
            onConfirm = { deleteAsk = false; actions.delete(p) },
            onDismiss = { deleteAsk = false },
            danger = true,
        )
    }
}
