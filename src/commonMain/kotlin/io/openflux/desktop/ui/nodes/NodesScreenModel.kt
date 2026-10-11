package io.openflux.desktop.ui.nodes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.openflux.desktop.model.PhpMessages
import io.openflux.desktop.model.PhpNodeRef
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.model.managed
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.ui.nav.Dest
import io.openflux.desktop.ui.node.NodeWizardModel
import io.openflux.desktop.ui.node.PhpWizardModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A profile the person runs the node of: made by the wizard, or on a hosting. */
val Profile.ownNode: Boolean get() = !managed && (source == ProfileSource.Node || phpNode != null)

/** The id of the server "root@203.0.113.10:22" as a screen names it. */
fun serverId(server: io.openflux.desktop.model.KnownServer) = "${server.host}:${server.port}:${server.user}"

/** The id of a server not yet typed in. */
const val NEW_SERVER = "+"

/** "203.0.113.10" of a node profile's direct address "203.0.113.10:31337"; empty when it has none. */
val Profile.serverHost: String
    get() = carriers.firstOrNull { it.type == TransportType.DIRECT }?.value?.substringBeforeLast(':', "")?.trim('[', ']').orEmpty()

/**
 * What is done with nodes: made by the wizards, changed on their servers, run on a hosting. A wizard lives as long as
 * its screen is on a stack ([release] is called when the navigation drops it), so a person can leave to read
 * something and come back to the same step.
 */
class NodesScreenModel(private val container: AppContainer) : ScreenModel {
    private val sessions = mutableMapOf<String, NodeWizardModel>()
    private val hostings = mutableMapOf<Dest, PhpWizardModel>()

    /**
     * The session with the server [id] ("host:port:user", [NEW_SERVER] for a new one): it connects over SSH, lists the
     * server's nodes and is the engine of changing and making them. It lives as long as the server's screen is on a
     * stack ([release]), so the way in is typed once.
     */
    fun session(id: String): NodeWizardModel = sessions.getOrPut(id) {
        NodeWizardModel(container, screenModelScope).also { wizard ->
            wizard.managing = true
            container.settings.settings.value.knownServers.firstOrNull { serverId(it) == id }?.let(wizard::useServer)
        }
    }

    /** The wizard making a node on a PHP hosting. */
    fun hostingWizard(dest: Dest): PhpWizardModel = hostings.getOrPut(dest) { PhpWizardModel(container, screenModelScope) }

    /** Lets go of what [dest] held: a server's SSH session, a hosting wizard. A node screen above a server keeps the session. */
    fun release(dest: Dest) {
        when (dest) {
            is Dest.Server -> sessions.remove(dest.id)?.close()
            is Dest.ChannelEdit -> sessions[dest.server]?.backToServer()
            is Dest.CreateChannel -> sessions[dest.server]?.backToServer()
            else -> hostings.remove(dest)?.close()
        }
    }

    override fun onDispose() {
        sessions.values.forEach { it.close() }
        hostings.values.forEach { it.close() }
        sessions.clear()
        hostings.clear()
    }

    // ---- a node on a PHP hosting ----

    /** What the node controls of the shown hosting profile last said, and whether one is running. */
    var nodeMessage by mutableStateOf<String?>(null)
        private set
    var nodeBusy by mutableStateOf(false)
        private set

    fun forgetNodeMessage() {
        nodeMessage = null
    }

    /** Runs the node of a hosting profile (idempotent: one already running is left alone). */
    fun startNode(profile: Profile) = nodeControl(profile, "Запускаю ноду…") { node ->
        val state = container.phpHosting.start(node.siteUrl, node.token, profile.transport.cliName, profile.value.trim(), chain = true)
        if (state.running) "Нода: ${PhpMessages.nodeStatus(state)}" else "Нода не ответила"
    }

    /** Asks the node how it is: running, which generation serves now, when it hands over. */
    fun refreshNode(profile: Profile) = nodeControl(profile, "Спрашиваю ноду…", unreachable = "Состояние ноды неизвестно: хостинг не ответил") { node ->
        "Нода: " + PhpMessages.nodeStatus(container.phpHosting.node(node.siteUrl, node.token, profile.transport.cliName, profile.value.trim()))
    }

    /** Opens the node's control panel (its page in the browser: state, generation, log, Start/Stop). */
    fun openNodePanel(profile: Profile) = nodeControl(profile, "Открываю панель ноды…") { node ->
        container.platform.openUrl(container.phpHosting.page(node.siteUrl, node.token, profile.transport.cliName, profile.value.trim()))
        "" // keep what was shown before
    }

    /** Stops the node on the hosting (the whole chain); a connection through it stops working. */
    fun stopNode(profile: Profile) = nodeControl(profile, "Останавливаю ноду…") { node ->
        container.phpHosting.stop(node.siteUrl, node.token, profile.transport.cliName, profile.value.trim())
        "Нода остановлена"
    }

    private fun nodeControl(
        profile: Profile,
        busyText: String,
        unreachable: String? = null,
        action: suspend (PhpNodeRef) -> String,
    ) {
        val node = profile.phpNode ?: return
        if (nodeBusy) return
        nodeBusy = true
        val before = nodeMessage
        nodeMessage = busyText
        screenModelScope.launch {
            nodeMessage = try {
                action(node).ifEmpty { before }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unreachable ?: e.message ?: "Не удалось связаться с хостингом"
            } finally {
                nodeBusy = false
            }
        }
    }
}
