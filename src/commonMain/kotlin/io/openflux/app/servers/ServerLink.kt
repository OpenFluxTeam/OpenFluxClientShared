package io.openflux.app.servers

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.openflux.desktop.model.InstalledChannel
import io.openflux.desktop.model.KnownServer
import io.openflux.desktop.model.NodeCoreSource
import io.openflux.desktop.model.NodeUpgrade
import io.openflux.desktop.model.NodeServers
import io.openflux.desktop.model.NodeWizardException
import io.openflux.desktop.model.ServerProbe
import io.openflux.desktop.model.SshTarget
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.service.NodeWizardService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A server's id in the app: "host:port:user". */
fun serverIdOf(host: String, port: Int, user: String) = "${host.trim()}:$port:${user.trim()}"
fun serverIdOf(server: KnownServer) = serverIdOf(server.host, server.port, server.user)

/** A server key to confirm before going on: new, or changed since last time ([mismatch]). */
data class HostKeyPrompt(val serverId: String, val fingerprint: String, val mismatch: Boolean)

/**
 * The app's one SSH connection to the person's servers (the core's node wizard holds one at a time).
 * It remembers which server it is open to and, before every call, opens the right one: a call for server B
 * while A is open goes to B (the old UI sent it through A). A connection quiet for a minute is opened again
 * first; a read call that finds it dropped connects and tries once more.
 *
 * The way in (password, key, sudo password) stays in memory only, for this run of the app, and is dropped by
 * [closeAccess], [forget] and [close]. The settings keep only the server's address and its key fingerprint.
 */
@Stable
class ServerLink(private val container: AppContainer) {
    private val service: NodeWizardService = container.nodeWizard
    private val settings = container.settings
    private val lock = Mutex()

    private class Access(val target: SshTarget, val source: NodeCoreSource, val remember: Boolean = false)

    private val access = mutableStateMapOf<String, Access>()
    private val sudo = mutableStateMapOf<String, String>()
    private val probes = mutableStateMapOf<String, ServerProbe>()

    /** The server the connection is open to now, if any. */
    var openTo by mutableStateOf<String?>(null)
        private set
    private var lastReply = 0L

    var hostKeyPrompt by mutableStateOf<HostKeyPrompt?>(null)
        private set
    private var pending: Access? = null

    fun hasAccess(serverId: String): Boolean = serverId in access

    /** What the server said about itself when last connected to. */
    fun probe(serverId: String): ServerProbe? = probes[serverId]

    /** The server wants a sudo password (the login is not root and sudo asks for one). */
    fun needsSudo(serverId: String): Boolean = probes[serverId]?.sudo == "password"

    fun sudo(serverId: String): String = sudo[serverId].orEmpty()

    fun setSudo(serverId: String, password: String) {
        sudo[serverId] = password
    }

    /**
     * Connects to [target] and keeps the way in for later calls; returns the server's id. A key not seen before
     * (or changed) fails with [hostKeyPrompt] set: [trust] goes on with it.
     */
    suspend fun connect(target: SshTarget, source: NodeCoreSource = NodeCoreSource.Core, remember: Boolean = false): String {
        val id = serverIdOf(target.host, target.port, target.user)
        val known = settings.settings.value.knownHostKeys[NodeServers.hostKeyId(target.host, target.port)].orEmpty()
        val a = Access(target.copy(host = target.host.trim(), user = target.user.trim(), hostKey = target.hostKey.ifEmpty { known }), source, remember)
        lock.withLock { open(id, a) }
        return id
    }

    /** Trusts the key [hostKeyPrompt] showed and connects with it. */
    suspend fun trust(): String {
        val prompt = hostKeyPrompt ?: throw NodeWizardException("Нечего подтверждать")
        val a = pending ?: throw NodeWizardException("Нечего подтверждать")
        hostKeyPrompt = null
        pending = null
        val t = a.target
        settings.update { s -> s.copy(knownHostKeys = s.knownHostKeys + (NodeServers.hostKeyId(t.host, t.port) to prompt.fingerprint)) }
        val trusted = Access(t.copy(hostKey = prompt.fingerprint), a.source, a.remember)
        lock.withLock { open(prompt.serverId, trusted) }
        return prompt.serverId
    }

    fun dismissHostKey() {
        hostKeyPrompt = null
        pending = null
    }

    /**
     * Runs [block] on server [serverId]. [retry]: a read that may be repeated safely, tried once more after
     * connecting again when the connection turned out dropped.
     */
    suspend fun <T> call(serverId: String, retry: Boolean = false, block: suspend NodeWizardService.() -> T): T = lock.withLock {
        val a = access[serverId] ?: throw NodeWizardException("Нет доступа к серверу: войдите заново")
        if (openTo != serverId || container.platform.now() - lastReply > IDLE_MS) open(serverId, a)
        val result = try {
            service.block()
        } catch (e: NodeWizardException) {
            if (!retry || e.sudo || e.captcha || e.hostKey != null) throw e
            open(serverId, a)
            service.block()
        }
        lastReply = container.platform.now()
        result
    }

    /** The nodes on [serverId], by port; reading each needs root, so the sudo password when the server asks for one. */
    suspend fun channels(serverId: String): List<InstalledChannel> {
        val ids = probes[serverId]?.channels.orEmpty()
        if (ids.isNotEmpty() && needsSudo(serverId) && sudo(serverId).isEmpty()) throw NodeWizardException("Введите пароль sudo", sudo = true)
        return ids.map { id -> call(serverId, retry = true) { showChannel(id, sudo(serverId)) } }.sortedBy { it.port }
    }

    /** Moves the nodes on [serverId] to the core this app pins; asks for the sudo password the way [channels] does. */
    suspend fun upgrade(serverId: String): NodeUpgrade {
        if (needsSudo(serverId) && sudo(serverId).isEmpty()) throw NodeWizardException("Введите пароль sudo", sudo = true)
        return call(serverId) { upgrade(sudo(serverId)) }
    }

    /** Connects again to read what is on the server now. */
    suspend fun refresh(serverId: String) {
        val a = access[serverId] ?: throw NodeWizardException("Нет доступа к серверу: войдите заново")
        lock.withLock { open(serverId, a) }
    }

    /** Drops the way in to [serverId]; the server stays in the list. */
    fun closeAccess(serverId: String) {
        access.remove(serverId)
        sudo.remove(serverId)
        if (openTo == serverId) {
            service.close()
            openTo = null
        }
    }

    /** Removes [serverId] from the person's servers (its key fingerprint stays: a changed key is still noticed). */
    fun forget(serverId: String) {
        closeAccess(serverId)
        container.platform.deleteSecret(secretKey(serverId))
        probes.remove(serverId)
        settings.update { s -> s.copy(knownServers = s.knownServers.filterNot { serverIdOf(it) == serverId }) }
    }

    fun close() {
        access.clear()
        sudo.clear()
        service.close()
        openTo = null
    }

    private suspend fun open(id: String, a: Access) {
        openTo = null
        val probe = try {
            service.connect(a.target, a.source)
        } catch (e: NodeWizardException) {
            val fingerprint = e.hostKey
            if (fingerprint != null && (e.trust || e.mismatch)) {
                pending = a
                hostKeyPrompt = HostKeyPrompt(id, fingerprint, e.mismatch)
            }
            throw e
        }
        openTo = id
        lastReply = container.platform.now()
        val fresh = access[id] !== a
        access[id] = a
        probes[id] = probe
        val t = a.target
        if (probe.sudo == "password" && sudo[id].isNullOrEmpty() && t.password.isNotEmpty()) sudo[id] = t.password
        // The password goes to the system's secret store only after the server took it, and only when asked; a later
        // reopening of the same way in does not write it again.
        val saved = if (fresh) keepPassword(id, t.password, a.remember) else hasSavedPassword(id)
        settings.update { s -> s.copy(knownServers = NodeServers.remember(s.knownServers, KnownServer(t.host, t.port, t.user, saved))) }
    }

    /** Whether a password for [serverId] sits in the system's secret store (the person ticked "Запомнить пароль"). */
    fun hasSavedPassword(serverId: String): Boolean =
        container.platform.secretStoreSupported && settings.settings.value.knownServers.any { serverIdOf(it) == serverId && it.rememberPassword }

    /** Forgets the saved password of [serverId]; the server stays in the list. */
    fun forgetPassword(serverId: String) {
        container.platform.deleteSecret(secretKey(serverId))
        settings.update { s -> s.copy(knownServers = s.knownServers.map { if (serverIdOf(it) == serverId) it.copy(rememberPassword = false) else it }) }
    }

    /**
     * Connects to [serverId] with its saved password; false when none is saved. A password the server no longer takes
     * is deleted at once and the person is asked for it again; other failures leave it for the next try.
     */
    suspend fun connectSaved(serverId: String): Boolean {
        if (!hasSavedPassword(serverId)) return false
        val known = settings.settings.value.knownServers.first { serverIdOf(it) == serverId }
        val password = withContext(Dispatchers.Default) { container.platform.loadSecret(secretKey(serverId)) }
        if (password.isNullOrEmpty()) {
            forgetPassword(serverId)
            return false
        }
        try {
            connect(SshTarget(known.host, known.port, known.user, password = password), remember = true)
        } catch (e: NodeWizardException) {
            if (isRejectedLogin(e)) {
                forgetPassword(serverId)
                throw NodeWizardException("Сохранённый пароль не подошёл и удалён: введите пароль заново")
            }
            throw e
        }
        return true
    }

    private fun keepPassword(serverId: String, password: String, remember: Boolean): Boolean {
        val platform = container.platform
        if (remember && password.isNotEmpty() && platform.secretStoreSupported && platform.saveSecret(secretKey(serverId), password)) return true
        platform.deleteSecret(secretKey(serverId))
        return false
    }

    private fun secretKey(serverId: String) = "ssh:$serverId"

    companion object {
        /** A connection quiet for longer is opened again before the next call. */
        const val IDLE_MS = 60_000L

        /** The server refused the login, password or key (not the sudo password, not a host key to confirm). */
        fun isRejectedLogin(e: NodeWizardException): Boolean =
            !e.sudo && e.hostKey == null && e.message.orEmpty().let { "не принял" in it && "sudo" !in it }
    }
}
