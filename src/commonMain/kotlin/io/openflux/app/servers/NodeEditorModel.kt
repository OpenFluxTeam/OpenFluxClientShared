package io.openflux.app.servers

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.openflux.app.nav.Dest
import io.openflux.desktop.model.AccountCookies
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.DocumentKind
import io.openflux.desktop.model.InstalledChannel
import io.openflux.desktop.model.KitDelivery
import io.openflux.desktop.model.KitScriptSides
import io.openflux.desktop.model.NewChannel
import io.openflux.desktop.model.NodeKit
import io.openflux.desktop.model.NodeMode
import io.openflux.desktop.model.ProfileTransfer
import io.openflux.desktop.model.installedKit
import io.openflux.desktop.model.nodeTransports
import io.openflux.desktop.model.ownerOfKit
import io.openflux.desktop.model.NodeDocuments
import io.openflux.desktop.model.NodeWizardException
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.model.ExtraTransport
import io.openflux.desktop.model.NodeTransport
import io.openflux.desktop.model.NodeTransports
import io.openflux.desktop.model.ScriptSettingsCodec
import kotlin.io.encoding.Base64
import io.openflux.desktop.service.AccountException
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.service.NodeWizardService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One node on a server, made or changed (spec 5.3): the same editor for both. A new node ([channelId] null) is
 * installed with [install] and then proven by connecting through it; an installed one is read with [load],
 * changed in a draft, and [apply] sends the change to the server (set-transports keeps its key, port and
 * context, so links handed out keep working) and updates its profile on this device.
 *
 * One action at a time; [busy] says what is running, [error] why the last one failed. The draft survives a
 * failure. The node's profile here is found by its key or made from the key the server gives ([adopt]).
 */
@Stable
class NodeEditorModel(
    private val container: AppContainer,
    private val link: ServerLink,
    val serverId: String,
    private val channelId: String?,
    private val scope: CoroutineScope,
    private val verifier: NodeVerifier = NodeVerifier(container),
    private val yandexCookies: () -> String = {
        AccountCookies.header(container.accounts.validSession(AccountKind.Yandex)?.cookies.orEmpty())
    },
    private val mailruCookies: () -> String = {
        AccountCookies.header(container.accounts.validSession(AccountKind.Mailru)?.cookies.orEmpty())
    },
    /** The profile this node is made for, when it exists already: it starts the draft and is kept (not made again) by [install]. */
    private val forProfile: Profile? = null,
) {
    val creating: Boolean get() = channelId == null && installedId == null

    /** Where the node lives; anything but a server is made from a kit and needs no SSH. */
    val placement: NodePlacement = NodePlacement.of(serverId)
    val kit: Boolean get() = placement.kit

    private var installedId: String? = channelId

    var installed by mutableStateOf<InstalledChannel?>(null)
        private set
    var original by mutableStateOf<NodeDraft?>(null)
        private set
    var draft by mutableStateOf(forProfile?.let { NodeDraft.fromProfile(it, wizardScripts()) } ?: NodeDraft(name = defaultName(null)))
        private set

    private var busyText by mutableStateOf<String?>(null)
    /** What is running now, in one line; null when idle. */
    val busy: String? get() = verifier.stage ?: busyText
    var error by mutableStateOf<String?>(null)
        private set
    /** A short note of what just worked ("Применено"). */
    var notice by mutableStateOf<String?>(null)
        private set
    /** Why the new node's check failed; the node stays installed until the person decides. */
    var verifyFailed by mutableStateOf<String?>(null)
        private set
    var verified by mutableStateOf<NodeVerifier.Result?>(null)
        private set
    /** Set when a new node is installed and proven (or kept anyway): the screen becomes this node's. */
    var done by mutableStateOf<Dest.Node?>(null)
        private set
    /** Why the document check from this device was skipped (Yandex asked this device for a captcha). */
    var documentWarning by mutableStateOf<String?>(null)
        private set

    /** The sudo password typed on this screen; the login password is used when the server takes it. */
    var sudoInput by mutableStateOf("")
    val needsSudo: Boolean get() = !kit && link.needsSudo(serverId)

    /** The Yandex sign-in the document was made with, and that document: the sign-in goes to the node only with it. */
    private var pendingCookies = ""
    private var pendingCookiesDoc = ""
    private var candidate: Profile? = null
    private var job: Job? = null

    private val host: String get() = hostOf(serverId)
    /** The server's address, for the node's "address:port". */
    val serverHost: String get() = host

    val changes: Int get() = original?.let { draft.changes(it) } ?: 0
    /** Why "Применить" cannot run now; null when it can. */
    val applyProblem: String? get() = original?.let { draft.applyProblem(it) }
    val hasUnsaved: Boolean get() = changes > 0

    /**
     * Why "Установить" cannot run now; null when it can. A node without SSH has no direct address of its own, so
     * it needs at least one transport to be reached at all.
     */
    val installProblem: String?
        get() = draft.problem() ?: if (kit && !draft.hasTransport) "Включите хотя бы один транспорт: у этой ноды нет прямого адреса" else null

    /** This node's profile on this device: the one with its key, or one dialing its address. */
    val localProfile: Profile?
        get() {
            val info = installed ?: return null
            // A node without SSH is its owner's profile: the one that records its channel.
            if (kit) return container.profiles.profiles.value.firstOrNull { it.kitNode?.channel == info.id }
            val dials = setOf("$host:${info.port}", "[$host]:${info.port}")
            return container.profiles.profiles.value.firstOrNull { p ->
                (info.key.isNotEmpty() && p.secret == info.key) ||
                    (p.secret.isNotBlank() && p.carriers.any { it.type == TransportType.DIRECT && it.value.trim() in dials })
            }
        }

    /** The installed scripts that bring a setup wizard of their own: their two ends are set up together. */
    private fun wizardScripts(): Set<String> = container.scripts.scripts.value.filter { it.setupPage }.map { it.id }.toSet()

    /** What a profile keeps of the client's side of each script it carries, by script id. */
    private fun clientSidesOf(p: Profile?): Map<String, Map<String, String>> =
        p?.carriers.orEmpty().filter { it.type == TransportType.SCRIPT && it.scriptId.isNotEmpty() }.associate { it.scriptId to it.settings }

    /**
     * What a script's node side says that is no secret (MAX: the node's user id): the start of a device's own side
     * when this device did not set the node up and the server keeps only the node's.
     */
    private fun publicOf(id: String, nodeSide: Map<String, String>): Map<String, String> {
        val secrets = container.scripts.byId(id)?.params.orEmpty().filter { it.type == "secret" }.map { it.key }.toSet()
        return nodeSide.filterKeys { it !in secrets }
    }

    /** The draft of an installed node, the client's side of its wizard scripts from [local] (this device's profile of it). */
    private fun draftOf(info: InstalledChannel, name: String, local: Profile?): NodeDraft {
        val d = NodeDraft.of(info, name, clientSidesOf(local), wizardScripts())
        return d.copy(scripts = d.scripts.map { s -> if (s.wizard && s.settings.isEmpty()) s.copy(settings = publicOf(s.id, s.nodeSettings.orEmpty())) else s })
    }

    fun edit(transform: (NodeDraft) -> NodeDraft) {
        draft = transform(draft)
        error = null
        notice = null
    }

    fun discard() {
        original?.let { draft = it }
        error = null
    }

    fun clearNotice() {
        notice = null
    }

    // ---- an installed node ----

    fun load() = run(if (kit) "Читаю ноду…" else "Читаю ноду на сервере…") {
        val id = installedId ?: return@run
        if (kit) {
            val p = container.profiles.profiles.value.firstOrNull { it.kitNode?.channel == id }
                ?: throw NodeWizardException("Профиль этой ноды удалён: ноду больше нечем изменить с этого устройства")
            val info = p.installedKit() ?: throw NodeWizardException("Профиль не описывает ноду")
            installed = info
            val o = draftOf(info, p.name, p)
            original = o
            draft = o
            return@run
        }
        val info = link.call(serverId, retry = true) { showChannel(id, sudo()) }
        installed = info
        val o = draftOf(info, localProfile?.name ?: defaultName(id), localProfile)
        original = o
        draft = o
    }

    fun apply() {
        val o = original ?: return
        val d = draft
        if (kit) return applyKit(o, d)
        if (!d.serverChanged(o)) {
            renameHere(d.name)
            original = o.copy(name = d.name)
            notice = "Сохранено"
            return
        }
        d.applyProblem(o)?.let { error = it; return }
        run("Применяю на сервере…") {
            val id = installedId ?: return@run
            val ready = withRooms(d)
            val transports = withPackages(ready.toTransports())
            val now = link.call(serverId) { setTransports(id, transports, sudo(), cookiesFor(ready), ready.mode) }
            pendingCookies = ""
            installed = now
            refreshProfile(now, ready.name, ready.scripts)
            val fresh = NodeDraft.of(now, ready.name, ready.scripts.associate { it.id to it.settings }, wizardScripts())
                .let { d -> d.copy(scripts = d.scripts.map { s -> if (s.wizard && s.settings.isEmpty()) s.copy(settings = publicOf(s.id, s.nodeSettings.orEmpty())) else s }) }
            original = fresh
            draft = fresh
            notice = "Применено"
        }
    }

    /** Gives the node the Yandex sign-in saved in the app (Settings → Сервисы), for its Yandex document. */
    fun pushYandexSignIn() {
        val info = installed ?: return
        if (kit) { error = NO_SIGN_IN_PUSH; return }
        val cookies = yandexCookies()
        if (!NodeDocuments.signedIn(cookies)) { error = "Сначала войдите в Яндекс: Настройки → Сервисы"; return }
        if (info.transportTypes.none { it == TransportType.VYANDEX || it == TransportType.YANDEX }) {
            error = "У ноды нет транспорта Яндекса"
            return
        }
        run("Передаю вход Яндекса ноде…") {
            installed = pushSignIn(info, cookies)
            recordNodeSignIn(container, serverId, info.id, AccountKind.Yandex)
            notice = "Вход Яндекса передан ноде"
        }
    }

    /**
     * The sign-in to the node's document of its service, merged on the node without a restart (the channel's
     * other document keeps its sign-in). A wizard without pushSignIn (an older core here) sends it the old way.
     */
    private suspend fun pushSignIn(info: InstalledChannel, cookies: String): InstalledChannel = try {
        link.call(serverId) { pushSignIn(info.id, info.transports, sudo(), cookies) }
    } catch (e: NodeWizardException) {
        if (e.message?.contains("не поддерживается") != true) throw e
        link.call(serverId) { setTransports(info.id, info.transports, sudo(), cookies, null) }
    }

    /** Gives the node the Mail.ru sign-in saved in the app (Settings → Сервисы): Mail.ru opens its document only for a signed-in user. */
    fun pushMailruSignIn() {
        val info = installed ?: return
        if (kit) { error = NO_SIGN_IN_PUSH; return }
        val cookies = mailruCookies()
        if (!NodeDocuments.signedInMailru(cookies)) { error = "Сначала войдите в Mail.ru: Настройки → Сервисы"; return }
        if (info.transportTypes.none { it == TransportType.MAILRU }) {
            error = "У ноды нет транспорта Mail.ru"
            return
        }
        run("Передаю вход Mail.ru ноде…") {
            installed = pushSignIn(info, cookies)
            recordNodeSignIn(container, serverId, info.id, AccountKind.Mailru)
            notice = "Вход Mail.ru передан ноде"
        }
    }

    /** Deletes the node from the server, and its profile here (it would only fail to connect). */
    fun remove(onGone: () -> Unit) = run(if (kit) "Убираю ноду…" else "Удаляю ноду с сервера…") {
        val id = installedId ?: return@run
        val local = localProfile
        if (kit) {
            // Nothing of it is on a server this app can reach: only the profile here goes, the machine keeps what it ran.
            local?.let { container.profiles.delete(it.id) }
            return@run onGone()
        }
        link.call(serverId) { remove(id, sudo()) }
        local?.let { container.profiles.delete(it.id) }
        onGone()
    }

    /** This node's profile here, made from the server's key when there is none yet. */
    suspend fun adopt(): Profile {
        localProfile?.let { return it }
        if (kit) throw NodeWizardException("Профиль этой ноды удалён")
        val info = installed ?: throw NodeWizardException("Нода ещё не прочитана")
        if (info.key.isEmpty()) throw NodeWizardException("Сервер не отдал ключ ноды: откройте сервер заново")
        val name = draft.name.trim().ifEmpty { defaultName(info.id) }
        val text = link.call(serverId) { channelShareLink(name, info.key, host, info.port, info.transports, info.context) }
        val sides = draftOf(info, name, null).scripts
        val made = Profile.fromShare(container.shareCodec.decode(text), container.profiles.newId(), container.platform.now(), ProfileSource.Node)
            .copy(icon = "ic_terminal", name = name)
            .withScripts(info.transports, sides)
        container.profiles.upsert(made)
        return made
    }

    /** Connects this device through the node. */
    fun connectHere() = run("Подключаюсь через ноду…") {
        val p = adopt()
        io.openflux.app.shell.connectAsClient(container, p)
        notice = "Подключаюсь через «${p.name}»"
    }

    // ---- a new node ----

    fun install() {
        installProblem?.let { error = it; return }
        if (kit) return createKit()
        run("Готовлю ноду…") {
            val ch = link.call(serverId) { newChannel() }
            val ready = withRooms(draft)
            val transports = withPackages(ready.toTransports())
            busyText = "Спрашиваю сервер, что изменится…"
            val plan = link.call(serverId, retry = true) { plan(ch.id, transports, cookiesFor(ready).isNotEmpty(), ready.autoUpdate, ready.mode) }
            busyText = "Устанавливаю ноду: ядро, настройки, запуск…"
            val signIn = cookiesFor(ready)
            link.call(serverId) { apply(ch, transports, plan.port, ready.autoUpdate, sudo(), signIn, ready.mode) }
            signInKind(signIn)?.let { recordNodeSignIn(container, serverId, ch.id, it) }
            pendingCookies = ""
            installedId = ch.id
            val name = ready.name.trim().ifEmpty { defaultName(ch.id) }
            val text = link.call(serverId) { shareLink(name, ch.key, host, plan.port, transports) }
            val made = Profile.fromShare(container.shareCodec.decode(text), forProfile?.id ?: container.profiles.newId(), forProfile?.createdAt ?: container.platform.now(), ProfileSource.Node)
                .copy(icon = forProfile?.icon ?: "ic_terminal", name = if (forProfile != null) name.ifBlank { forProfile.name } else name)
                .withScripts(transports, ready.scripts)
            val p = if (forProfile != null) made.keepingCarrierChoices(forProfile) else made
            container.profiles.upsert(p)
            container.settings.update { it.copy(selectedProfileId = p.id) }
            candidate = p
            prove(p, transports.firstOrNull()?.let { TransportType.fromCli(it.type) })
        }
    }

    fun retryVerify() {
        val p = candidate ?: return
        run("Проверяю ноду…") { prove(p, null) }
    }

    fun stopWaiting() = verifier.stopWaiting()

    /** Keeps the new node though the check did not pass. */
    fun keepAnyway() {
        val id = installedId ?: return
        verifyFailed = null
        done = Dest.Node(serverId, id)
    }

    /** Removes the new node whose check failed, and its profile. */
    fun removeFailed() = run("Удаляю ноду с сервера…") {
        val id = installedId ?: return@run
        link.call(serverId) { remove(id, sudo()) }
        candidate?.let { container.profiles.delete(it.id) }
        candidate = null
        installedId = null
        verifyFailed = null
    }

    private suspend fun prove(p: Profile, primary: TransportType?) {
        verifyFailed = null
        try {
            val addresses = link.call(serverId) { resolve(host) }
            verified = verifier.verify(p, addresses, primary)
            done = Dest.Node(serverId, installedId ?: return)
        } catch (e: NodeWizardException) {
            verifyFailed = e.message ?: "Проверка не прошла"
        }
    }

    // ---- a script's own wizard ----

    /**
     * Runs the setup wizard of the script [id] (it signs the accounts in and checks them) and takes both sides of what
     * it hands back: the client's into the draft's settings of the script, the node's into its nodeSettings. A script
     * already set up on the node but not here (the server keeps only the node's side) is asked only for this side.
     */
    fun setupScript(id: String) {
        val current = draft.scripts.firstOrNull { it.id == id }
        val primary = primaryKey(id)
        val required = container.scripts.byId(id)?.params.orEmpty().filter { it.required }.map { it.key }
        // The client side is ready when every required parameter is filled: the primary one lives in the value, the rest in settings.
        fun filled(k: String) = (current?.settings?.get(k) ?: if (k == primary) current?.value else null).orEmpty().isNotBlank()
        val clientReady = current != null &&
            (if (required.isEmpty()) current.settings.isNotEmpty() || current.value.isNotBlank() else required.all(::filled))
        // The node's side is there but this device's is not (a node set up elsewhere): the wizard only signs this side in.
        val known = current?.takeIf { !clientReady }?.nodeSettings?.get("uid")?.takeIf { it.isNotBlank() }?.let { mapOf("uid" to it) } ?: emptyMap()
        container.scriptSettings.openSetup(id, known) { answer ->
            edit { d ->
                // The wizard hands the primary parameter (the carrier's value, e.g. the PRUFFME board URL) inside the
                // client/node maps. It belongs in NodeScript.value, not the settings: toTransports builds the node's
                // transport url from the value and withScripts gives the client carrier that same value. Leaving it in
                // settings left both ends with an empty url, so configured() was false and the wizard re-opened at
                // connect time. A script with no profile-scoped primary (e.g. oneme-webrtc, two accounts) keeps its
                // values in settings untouched.
                val value = (primary?.let { answer.client[it] }?.takeIf { it.isNotBlank() } ?: current?.value).orEmpty()
                val clientSettings = primary?.let { answer.client - it } ?: answer.client
                // The node's own primary usually equals value (the shared case above) and is dropped the same way;
                // when an old, not-yet-scoped script's wizard gives the node a DIFFERENT primary (two accounts),
                // toTransports' url is still the client's, so that value must survive in nodeSettings for
                // withPackages to send - dropping it here sent the client's own value to the node instead of the
                // one the wizard signed the node in with.
                val nodeRaw = (answer.node ?: current?.nodeSettings)?.let { ns -> primary?.let { key -> if (ns[key] == value) ns - key else ns } ?: ns }
                // The ends differ only when the node's settings really differ from the client's; a shared-board carrier
                // (pruffme) answers with identical sides and stays non-split.
                val nodeSettings = nodeRaw?.takeIf { it != clientSettings }
                val set = NodeScript(id, value, clientSettings, nodeSettings, wizard = true)
                d.copy(scripts = if (current != null) d.scripts.map { if (it.id == id) set else it } else d.scripts + set)
            }
        }
    }

    // ---- a node without SSH ----

    /** The command for this node, once asked for ([showKit]); null while hidden. */
    var kitShown by mutableStateOf<NodeKit?>(null)
        private set

    fun hideKit() {
        kitShown = null
    }

    private fun primaryKey(id: String) = container.scripts.byId(id)?.primaryParam?.key

    /** Both sides of each script of [scripts]; the primary parameter's key stays out (it is the carrier's value). */
    private fun sidesOf(scripts: List<NodeScript>): Map<String, KitScriptSides> = scripts.associate { s ->
        val primary = primaryKey(s.id)
        s.id to KitScriptSides(s.settings.filterKeys { it != primary }, s.nodeSettings?.filterKeys { it != primary })
    }

    /**
     * Makes the node: the key (the profile's own when it is one a node can use), the kit the core makes of the channel,
     * and the owner's profile of it - made again for [forProfile], which keeps its id, name and icon. Nothing is
     * installed: the node starts when its kit is run, or its profile taken to the device that will be the node.
     */
    private fun createKit() = run("Готовлю ноду…") {
        val old = forProfile
        val minted = container.nodeWizard.newChannel()
        val ch = old?.secret?.takeIf { HEX_KEY.matches(it) }?.let { NewChannel(minted.id, it) } ?: minted
        val ready = withRooms(draft)
        val transports = withPackages(ready.toTransports())
        val kit = container.nodeWizard.kit(ch, transports, ready.autoUpdate, ready.mode)
        val name = ready.name.trim().ifEmpty { defaultName(ch.id) }
        val made = Profile.ownerOfKit(
            id = old?.id ?: container.profiles.newId(),
            name = if (old != null) name.ifBlank { old.name } else name,
            icon = old?.icon ?: "ic_terminal",
            createdAt = old?.createdAt ?: container.platform.now(),
            channel = ch, kit = kit, delivery = placement.delivery ?: throw NodeWizardException("Нода без места"),
            mode = ready.mode, autoUpdate = ready.autoUpdate, scripts = sidesOf(ready.scripts),
        ).let { if (old != null) it.keepingCarrierChoices(old) else it }
        container.profiles.upsert(made)
        container.settings.update { it.copy(selectedProfileId = made.id) }
        installedId = ch.id
        done = Dest.Node(placement.id ?: throw NodeWizardException("Нода без места"), ch.id)
    }

    /**
     * The same channel with the transports of [d]: a new kit (and the node's profile for another device), the
     * channel's key and context as they were - a node that runs the new kit keeps its clients.
     */
    private fun applyKit(o: NodeDraft, d: NodeDraft) {
        val p = localProfile ?: run { error = "Профиль этой ноды удалён"; return }
        val record = p.kitNode ?: return
        if (!d.serverChanged(o)) {
            renameHere(d.name)
            original = o.copy(name = d.name)
            notice = "Сохранено"
            return
        }
        d.applyProblem(o)?.let { error = it; return }
        run("Собираю набор…") {
            val ready = withRooms(d)
            val transports = withPackages(ready.toTransports())
            val ch = NewChannel(record.channel, p.secret)
            val kit = container.nodeWizard.kit(ch, transports, record.autoUpdate, ready.mode)
            val fresh = Profile.ownerOfKit(
                p.id, ready.name.trim().ifEmpty { p.name }, p.icon, p.createdAt, ch,
                kit.copy(context = p.context.ifEmpty { kit.context }), record.delivery, ready.mode, record.autoUpdate, sidesOf(ready.scripts),
            ).keepingCarrierChoices(p)
            container.profiles.upsert(fresh)
            val info = fresh.installedKit() ?: throw NodeWizardException("Профиль не описывает ноду")
            installed = info
            val now = draftOf(info, fresh.name, fresh)
            original = now
            draft = now
            kitShown = null
            notice = when (record.delivery) {
                KitDelivery.Manual -> "Набор обновлён: выполните команду на ноде ещё раз"
                KitDelivery.Device -> "Профиль ноды обновлён: передайте его на устройство ещё раз"
                KitDelivery.Here -> "Применено: перезапустите ноду на этом устройстве"
            }
        }
    }

    /** The command that installs this node (or changes it, when it is on the machine already), as the core renders it now. */
    fun showKit() = run("Собираю команду…") {
        val p = localProfile ?: throw NodeWizardException("Профиль этой ноды удалён")
        val record = p.kitNode ?: throw NodeWizardException("Эта нода не из набора")
        val transports = withPackages(p.nodeTransports())
        kitShown = container.nodeWizard.kit(NewChannel(record.channel, p.secret), transports, record.autoUpdate, NodeMode.of(record.mode))
    }

    /**
     * The node's profile for another device of the person, as a line (QR, link): [ProfileTransfer.Role.Node] for the
     * device that will be the node, [ProfileTransfer.Role.Client] for one that connects through it.
     */
    fun transferLine(role: ProfileTransfer.Role): String? {
        val p = localProfile ?: return null
        val keys = p.carriers.mapNotNull { c -> c.scriptId.takeIf { it.isNotEmpty() }?.let { id -> container.scripts.byId(id)?.let { id to it.pubkeyHex } } }.toMap()
        return ProfileTransfer.encode(p, role, keys)
    }

    // ---- documents ----

    /** Makes the node's Yandex document in the built-in browser (signing in to Yandex there) and checks it. */
    fun createYandexDocument() = run("Открываю Яндекс во встроенном браузере…") {
        val doc = container.nodeWizard.createDocument(NodeDocuments.fileName(draft.name, host, container.platform.now())) { busyText = it }
        if (NodeDocuments.signedIn(doc.cookieHeader)) {
            pendingCookies = doc.cookieHeader
            pendingCookiesDoc = NodeDocuments.clean(doc.url) ?: doc.url
        }
        draft = draft.copy(yandexDoc = doc.url, volga = draft.volga || !draft.yandexDocs)
        checkDocumentNow(doc.url)
    }

    /** Checks a pasted Yandex document the way the node will open it. */
    fun checkDocument() = run("Проверяю документ…") { checkDocumentNow(draft.yandexDoc) }

    private suspend fun checkDocumentNow(url: String) {
        val clean = NodeDocuments.clean(url) ?: throw NodeWizardException("Нужна ссылка на документ Яндекса вида https://docs.yandex.ru/edit/d/…")
        draft = draft.copy(yandexDoc = clean)
        documentWarning = try {
            container.nodeWizard.checkDocument(clean)
            null
        } catch (e: NodeWizardException) {
            if (!e.captcha) throw e
            "Яндекс попросил проверку у этого устройства: документ проверит сама нода"
        }
    }

    fun createMailruDocument() = run("Создаю документ в Облаке Mail…") {
        val url = accountDocument { container.accounts.createDocument(AccountKind.Mailru, it) } ?: return@run
        draft = draft.copy(mailru = true, mailruLink = url)
    }

    fun createBoard() = run("Создаю доску в Яндексе…") {
        val url = accountDocument { container.accounts.createDocument(DocumentKind.YandexBoard, it) } ?: return@run
        draft = draft.copy(board = true, boardLink = url)
    }

    /** The node's Telemost conference: the Telemost page opens in the built-in browser and the person makes it there. */
    fun createTelemost() = run("Открываю Телемост во встроенном браузере…") {
        val url = accountDocument { container.accounts.createDocument(DocumentKind.TelemostConference, it) } ?: return@run
        draft = draft.copy(telemost = true, telemostLink = url)
    }

    /** New Avito Code rooms now (made by the server, as on applying), so their code can be seen, copied or kept. */
    fun createAvitoRooms() = run("Создаю комнаты Avito…") {
        val rooms = host { this.createAvitoRooms() }
        draft = draft.copy(avito = true, avitoRooms = rooms)
    }

    /** New cups.online rooms now, the same way. */
    fun createCupsRooms() = run("Создаю комнаты cups.online…") {
        val rooms = host { this.createCupsRooms() }
        draft = draft.copy(cups = true, cupsRooms = rooms)
    }

    private suspend fun accountDocument(make: suspend (String) -> String): String? = try {
        make(NodeDocuments.fileName(draft.name, host, container.platform.now()))
    } catch (e: AccountException) {
        if (e.cancelled) null else throw NodeWizardException(e.message ?: "Не получилось создать документ")
    }

    // ---- helpers ----

    /** Runs [block] on the wizard: through the SSH connection to the server, or at once for a node without one. */
    private suspend fun <T> host(block: suspend NodeWizardService.() -> T): T =
        if (kit) container.nodeWizard.block() else link.call(serverId, block = block)

    private fun sudo(): String {
        if (sudoInput.isNotEmpty()) link.setSudo(serverId, sudoInput)
        val s = link.sudo(serverId)
        if (needsSudo && s.isEmpty()) throw NodeWizardException("Введите пароль sudo", sudo = true)
        return s
    }

    private suspend fun withRooms(d: NodeDraft): NodeDraft {
        var r = d
        if (r.cups && r.cupsRooms.isEmpty()) {
            busyText = "Создаю комнаты cups.online…"
            val rooms = host { this.createCupsRooms() }
            r = r.copy(cupsRooms = rooms)
            draft = draft.copy(cupsRooms = rooms)
        }
        if (r.avito && r.avitoRooms.isEmpty()) {
            busyText = "Создаю комнаты Avito…"
            val rooms = host { this.createAvitoRooms() }
            r = r.copy(avitoRooms = rooms)
            draft = draft.copy(avitoRooms = rooms)
        }
        return r
    }

    /**
     * The JS transports as the node gets them: this device's package (it goes to the server each time, so the node
     * runs the version the app has), the author's key, and the primary parameter folded into the settings line the
     * way the app runs it (CoreConfig). If the last setup page submission included a node half (the {client, node}
     * pattern), it is merged into that transport's settings so the exit starts with them.
     */
    private fun withPackages(ts: List<NodeTransport>): List<NodeTransport> {
        val nodeParams = container.connection.consumeNodeParams()
        return ts.map { t ->
            if (t.type != TransportType.SCRIPT.cliName) return@map t
            val installed = container.scripts.byId(t.script)?.takeIf(NodeTransports::nodeCanRun)
                ?: throw NodeWizardException("JS-транспорт ${t.script} не установлен на этом устройстве как подписанный пакет .flux")
            val bytes = container.scripts.packageBytes(t.script)?.first
                ?: throw NodeWizardException("Пакет JS-транспорта ${installed.name.ifBlank { t.script }} не читается: переустановите его")
            // The node half a live setup page left behind (consumeNodeParams) is for a script the editor's own wizard
            // did not set up: a script that was has its node side in the draft already.
            val wizardSetUp = draft.scripts.firstOrNull { it.id == t.script }?.split == true
            // A split script's own wizard already gave each end its right value (settings/forNode via toTransports);
            // url is only ever the client's half of a shared primary, so once the two ends differ it must not
            // overwrite what the node side already carries in params.
            var settings = ScriptSettingsCodec.withPrimary(ScriptSettingsCodec.decode(t.params), installed.primaryParam?.key, if (wizardSetUp) "" else t.url)
            if (nodeParams != null && nodeParams.first == t.script && !wizardSetUp) {
                settings = settings + nodeParams.second
            }
            t.copy(
                pubkey = installed.pubkeyHex,
                pkg = Base64.encode(bytes),
                params = if (settings.isEmpty()) "" else ScriptSettingsCodec.encode(settings),
            )
        }
    }

    /**
     * The link cannot carry a JS transport: the node's profile gets them here. A transport whose ends differ gets the
     * CLIENT's side from [sides] (the node's side stays on the server); one with the same settings at both ends gets
     * what the node runs it with.
     */
    private fun Profile.withScripts(ts: List<NodeTransport>, sides: List<NodeScript>): Profile {
        val scripts = ts.filter { it.type == TransportType.SCRIPT.cliName && it.script.isNotEmpty() }
        if (scripts.isEmpty()) return this
        val installed = scripts.associate { it.script to container.scripts.byId(it.script) }
        val extras = scripts.map { t ->
            val primary = installed[t.script]?.primaryParam?.key
            val side = sides.firstOrNull { it.id == t.script }
            val settings = if (side != null && (side.split || side.wizard)) side.settings else ScriptSettingsCodec.decode(t.params)
            ExtraTransport(
                TransportType.SCRIPT, t.url, priority = SCRIPT_PRIORITY, scriptId = t.script,
                settings = settings.filterKeys { it != primary },
            )
        }
        return copy(session = true, extras = this.extras.filterNot { it.type == TransportType.SCRIPT } + extras)
    }

    /** What the person chose for a carrier of the profile this node was made for (its network) survives the rebuild. */
    private fun Profile.keepingCarrierChoices(old: Profile): Profile {
        fun net(c: ExtraTransport) = old.carriers.firstOrNull { it.type == c.type && it.scriptId == c.scriptId && it.value == c.value }?.network ?: c.network
        val main = carriers.first()
        return copy(network = net(main), extras = extras.map { it.copy(network = net(it)) })
    }

    /**
     * The sign-in goes along with a Yandex document (only the one it made) or, failing that, with a Mail.ru
     * document: the app's saved Mail.ru account, since Mail.ru no longer opens a public document anonymously.
     */
    private fun cookiesFor(d: NodeDraft): String {
        if (d.needsYandexDoc && pendingCookiesDoc.isNotEmpty() && d.yandexDoc == pendingCookiesDoc) return pendingCookies
        return if (d.mailru && d.mailruLink.isNotBlank()) mailruCookies().takeIf(NodeDocuments::signedInMailru).orEmpty() else ""
    }

    /** Whose sign-in a Cookie header is, as the core routes it: Session_id is Yandex's, Mpop Mail.ru's. */
    private fun signInKind(cookies: String): AccountKind? = when {
        cookies.isEmpty() -> null
        NodeDocuments.signedIn(cookies) -> AccountKind.Yandex
        NodeDocuments.signedInMailru(cookies) -> AccountKind.Mailru
        else -> null
    }

    private fun renameHere(name: String) {
        val p = localProfile ?: return
        if (name.isNotBlank() && p.name != name.trim()) container.profiles.upsert(p.copy(name = name.trim()))
    }

    private suspend fun refreshProfile(now: InstalledChannel, name: String, sides: List<NodeScript>) {
        val p = localProfile ?: return
        val key = now.key.ifEmpty { p.secret }
        val text = link.call(serverId) { channelShareLink(name.trim().ifEmpty { p.name }, key, host, now.port, now.transports, now.context) }
        val fresh = Profile.fromShare(container.shareCodec.decode(text), p.id, p.createdAt, p.source)
            .copy(icon = p.icon, name = name.trim().ifEmpty { p.name })
            .withScripts(now.transports, sides)
            .keepingCarrierChoices(p)
        container.profiles.upsert(fresh)
    }

    private fun defaultName(id: String?): String {
        val base = if (kit) "Нода (${placement.short})" else "Нода $host"
        val taken = container.profiles.profiles.value.any { it.name == base }
        return if (taken && id != null) "$base · $id" else base
    }

    private fun hostOf(id: String): String =
        container.settings.settings.value.knownServers.firstOrNull { serverIdOf(it) == id }?.host ?: id.substringBefore(':')

    private fun run(message: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        busyText = message
        error = null
        notice = null
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: NodeWizardException) {
                error = if (e.sudo && e.message != "Введите пароль sudo") "sudo не принял пароль" else e.message
            } catch (e: Exception) {
                error = e.message ?: "Не получилось"
            } finally {
                busyText = null
            }
        }
    }

    private companion object {
        /** The node's priority for its JS transports (provision.transportPriority). */
        const val SCRIPT_PRIORITY = 60
        val HEX_KEY = Regex("[0-9a-f]{64}")
        const val NO_SIGN_IN_PUSH = "Вход нельзя передать ноде, до которой у приложения нет SSH: он уходит вместе с новым набором для ноды"
    }
}
