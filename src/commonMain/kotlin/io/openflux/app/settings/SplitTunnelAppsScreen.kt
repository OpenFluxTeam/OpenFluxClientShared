package io.openflux.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.openflux.app.components.Banner
import io.openflux.app.components.ChangesBar
import io.openflux.app.components.Chip
import io.openflux.app.components.ConfirmDialog
import io.openflux.app.components.Field
import io.openflux.app.components.ProgressLine
import io.openflux.app.components.RowItem
import io.openflux.app.components.ScreenScaffold
import io.openflux.app.components.Tone
import io.openflux.app.platform.PlatformBackHandler
import io.openflux.app.shell.LocalNavigator
import io.openflux.app.text.Fmt
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.SplitTunnelMode
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.profile
import io.openflux.desktop.service.InstalledApp
import io.openflux.desktop.service.LocalAppContainer
import kotlinx.coroutines.CancellationException

private enum class AppFilter(val label: String) {
    User("Пользовательские"),
    System("Системные"),
    All("Все"),
    Russian("Российские"),
}

/** Picks the apps that go through Android's VPN, or past it (split tunneling). */
@Composable
fun SplitTunnelAppsScreen() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val settings by container.settings.settings.collectAsState()
    val mode = settings.splitTunnelMode
    val saved = settings.splitTunnelPackages
    var draft by remember { mutableStateOf(saved.toSet()) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AppFilter.User) }
    var russianPresetApplied by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var leaveAsk by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        loading = true
        loadError = null
        try {
            apps = container.platform.installedApps()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message ?: "Не удалось прочитать список приложений"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(filter, apps) {
        if (filter == AppFilter.Russian && apps != null && !russianPresetApplied) {
            draft = draft + apps.orEmpty().filter { isRussianApp(it.packageName) }.map { it.packageName }
            russianPresetApplied = true
        }
    }

    val catalog = apps.orEmpty()
    val known = remember(catalog) { catalog.mapTo(HashSet()) { it.packageName } }
    val missing = remember(draft, known) { draft.filterNot { it in known }.sorted() }
    val changes = (draft - saved.toSet()).size + (saved.toSet() - draft).size
    val hasUnsaved = changes > 0
    val problem = if (mode == SplitTunnelMode.Include && draft.isEmpty()) "Выберите хотя бы одно приложение" else null
    val visible = catalog.filter { app ->
        (when (filter) {
            AppFilter.User -> !app.system
            AppFilter.System -> app.system
            AppFilter.All -> true
            AppFilter.Russian -> isRussianApp(app.packageName)
        }) && (query.isBlank() || app.label.contains(query, ignoreCase = true) || app.packageName.contains(query, ignoreCase = true))
    }
    val leave: () -> Unit = { if (hasUnsaved) leaveAsk = true else navigator.back() }
    PlatformBackHandler(enabled = hasUnsaved) { leaveAsk = true }

    fun toggle(packageName: String) {
        draft = if (packageName in draft) draft - packageName else draft + packageName
    }

    fun apply() {
        if (problem != null) return
        val packages = draft.sorted()
        container.settings.update { it.copy(splitTunnelPackages = packages) }
        val s = container.connection.state.value
        if (s.isActive && (mode != SplitTunnelMode.Include || packages.isNotEmpty())) {
            s.profile?.let { container.connection.connect(it) }
        }
        navigator.back()
    }

    ScreenScaffold(
        "Приложения",
        onBack = leave,
        scroll = false,
        bottomBar = {
            ChangesBar(
                changes = changes,
                busy = null,
                problem = problem,
                error = null,
                onDiscard = { draft = saved.toSet() },
                onApply = ::apply,
                applyLabel = "Сохранить",
            )
        },
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
            Field(query, { query = it }, "Поиск", placeholder = "Название или пакет")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                AppFilter.entries.forEach { f ->
                    Chip(f.label, filter == f, { filter = f })
                }
            }
            Text(
                if (draft.isEmpty()) "Ничего не выбрано" else "Выбрано: ${draft.size}",
                style = AppTheme.type.bodySmall,
                color = AppTheme.colors.textSecondary,
            )
            if (mode == SplitTunnelMode.Include && draft.isEmpty()) {
                Text(
                    "Выберите хотя бы одно приложение: с пустым списком VPN не запустится.",
                    style = AppTheme.type.bodySmall,
                    color = AppTheme.colors.danger,
                )
            }
            when {
                loading -> ProgressLine("Загрузка приложений…", Modifier.padding(top = AppTheme.spacing.m))
                loadError != null -> Banner(
                    loadError!!,
                    Tone.Danger,
                    action = "Повторить",
                    onAction = { reload++ },
                )
                catalog.isEmpty() -> Text(
                    "На этом устройстве не найдено ни одного приложения.",
                    style = AppTheme.type.bodySmall,
                    color = AppTheme.colors.textSecondary,
                )
                else -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    if (missing.isNotEmpty()) {
                        item { ListCaption("Не установлены") }
                        items(missing, key = { "missing:$it" }) { packageName ->
                            RowItem(
                                packageName,
                                subtitle = "Не установлено",
                                onClick = { draft = draft - packageName },
                                trailing = {
                                    Checkbox(
                                        checked = true,
                                        onCheckedChange = { draft = draft - packageName },
                                        colors = checkboxColors(),
                                    )
                                },
                            )
                        }
                    }
                    if (visible.isEmpty()) {
                        item {
                            Text(
                                "Ничего не найдено",
                                Modifier.padding(AppTheme.spacing.l),
                                style = AppTheme.type.bodySmall,
                                color = AppTheme.colors.textSecondary,
                            )
                        }
                    } else {
                        items(visible, key = { it.packageName }) { app ->
                            AppRow(app, app.packageName in draft, ::toggle)
                        }
                    }
                }
            }
            if (apps != null && catalog.isNotEmpty()) {
                Text(
                    "${visible.size} ${Fmt.plural(visible.size, "приложение", "приложения", "приложений")}",
                    style = AppTheme.type.bodySmall,
                    color = AppTheme.colors.textSecondary,
                )
            }
        }
    }

    if (leaveAsk) {
        ConfirmDialog(
            title = "Изменения не сохранены",
            text = "Если выйти, выбранные приложения не изменятся.",
            confirm = "Выйти без сохранения",
            onConfirm = {
                leaveAsk = false
                draft = saved.toSet()
                navigator.back()
            },
            onDismiss = { leaveAsk = false },
            danger = true,
            dismiss = "Остаться",
        )
    }
}

@Composable
private fun AppRow(app: InstalledApp, checked: Boolean, onToggle: (String) -> Unit) {
    val c = AppTheme.colors
    RowItem(
        app.label,
        subtitle = if (app.system) "${app.packageName} · Системное" else app.packageName,
        onClick = { onToggle(app.packageName) },
        leading = {
            if (app.icon != null) {
                androidx.compose.foundation.Image(
                    bitmap = app.icon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(Icons.Rounded.Apps, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(32.dp))
            }
        },
        trailing = {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle(app.packageName) },
                colors = checkboxColors(),
            )
        },
    )
}

@Composable
private fun ListCaption(text: String) {
    Text(
        text.uppercase(),
        Modifier.padding(start = AppTheme.spacing.xs, top = AppTheme.spacing.m, bottom = AppTheme.spacing.s),
        style = AppTheme.type.label,
        color = AppTheme.colors.textSecondary,
    )
}

@Composable
private fun checkboxColors() = CheckboxDefaults.colors(
    checkedColor = AppTheme.colors.accent,
    uncheckedColor = AppTheme.colors.textSecondary,
    checkmarkColor = AppTheme.colors.onAccent,
)
