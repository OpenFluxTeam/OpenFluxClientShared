package io.openflux.app.servers

import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.InstalledChannel
import io.openflux.desktop.model.NodeSignInPolicy
import io.openflux.desktop.model.NodeSignInRecord
import io.openflux.desktop.model.NodeTransport
import io.openflux.desktop.model.NodeWizardException
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.service.NodeSignInAccess
import io.openflux.desktop.service.NodeSignInKeeper
import io.openflux.desktop.service.NodeSignInLedger
import io.openflux.desktop.service.SettingsRepository
import kotlinx.coroutines.CancellationException

/** The keeper's way to a node: the owner's SSH access that [ServerLink] holds, opened with a saved password when none is open. */
class ServerLinkSignInAccess(private val link: ServerLink) : NodeSignInAccess {
    override suspend fun reachable(serverId: String): Boolean {
        if (!link.hasAccess(serverId)) {
            val hadPrompt = link.hostKeyPrompt != null
            val opened = try {
                link.connectSaved(serverId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NodeWizardException) {
                // A changed server key is for the person to look at when they open the server, not a dialog out of nowhere.
                if (!hadPrompt && e.hostKey != null) link.dismissHostKey()
                false
            }
            if (!opened) return false
        }
        return !(link.needsSudo(serverId) && link.sudo(serverId).isEmpty())
    }

    override suspend fun show(serverId: String, channel: String): InstalledChannel =
        link.call(serverId, retry = true) { showChannel(channel, link.sudo(serverId)) }

    override suspend fun push(serverId: String, channel: String, transports: List<NodeTransport>, cookieHeader: String): InstalledChannel =
        link.call(serverId) { pushSignIn(channel, transports, link.sudo(serverId), cookieHeader) }
}

/** The keeper's records in the settings (fingerprints and times only). */
class SettingsSignInLedger(private val settings: SettingsRepository) : NodeSignInLedger {
    override val enabled: Boolean get() = settings.settings.value.keepNodeSignIns && settings.settings.value.useAccountSessions
    override fun all(): Map<String, NodeSignInRecord> = settings.settings.value.nodeSignIns
    override fun put(key: String, record: NodeSignInRecord) = settings.update { it.copy(nodeSignIns = it.nodeSignIns + (key to record)) }
}

fun nodeSignInKeeper(container: AppContainer, link: ServerLink): NodeSignInKeeper = NodeSignInKeeper(
    session = { container.accounts.validSession(it) },
    recheck = { container.accounts.check(it) },
    access = ServerLinkSignInAccess(link),
    ledger = SettingsSignInLedger(container.settings),
    now = { container.platform.now() },
    log = { text, level -> container.nodeWizard.note(text, level) },
)

/** The owner gave [channel] on [serverId] the saved [kind] sign-in: the app keeps it fresh from now on. */
fun recordNodeSignIn(container: AppContainer, serverId: String, channel: String, kind: AccountKind) {
    val session = container.accounts.validSession(kind) ?: return
    if (NodeSignInPolicy.fingerprint(kind, session.cookies).isEmpty()) return
    SettingsSignInLedger(container.settings).put(
        NodeSignInPolicy.key(serverId, channel, kind),
        NodeSignInPolicy.pushed(NodeSignInRecord(), kind, session, container.platform.now()).copy(repeats = 0),
    )
}
