package io.openflux.desktop.service

import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.AccountCookies
import io.openflux.desktop.model.AccountSession
import io.openflux.desktop.model.AuthStatus
import io.openflux.desktop.model.InstalledChannel
import io.openflux.desktop.model.LogLevel
import io.openflux.desktop.model.NodeSignInDecision
import io.openflux.desktop.model.NodeSignInPolicy
import io.openflux.desktop.model.NodeSignInRecord
import io.openflux.desktop.model.NodeTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How the keeper reaches a node: over the owner's own SSH access, never through the tunnel. */
interface NodeSignInAccess {
    /** Whether [serverId] can be reached now without asking the person (access open this run, or a saved password). */
    suspend fun reachable(serverId: String): Boolean
    suspend fun show(serverId: String, channel: String): InstalledChannel
    suspend fun push(serverId: String, channel: String, transports: List<NodeTransport>, cookieHeader: String): InstalledChannel
}

/** Where the keeper keeps its records ([io.openflux.desktop.model.AppSettings.nodeSignIns]). */
interface NodeSignInLedger {
    val enabled: Boolean
    fun all(): Map<String, NodeSignInRecord>
    fun put(key: String, record: NodeSignInRecord)
}

/** What the owner sees for one node and service. */
data class NodeSignInStatus(val text: String, val problem: Boolean, val at: Long)

/**
 * Keeps the owner's nodes signed in to Yandex and Mail.ru while the app runs ([run]): every
 * [NodeSignInPolicy.PERIOD_MS], and at once when a saved sign-in changes, it reads each node that got a sign-in
 * from this app (node-install.sh show, over SSH) and gives it the account's sign-in again when
 * [NodeSignInPolicy.decide] says so. A push goes only over the owner's SSH access to the server; the node merges it
 * into that one document without a restart. Cookie values are never logged.
 */
class NodeSignInKeeper(
    private val session: (AccountKind) -> AccountSession?,
    private val recheck: suspend (AccountKind) -> AuthStatus,
    private val access: NodeSignInAccess,
    private val ledger: NodeSignInLedger,
    private val now: () -> Long,
    private val log: (String, LogLevel) -> Unit,
) {
    private val lock = Mutex()
    private val _status = MutableStateFlow<Map<String, NodeSignInStatus>>(emptyMap())
    val status: StateFlow<Map<String, NodeSignInStatus>> = _status.asStateFlow()

    /** Records that the owner gave [channel] on [serverId] this sign-in by hand (or the wizard did): keep it fresh from now on. */
    fun recordPush(serverId: String, channel: String, kind: AccountKind, session: AccountSession) {
        val key = NodeSignInPolicy.key(serverId, channel, kind)
        ledger.put(key, NodeSignInPolicy.pushed(NodeSignInRecord(), kind, session, now()).copy(repeats = 0))
        note(key, "вход в ${kind.label} передан ноде", false)
    }

    /** Runs until cancelled: once soon after start, then every period and whenever [sessions] change. */
    suspend fun run(sessions: Flow<Map<AccountKind, AccountSession>>, firstDelayMs: Long = 60_000L) = coroutineScope {
        launch {
            sessions.map { s -> s.mapValues { (k, v) -> NodeSignInPolicy.fingerprint(k, v.cookies) } }
                .distinctUntilChanged().drop(1).collect { runCatching { tick() } }
        }
        delay(firstDelayMs)
        while (true) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log("Вход в ноды: ${e.message}", LogLevel.Warning)
            }
            delay(NodeSignInPolicy.PERIOD_MS)
        }
    }

    /** One look at every node the app keeps signed in. */
    suspend fun tick() = lock.withLock {
        if (!ledger.enabled) return@withLock
        val byNode = ledger.all().keys.mapNotNull { parse(it) }.groupBy { it.first to it.second }
        for ((node, kinds) in byNode) {
            val (serverId, channel) = node
            val keys = kinds.map { NodeSignInPolicy.key(serverId, channel, it.third) }
            val reachable = try {
                access.reachable(serverId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                false
            }
            if (!reachable) {
                keys.forEach { note(it, "нет сохранённого доступа к серверу: откройте его, чтобы нода получала вход", true) }
                continue
            }
            var info = try {
                access.show(serverId, channel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                keys.forEach { k -> failed(k, "не удалось прочитать ноду: ${e.message}") }
                continue
            }
            for ((_, _, kind) in kinds) {
                info = keep(serverId, channel, kind, info)
            }
        }
    }

    private suspend fun keep(serverId: String, channel: String, kind: AccountKind, info: InstalledChannel): InstalledChannel {
        val key = NodeSignInPolicy.key(serverId, channel, kind)
        var record = ledger.all()[key] ?: return info
        var decision = NodeSignInPolicy.decide(kind, session(kind), info, record, now())
        if (decision == NodeSignInDecision.Recheck) {
            val status = recheck(kind)
            decision = if (status is AuthStatus.SignedIn) {
                NodeSignInPolicy.decide(kind, session(kind), info, record, now()).let {
                    if (it == NodeSignInDecision.Recheck) NodeSignInDecision.Skip("вход в ${kind.label} не проверился") else it
                }
            } else {
                NodeSignInDecision.Skip("вход в ${kind.label} не подтвердился: проверьте его в Настройках → Сервисы", problem = true)
            }
        }
        when (decision) {
            is NodeSignInDecision.Skip -> {
                val docs = NodeSignInPolicy.documents(info, kind)
                if (info.signin.none { it.doc in docs }) {
                    record = NodeSignInPolicy.settled(record)
                    ledger.put(key, record)
                }
                if (decision.problem || _status.value[key]?.problem == true) note(key, decision.why, decision.problem)
                return info
            }
            is NodeSignInDecision.Push -> {
                val s = session(kind) ?: return info
                // The decision checked the sign-in is valid and of this service; the core routes it to that service's document only.
                if (!AccountCookies.signedIn(kind, s.cookies)) return info
                log("${decision.why.replaceFirstChar { it.uppercase() }} — передаю ноде $channel", LogLevel.Info)
                return try {
                    val fresh = access.push(serverId, channel, info.transports, AccountCookies.header(s.cookies))
                    ledger.put(key, NodeSignInPolicy.pushed(record, kind, s, now()))
                    note(key, "вход в ${kind.label} обновлён на ноде: ${decision.why}", false)
                    log("Вход в ${kind.label} передан ноде $channel", LogLevel.Success)
                    fresh
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failed(key, "не удалось передать вход в ${kind.label}: ${e.message}")
                    info
                }
            }
            NodeSignInDecision.Recheck -> return info
        }
    }

    private fun failed(key: String, text: String) {
        ledger.all()[key]?.let { ledger.put(key, NodeSignInPolicy.failed(it, now())) }
        note(key, text, true)
        log("Вход в ноды: $text", LogLevel.Warning)
    }

    private fun note(key: String, text: String, problem: Boolean) {
        _status.update { it + (key to NodeSignInStatus(text, problem, now())) }
    }

    private fun parse(key: String): Triple<String, String, AccountKind>? {
        val parts = key.split('|')
        if (parts.size != 3) return null
        val kind = AccountKind.entries.firstOrNull { it.name == parts[2] } ?: return null
        return Triple(parts[0], parts[1], kind)
    }
}
