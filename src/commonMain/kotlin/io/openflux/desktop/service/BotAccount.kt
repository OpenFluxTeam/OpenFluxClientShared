package io.openflux.desktop.service

import io.openflux.desktop.model.BotBanner
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotInvoice
import io.openflux.desktop.model.BotLink
import io.openflux.desktop.model.BotPlan
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSessionItem
import io.openflux.desktop.model.BotState
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.model.OpenFluxBot
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ShareLinkCodec
import io.openflux.desktop.model.ShareLinkException
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A bot connection link resolved: the profile's name and its `openflux://` link. */
data class BotConnection(val name: String, val link: String) {
    override fun toString() = "BotConnection($name)"
}

/** The bot's API (api.openflux.dev). Every call fails with [BotException]. */
interface BotApi {
    suspend fun subscription(token: String): BotSubscription
    suspend fun signOut(token: String)
    /** The connection behind a short link; not_found once it ended or was replaced. */
    suspend fun resolve(connectToken: String): BotConnection

    /** Trades the sign-in page's one-time code for our session (POST /api/v1/auth/app/exchange). */
    suspend fun exchange(code: String, verifier: String, redirectUri: String): BotSession =
        throw BotException("unsupported", "Вход здесь не поддерживается")
    /** The account's connections. */
    suspend fun sessions(token: String): List<BotSessionItem> = throw BotException("unsupported", "Бот OpenFlux здесь не поддерживается")
    /** Makes a connection in a free automatic slot; "forbidden" when there is none. */
    suspend fun createSession(token: String): BotSessionItem = throw BotException("unsupported", "Бот OpenFlux здесь не поддерживается")

    /** The terms the person (or, without [token], anybody) may buy. */
    suspend fun plans(token: String?): List<BotPlan> = throw BotException("unsupported", "Покупка здесь не поддерживается")
    /**
     * An invoice for a NEW slot on the term [termCode]. A repeat with the same [idempotencyKey] (8–64 of `A-Z a-z 0-9 - _`)
     * returns the same invoice, so a call lost on the way is repeated with the key it had.
     */
    suspend fun createInvoice(token: String, termCode: String, idempotencyKey: String): BotInvoice =
        throw BotException("unsupported", "Покупка здесь не поддерживается")
    /** One of the person's own invoices; not_found for anyone else's. */
    suspend fun invoice(token: String, id: String): BotInvoice = throw BotException("unsupported", "Покупка здесь не поддерживается")
    /** The service's banners for everyone. */
    suspend fun banners(): List<BotBanner> = throw BotException("unsupported", "Сервис OpenFlux здесь не поддерживается")
}

object NoBotApi : BotApi {
    private fun unsupported(): Nothing = throw BotException("unsupported", "Бот OpenFlux здесь не поддерживается")
    override suspend fun subscription(token: String) = unsupported()
    override suspend fun signOut(token: String) = unsupported()
    override suspend fun resolve(connectToken: String) = unsupported()
}

interface BotRepository {
    val state: StateFlow<BotState>
    fun save(state: BotState)
}

class InMemoryBotRepository : BotRepository {
    private val flow = MutableStateFlow(BotState())
    override val state: StateFlow<BotState> = flow.asStateFlow()
    override fun save(state: BotState) { flow.value = state }
}

/**
 * The OpenFlux bot on this device, until its API takes apps directly: people buy in the bot, which gives each
 * connection a short link. The app turns such a link into a profile ([ProfileSource.Bot], read-only like a
 * subscription's), keeps it current and says when the bot no longer serves it (the subscription ended, or the
 * connection was replaced: the bot then gives a new link). A sign-in (email or Telegram, on the OpenFlux page) shows the subscription itself.
 */
class BotAccountManager(
    private val api: BotApi,
    private val repo: BotRepository,
    private val profiles: ProfileRepository,
    private val codec: ShareLinkCodec,
    private val platform: PlatformServices,
    private val connection: ConnectionService,
    private val accounts: Accounts,
) {
    val state: StateFlow<BotState> get() = repo.state
    private val lock = Mutex()

    /** Asks the background loop to look again now (a sign-in just finished). */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    // ---- signing in ----

    /** The session for a code the sign-in page gave; [completeLogin] then keeps it. */
    suspend fun exchange(code: String, verifier: String, redirectUri: String): BotSession = api.exchange(code, verifier, redirectUri)

    /** Keeps the session a finished Telegram sign-in gave, then brings the account's subscription and connection in. */
    suspend fun completeLogin(session: BotSession) {
        lock.withLock { repo.save(repo.state.value.copy(session = session, lastError = null, connectionNote = null)) }
        try {
            refresh()
        } finally {
            // The background loop may be asleep for hours (signed out when it began): wake it, also when this was cancelled.
            wake.trySend(Unit)
        }
    }

    /** Signs out of this device: the account's connection goes with it, links added by hand stay. */
    suspend fun signOut() {
        val s = repo.state.value.session ?: return
        runCatching { api.signOut(s.token) }
        lock.withLock {
            val mine = repo.state.value.links.filter { it.account }
            stopIfRunning(mine.map { it.profileId }.toSet())
            mine.forEach { profiles.delete(it.profileId) }
            repo.save(
                repo.state.value.copy(
                    session = null, subscription = null, links = repo.state.value.links.filterNot { it.account },
                    preparing = false, connectionNote = null,
                ),
            )
        }
    }

    // ---- connection links ----

    /** Turns a bot link into a profile (or updates the one it made before); returns the profile. */
    suspend fun addLink(text: String): Profile {
        val token = OpenFluxBot.connectToken(text) ?: throw BotException("bad_link", "Это не ссылка бота OpenFlux")
        return lock.withLock { addLinkLocked(token, account = false) }
    }

    private suspend fun addLinkLocked(token: String, account: Boolean): Profile {
        val conn = try {
            api.resolve(token)
        } catch (e: BotException) {
            if (e.notFound) throw BotException("not_found", GONE) else throw e
        }
        val known = repo.state.value.links.firstOrNull { it.token == token }
        val p = profileOf(conn, known?.profileId ?: profiles.newId(), known)
        profiles.upsert(p)
        val link = BotLink(token, p.id, conn.name, platform.now(), account = account || known?.account == true)
        saveLinks(repo.state.value.links.filterNot { it.token == token } + link)
        return p
    }

    /** Forgets a connection: its profile goes too. */
    suspend fun removeLink(token: String) = lock.withLock { removeLinkLocked(token) }

    private fun removeLinkLocked(token: String) {
        val link = repo.state.value.links.firstOrNull { it.token == token } ?: return
        stopIfRunning(setOf(link.profileId))
        profiles.delete(link.profileId)
        saveLinks(repo.state.value.links.filterNot { it.token == token })
    }

    /** The link of a profile the bot gave, for "Открыть в боте" and the card; null for any other profile. */
    fun linkOf(profileId: String): BotLink? = repo.state.value.links.firstOrNull { it.profileId == profileId }

    // ---- keeping it current ----

    /** Asks about every connection and, signed in, the subscription. Failures stay on the state, not thrown. */
    suspend fun refresh() {
        _refreshing.value = true
        try {
            lock.withLock {
                try {
                    refreshLocked()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A link the core cannot read, an answer of a shape we do not know: said to the person, never a crash.
                    repo.save(repo.state.value.copy(preparing = false, lastError = unreadable(e)))
                }
            }
        } finally {
            _refreshing.value = false
        }
    }

    private suspend fun refreshLocked() {
        var error: String? = null
        val links = repo.state.value.links.map { link ->
            try {
                val conn = api.resolve(link.token)
                val old = profiles.profiles.value.firstOrNull { it.id == link.profileId }
                profiles.upsert(profileOf(conn, link.profileId, link, old))
                link.copy(name = conn.name, checkedAt = platform.now(), gone = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotException) {
                if (e.notFound) {
                    stopIfRunning(setOf(link.profileId))
                    link.copy(checkedAt = platform.now(), gone = true)
                } else {
                    error = e.message
                    link
                }
            } catch (e: ShareLinkException) {
                // One link this app's core cannot read must not stop the others: the reason is said, the rest go on.
                error = unreadable(e)
                link
            }
        }
        var s = repo.state.value.copy(links = links)
        val session = s.session
        if (session != null) {
            s = try {
                s.copy(subscription = api.subscription(session.token))
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotException) {
                if (e.unauthorized) s.copy(session = null, subscription = null) else s.also { error = e.message }
            }
        }
        repo.save(s.copy(lastError = error))
        ensureConnectionLocked()
    }

    /**
     * The signed-in account's connections: every one of the bot that has a link, running or paused, becomes a
     * profile (a paused connection keeps its slot, key and carriers, and works again the moment it is resumed in
     * the bot); a new one is asked for only when none runs and none is being made.
     */
    private suspend fun ensureConnectionLocked() {
        val session = repo.state.value.session ?: return
        // A connection the bot ended or replaced goes; below asks for the current ones.
        for (l in repo.state.value.links.filter { it.account && it.gone }) removeLinkLocked(l.token)

        val items = try {
            api.sessions(session.token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BotException) {
            return ensureFailed(e)
        }
        // A link of the account whose connection is over (or replaced) goes. One that is merely restarting stays,
        // so a tunnel in use is never cut by a status that passes.
        val alive = items.filter { it.connectToken.isNotEmpty() && it.status !in ENDED }.map { it.connectToken }.toSet()
        for (l in repo.state.value.links.filter { it.account && it.token !in alive }) removeLinkLocked(l.token)

        var waiting = false
        var unreadable: String? = null
        for (item in items.filter { it.connectToken.isNotEmpty() && (it.status == RUNNING || it.status == PAUSED) }) {
            if (repo.state.value.links.any { it.token == item.connectToken }) continue
            try {
                addLinkLocked(item.connectToken, account = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotException) {
                if (e.notFound) waiting = true else return ensureFailed(e) // not found: the link is not ready yet
            } catch (e: ShareLinkException) {
                unreadable = unreadable(e) // one the core cannot read must not hide the others
            }
        }
        // A connection that runs and whose link is held: the account has one (a running one whose link is not ready yet is still "being made").
        val running = items.any { item -> item.ready && repo.state.value.links.any { it.token == item.connectToken } }
        val paused = items.any { it.status == PAUSED }
        when {
            running -> {
                repo.save(repo.state.value.copy(preparing = false, connectionNote = null, lastError = unreadable ?: repo.state.value.lastError))
                return
            }
            // A connection the core cannot read: said with its reason; nothing is waited for and nothing is made over it.
            !waiting && unreadable != null -> {
                repo.save(repo.state.value.copy(preparing = false, connectionNote = null, lastError = unreadable))
                return
            }
            // Something is being made, or is up but without its link yet: never make another over it.
            waiting || items.any { it.status !in ENDED && it.status != PAUSED } -> {
                repo.save(repo.state.value.copy(preparing = true, connectionNote = null, lastError = unreadable ?: repo.state.value.lastError))
                return
            }
        }
        // Nothing runs and nothing is being made: a new connection is asked for (an automatic slot brings a paused
        // one back; otherwise the service says why not).
        val made = try {
            api.createSession(session.token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BotException) {
            if (paused && e.forbidden) {
                return repo.save(repo.state.value.copy(preparing = false, connectionNote = PAUSED_NOTE, lastError = unreadable ?: repo.state.value.lastError))
            }
            return ensureFailed(e)
        }
        if (!made.ready) {
            repo.save(repo.state.value.copy(preparing = true, connectionNote = null))
            return
        }
        if (repo.state.value.links.none { it.token == made.connectToken }) {
            try {
                addLinkLocked(made.connectToken, account = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotException) {
                return ensureFailed(e)
            } catch (e: ShareLinkException) {
                return repo.save(repo.state.value.copy(preparing = false, lastError = unreadable(e)))
            }
        }
        repo.save(repo.state.value.copy(preparing = false, connectionNote = null))
    }
    private fun ensureFailed(e: BotException) {
        val s = repo.state.value
        repo.save(
            when {
                e.unauthorized -> s.copy(session = null, subscription = null, preparing = false)
                // The service's own words (why there is no slot) when it gave them, else the usual sentence.
                e.forbidden -> s.copy(preparing = false, connectionNote = e.message?.takeIf { it.isNotBlank() && it != HttpForbidden } ?: NO_SLOT)
                e.notFound -> s.copy(preparing = true, connectionNote = null) // the link is not ready yet
                else -> s.copy(preparing = false, lastError = e.message)
            },
        )
    }

    /** Refreshes when the app starts and then every few hours (every few seconds while the connection is being made). */
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                val s = repo.state.value
                if (s.session != null || s.links.isNotEmpty()) refresh()
                wake.tryReceive() // a wake-up that came while refreshing is answered by that refresh
                withTimeoutOrNull(if (repo.state.value.preparing) PREPARE_MS else REFRESH_MS) { wake.receive() }
            }
        }
    }

    private suspend fun profileOf(conn: BotConnection, id: String, link: BotLink?, old: Profile? = null): Profile {
        val config = codec.decode(conn.link)
        val fresh = Profile.fromShare(config, id, platform.now(), ProfileSource.Bot)
            .copy(name = conn.name.ifBlank { link?.name.orEmpty() }.ifBlank { "OpenFlux" }, icon = "ic_public")
        return if (old == null) fresh else fresh.copy(createdAt = old.createdAt)
    }

    private fun stopIfRunning(ids: Set<String>) {
        val running = connection.state.value
        if (running.isActive && running.profile?.id in ids) connection.disconnect()
    }

    private fun saveLinks(links: List<BotLink>) = repo.save(repo.state.value.copy(links = links))

    /** What the person is told when a connection of the bot cannot be read: the reason when the core gave one. */
    private fun unreadable(e: Exception): String =
        if (e is ShareLinkException) "Не удалось прочитать подключение от бота: ${e.message}" else UNREADABLE

    companion object {
        /** The words an HTTP 403 carries when the service said nothing of its own; not shown as a reason. */
        const val HttpForbidden = "Нет доступа: подписка не действует или нет свободного места"
        private const val RUNNING = "running"
        private const val PAUSED = "paused"
        const val PAUSED_NOTE = "Подключения приостановлены в боте и заняли все места. Они уже добавлены здесь: возобновите одно в боте, в «☁️ Мои подключения», и оно заработает само"
        const val GONE = "Бот больше не обслуживает это подключение: подписка закончилась или подключение заменили. Возьмите новую ссылку в @${OpenFluxBot.USERNAME}"
        const val UNREADABLE = "Не удалось прочитать подключение от бота: обновите приложение"
        /** A connection in one of these statuses is over; any other (running, creating, starting…) is still the account's. */
        private val ENDED = setOf("stopped", "failed", "error", "deleted", "expired")
        const val NO_SLOT ="Подключения нет: подписка не действует или все места заняты. Продлите подписку в @${OpenFluxBot.USERNAME}"
        private const val PREPARE_MS = 15 * 1000L
        private const val REFRESH_MS = 3 * 60 * 60 * 1000L
    }
}
