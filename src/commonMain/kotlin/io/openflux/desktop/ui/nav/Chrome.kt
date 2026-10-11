package io.openflux.desktop.ui.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.openflux.desktop.model.Profile
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.desktop.ui.LocalScrollbars
import io.openflux.desktop.ui.components.AppIconButton
import io.openflux.desktop.ui.components.AppIcons
import io.openflux.desktop.ui.components.TextAction
import io.openflux.desktop.ui.theme.AppTheme

/** What a screen is called where the person reads where "back" goes ("← Подключения", "← Нода Амстердам"). */
fun destTitle(dest: Dest, profiles: List<Profile>): String {
    fun name(id: String?) = profiles.firstOrNull { it.id == id }?.name?.ifBlank { null } ?: "Профиль"
    return when (dest) {
        Dest.Home -> "Главная"
        Dest.Connections -> "Подключения"
        Dest.Nodes -> "Ноды"
        Dest.Settings -> "Настройки"
        Dest.AddConnection -> "Добавить подключение"
        is Dest.Profile -> name(dest.id)
        is Dest.ProfileEdit -> if (dest.id == null) "Новый профиль" else name(dest.id)
        Dest.Account -> "Подписка"
        Dest.Auth -> "Аккаунт OpenFlux"
        is Dest.Server -> if (dest.id == "+") "Новый сервер" else dest.id.split(':').let { p -> if (p.size >= 3) "${p[2]}@${p[0]}" else dest.id }
        is Dest.ChannelEdit -> "Нода ${dest.channel}"
        is Dest.CreateChannel -> "Новая нода"
        Dest.CreateHosting -> "Нода без сервера"
        is Dest.Node -> name(dest.id)
        Dest.Logs -> "Журнал"
        Dest.ScriptTransports -> "Транспорты"
    }
}

/**
 * The frame every screen but a place's first has: "← where you came from", the title, the actions, and the page
 * under them, scrolling. A phone's system back does the same as the arrow.
 */
@Composable
fun DetailScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    maxWidth: Dp = 720.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val nav = LocalNav.current
    val actionsOf = LocalActions.current
    val profiles by LocalAppContainer.current.profiles.profiles.collectAsState()
    val back = nav.below?.takeUnless { LocalSplitView.current }?.let { destTitle(it, profiles) }
    val scroll = rememberScrollState()
    val scrollbars = LocalScrollbars.current
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = AppTheme.spacing.page, vertical = AppTheme.spacing.xl)) {
            Column(Modifier.widthIn(max = maxWidth)) {
                if (back != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIconButton("Назад", actionsOf::back, resource = AppIcons.Back)
                        Spacer(Modifier.width(AppTheme.spacing.xs))
                        TextAction(back, actionsOf::back)
                    }
                    Spacer(Modifier.height(AppTheme.spacing.s))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (leading != null) {
                        leading()
                        Spacer(Modifier.width(AppTheme.spacing.l))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = AppTheme.typography.pageTitle, color = AppTheme.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null) {
                            Text(subtitle, style = AppTheme.typography.bodySmall, color = AppTheme.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) { actions() }
                }
                Spacer(Modifier.height(AppTheme.spacing.xl))
                content()
            }
        }
        scrollbars.Vertical(scroll, Modifier.align(Alignment.CenterEnd))
    }
}

/** True while a screen is shown beside its list (a wide window): the list is the way back, so the screen has no back link. */
val LocalSplitView = androidx.compose.runtime.staticCompositionLocalOf { false }

/** "← where you came from" for screens that scroll on their own (the journal, the transport catalogue). */
@Composable
fun BackBar(modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    val actions = LocalActions.current
    val profiles by LocalAppContainer.current.profiles.profiles.collectAsState()
    val below = nav.below?.takeUnless { LocalSplitView.current } ?: return
    Row(modifier.padding(horizontal = AppTheme.spacing.page, vertical = AppTheme.spacing.s), verticalAlignment = Alignment.CenterVertically) {
        AppIconButton("Назад", actions::back, resource = AppIcons.Back)
        Spacer(Modifier.width(AppTheme.spacing.xs))
        TextAction(destTitle(below, profiles), actions::back)
    }
}
