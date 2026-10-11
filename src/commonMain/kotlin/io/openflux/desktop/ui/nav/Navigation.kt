package io.openflux.desktop.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The four places of the app. Each has its own stack of screens, so going to another place and coming back
 * finds the person where they left, and "back" never jumps between places by accident.
 *
 *  - [Home]: connect, disconnect, what is going on.
 *  - [Connections]: everything one connects *with*: the OpenFlux account, keys, links and the profiles they make.
 *  - [Nodes]: what one *runs*: the nodes on one's own servers or hosting, made here and changed here.
 *  - [Settings]: how the app behaves, sign-ins to services, the journal, updates.
 */
enum class Section(val title: String) {
    Home("Главная"),
    Connections("Подключения"),
    Nodes("Ноды"),
    Settings("Настройки"),
}

/**
 * A screen. Detail screens ([Profile], [ProfileEdit], [Node], [Server], [ChannelEdit], [CreateChannel], [CreateHosting], [AddConnection], [Account],
 * [Auth]) are not tied to a place: they open on top of the stack of the place the person is in, so "back" returns
 * to where they came from, whichever place that was.
 */
sealed interface Dest {
    /** The first screen of a place. */
    data object Home : Dest
    data object Connections : Dest
    data object Nodes : Dest
    data object Settings : Dest

    // ---- connections ----
    /** The ways to connect: a key or link, an account, a node of one's own, by hand. */
    data object AddConnection : Dest
    /** One profile: what it is made of and what can be done with it. */
    data class Profile(val id: String) : Dest
    /** The fields of a profile; [id] null for a new one. */
    data class ProfileEdit(val id: String?) : Dest
    /** The OpenFlux account: subscription, plans, devices. */
    data object Account : Dest
    /** Signing in to the OpenFlux account: one screen with its own steps (choose, register, sign in, recover). */
    data object Auth : Dest

    // ---- nodes ----
    /**
     * One of the person's servers, by its id ("host:port:user", "+" for a new one): the way in, then every node
     * (channel) on it: change, share, connect to, delete, add.
     */
    data class Server(val id: String) : Dest
    /** Changing a node installed on a server: its transports and mode. */
    data class ChannelEdit(val server: String, val channel: String) : Dest
    /** Making a new node on a connected server. */
    data class CreateChannel(val server: String) : Dest
    /** Making a node on an ordinary PHP hosting (no server of one's own). */
    data object CreateHosting : Dest
    /** A node's profile on this device: where it is, how it connects, what it can be given to. Hosting nodes are run from here. */
    data class Node(val id: String) : Dest

    // ---- settings ----
    data object Logs : Dest
    data object ScriptTransports : Dest

    /** The place whose first screen this is; null for a detail screen. */
    val root: Section?
        get() = when (this) {
            Home -> Section.Home
            Connections -> Section.Connections
            Nodes -> Section.Nodes
            Settings -> Section.Settings
            else -> null
        }
}

/**
 * Where the person is: the place and, in each place, the stack of screens. Pure state, no UI: the shell draws the
 * top of the current place's stack.
 */
class AppNav {
    var section by mutableStateOf(Section.Home)
        private set

    /** Told of every screen taken off a stack, so whatever it held (a wizard's SSH session, a draft) can be let go. */
    var onRemoved: (Dest) -> Unit = {}

    private val stacks: Map<Section, androidx.compose.runtime.snapshots.SnapshotStateList<Dest>> =
        Section.entries.associateWith { mutableStateListOf(rootOf(it)) }

    /** The screens of [section], first to last. */
    fun stack(section: Section): List<Dest> = stacks.getValue(section)

    /** What is on screen now. */
    val top: Dest get() = stacks.getValue(section).last()

    /** The screen under the top one in the current place: where "back" goes, for the header's "← Подключения". */
    val below: Dest? get() = stacks.getValue(section).let { if (it.size > 1) it[it.size - 2] else null }

    val depth: Int get() = stacks.getValue(section).size

    /** True when back has somewhere to go: a screen above the first, or another place than Home. */
    val canGoBack: Boolean get() = depth > 1 || section != Section.Home

    /** A tap on a place's tab: the place itself; tapped again while in it, its first screen. */
    fun select(target: Section) {
        if (target == section) popToRoot() else section = target
    }

    /** Opens [dest] on top of the current place's stack (not twice in a row). */
    fun push(dest: Dest) {
        val root = dest.root
        if (root != null) {
            section = root
            popToRoot()
            return
        }
        val stack = stacks.getValue(section)
        if (stack.last() != dest) stack.add(dest)
    }

    /**
     * Opens [dest] in [target], whatever place the person is in: from Home's "Создать ноду" to the Nodes place, from
     * the captcha's "Войти в Яндекс" to Settings. The place's earlier screens stay below it.
     */
    fun goIn(target: Section, dest: Dest) {
        section = target
        push(dest)
    }

    /** Replaces the top screen (a finished wizard becomes the node it made; signing in becomes the account). */
    fun replaceTop(dest: Dest) {
        val stack = stacks.getValue(section)
        if (stack.size > 1) drop(stack, stack.lastIndex)
        push(dest)
    }

    /** Back one screen; at a place's first screen, to Home; at Home, not handled (the system leaves the app). */
    fun back(): Boolean {
        val stack = stacks.getValue(section)
        return when {
            stack.size > 1 -> { drop(stack, stack.lastIndex); true }
            section != Section.Home -> { section = Section.Home; true }
            else -> false
        }
    }

    /** Back to the current place's first screen. */
    fun popToRoot() {
        val stack = stacks.getValue(section)
        while (stack.size > 1) drop(stack, stack.lastIndex)
    }

    /** Back until [dest] is the top, when it is in the stack. */
    fun popTo(dest: Dest) {
        val stack = stacks.getValue(section)
        val at = stack.indexOf(dest)
        if (at < 0) return
        while (stack.size > at + 1) drop(stack, stack.lastIndex)
    }

    /** Drops every screen that shows profile [id] (it was deleted): no screen is left pointing at nothing. */
    fun forget(id: String) {
        // A first screen shows no profile, so a stack never empties.
        for ((_, stack) in stacks) {
            val gone = stack.filter { it.shows(id) }
            stack.removeAll(gone)
            gone.forEach(onRemoved)
        }
    }

    private fun drop(stack: MutableList<Dest>, index: Int) {
        val gone = stack.removeAt(index)
        onRemoved(gone)
    }

    companion object {
        fun rootOf(section: Section): Dest = when (section) {
            Section.Home -> Dest.Home
            Section.Connections -> Dest.Connections
            Section.Nodes -> Dest.Nodes
            Section.Settings -> Dest.Settings
        }
    }
}

private fun Dest.shows(id: String): Boolean = when (this) {
    is Dest.Profile -> this.id == id
    is Dest.ProfileEdit -> this.id == id
    is Dest.Node -> this.id == id
    else -> false
}
