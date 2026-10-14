package io.openflux.desktop.service

import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotLink
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSessionItem
import io.openflux.desktop.model.BotState
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.model.LoginPoll
import io.openflux.desktop.model.LoginStart
import io.openflux.desktop.model.OpenFluxBot
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ShareLinkCodec
import io.openflux.desktop.model.TelegramUser
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A bot connection link resolved: the profile's name and its `openflux://` link. */
data class BotConnection(val name: String, val link: String) {
    override fun toString() = "BotConnection($name)"
}

/** The bot's API (api.openflux.dev). Every call fails with [BotException]. */
interface BotApi {
    /** Signs in with a user Telegram signed for the bot; registers them on first use. */
    suspend fun signIn(user: TelegramUser): BotSession
    suspend fun subscription(token: String): BotSubscription
    suspend fun signOut(token: String)
    /** The connection behind a short link; not_found once it ended or was replaced. */
    suspend fun resolve(connectToken: String): BotConnection

    /** Begins a Telegram sign-in: the link that opens the bot, and the nonce to ask about. */
    suspend fun startLogin(): LoginStart = throw BotException("unsupported", "Бот OpenFlux здесь не поддерживается")
    suspend fun pollLogin(nonce: String): LoginPoll = throw BotException("unsupported", "Бот OpenFlux здесь не поддерживается")
    /** The account's connections. */
    suspend fun sessions(token: String): List<BotSessionItem> = throw BotException("unsupported", "Бот OpenFlux здесь не поддерживается")
    /** Makes a connection in a free automatic slot; "forbidden" when there is none. */
    suspend fun createSession(token: String): BotSessionItem = throw BotException("unsupported", "Бот OpenFlux здесь не поддерживается")
}

object NoBotApi : BotApi {
    private fun unsupported(): Nothing = throw BotException("unsupported", "Бот OpenFlux здесь не поддерживается")
    override suspend fun signIn(user: TelegramUser) = unsupported()
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
 * connection was replaced: the bot then gives a new link). A Telegram sign-in shows the subscription itself.
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

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    // ---- signing in ----

    /** Telegram's sign-in for the bot in the built-in browser, then the bot's session. Registers on first use. */
    suspend fun signIn(): BotSession {
        val user = accounts.telegramSignIn(OpenFluxBot.signInUrl, OpenFluxBot.RETURN_TO) { OpenFluxBot.authResult(it) }
        val session = api.signIn(user)
        lock.withLock { repo.save(repo.state.value.copy(session = session, lastError = null)) }
        refresh()
        return session
    }

    suspend fun startLogin(): LoginStart = api.startLogin()

    suspend fun pollLogin(nonce: String): LoginPoll = api.pollLogin(nonce)

    /** Keeps the session a finished Telegram sign-in gave, then brings the account's subscription and connection in. */
    suspend fun completeLogin(session: BotSession) {
        lock.withLock { repo.save(repo.state.value.copy(session = session, lastError = null, connectionNote = null)) }
        refresh()
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
            lock.withLock { refreshLocked() }
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

    /** The signed-in account's connection: the running one of the bot, or a new one in a free slot. */
    private suspend fun ensureConnectionLocked() {
        val session = repo.state.value.session ?: return
        // A connection the bot ended or replaced goes; below asks for the current one.
        for (l in repo.state.value.links.filter { it.account && it.gone }) removeLinkLocked(l.token)

        val items = try {
            api.sessions(session.token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BotException) {
            return ensureFailed(e)
        }
        val ready = items.firstOrNull { it.ready }
        val item = when {
            ready != null -> ready
            items.any { it.status == "creating" } -> null
            else -> try {
                api.createSession(session.token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotException) {
                return ensureFailed(e)
            }
        }
        if (item == null || !item.ready) {
            repo.save(repo.state.value.copy(preparing = true, connectionNote = null))
            return
        }
        val current = repo.state.value.links.firstOrNull { it.account }
        if (current != null && current.token != item.connectToken) removeLinkLocked(current.token)
        if (repo.state.value.links.none { it.token == item.connectToken }) {
            try {
                addLinkLocked(item.connectToken, account = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotException) {
                return ensureFailed(e)
            }
        }
        repo.save(repo.state.value.copy(preparing = false, connectionNote = null))
    }

    private fun ensureFailed(e: BotException) {
        val s = repo.state.value
        repo.save(
            when {
                e.unauthorized -> s.copy(session = null, subscription = null, preparing = false)
                e.forbidden -> s.copy(preparing = false, connectionNote = NO_SLOT)
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
                delay(if (repo.state.value.preparing) PREPARE_MS else REFRESH_MS)
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

    companion object {
        const val GONE = "Бот больше не обслуживает это подключение: подписка закончилась или подключение заменили. Возьмите новую ссылку в @${OpenFluxBot.USERNAME}"
        const val NO_SLOT = "Подключения нет: подписка не действует или все места заняты. Продлите подписку в @${OpenFluxBot.USERNAME}"
        private const val PREPARE_MS = 15 * 1000L
        private const val REFRESH_MS = 3 * 60 * 60 * 1000L
    }
}
