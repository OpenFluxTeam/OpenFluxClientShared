package io.openflux.app.settings

import kotlinx.coroutines.CancellationException
import io.openflux.desktop.model.CoreSource
import io.openflux.app.components.Tone
import io.openflux.app.components.TextAction
import io.openflux.app.components.ProgressLine
import io.openflux.app.components.Field
import io.openflux.app.components.Banner
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import io.openflux.app.components.LocalToaster
import io.openflux.app.components.PrimaryButton
import io.openflux.app.components.RowItem
import io.openflux.app.components.SecondaryButton
import io.openflux.app.components.Section
import io.openflux.app.components.Segmented
import io.openflux.app.components.Toggle
import io.openflux.app.components.ValueRow
import io.openflux.app.nav.Dest
import io.openflux.app.shell.LocalNavigator
import io.openflux.app.text.Fmt
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.UpdateChannel
import io.openflux.desktop.model.UpdateState
import io.openflux.desktop.service.LocalAppContainer
import kotlinx.coroutines.launch

/*
 * Settings of a full build: the experimental features (JS transports), the app's own updates, developer mode,
 * the support chat. Reached only through io.openflux.app.shell.BuildExtras, so a build without them (iOS) does
 * not carry them.
 */

@Composable
internal fun FullExperimentalPage() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    // Nothing here where the app may not take JS transports (PlatformServices.scriptsInApp), whatever opened it.
    if (!container.platform.scriptsInApp) {
        LaunchedEffect(Unit) { navigator.back() }
        return
    }
    val settings by container.settings.settings.collectAsState()
    Section(null) {
        Toggle(
            "Экспериментальные функции", settings.experimental, { v -> container.settings.update { it.copy(experimental = v) } },
            subtitle = if (settings.experimental) "Включены: профили с JS-транспортом подключаются" else "Выключены: профили с JS-транспортом не подключатся",
        )
        if (settings.experimental) RowItem("Транспорты на JS", subtitle = "Каталог: добавить, обновить, отключить", onClick = { navigator.open(Dest.Scripts) })
    }
    Text(
        "Могут работать нестабильно и меняться от версии к версии. JS-транспорт — чужой код без песочницы, с полным доступом к сети: ставьте только от авторов, которым доверяете.",
        style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary,
    )
}

/** "О программе": the app's version; ten taps on it turn developer mode on where the platform has one. */
@Composable
internal fun FullAboutVersionRow() {
    val container = LocalAppContainer.current
    val toaster = LocalToaster.current
    val platform = container.platform
    val taps = remember(container) { VersionTaps(container.settings) }
    if (platform.developerModeInApp) RowItem("Версия приложения", subtitle = platform.appVersion, onClick = { taps.tap()?.let(toaster::show) })
    else ValueRow("Версия приложения", platform.appVersion)
}

/** "О программе": the last release the app knows of. */
@Composable
internal fun FullAboutReleaseRow() {
    val container = LocalAppContainer.current
    val platform = container.platform
    if (!platform.selfUpdates) return
    val settings by container.settings.settings.collectAsState()
    val update by container.updates.state.collectAsState()
    ValueRow(
        "Последний выпуск",
        when (val u = update) {
            UpdateState.Checking -> "проверяю…"
            UpdateState.UpToDate -> "у вас последняя версия"
            is UpdateState.Available -> u.release.version
            is UpdateState.Downloading -> "скачиваю ${u.release.version}…"
            is UpdateState.Installing -> "${u.release.version}: установка"
            is UpdateState.Failed -> u.message
            UpdateState.Idle -> if (settings.updateCheckedAt == 0L) "не проверялось" else "проверено ${Fmt.ago(platform.now() - settings.updateCheckedAt)}"
        },
    )
}

/** "О программе": the updates' controls, developer mode's switch, the support chat. */
@Composable
internal fun FullAboutControls() {
    val container = LocalAppContainer.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val platform = container.platform
    val settings by container.settings.settings.collectAsState()
    // A store updates the app where it does not update itself: no checking, downloading or channel here.
    if (platform.selfUpdates) {
        val update by container.updates.state.collectAsState()
        val offered = update as? UpdateState.Available
        if (offered != null) PrimaryButton("Обновить до ${offered.release.version}", container.updates::install, Modifier.fillMaxWidth())
        SecondaryButton("Проверить обновления", {
            scope.launch {
                container.updates.check(manual = true)
                if (container.updates.state.value == UpdateState.UpToDate) toaster.show("У вас последняя версия")
            }
        }, Modifier.fillMaxWidth())
        Section(null) {
            Toggle("Проверять обновления автоматически", settings.autoUpdate, { v -> container.settings.update { it.copy(autoUpdate = v) } }, subtitle = "Раз в несколько часов. Обновления ставятся только с вашего согласия")
        }
        if (settings.developerMode) {
            io.openflux.app.components.Group("Канал обновлений") {
                Segmented(UpdateChannel.entries, settings.updateChannel, { c -> container.settings.update { it.copy(updateChannel = c) } }, label = { it.label })
            }
        }
    }
    if (settings.developerMode) {
        Section(null) { Toggle("Режим разработчика", true, { on -> container.settings.update { it.copy(developerMode = on) } }) }
    }
    platform.supportUrl?.let { url ->
        Section(null) { RowItem("Поддержка", subtitle = "Чат в Telegram: вопросы, проблемы с подключением", onClick = { platform.openUrl(url) }) }
    }
}

/** "Ядро" on the desktop: which file of the core runs (the built-in one, a downloaded release, a path). */
@Composable
internal fun FullCoreFile() {
    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsState()
    val platform = container.platform
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloads by remember { mutableStateOf(0) }
    io.openflux.app.components.Group("Файл ядра") {
        Segmented(CoreSource.choices, settings.coreSource, { s -> container.settings.update { it.copy(coreSource = s) } }, label = { it.label })
    }
    val repo = settings.coreSource.repo
    when {
        settings.coreSource == CoreSource.Bundled -> Section(null) { ValueRow("Встроенное ядро", platform.coreVersion) }
        repo != null -> {
            val source = settings.coreSource
            val tag = remember(source, downloads) { platform.downloadedCore(source) }
            Section(null) { ValueRow("Скачанное ядро", tag?.let { "$it · $repo" } ?: "не скачано") }
            if (downloading) ProgressLine("Скачиваю ядро…")
            else SecondaryButton(if (tag == null) "Скачать последний выпуск" else "Проверить и обновить", {
                downloading = true; error = null; result = null
                scope.launch {
                    try {
                        val got = platform.downloadCore(source)
                        result = if (got == tag) "Уже последняя версия: $got" else "Скачано ядро $got"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Не удалось скачать ядро"
                    } finally {
                        downloading = false
                        downloads++
                    }
                }
            }, enabled = platform.coreDownloadSupported)
            result?.let { Banner(it, Tone.Success) }
            error?.let { Banner(it, Tone.Danger) }
            Text("Выпуск из github.com/$repo, сверенный с SHA256SUMS.txt. Мастер своей ноды всегда работает со встроенным ядром.", style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary)
        }
        else -> {
            Field(settings.customCorePath, { v -> container.settings.update { it.copy(customCorePath = v) } }, "Путь к файлу ядра", mono = true)
            TextAction("Выбрать файл…", {
                scope.launch { platform.pickFile("Файл ядра OpenFlux", emptyList())?.let { path -> container.settings.update { it.copy(customCorePath = path) } } }
            })
        }
    }
}
