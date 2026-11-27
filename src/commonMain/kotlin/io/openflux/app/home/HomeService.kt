package io.openflux.app.home

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.openflux.app.components.Banner
import io.openflux.app.components.ProgressLine
import io.openflux.app.components.RowItem
import io.openflux.app.components.TextAction
import io.openflux.app.components.Tone
import io.openflux.app.nav.Dest
import io.openflux.app.shell.LocalNavigator
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.OpenFluxBot
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.SubscriptionState
import io.openflux.desktop.service.BotAccountManager
import io.openflux.desktop.service.LocalAppContainer

/*
 * Home of a build with the OpenFlux service: what the subscription and the bot add to it. Reached only through
 * io.openflux.app.shell.BuildExtras, so a build without the service does not carry it.
 */

/** The start panel's first word when the service has one: true when it took the panel. */
@Composable
internal fun ServiceStartNotice(): Boolean {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val subscription by container.subscription.state.collectAsState()
    val preparing by container.subscription.preparing.collectAsState()
    val bot by container.bot.state.collectAsState()
    val open = if (container.platform.purchasesInApp) "Открыть подписку" else "Открыть аккаунт"
    if (preparing || bot.preparing) {
        Text("Готовим вашу локацию", style = AppTheme.type.screenTitle, color = AppTheme.colors.text)
        ProgressLine("Обычно это минута-две, она появится здесь сама")
        return true
    }
    // Signed in through Telegram but nothing to connect to: say why, instead of offering to sign in again.
    bot.connectionNote?.let { note ->
        Banner(note, Tone.Warning, action = open, onAction = { navigator.open(Dest.Account) })
        return true
    }
    if (bot.session != null) {
        Banner("Вы вошли, подключения пока нет", Tone.Info, action = open, onAction = { navigator.open(Dest.Account) })
        return true
    }
    if (subscription != null) {
        Banner("В подписке пока нет локаций", Tone.Info, action = open, onAction = { navigator.open(Dest.Account) })
        return true
    }
    return false
}

/** The start panel's ways in through the service: the account, and a key or a link. */
@Composable
internal fun ServiceStartRows() {
    val platform = LocalAppContainer.current.platform
    val navigator = LocalNavigator.current
    if (platform.accountSignInInApp) RowItem(
        "Войти в аккаунт OpenFlux",
        subtitle = if (platform.purchasesInApp) "По почте или через Telegram; подписка и подключение появятся сами" else "По почте или через Telegram; подключения появятся сами",
        onClick = { navigator.open(Dest.Auth) },
    )
    RowItem("Ключ или ссылка", subtitle = "От друга или из сервиса", onClick = { navigator.open(Dest.AddKey) })
}

@Composable
internal fun serviceSubscriptionState(): SubscriptionState? {
    val subscription by LocalAppContainer.current.subscription.state.collectAsState()
    return subscription
}

@Composable
internal fun ServiceSubscriptionBanner(b: HomeBanner.Subscription) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    Banner(b.look.line, Tone.Warning, action = if (container.platform.purchasesInApp) "Продлить" else "Открыть", onAction = { navigator.open(Dest.Account) })
}

/** A connection from the bot that the bot no longer serves: the way to a new one. */
@Composable
internal fun ServiceGoneLinkBanner(chosen: Profile) {
    val container = LocalAppContainer.current
    val bot by container.bot.state.collectAsState()
    if (bot.links.any { it.profileId == chosen.id && it.gone }) {
        Banner(BotAccountManager.GONE, Tone.Warning, action = "Открыть бота", onAction = { container.platform.openUrl(OpenFluxBot.CHAT_URL) })
    }
}

/** The subscription's own row above its locations in the list. */
@Composable
internal fun ServiceSubscriptionGroupRow() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val subscription by container.subscription.state.collectAsState()
    subscription?.let { sub ->
        val look = sub.look(container.platform.now(), container.platform.utcOffsetSeconds)
        RowItem(
            sub.title.ifBlank { "Подписка" }, subtitle = look.line,
            trailing = { TextAction(if (look.attention && container.platform.purchasesInApp) "Продлить" else "Открыть", { navigator.open(Dest.Account) }) },
        )
    }
}
