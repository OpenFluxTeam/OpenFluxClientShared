package io.openflux.desktop.service

import io.openflux.desktop.model.AccountInfo
import io.openflux.desktop.model.AuthResult
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.OrderInfo
import io.openflux.desktop.model.PlansResult
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ServiceEndpoint
import io.openflux.desktop.model.SubStatus
import io.openflux.desktop.model.SubscriptionDocument
import io.openflux.desktop.model.SubscriptionException
import io.openflux.desktop.model.SubscriptionInvite
import io.openflux.desktop.model.SubscriptionKind
import io.openflux.desktop.model.SubscriptionState
import io.openflux.desktop.model.ShareLinkCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The subscription service's API (docs/subscriptions.ru.md). Every call fails
 * with [SubscriptionException]. The platform implements it with HTTPS and the
 * certificate pin.
 */
interface SubscriptionApi {
    suspend fun plans(ep: ServiceEndpoint): PlansResult
    suspend fun register(ep: ServiceEndpoint, login: String, password: String, deviceName: String): AuthResult
    suspend fun login(ep: ServiceEndpoint, login: String, password: String, deviceName: String, replaceDeviceId: String? = null): AuthResult
    suspend fun recover(ep: ServiceEndpoint, login: String, recoveryKey: String, newPassword: String, deviceName: String): AuthResult
    suspend fun account(ep: ServiceEndpoint, token: String): AccountInfo
    suspend fun document(ep: ServiceEndpoint, token: String): SubscriptionDocument
    /** A subscription key without an account: the document at [url]. */
    suspend fun keyDocument(ep: ServiceEndpoint, url: String): SubscriptionDocument
    suspend fun startTrial(ep: ServiceEndpoint, token: String): AccountInfo
    suspend fun checkout(ep: ServiceEndpoint, token: String, planId: String): OrderInfo
    suspend fun order(ep: ServiceEndpoint, token: String, orderId: String): OrderInfo
    suspend fun changePassword(ep: ServiceEndpoint, token: String, password: String, newPassword: String): AccountInfo
    suspend fun logout(ep: ServiceEndpoint, token: String)
    suspend fun removeDevice(ep: ServiceEndpoint, token: String, deviceId: String): AccountInfo
    suspend fun deleteAccount(ep: ServiceEndpoint, token: String, password: String)
}

/** Where the subscription is kept between runs. */
interface SubscriptionRepository {
    val state: StateFlow<SubscriptionState?>
    fun save(state: SubscriptionState?)
}

class InMemorySubscriptionRepository : SubscriptionRepository {
    private val flow = MutableStateFlow<SubscriptionState?>(null)
    override val state: StateFlow<SubscriptionState?> = flow.asStateFlow()
    override fun save(state: SubscriptionState?) { flow.value = state }
}

/** For a platform without the service client: every call says so. */
object NoSubscriptionApi : SubscriptionApi {
    private fun unsupported(): Nothing = throw SubscriptionException("unsupported", "Подписка здесь не поддерживается")
    override suspend fun plans(ep: ServiceEndpoint) = unsupported()
    override suspend fun register(ep: ServiceEndpoint, login: String, password: String, deviceName: String) = unsupported()
    override suspend fun login(ep: ServiceEndpoint, login: String, password: String, deviceName: String, replaceDeviceId: String?) = unsupported()
    override suspend fun recover(ep: ServiceEndpoint, login: String, recoveryKey: String, newPassword: String, deviceName: String) = unsupported()
    override suspend fun account(ep: ServiceEndpoint, token: String) = unsupported()
    override suspend fun document(ep: ServiceEndpoint, token: String) = unsupported()
    override suspend fun keyDocument(ep: ServiceEndpoint, url: String) = unsupported()
    override suspend fun startTrial(ep: ServiceEndpoint, token: String) = unsupported()
    override suspend fun checkout(ep: ServiceEndpoint, token: String, planId: String) = unsupported()
    override suspend fun order(ep: ServiceEndpoint, token: String, orderId: String) = unsupported()
    override suspend fun changePassword(ep: ServiceEndpoint, token: String, password: String, newPassword: String) = unsupported()
    override suspend fun logout(ep: ServiceEndpoint, token: String) = unsupported()
    override suspend fun removeDevice(ep: ServiceEndpoint, token: String, deviceId: String) = unsupported()
    override suspend fun deleteAccount(ep: ServiceEndpoint, token: String, password: String) = unsupported()
}

/** What a pasted key shows before it is added. */
data class KeyPreview(val invite: SubscriptionInvite, val document: SubscriptionDocument)

/**
 * The app's subscription: signing in, the document refreshed in the
 * background, and the profiles that come with it. The profiles of a
 * subscription are the app's own (source [ProfileSource.Subscription]): it
 * adds, updates and removes them as the service's document says.
 */
class SubscriptionManager(
    private val api: SubscriptionApi,
    private val repo: SubscriptionRepository,
    private val profiles: ProfileRepository,
    private val codec: ShareLinkCodec,
    private val platform: PlatformServices,
    private val connection: ConnectionService,
    private val defaultEndpoint: ServiceEndpoint,
) {
    val state: StateFlow<SubscriptionState?> get() = repo.state
    val endpoint: ServiceEndpoint get() = state.value?.endpoint ?: defaultEndpoint
    private val lock = Mutex()

    /** The refresh running now, for the card's spinner. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Plans for sale; asked from the service when the screen needs them. */
    suspend fun plans(): PlansResult = api.plans(endpoint)

    // ---- signing in ----

    suspend fun register(login: String, password: String): AuthResult = signedIn(api.register(endpoint, login.trim(), password, platform.deviceName))

    suspend fun login(login: String, password: String, replaceDeviceId: String? = null): AuthResult =
        signedIn(api.login(endpoint, login.trim(), password, platform.deviceName, replaceDeviceId))

    suspend fun recover(login: String, recoveryKey: String, newPassword: String): AuthResult =
        signedIn(api.recover(endpoint, login.trim(), recoveryKey.trim(), newPassword, platform.deviceName))

    private suspend fun signedIn(auth: AuthResult): AuthResult = lock.withLock {
        val ep = endpoint
        // A key's profiles and another account's go; the new account's come with the document.
        val state = SubscriptionState(
            endpoint = ep, kind = SubscriptionKind.Account, deviceToken = auth.deviceToken, deviceId = auth.deviceId,
            account = auth.account, status = auth.account.status, expires = auth.account.expires, support = auth.account.support,
        )
        repo.save(state)
        runCatching { fetchAndApply(state) }.onFailure { if (it is CancellationException) throw it }
        auth
    }

    // ---- a key without an account ----

    /** Asks the service what [text] is a key to, without keeping anything. */
    suspend fun previewKey(text: String): KeyPreview {
        val invite = SubscriptionInvite.parse(text) ?: throw SubscriptionException("bad_invite", "Это не ключ подписки")
        return KeyPreview(invite, api.keyDocument(invite.endpoint, invite.url))
    }

    suspend fun addKey(preview: KeyPreview): Unit = lock.withLock {
        val state = SubscriptionState(endpoint = preview.invite.endpoint, kind = SubscriptionKind.Key, keyUrl = preview.invite.url)
        repo.save(state)
        applyDocument(state, preview.document)
    }

    // ---- the document ----

    /** Refreshes now (the card's button, the app starting); a failure is kept for the card, not thrown. */
    suspend fun refresh(): Boolean {
        val state = state.value ?: return false
        _refreshing.value = true
        return try {
            lock.withLock { refreshLocked(state) }
        } finally {
            _refreshing.value = false
        }
    }

    private suspend fun refreshLocked(state: SubscriptionState): Boolean = try {
        fetchAndApply(state)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: SubscriptionException) {
        when {
            // Signed out elsewhere, or the password changed: sign in again.
            e.unauthorized -> dropSession()
            else -> repo.save(repo.state.value?.copy(lastError = e.message))
        }
        false
    }

    private suspend fun fetchAndApply(state: SubscriptionState) {
        if (state.kind == SubscriptionKind.Key) {
            applyDocument(state, api.keyDocument(state.endpoint, state.keyUrl))
            return
        }
        val doc = api.document(state.endpoint, state.deviceToken)
        // The account (devices, plan) comes along; its failure does not spoil the document.
        val account = runCatching { api.account(state.endpoint, state.deviceToken) }.getOrNull()
        applyDocument(state.copy(account = account ?: state.account), doc)
    }

    /**
     * Makes the profiles and the card say what [doc] says: new locations come,
     * known ones get their new key and documents, gone ones go, and a document
     * that is not active empties the list.
     */
    private suspend fun applyDocument(state: SubscriptionState, doc: SubscriptionDocument) {
        val wanted = LinkedHashMap<String, Profile>()
        if (doc.status == SubStatus.ACTIVE) {
            for (p in doc.profiles) {
                val id = profileId(p.id)
                val config = try {
                    codec.decode(p.link)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue // a link this build cannot read is skipped, the others still work
                }
                val fresh = Profile.fromShare(config, id, platform.now(), ProfileSource.Subscription).copy(name = p.name, icon = "ic_public")
                val old = profiles.profiles.value.firstOrNull { it.id == id }
                wanted[id] = if (old == null) fresh else fresh.copy(createdAt = old.createdAt)
            }
        }
        for (profile in wanted.values) profiles.upsert(profile)
        val gone = profiles.profiles.value.filter { it.source == ProfileSource.Subscription && it.id !in wanted }
        val running = connection.state.value
        if (running.isActive && (running.profile?.id in gone.map { it.id } || (doc.status != SubStatus.ACTIVE && running.profile?.source == ProfileSource.Subscription))) {
            connection.disconnect()
        }
        gone.forEach { profiles.delete(it.id) }
        repo.save(
            state.copy(
                title = doc.title.ifBlank { state.title }, status = doc.status, expires = doc.expires, support = doc.support.ifBlank { state.support },
                announce = doc.announce, updateHours = doc.updateHours.coerceIn(1, 168), lastRefreshed = platform.now(), lastError = null,
            ),
        )
    }

    private fun profileId(remote: String) = "sub-$remote"

    /** The token is no longer good: the device is signed out, the subscription's profiles go. */
    private fun dropSession() {
        val running = connection.state.value
        if (running.isActive && running.profile?.source == ProfileSource.Subscription) connection.disconnect()
        profiles.profiles.value.filter { it.source == ProfileSource.Subscription }.forEach { profiles.delete(it.id) }
        repo.save(null)
    }

    // ---- the account ----

    suspend fun startTrial(): AccountInfo = authed { token -> api.startTrial(endpoint, token) }.also { refresh() }

    suspend fun checkout(planId: String): OrderInfo = authed { token -> api.checkout(endpoint, token, planId) }

    /** The order's state; a paid one refreshes the subscription. */
    suspend fun order(orderId: String): OrderInfo = authed { token -> api.order(endpoint, token, orderId) }.also { if (it.paid) refresh() }

    suspend fun changePassword(password: String, newPassword: String): AccountInfo =
        authed { token -> api.changePassword(endpoint, token, password, newPassword) }.also { setAccount(it) }

    suspend fun removeDevice(deviceId: String): AccountInfo =
        authed { token -> api.removeDevice(endpoint, token, deviceId) }.also { setAccount(it) }

    /** Leaves this device: the service forgets it, the profiles of the subscription go. */
    suspend fun signOut() = lock.withLock {
        val s = repo.state.value ?: return@withLock
        if (s.kind == SubscriptionKind.Account) runCatching { api.logout(s.endpoint, s.deviceToken) }
        dropSession()
    }

    suspend fun deleteAccount(password: String) {
        authed { token -> api.deleteAccount(endpoint, token, password) }
        lock.withLock { dropSession() }
    }

    /** Closes the owner's message so it is not shown again. */
    fun dismissAnnounce() {
        val s = repo.state.value ?: return
        repo.save(s.copy(announceSeen = s.announce))
    }

    private fun setAccount(account: AccountInfo) {
        val s = repo.state.value ?: return
        repo.save(s.copy(account = account, status = account.status, expires = account.expires))
    }

    private suspend fun <T> authed(call: suspend (String) -> T): T {
        val s = repo.state.value
        if (s == null || s.kind != SubscriptionKind.Account) throw SubscriptionException("unauthorized", "Войдите в аккаунт.")
        return try {
            call(s.deviceToken)
        } catch (e: SubscriptionException) {
            if (e.unauthorized) lock.withLock { dropSession() }
            throw e
        }
    }

    // ---- in the background ----

    /** Refreshes when the app starts, then whenever the service's `updateHours` have passed. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            var first = true
            while (true) {
                val s = state.value
                val due = s != null && (first || platform.now() - s.lastRefreshed >= s.updateHours * 3_600_000L)
                if (due) refresh()
                first = false
                delay(CHECK_EVERY_MS)
            }
        }
    }

    private companion object {
        const val CHECK_EVERY_MS = 5 * 60 * 1000L
    }
}
