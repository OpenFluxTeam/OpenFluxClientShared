package io.openflux.desktop.ui.nav

import androidx.compose.runtime.staticCompositionLocalOf
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.SubscriptionInvite
import io.openflux.desktop.model.managed
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.ui.nodes.NodesScreenModel
import io.openflux.desktop.ui.profiles.ProfilesScreenModel
import io.openflux.desktop.ui.settings.SettingsCategory
import io.openflux.desktop.ui.subscription.AuthStep
import io.openflux.desktop.ui.subscription.SubscriptionScreenModel

/** The state the screens share across the whole app, made once: what is being done with profiles, nodes and the account. */
class AppModels(container: AppContainer) {
    val profiles = ProfilesScreenModel(container)
    val account = SubscriptionScreenModel(container)
    val nodes = NodesScreenModel(container)
    val settings = io.openflux.desktop.ui.settings.SettingsScreenModel(container)

    /** The page of Settings another screen asked for ("Войти в Яндекс"); Settings takes it and clears it. */
    var settingsCategory: SettingsCategory? by androidx.compose.runtime.mutableStateOf(null)

    fun dispose() {
        profiles.onDispose()
        account.onDispose()
        nodes.onDispose()
        settings.onDispose()
    }
}

/**
 * Every move between screens, named after what the person wants: the screens call these instead of knowing where
 * things live. One list of entry points means one place to read how the app is traversed.
 */
class AppActions(val nav: AppNav, private val models: AppModels, private val container: AppContainer) {
    init {
        // A screen leaving a stack lets go of what it held.
        nav.onRemoved = { dest ->
            models.nodes.release(dest)
            if (dest is Dest.ProfileEdit) models.profiles.endEdit()
        }
    }

    fun back() {
        nav.back()
    }

    // ---- places ----

    fun openHome() = nav.select(Section.Home)

    // ---- connections ----

    /** The ways to connect: a key or link, an account, a node, by hand. */
    fun addConnection() = nav.goIn(Section.Connections, Dest.AddConnection)

    /** A key or a profile link that came from outside the app (a scanned code, a chat). */
    fun incomingLink(link: String) {
        if (SubscriptionInvite.looksLike(link)) {
            container.pendingKey.value = link
            container.incomingLink.value = null
            nav.goIn(Section.Connections, Dest.AddConnection)
        } else {
            // A profile link: the import dialog reads it, over the list of connections.
            container.incomingLink.value = null
            models.profiles.importText = link
            models.profiles.importOpen = true
            nav.goIn(Section.Connections, Dest.Connections)
        }
    }

    /** One profile's screen; [replace] when it is shown beside the list, so choosing another swaps it instead of stacking. */
    fun openProfile(id: String, replace: Boolean = false) = show(Dest.Profile(id), replace)

    private fun show(dest: Dest, replace: Boolean) {
        val top = nav.top
        val onEntity = top is Dest.Profile || top is Dest.Node || top is Dest.ProfileEdit || top is Dest.NodeEdit
        if (replace && onEntity) nav.replaceTop(dest) else nav.push(dest)
    }

    /** The fields of a profile; a node of one's own is changed on its own screens, but its fields are here too. */
    fun editProfile(profile: Profile) {
        if (profile.managed) return // the subscription's locations are the service's to change
        models.profiles.beginEdit(profile.id)
        nav.push(Dest.ProfileEdit(profile.id))
    }

    fun newProfile() {
        models.profiles.beginEdit(null)
        nav.goIn(Section.Connections, Dest.ProfileEdit(null))
    }

    /** Leaves the editor after a save or a cancel. */
    fun finishEdit() {
        nav.back()
    }

    fun deleteProfile(profile: Profile) {
        models.profiles.delete(profile)
        nav.forget(profile.id)
    }

    fun showAccount(signedIn: Boolean) {
        if (signedIn) nav.goIn(Section.Connections, Dest.Account) else signIn(AuthStep.Choose)
    }

    fun signIn(step: AuthStep = AuthStep.Choose) {
        models.account.go(step)
        nav.goIn(Section.Connections, Dest.Auth)
    }

    // ---- nodes ----

    fun createNode(kind: NodeKind, recentServer: String? = null) = nav.goIn(Section.Nodes, Dest.CreateNode(kind, recentServer))

    /** A node of one's own: its server, transports, users. */
    fun openNode(id: String, replace: Boolean = false) = show(Dest.Node(id), replace)

    /** Changing the node on its server. */
    fun editNode(id: String) = nav.push(Dest.NodeEdit(id))

    /** A wizard made a node: its screen replaces the wizard. */
    fun nodeMade(id: String) {
        if (id.isEmpty()) nav.back() else nav.replaceTop(Dest.Node(id))
    }

    // ---- settings ----

    fun openSettings(category: SettingsCategory? = null) {
        models.settingsCategory = category
        nav.goIn(Section.Settings, Dest.Settings)
    }

    fun openLogs() = nav.goIn(Section.Settings, Dest.Logs)

    fun openScriptTransports() = nav.goIn(Section.Settings, Dest.ScriptTransports)
}

val LocalNav = staticCompositionLocalOf<AppNav> { error("AppNav is not provided") }
val LocalActions = staticCompositionLocalOf<AppActions> { error("AppActions is not provided") }
val LocalModels = staticCompositionLocalOf<AppModels> { error("AppModels is not provided") }
