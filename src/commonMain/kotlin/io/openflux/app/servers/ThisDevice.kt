package io.openflux.app.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import io.openflux.app.components.Banner
import io.openflux.app.components.Chip
import io.openflux.app.components.Collapsible
import io.openflux.app.components.ConnectLook
import io.openflux.app.components.Field
import io.openflux.app.components.IconBubble
import io.openflux.app.components.QrView
import io.openflux.app.components.RowItem
import io.openflux.app.components.ScreenScaffold
import io.openflux.app.components.ShareLinkActions
import io.openflux.app.components.Section
import io.openflux.app.components.Sheet
import io.openflux.app.components.Status
import io.openflux.app.components.Tone
import io.openflux.app.components.ValueRow
import io.openflux.app.home.SwitchBlock
import io.openflux.app.home.profileDot
import io.openflux.app.home.statusLine
import io.openflux.app.nav.Dest
import io.openflux.app.shell.LocalNavigator
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.ExitBackend
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.describe
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.managed
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.desktop.service.PlatformKind

/**
 * This device as an exit node for others (spec 5.5): which profile's transports it serves, how (L4/L3 on the
 * desktop), its direct port and the address clients dial; start, stop, and the link once it runs. Stopping makes
 * it a client again, so Home connects as usual.
 */
@Stable
class ThisDeviceModel(private val container: AppContainer) {
    /** Profiles this device can serve: not a subscription's location, not a node on a hosting. */
    val choices: List<Profile> get() = container.profiles.profiles.value.filter { !it.managed && !it.stream }

    val running: Boolean
        get() {
            val s = container.connection.state.value
            return s.isActive && container.settings.settings.value.mode == ConnectionMode.Exit
        }

    fun start(profile: Profile) {
        container.settings.update { it.copy(mode = ConnectionMode.Exit, selectedProfileId = profile.id) }
        container.connection.connect(profile, ConnectionMode.Exit)
    }

    fun stop() {
        container.connection.disconnect()
        container.settings.update { it.copy(mode = ConnectionMode.Client) }
    }
}

@Composable
fun ThisDeviceScreen() {
    val navigator = LocalNavigator.current
    ScreenScaffold("Это устройство", onBack = { navigator.back() }) { ExitNodeBody() }
}

/**
 * The node's controls, laid out as the client's Home: the same switch (the universe or the plain ring) starts and
 * stops it, the state under it, then what it serves, as the client's connection row with its own picker; once it
 * runs, its QR and link. «Это устройство» and Home's «Выходная нода»; [underButton] goes under the switch (Home's
 * Клиент / Выходная нода switch).
 */
@Composable
fun ExitNodeBody(underButton: (@Composable () -> Unit)? = null) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val m = remember(container) { ThisDeviceModel(container) }
    val settings by container.settings.settings.collectAsState()
    val state by container.connection.state.collectAsState()
    val profiles by container.profiles.profiles.collectAsState()
    val link by container.connection.exitShareLink.collectAsState()
    val choices = remember(profiles) { m.choices }
    val exit = settings.mode == ConnectionMode.Exit && state.isActive
    val chosen = choices.firstOrNull { it.id == settings.selectedProfileId } ?: choices.firstOrNull()
    val platform = container.platform
    var picker by rememberSaveable { mutableStateOf(false) }
    if (choices.isEmpty() && !exit) {
        Text(
            "Устройство может работать нодой: другие подключаются через него к интернету.",
            style = AppTheme.type.body, color = AppTheme.colors.textSecondary,
        )
        Banner("Нужен профиль со своими транспортами и ключом: добавьте его через ＋", Tone.Info)
        underButton?.invoke()
        return
    }
    val failed = (state as? ConnectionState.Failed)?.takeIf { settings.mode == ConnectionMode.Exit }
    val look = when {
        exit -> if (state is ConnectionState.Connected) ConnectLook.On else ConnectLook.Busy
        failed != null -> ConnectLook.Error
        else -> ConnectLook.Off
    }
    val title = when {
        exit -> statusLine(state)
        failed != null -> "Нода не запущена"
        else -> "Нода выключена"
    }
    SwitchBlock(
        look,
        if (exit) "Остановить ноду" else "Запустить ноду",
        onClick = { if (exit) m.stop() else chosen?.let(m::start) },
        title = title,
        connected = (state as? ConnectionState.Connected)?.takeIf { exit },
        state = state,
        // Ctrl+Enter stops a running node and never starts one.
        shortcut = if (exit) "Ctrl+Enter" else null,
        speeds = false,
        under = underButton,
    )
    if (!exit) Text(
        "Другие подключаются через это устройство к интернету.",
        style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.isActive && !exit) {
        Text("Сейчас устройство подключено как клиент: запуск ноды остановит это подключение.", style = AppTheme.type.bodySmall, color = AppTheme.colors.warning)
    }
    if (chosen != null) {
        // Like the client's connection row: it always opens the picker while the node is stopped.
        val pick = !exit
        Section("Что раздавать") {
            RowItem(
                chosen.name,
                subtitle = chosen.transport.title,
                leading = { IconBubble(Icons.Rounded.Dns, status = if (exit) profileDot(state, chosen.id) else Status.Off) },
                trailing = if (pick) ({ Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = AppTheme.colors.textSecondary) }) else null,
                onClick = if (pick) ({ picker = true }) else null,
            )
        }
    }
    failed?.let {
        Banner(it.message, Tone.Danger, action = "Повторить", onAction = { chosen?.let(m::start) }, secondAction = "Журнал", onSecondAction = { navigator.open(Dest.Logs) })
    }
    if (exit) {
        val text = link
        if (text != null) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
                QrView(text, platform)
                ShareLinkActions(text)
                Text(
                    "По этой ссылке подключится любой. Одновременно работает одно устройство — второе вытеснит первое.",
                    style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary, textAlign = TextAlign.Center,
                )
            }
        } else {
            Text("Ядро готовит ссылку…", style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary)
        }
        Section(null) {
            Collapsible("Сведения") {
                if (platform.kind == PlatformKind.Desktop && platform.exitL3Supported) ValueRow("Режим", settings.exitBackend.label)
                ValueRow("Порт прямого подключения", settings.exitDirectPort.toString(), mono = true)
                ValueRow("Адрес для клиентов", settings.exitShareHost.ifBlank { "определился сам" })
            }
        }
    } else {
        // How it serves, as the client's chips: one of them is on.
        if (platform.kind == PlatformKind.Desktop && platform.exitL3Supported) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                for (b in ExitBackend.entries) Chip(b.label, settings.exitBackend == b, { container.settings.update { it.copy(exitBackend = b) } })
            }
            Text(
                settings.exitBackend.describe(platform.exitL3Needs),
                style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Section(null) {
            Collapsible("Дополнительно") {
                Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
                    Field(
                        settings.exitDirectPort.toString(),
                        { v -> v.toIntOrNull()?.takeIf { it in 1..65535 }?.let { p -> container.settings.update { it.copy(exitDirectPort = p) } } },
                        "Порт прямого подключения", keyboardType = KeyboardType.Number,
                    )
                    Field(settings.exitShareHost, { v -> container.settings.update { it.copy(exitShareHost = v.trim()) } }, "Адрес для клиентов", placeholder = "определится сам")
                }
            }
        }
    }
    if (picker) {
        Sheet(onDismiss = { picker = false }, title = "Что раздавать") {
            Column(Modifier.padding(horizontal = AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
                Section(null) {
                    for (p in choices) {
                        RowItem(
                            p.name, subtitle = p.transport.title,
                            leading = { IconBubble(Icons.Rounded.Dns) },
                            selected = p.id == chosen?.id,
                            onClick = { container.settings.update { it.copy(selectedProfileId = p.id) }; picker = false },
                        )
                    }
                }
            }
        }
    }
}
