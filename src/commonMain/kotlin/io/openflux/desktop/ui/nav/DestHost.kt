package io.openflux.desktop.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.desktop.ui.components.AppButton
import io.openflux.desktop.ui.components.AppIcons
import io.openflux.desktop.ui.components.ButtonRow
import io.openflux.desktop.ui.components.ButtonStyle
import io.openflux.desktop.ui.components.EmptyState
import io.openflux.desktop.ui.connections.AccountScreenHost
import io.openflux.desktop.ui.connections.AddConnectionScreen
import io.openflux.desktop.ui.connections.AuthScreen
import io.openflux.desktop.ui.connections.ConnectionsList
import io.openflux.desktop.ui.connections.ProfileEditScreen
import io.openflux.desktop.ui.connections.ProfileScreen
import io.openflux.desktop.ui.logs.LogsPage
import io.openflux.desktop.ui.nodes.CreateNodeScreen
import io.openflux.desktop.ui.nodes.NodeEditScreen
import io.openflux.desktop.ui.nodes.NodeScreen
import io.openflux.desktop.ui.nodes.NodesList
import io.openflux.desktop.ui.scripts.ScriptsPage
import io.openflux.desktop.ui.settings.SettingsRoot
import io.openflux.desktop.ui.theme.AppTheme

/** The screen of [dest]: the one place that says which composable shows which destination. */
@Composable
fun DestContent(dest: Dest) {
    when (dest) {
        Dest.Home -> Unit // Home is drawn by its own tab
        Dest.Connections -> ConnectionsList(selectedId = null, split = false, modifier = Modifier.fillMaxSize())
        Dest.Nodes -> NodesList(selectedId = null, split = false, modifier = Modifier.fillMaxSize())
        Dest.Settings -> SettingsRoot()
        Dest.AddConnection -> AddConnectionScreen()
        is Dest.Profile -> ProfileScreen(dest.id)
        is Dest.ProfileEdit -> ProfileEditScreen(dest)
        Dest.Account -> AccountScreenHost()
        Dest.Auth -> AuthScreen()
        is Dest.CreateNode -> CreateNodeScreen(dest)
        is Dest.Node -> NodeScreen(dest.id)
        is Dest.NodeEdit -> NodeEditScreen(dest)
        Dest.Logs -> LogsPage()
        Dest.ScriptTransports -> ScriptsPage()
    }
}

/** The profile or node a screen is about; null for any other. */
private val Dest.entityId: String?
    get() = when (this) {
        is Dest.Profile -> id
        is Dest.ProfileEdit -> id
        is Dest.Node -> id
        is Dest.NodeEdit -> id
        else -> null
    }

private val Dest.isEntity: Boolean
    get() = this is Dest.Profile || this is Dest.ProfileEdit || this is Dest.Node || this is Dest.NodeEdit

/**
 * What a place shows: the top screen of its stack. In a wide window the lists of Connections and Nodes keep their
 * place at the left and a profile's or node's screen opens at the right, so choosing from the list never leaves it;
 * every other screen (adding, signing in, a wizard) takes the whole window.
 */
@Composable
fun PlaceHost(section: Section) {
    val nav = LocalNav.current
    val top = nav.stack(section).last()
    val hasList = section == Section.Connections || section == Section.Nodes
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        if (hasList && wide && (top.isEntity || top.root != null)) {
            val listWidth = (maxWidth * 0.32f).coerceIn(300.dp, 420.dp)
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(listWidth).fillMaxHeight()) {
                    val listModifier = Modifier.fillMaxSize()
                    if (section == Section.Connections) ConnectionsList(top.entityId, split = true, modifier = listModifier)
                    else NodesList(top.entityId, split = true, modifier = listModifier)
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(AppTheme.colors.border))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (top.isEntity) CompositionLocalProvider(LocalSplitView provides true) { DestContent(top) }
                    else NothingChosen(section)
                }
            }
        } else {
            DestContent(top)
        }
    }
}

/** The right-hand side of a wide list with nothing chosen. */
@Composable
private fun NothingChosen(section: Section) {
    val actions = LocalActions.current
    val profiles by LocalAppContainer.current.profiles.profiles.collectAsState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (section == Section.Connections) {
            EmptyState(
                title = if (profiles.isEmpty()) "Подключений пока нет" else "Выберите подключение",
                message = if (profiles.isEmpty()) "Войдите в аккаунт, вставьте ключ или ссылку, либо поставьте свою ноду."
                else "Слева список: откройте подключение, чтобы посмотреть, изменить или передать его. Двойной щелчок подключает.",
                resource = AppIcons.Public,
            ) {
                ButtonRow(alignment = Alignment.CenterHorizontally) {
                    AppButton("Добавить подключение", actions::addConnection, leading = Icons.Rounded.Add)
                }
            }
        } else {
            EmptyState(
                title = "Выберите ноду",
                message = "Слева ваши ноды. Откройте одну, чтобы посмотреть её сервер, поменять транспорты или добавить пользователя.",
                resource = AppIcons.Public,
            ) {
                ButtonRow(alignment = Alignment.CenterHorizontally) {
                    AppButton("На своём сервере", { actions.createNode(NodeKind.Server) }, leading = Icons.Rounded.Dns)
                    AppButton("Без сервера", { actions.createNode(NodeKind.Hosting) }, style = ButtonStyle.Secondary, leading = Icons.Rounded.Cloud)
                }
            }
        }
    }
}
