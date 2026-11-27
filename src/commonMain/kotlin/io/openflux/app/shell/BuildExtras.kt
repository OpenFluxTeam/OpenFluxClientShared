package io.openflux.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation3.runtime.NavEntry
import io.openflux.app.account.AccountScreen
import io.openflux.app.account.SignInScreen
import io.openflux.app.add.AddKeyModel
import io.openflux.app.add.KeyResult
import io.openflux.app.add.NOTHING_TO_SHARE_WITH_SERVICE
import io.openflux.app.add.ServiceKeyField
import io.openflux.app.add.ServiceKeyPreview
import io.openflux.app.add.serviceKeyButton
import io.openflux.app.components.Banner
import io.openflux.app.components.Tone
import io.openflux.app.home.HomeBanner
import io.openflux.app.home.ServiceGoneLinkBanner
import io.openflux.app.home.ServiceStartNotice
import io.openflux.app.home.ServiceStartRows
import io.openflux.app.home.ServiceSubscriptionBanner
import io.openflux.app.home.ServiceSubscriptionGroupRow
import io.openflux.app.home.SubLook
import io.openflux.app.home.look
import io.openflux.app.home.serviceSubscriptionState
import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Dest
import io.openflux.app.nav.ListDetail
import io.openflux.app.nav.Section
import io.openflux.app.nav.destEntry
import io.openflux.app.settings.FullAboutControls
import io.openflux.app.settings.FullAboutReleaseRow
import io.openflux.app.settings.FullAboutVersionRow
import io.openflux.app.settings.FullCoreFile
import io.openflux.app.settings.FullExperimentalPage
import io.openflux.app.settings.ScriptsScreen
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.SubscriptionState
import io.openflux.desktop.service.AppContainer

/**
 * What the desktop and Android builds have and the App Store build does not: the OpenFlux service (account,
 * subscription, the bot's links, buying), the catalogue of JS transports, the app's own updates, developer mode.
 *
 * The rest of the interface reaches all of it only through [buildExtras]. Where that is null (iOS) nothing refers
 * to this code, so the compiler leaves it and its texts out of the binary: an App Store app must not carry a paid
 * service's screens, a way to load code or a hidden mode, even unreachable (App Store 3.1.1, 2.5.2, 5.6).
 * PlatformServices' `*InApp` flags stay the switch at run time, and what tests turn. The services behind these
 * screens come the same way: io.openflux.desktop.service.ServiceExtras.
 */
interface BuildExtras {
    // The shell.

    /** The screen of a destination only a full build has; null for any other. */
    fun entry(dest: Dest, navigator: AppNavigator): NavEntry<Dest>?

    /** What runs while the app is open: the app's own updates, and the script transports' with [experimental] on. */
    @Composable fun Background(container: AppContainer, experimental: Boolean)

    /** A new release of the app, in one line. */
    @Composable fun UpdateBanner()

    /** "Аккаунт OpenFlux" of the ＋ menu. */
    fun openAccount(container: AppContainer, navigator: AppNavigator)

    // "Ключ, ссылка или файл".

    /** Reads what was typed as the service's key or link, or a profile link. */
    fun readKey(m: AddKeyModel, text: String)

    /** Adds what the service gave; where to go after, or null. */
    fun addKey(m: AddKeyModel, r: KeyResult): Dest?

    @Composable fun KeyField(m: AddKeyModel)

    /** The button's words for what the service gave; null: the usual ones. */
    fun keyButton(r: KeyResult): String?

    @Composable fun KeyPreview(r: KeyResult)

    /** "Поделиться" with nothing to share. */
    val nothingToShare: String

    // Profiles.

    /** Over a profile the subscription brought. */
    @Composable fun ManagedBanner(withLink: Boolean)

    val managedShareRefusal: String

    /** Over a JS transport in the editor while the experimental features are off. */
    val experimentalOffNote: String

    // Home.

    /** The start panel's first word when the service has one: true when it took the panel. */
    @Composable fun StartNotice(): Boolean

    /** The start panel's ways in through the service. */
    @Composable fun StartRows()

    @Composable fun subscriptionState(): SubscriptionState?

    fun subscriptionLook(s: SubscriptionState, nowMillis: Long, offsetSeconds: Int): SubLook

    @Composable fun SubscriptionBanner(b: HomeBanner.Subscription)

    /** A connection from the bot that the bot no longer serves. */
    @Composable fun GoneLinkBanner(chosen: Profile)

    /** The subscription's own row above its locations. */
    @Composable fun SubscriptionGroupRow()

    // Settings.

    /** What the account's row is called where there is no sign-in. */
    val accountRowTitle: String

    @Composable fun ExperimentalPage()

    /** "Ядро" on the desktop: which file of the core runs. */
    @Composable fun CoreFile()

    /** "О программе": the version (ten taps turn developer mode on), the last release, the updates' controls. */
    @Composable fun AboutVersionRow()

    @Composable fun AboutReleaseRow()

    @Composable fun AboutControls()
}

/** The full build's extras; null in a build without them. */
expect val buildExtras: BuildExtras?

/** Everything [BuildExtras] promises. Named only by the builds that have it (the `actual` of [buildExtras]). */
object FullBuildExtras : BuildExtras {
    override fun entry(dest: Dest, navigator: AppNavigator): NavEntry<Dest>? = when (dest) {
        Dest.Auth -> destEntry(dest) { SignInScreen() }
        Dest.Account -> destEntry(dest, ListDetail.detail(Section.Settings)) { AccountScreen() }
        Dest.Scripts -> destEntry(dest) { ScriptsScreen() }
        else -> null
    }

    @Composable
    override fun Background(container: AppContainer, experimental: Boolean) {
        LaunchedEffect(Unit) { container.updates.start() }
        LaunchedEffect(experimental) { if (experimental) runCatching { container.scriptUpdater.autoCheck() } }
    }

    @Composable override fun UpdateBanner() = UpdateLine()

    override fun openAccount(container: AppContainer, navigator: AppNavigator) {
        val signedIn = container.bot.state.value.session != null
        navigator.open(if (signedIn) Dest.Account else Dest.Auth)
    }

    override fun readKey(m: AddKeyModel, text: String) = m.readServiceKey(text)
    override fun addKey(m: AddKeyModel, r: KeyResult): Dest? = m.addServiceKey(r)
    @Composable override fun KeyField(m: AddKeyModel) = ServiceKeyField(m)
    override fun keyButton(r: KeyResult): String? = serviceKeyButton(r)
    @Composable override fun KeyPreview(r: KeyResult) = ServiceKeyPreview(r)
    override val nothingToShare: String get() = NOTHING_TO_SHARE_WITH_SERVICE

    @Composable
    override fun ManagedBanner(withLink: Boolean) {
        val navigator = LocalNavigator.current
        if (withLink) Banner("Приходит с подпиской, менять его нельзя", Tone.Info, action = "Подписка", onAction = { navigator.open(Dest.Account) })
        else Banner("Приходит с подпиской, менять его нельзя", Tone.Info)
    }

    override val managedShareRefusal: String get() = "Локациями подписки делиться нельзя"
    override val experimentalOffNote: String get() = "Экспериментальные функции выключены: с этим транспортом профиль не подключится."

    @Composable override fun StartNotice(): Boolean = ServiceStartNotice()
    @Composable override fun StartRows() = ServiceStartRows()
    @Composable override fun subscriptionState(): SubscriptionState? = serviceSubscriptionState()
    override fun subscriptionLook(s: SubscriptionState, nowMillis: Long, offsetSeconds: Int): SubLook = s.look(nowMillis, offsetSeconds)
    @Composable override fun SubscriptionBanner(b: HomeBanner.Subscription) = ServiceSubscriptionBanner(b)
    @Composable override fun GoneLinkBanner(chosen: Profile) = ServiceGoneLinkBanner(chosen)
    @Composable override fun SubscriptionGroupRow() = ServiceSubscriptionGroupRow()

    override val accountRowTitle: String get() = "Подключения OpenFlux"
    @Composable override fun ExperimentalPage() = FullExperimentalPage()
    @Composable override fun CoreFile() = FullCoreFile()
    @Composable override fun AboutVersionRow() = FullAboutVersionRow()
    @Composable override fun AboutReleaseRow() = FullAboutReleaseRow()
    @Composable override fun AboutControls() = FullAboutControls()
}
