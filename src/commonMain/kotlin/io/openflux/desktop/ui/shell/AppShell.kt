package io.openflux.desktop.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabNavigator
import io.openflux.desktop.model.ThemeMode
import io.openflux.desktop.model.isActive
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.desktop.service.PlatformKind
import io.openflux.desktop.ui.BrowserViews
import io.openflux.desktop.ui.LocalBrowserViews
import io.openflux.desktop.ui.LocalScrollbars
import io.openflux.desktop.ui.LocalShortcuts
import io.openflux.desktop.ui.LocalTouchUi
import io.openflux.desktop.ui.NoBrowserViews
import io.openflux.desktop.ui.PlatformBackHandler
import io.openflux.desktop.ui.Scrollbars
import io.openflux.desktop.ui.Shortcuts
import io.openflux.desktop.ui.accounts.SignInDialog
import io.openflux.desktop.ui.components.LocalToaster
import io.openflux.desktop.ui.components.ToastHost
import io.openflux.desktop.ui.components.Toaster
import io.openflux.desktop.ui.connections.ConnectionsTab
import io.openflux.desktop.ui.home.HomeTab
import io.openflux.desktop.ui.nav.AppActions
import io.openflux.desktop.ui.nav.AppModels
import io.openflux.desktop.ui.nav.AppNav
import io.openflux.desktop.ui.nav.Dest
import io.openflux.desktop.ui.nav.LocalActions
import io.openflux.desktop.ui.nav.LocalModels
import io.openflux.desktop.ui.nav.LocalNav
import io.openflux.desktop.ui.nav.Section
import io.openflux.desktop.ui.nodes.NodesTab
import io.openflux.desktop.ui.profiles.DeleteDialog
import io.openflux.desktop.ui.profiles.ImportDialog
import io.openflux.desktop.ui.profiles.ShareDialog
import io.openflux.desktop.ui.settings.SettingsTab
import io.openflux.desktop.ui.theme.AppTheme
import io.openflux.desktop.ui.theme.OpenFluxTheme

/**
 * The window's width class: a phone ([WidthClass.Phone]) gets a bottom bar,
 * below [WidthClass.Medium] the sidebar shows icons only.
 */
enum class WidthClass { Phone, Compact, Medium, Expanded }

fun widthClassOf(width: Dp): WidthClass = when {
    width < 600.dp -> WidthClass.Phone
    width < 1000.dp -> WidthClass.Compact
    width < 1440.dp -> WidthClass.Medium
    else -> WidthClass.Expanded
}

/** What the window looks like to the screens in it. Where the person is lives in [AppNav]. */
@Stable
class ShellController {
    var widthClass by mutableStateOf(WidthClass.Medium)
        internal set
}

val LocalShell = staticCompositionLocalOf { ShellController() }

/** The four places of the app, in the order of the sidebar and the bottom bar. */
val appTabs: List<Tab> = listOf(HomeTab, ConnectionsTab, NodesTab, SettingsTab)

fun tabOf(section: Section): Tab = when (section) {
    Section.Home -> HomeTab
    Section.Connections -> ConnectionsTab
    Section.Nodes -> NodesTab
    Section.Settings -> SettingsTab
}

fun sectionOf(tab: Tab): Section = when (tab) {
    ConnectionsTab -> Section.Connections
    NodesTab -> Section.Nodes
    SettingsTab -> Section.Settings
    else -> Section.Home
}

@Composable
fun OpenFluxApp(container: AppContainer, scrollbars: Scrollbars, shortcuts: Shortcuts, browsers: BrowserViews = NoBrowserViews) {
    val settings by container.settings.settings.collectAsState()
    // Once a day: look for updates of the installed script transports (only with the experimental features on).
    LaunchedEffect(settings.experimental) { if (settings.experimental) runCatching { container.scriptUpdater.autoCheck() } }
    // The app's own updates: a look at GitHub's releases a few seconds after the start and every few hours.
    LaunchedEffect(Unit) { container.updates.start() }
    val dark = when (settings.theme) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val toaster = remember { Toaster() }
    val shell = remember { ShellController() }
    val nav = remember { AppNav() }
    val models = remember { AppModels(container) }
    val actions = remember { AppActions(nav, models, container) }
    DisposableEffect(models) { onDispose { models.dispose() } }
    val touch = container.platform.kind == PlatformKind.Android
    OpenFluxTheme(dark, touch) {
        CompositionLocalProvider(
            LocalTouchUi provides touch,
            LocalAppContainer provides container,
            LocalToaster provides toaster,
            LocalScrollbars provides scrollbars,
            LocalBrowserViews provides browsers,
            LocalShortcuts provides shortcuts,
            LocalShell provides shell,
            LocalNav provides nav,
            LocalModels provides models,
            LocalActions provides actions,
        ) {
            AppShell(shell, toaster)
        }
    }
}

@Composable
private fun AppShell(shell: ShellController, toaster: Toaster) {
    val container = LocalAppContainer.current
    val shortcuts = LocalShortcuts.current
    val touch = LocalTouchUi.current
    val nav = LocalNav.current
    val actions = LocalActions.current
    val models = LocalModels.current
    val settings by container.settings.settings.collectAsState()
    val incomingLink by container.incomingLink.collectAsState()

    // The journal and the transport catalogue are pages of Settings: with the experimental features off, the catalogue is gone.
    LaunchedEffect(settings.experimental) {
        if (!settings.experimental && nav.section == Section.Settings && nav.top == Dest.ScriptTransports) nav.back()
    }
    // A key or a profile link that came from outside (a scanned code, a chat) goes where it is read.
    LaunchedEffect(incomingLink) {
        val link = incomingLink ?: return@LaunchedEffect
        actions.incomingLink(link)
    }
    // Back goes up the current place's screens; from the first screen of a place, to Home; on Home the system leaves the app.
    // Only on touch: Esc stays with dialogs on the desktop.
    PlatformBackHandler(enabled = touch && nav.canGoBack) { nav.back() }
    DisposableEffect(nav) {
        val unregister = shortcuts.register { event ->
            if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@register false
            val place = when (event.key) {
                Key.One -> Section.Home
                Key.Two -> Section.Connections
                Key.Three -> Section.Nodes
                Key.Four -> Section.Settings
                else -> null
            }
            when {
                place != null -> { nav.select(place); true }
                event.key == Key.Enter -> {
                    val state = container.connection.state.value
                    if (state.isActive) container.connection.disconnect() else HomeTab.connectSelected(container)
                    true
                }
                // A wizard or an editor owns the keys while it is open.
                event.key == Key.N && nav.top.root != null -> { actions.newProfile(); true }
                event.key == Key.I && nav.top.root != null -> { models.profiles.importOpen = true; true }
                else -> false
            }
        }
        onDispose { unregister() }
    }
    val current = tabOf(nav.section)
    TabNavigator(HomeTab) { navigator ->
        // Edge to edge on Android: the background runs under the system bars, the content stays clear of them.
        BoxWithConstraints(Modifier.fillMaxSize().background(AppTheme.colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            shell.widthClass = widthClassOf(maxWidth)
            val collapsed = settings.sidebarCollapsed || shell.widthClass == WidthClass.Compact
            val content = @Composable { modifier: Modifier ->
                Column(modifier) {
                    // A new release of the app, when there is one.
                    UpdateBanner()
                    AnimatedContent(
                        targetState = current,
                        transitionSpec = {
                            (fadeIn(tween(220, delayMillis = 40)) + slideInVertically(tween(260)) { it / 40 })
                                .togetherWith(fadeOut(tween(120)))
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentKey = { it.key },
                    ) { tab ->
                        Box(Modifier.fillMaxSize()) { navigator.saveableState("tab", tab) { tab.Content() } }
                    }
                }
            }
            if (shell.widthClass == WidthClass.Phone) {
                Column(Modifier.fillMaxSize()) {
                    content(Modifier.weight(1f).fillMaxWidth())
                    // The keyboard takes the room: the tab bar would only float above it.
                    if (WindowInsets.ime.getBottom(LocalDensity.current) == 0) {
                        BottomBar(current = current, tabs = appTabs, onSelect = { nav.select(sectionOf(it)) })
                    }
                }
            } else Row(Modifier.fillMaxSize()) {
                Sidebar(
                    current = current,
                    tabs = appTabs,
                    collapsed = collapsed,
                    canExpand = shell.widthClass != WidthClass.Compact,
                    onSelect = { nav.select(sectionOf(it)) },
                    onToggleCollapsed = { container.settings.update { s -> s.copy(sidebarCollapsed = !s.sidebarCollapsed) } },
                    modifier = Modifier.fillMaxHeight().width(if (collapsed) AppTheme.dimens.sidebarCompactWidth else AppTheme.dimens.sidebarWidth),
                )
                content(Modifier.weight(1f).fillMaxHeight())
            }
            // Above the bottom bar on a phone.
            val toastGap = if (shell.widthClass == WidthClass.Phone) AppTheme.dimens.bottomBarHeight + AppTheme.spacing.m else AppTheme.spacing.xxl
            ToastHost(toaster, Modifier.align(Alignment.BottomCenter).padding(bottom = toastGap, start = AppTheme.spacing.l, end = AppTheme.spacing.l))
            CaptchaDialog()
            SignInDialog()
            ScriptSettingsDialog()
            // The dialogs of profiles: import, share, delete. They open over whichever screen asked.
            val profiles = models.profiles
            if (profiles.importOpen) ImportDialog(profiles)
            profiles.shareFor?.let { ShareDialog(profiles, it) }
            profiles.deleteFor?.let { DeleteDialog(profiles, it) }
        }
    }
}
