package io.openflux.desktop.ui.node

import io.openflux.desktop.model.DocumentKind
import io.openflux.desktop.service.AccountException
import io.openflux.desktop.model.AccountKind
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.ExitAddress
import io.openflux.desktop.model.InstalledChannel
import io.openflux.desktop.model.KnownServer
import io.openflux.desktop.model.LogLevel
import io.openflux.desktop.model.NewChannel
import io.openflux.desktop.model.NodeCoreSource
import io.openflux.desktop.model.NodeDocuments
import io.openflux.desktop.model.NodePlan
import io.openflux.desktop.model.NodeTransport
import io.openflux.desktop.model.NodeTransports
import io.openflux.desktop.model.NodeServers
import io.openflux.desktop.model.NodeWizardException
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ServerProbe
import io.openflux.desktop.model.SshTarget
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.profile
import io.openflux.desktop.service.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class WizardStep(val number: Int) { Server(1), Document(2), Plan(3), Verify(4), Done(4) }

/** A server key to confirm: new ([mismatch] false) or changed. */
data class HostKeyPrompt(val fingerprint: String, val mismatch: Boolean)

/**
 * "Своя нода": installs a new, independent channel on the user's VDS and
 * hands back a verified profile, like the Android NodeWizardActivity. Steps:
 * SSH to the server, the channel's transports (any of a Yandex document, a
 * Mail.ru document and cups.online rooms, direct always as the backup),
 * what will change on the server, install, then a real connection through
 * the new node. Running it
 * again on the same server adds another channel next to the existing ones.
 *
 * The SSH password, private key and sudo password live only in this object
 * for the length of the wizard; the Yandex login (cookies from the built-in
 * browser) only until install.
 */
@Stable
class NodeWizardModel(private val container: AppContainer, private val scope: CoroutineScope) {
    private val service = container.nodeWizard
    private val settings = container.settings
    private val connection = container.connection

    var step by mutableStateOf(WizardStep.Server)
        private set
    /** What a running call is doing; null when idle. */
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)

    // Step 1: server.
    var host by mutableStateOf("")
    var port by mutableStateOf("22")
    var user by mutableStateOf("root")
    var useKey by mutableStateOf(false)
    var password by mutableStateOf("")
    var privateKey by mutableStateOf("")
    var passphrase by mutableStateOf("")
    /** Whose core the server gets and then follows with its updater. */
    var nodeCore by mutableStateOf(NodeCoreSource.Fork)
    var hostKeyPrompt by mutableStateOf<HostKeyPrompt?>(null)
        private set
    var probe by mutableStateOf<ServerProbe?>(null)
        private set
    /** The server the wizard is connected to, to reconnect after SSH drops. */
    private var target: SshTarget? = null
    /** When the server last answered: SSH may drop while the user signs in to Yandex. */
    private var lastServerReply = 0L

    // Step 2: transports and the Yandex document.
    var channel by mutableStateOf<NewChannel?>(null)
        private set
    var name by mutableStateOf("")
    /** The carriers besides direct, which every channel has as the backup. */
    var useVolga by mutableStateOf(true)
    /** The Yandex document again, through the older Yandex Docs carrier. */
    var useYandexDocs by mutableStateOf(false)
    var useMailru by mutableStateOf(false)
    var useBoards by mutableStateOf(false)
    var useCups by mutableStateOf(false)
    var mailruInput by mutableStateOf("")
    var boardInput by mutableStateOf("")
    /** The cups.online rooms made for this channel, kept if the user goes back. */
    private var cupsRooms = ""
    var documentInput by mutableStateOf("")
    var documentUrl by mutableStateOf("")
        private set
    /** Why the node, not this computer, will check the document. */
    var documentWarning by mutableStateOf("")
        private set
    /** Progress of the built-in browser while the document is being made. */
    var documentProgress by mutableStateOf<String?>(null)
        private set
    /** The Yandex sign-in for the node, dropped once it is installed. */
    private var yandexCookies = ""
    /** The document the sign-in created; another document gets none. */
    private var cookiesDocument = ""
    /** The node will get the Yandex sign-in: there is one and the channel has a Yandex document. */
    val nodeSignedIn: Boolean get() = withCookies
    /** The Yandex page to show while the document is being made. */
    val documentPage get() = service.documentPage

    // Step 3: plan.
    var plan by mutableStateOf<NodePlan?>(null)
        private set
    var sudoPassword by mutableStateOf("")
    val needsSudoPassword: Boolean get() = probe?.sudo == "password"
    /** The server's core updater: one timer for every channel there. */
    var autoUpdate by mutableStateOf(true)
        private set
    /** The carriers the plan was made for, primary first. */
    var transports by mutableStateOf<List<NodeTransport>>(emptyList())
        private set
    val transportTypes: List<TransportType> get() = transports.mapNotNull { TransportType.fromCli(it.type) }

    // Step 4: verification and the result.
    var installed by mutableStateOf(false)
        private set
    var shareLink by mutableStateOf("")
        private set
    var profile by mutableStateOf<Profile?>(null)
        private set
    var verifiedIp by mutableStateOf("")
        private set
    /**
     * The new node answered the Session (its key and document are right),
     * though this device could not ask where the traffic leaves: Android
     * keeps the app out of its own VPN.
     */
    var sessionProven by mutableStateOf(false)
        private set
    var primaryUp by mutableStateOf(false)
        private set
    var verifyFailed by mutableStateOf<String?>(null)
        private set
    var saved by mutableStateOf(false)
        private set
    private var stopWaitingForPrimary = false

    // Changing the documents of a channel already on the server.
    /** The installed channel whose documents are being changed; null while a new one is made. */
    var editing by mutableStateOf<InstalledChannel?>(null)
        private set
    /** [editing]'s saved profile on this device (it holds the key), to give it the new documents. */
    private var editingProfile: Profile? = null
    /** This device has [editing]'s profile, so the wizard can check the channel and update the profile. */
    val editingHasProfile: Boolean get() = editingProfile != null
    /** A failed check may remove only a channel this wizard made, never one it was asked to change. */
    val canRemoveChannel: Boolean get() = editing == null
    /** The channel the result is about, for the done step: the one changed, or the one installed. */
    val resultChannel: String
        get() = editing?.let { "${it.id}, порт ${it.port}" } ?: "${channel?.id.orEmpty()}, порт ${plan?.port ?: 0}"

    private var job: Job? = null

    val knownServers: List<KnownServer> get() = settings.settings.value.knownServers
    /** Leaving now would leave an installed channel without a saved profile. */
    val unsaved: Boolean get() = installed && !saved

    // ---- step 1: server ----

    fun useServer(server: KnownServer) {
        host = server.host
        port = server.port.toString()
        user = server.user
        error = null
    }

    fun readKeyFile() {
        scope.launch {
            val path = container.platform.pickFile("Приватный ключ SSH", emptyList()) ?: return@launch
            val text = container.platform.readTextFile(path)
            if (text == null) error = "Не удалось прочитать ключ: файл больше 64 КБ или недоступен"
            else { privateKey = text.trim(); error = null }
        }
    }

    fun connect(trusted: String? = null) {
        val host = host.trim()
        val user = user.trim()
        val port = NodeServers.port(port)
        when {
            host.isEmpty() || user.isEmpty() || port == null -> { error = "Укажите адрес, порт и логин"; return }
            useKey && privateKey.isBlank() -> { error = "Вставьте приватный ключ или выберите файл"; return }
            !useKey && password.isEmpty() -> { error = "Введите пароль"; return }
        }
        val id = NodeServers.hostKeyId(host, port)
        val target = SshTarget(
            host = host, port = port, user = user,
            password = if (useKey) "" else password,
            privateKey = if (useKey) privateKey.trim() else "",
            passphrase = if (useKey) passphrase else "",
            hostKey = trusted ?: settings.settings.value.knownHostKeys[id].orEmpty(),
        )
        launchCall("Подключаюсь к серверу и проверяю его…", onFailure = { e ->
            val fingerprint = e.hostKey
            if (fingerprint != null && (e.trust || e.mismatch)) {
                hostKeyPrompt = HostKeyPrompt(fingerprint, e.mismatch)
                true
            } else false
        }) {
            probe = service.connect(target, nodeCore)
            this.target = target
            lastServerReply = container.platform.now()
            settings.update { s -> s.copy(knownServers = NodeServers.remember(s.knownServers, KnownServer(host, port, user))) }
            if (channel == null) channel = service.newChannel()
            if (name.isBlank()) name = "Нода $host"
            step = WizardStep.Document
        }
    }

    fun trustHostKey() {
        val prompt = hostKeyPrompt ?: return
        hostKeyPrompt = null
        val port = NodeServers.port(port) ?: return
        val fingerprint = prompt.fingerprint
        settings.update { s -> s.copy(knownHostKeys = s.knownHostKeys + (NodeServers.hostKeyId(host, port) to fingerprint)) }
        connect(fingerprint)
    }

    fun dismissHostKey() {
        hostKeyPrompt = null
    }

    // ---- step 2: document ----

    fun createDocument() {
        if (channel == null) return
        launchCall("Открываю Яндекс во встроенном браузере…") {
            try {
                val fileName = NodeDocuments.fileName(name, host, container.platform.now())
                val document = service.createDocument(fileName) { step ->
                    documentProgress = step
                    service.note(step, LogLevel.Debug)
                }
                yandexCookies = document.cookieHeader.takeIf(NodeDocuments::signedIn).orEmpty()
                cookiesDocument = document.url
                checkNow(document.url)
            } finally {
                documentProgress = null
            }
        }
    }

    fun cancelDocument() {
        service.cancelDocument()
    }

    /** The Yandex document is needed: the Volga or the Yandex Docs carrier is on. */
    val needsYandexDocument: Boolean get() = useVolga || useYandexDocs

    /**
     * The channel's Yandex board, made with the saved Yandex account
     * (signing in first in the Accounts window) and opened to guests as
     * editors, the way the node's boards carrier joins it.
     */
    fun createBoard() {
        if (channel == null) return
        launchCall("Открываю Яндекс Доски в окне входа…") {
            try {
                val fileName = NodeDocuments.fileName(name, host, container.platform.now())
                boardInput = container.accounts.createDocument(DocumentKind.YandexBoard, fileName)
                service.note("мастер: доска создана", LogLevel.Info)
            } catch (e: AccountException) {
                if (e.cancelled) return@launchCall
                throw NodeWizardException(e.message ?: "Не получилось создать доску")
            }
        }
    }

    /**
     * The channel's Mail.ru document, made with the saved Mail.ru account
     * (signing in first in the Accounts window): published, open to editing
     * by link, checked the way the node will open it.
     */
    fun createMailruDocument() {
        if (channel == null) return
        launchCall("Открываю Облако Mail в окне входа…") {
            try {
                val fileName = NodeDocuments.fileName(name, host, container.platform.now())
                mailruInput = container.accounts.createDocument(AccountKind.Mailru, fileName)
                service.note("мастер: документ Mail.ru создан", LogLevel.Info)
            } catch (e: AccountException) {
                if (e.cancelled) return@launchCall
                throw NodeWizardException(e.message ?: "Не получилось создать документ Mail.ru")
            }
        }
    }

    fun checkDocument() {
        launchCall("Проверяю документ так, как его увидит нода…") { checkNow(documentInput) }
    }

    private suspend fun checkNow(url: String) {
        val clean = NodeDocuments.clean(url) ?: throw NodeWizardException("Нужна ссылка вида https://docs.yandex.ru/edit/d/…")
        if (clean != cookiesDocument) yandexCookies = ""
        documentUrl = clean
        documentInput = clean
        busy = "Проверяю документ так, как его увидит нода…"
        documentWarning = try {
            service.checkDocument(clean)
            ""
        } catch (e: NodeWizardException) {
            // Yandex challenges this computer's address; the node checks
            // from its own, and the final verification decides.
            if (!e.captcha) throw e
            "Яндекс попросил проверку у этого устройства, поэтому документ проверит сама нода при запуске."
        }
        // A channel being changed is changed only on "Применить".
        if (editing == null) proceed()
    }

    /** Goes on to the plan once the Yandex document is ready or not chosen. */
    fun next() {
        launchCall("Готовлю транспорты канала…") { proceed() }
    }

    /**
     * Gathers the chosen carriers (the Yandex document checked by now, the
     * Mail.ru link, new cups.online rooms) and asks the server for the plan.
     */
    private suspend fun proceed() {
        val chosen = mutableListOf<NodeTransport>()
        if (needsYandexDocument && documentUrl.isEmpty()) {
            throw NodeWizardException("Создайте документ Яндекса или вставьте ссылку на свой")
        }
        if (useVolga) chosen += NodeTransport(TransportType.VYANDEX.cliName, documentUrl)
        if (useYandexDocs) chosen += NodeTransport(TransportType.YANDEX.cliName, documentUrl)
        if (useMailru) {
            val link = NodeTransports.cleanMailru(mailruInput)
                ?: throw NodeWizardException("Нужна публичная ссылка Mail.ru вида https://cloud.mail.ru/public/…/…")
            mailruInput = link
            chosen += NodeTransport(TransportType.MAILRU.cliName, link)
        }
        if (useBoards) {
            val link = NodeTransports.cleanBoard(boardInput)
                ?: throw NodeWizardException("Нужна ссылка на доску вида https://boards.yandex.ru/whiteboard/?hash=…")
            boardInput = link
            chosen += NodeTransport(TransportType.BOARDS.cliName, link)
        }
        if (useCups) {
            if (cupsRooms.isEmpty()) {
                busy = "Создаю комнаты cups.online…"
                cupsRooms = service.createCupsRooms()
            }
            chosen += NodeTransport(TransportType.CUPSONLINE.cliName, cupsRooms)
        }
        transports = chosen
        editing?.let { applyEdit(it); return }
        askPlan()
        if (needsSudoPassword && sudoPassword.isEmpty() && !useKey) sudoPassword = password
        step = WizardStep.Plan
    }

    /** The node gets the Yandex sign-in only for a Yandex document it has. */
    private val withCookies: Boolean get() = yandexCookies.isNotEmpty() &&
        transports.any { it.type == TransportType.VYANDEX.cliName || it.type == TransportType.YANDEX.cliName }

    private suspend fun askPlan() {
        busy = "Спрашиваю сервер, что изменится…"
        plan = onServer(retry = true) { service.plan(channel!!.id, transports, withCookies, autoUpdate) }
    }

    fun forgetYandexSignIn() {
        yandexCookies = ""
        // The plan lists the sign-in step; ask again without it.
        launchCall("Спрашиваю сервер, что изменится…") { askPlan() }
    }

    fun changeAutoUpdate(on: Boolean) {
        if (busy != null || on == autoUpdate) return
        autoUpdate = on
        // The plan lists what happens to the updater; ask again.
        launchCall("Спрашиваю сервер, что изменится…") { askPlan() }
    }

    // ---- step 3: install ----

    fun install() {
        val channel = channel ?: return
        val plan = plan ?: return
        if (needsSudoPassword && sudoPassword.isEmpty()) { error = "Введите пароль sudo"; return }
        launchCall("Устанавливаю ноду: скачиваю ядро, пишу конфигурацию, запускаю…", onFailure = { e ->
            if (e.sudo) { error = "sudo не принял пароль"; true } else false
        }) {
            val cookies = if (withCookies) yandexCookies else ""
            onServer { service.apply(channel, transports, plan.port, autoUpdate, sudoPassword, cookies) }
            installed = true
            yandexCookies = ""
            val link = service.shareLink(profileName(), channel.key, host.trim(), plan.port, transports)
            shareLink = link
            val candidate = Profile.fromShare(
                container.shareCodec.decode(link), container.profiles.newId(), container.platform.now(), ProfileSource.Node,
            ).copy(icon = "ic_terminal")
            profile = candidate
            verifyNow(candidate)
        }
    }

    private fun profileName() = name.trim().ifEmpty { "Нода ${host.trim()}" }

    fun back() {
        if (busy != null) return
        error = null
        if (step == WizardStep.Document && editing != null) { cancelEditing(); return }
        step = when (step) {
            WizardStep.Document -> WizardStep.Server
            WizardStep.Plan -> WizardStep.Document
            else -> step
        }
    }

    // ---- step 4: verification ----

    /**
     * Connects through the new node as a client and asks api.ipify.org
     * where the traffic leaves: it must be the server's address. Then waits
     * for the primary carrier, which for a Yandex document may first need
     * the node's own check (the app-wide captcha dialog shows it).
     */
    fun verify() {
        val candidate = profile ?: return
        launchCall("Подключаюсь к новой ноде…") { verifyNow(candidate) }
    }

    private suspend fun verifyNow(candidate: Profile) {
        step = WizardStep.Verify
        verifyFailed = null
        verifiedIp = ""
        sessionProven = false
        primaryUp = false
        stopWaitingForPrimary = false
        busy = "Подключаюсь к новой ноде…"
        try {
            val expected = service.resolve(host.trim())
            // connect() works in the background: until the state changes,
            // what shows is the previous attempt (a Failed of this same
            // profile ended a retry at once with the old error).
            val before = connection.state.value
            connection.connect(candidate, ConnectionMode.Client)
            val deadline = container.platform.now() + VERIFY_TIMEOUT_MS
            // connect() is asynchronous: the previous connection may still
            // show until the new one starts.
            var started = false
            var lastProblem = ""
            while (verifiedIp.isEmpty() && !sessionProven) {
                if (stopWaitingForPrimary) throw NodeWizardException("Проверка остановлена: нода пока не ответила")
                if (container.platform.now() > deadline) {
                    throw NodeWizardException(
                        if (lastProblem.isNotEmpty()) "Канал поднялся, но запрос через него не прошёл: $lastProblem"
                        else "Нода не ответила: проверьте, что сервер доступен, а документ и ключ совпадают",
                    )
                }
                val state = connection.state.value
                if (state !== before && state.profile?.id == candidate.id) started = true
                else if (started) throw NodeWizardException("Проверку прервало отключение или другое подключение")
                when {
                    !started -> Unit
                    state is ConnectionState.Failed -> throw NodeWizardException(state.message)
                    state is ConnectionState.Connected -> {
                        busy = when {
                            connection.captcha.value != null -> "Яндекс просит пройти проверку: пройдите её в открывшемся окне"
                            lastProblem.isNotEmpty() -> "Канал поднялся, пробую открыть сайт через него ещё раз…"
                            else -> "Канал поднялся, открываю сайт через него…"
                        }
                        when (val address = connection.exitAddress.value) {
                            is ExitAddress.Known -> {
                                if (expected.isNotEmpty() && address.ip !in expected) {
                                    throw NodeWizardException("Запрос вышел с адреса ${address.ip}, а не с адреса сервера")
                                }
                                verifiedIp = address.ip
                            }
                            is ExitAddress.NotCheckable -> sessionProven = true
                            is ExitAddress.Unavailable -> {
                                // Like the Android wizard: the carrier may need a
                                // moment (or a passed check); try until the deadline.
                                lastProblem = address.reason
                                delay(RETRY_MS)
                                connection.refreshExitAddress()
                            }
                            else -> Unit
                        }
                    }
                }
                if (verifiedIp.isEmpty() && !sessionProven) delay(POLL_MS)
            }
            // Traffic may have gone through the direct backup. Give the
            // primary carrier the rest of the time to come up: a Yandex node
            // may first need its own check, which the app-wide captcha dialog shows.
            primaryType?.let { busy = "Жду канал через ${it.shortLabel}…" }
            while (!stopWaitingForPrimary && container.platform.now() < deadline && !primaryLive()) {
                if (connection.state.value.profile?.id != candidate.id) break
                delay(POLL_MS)
            }
            primaryUp = primaryLive()
            step = WizardStep.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val state = connection.state.value
            if (state.profile?.id == candidate.id && state.isActive) connection.disconnect()
            verifyFailed = e.message ?: "Проверка не прошла"
        }
    }

    /** The highest-priority carrier; null for a direct-only channel. */
    val primaryType: TransportType? get() = transportTypes.firstOrNull()

    private fun primaryLive(): Boolean {
        val primary = primaryType ?: return true
        return connection.traffic.value.activeTransport == primary.cliName
    }

    /** Stops waiting for the primary carrier and keeps what was proven so far. */
    fun stopWaiting() {
        stopWaitingForPrimary = true
    }

    fun skipVerification() {
        verifyFailed = null
        step = WizardStep.Done
    }

    // ---- a channel already on the server ----

    /** Reads [id] on the server and fills the transports step with its documents. */
    fun editChannel(id: String) {
        if (needsSudoPassword && sudoPassword.isEmpty() && !useKey) sudoPassword = password
        if (needsSudoPassword && sudoPassword.isEmpty()) { error = "Введите пароль sudo"; return }
        launchCall("Читаю канал $id на сервере…", onFailure = { e ->
            if (e.sudo) { error = "sudo не принял пароль"; true } else false
        }) {
            startEditing(onServer { service.showChannel(id, sudoPassword) })
        }
    }

    private fun startEditing(info: InstalledChannel) {
        fun url(type: TransportType) = info.transports.firstOrNull { it.type == type.cliName }?.url.orEmpty()
        editing = info
        val yandex = url(TransportType.VYANDEX).ifEmpty { url(TransportType.YANDEX) }
        useVolga = url(TransportType.VYANDEX).isNotEmpty()
        useYandexDocs = url(TransportType.YANDEX).isNotEmpty()
        documentUrl = yandex
        documentInput = yandex
        documentWarning = ""
        useMailru = url(TransportType.MAILRU).isNotEmpty()
        mailruInput = url(TransportType.MAILRU)
        useBoards = url(TransportType.BOARDS).isNotEmpty()
        boardInput = url(TransportType.BOARDS)
        useCups = url(TransportType.CUPSONLINE).isNotEmpty()
        cupsRooms = url(TransportType.CUPSONLINE)
        yandexCookies = ""
        cookiesDocument = ""
        editingProfile = channelProfile(info)
        editingProfile?.let { name = it.name }
        notice = null
        val types = info.transports.joinToString(",") { it.type }.ifEmpty { "direct" }
        service.note("мастер: канал ${info.id}: $types, порт ${info.port}, ${info.state}", LogLevel.Info)
    }

    /**
     * The saved profile of an installed channel: one with the key whose direct
     * carrier dials this server at the channel's port, made by this wizard or
     * imported from the owner's link alike.
     */
    private fun channelProfile(info: InstalledChannel): Profile? {
        val host = host.trim()
        val dials = setOf("$host:${info.port}", "[$host]:${info.port}")
        return container.profiles.profiles.value.firstOrNull { p ->
            p.secret.isNotBlank() && p.carriers.any { it.type == TransportType.DIRECT && it.value.trim() in dials }
        }
    }

    /** Back to making a new channel. */
    fun cancelEditing() {
        if (busy != null) return
        editing = null
        editingProfile = null
        useVolga = true
        useYandexDocs = false
        useMailru = false
        useBoards = false
        useCups = false
        documentUrl = ""
        documentInput = ""
        documentWarning = ""
        mailruInput = ""
        boardInput = ""
        cupsRooms = ""
        name = "Нода ${host.trim()}"
        error = null
    }

    /**
     * Changes [current]'s documents on the server (its key, port and context
     * stay), then, when this device has its profile, checks the channel
     * through the new documents like a new one and offers the profile back.
     */
    private suspend fun applyEdit(current: InstalledChannel) {
        if (transports.isEmpty()) throw NodeWizardException("Оставьте хотя бы один документ: без него канал не нужен")
        if (needsSudoPassword && sudoPassword.isEmpty() && !useKey) sudoPassword = password
        if (needsSudoPassword && sudoPassword.isEmpty()) throw NodeWizardException("Введите пароль sudo")
        busy = "Меняю транспорты канала ${current.id} на сервере…"
        val cookies = if (withCookies) yandexCookies else ""
        val now = onServer { service.setTransports(current.id, transports, sudoPassword, cookies) }
        yandexCookies = ""
        editing = now
        val described = NodeTransports.describe(now.transportTypes)
        service.note("мастер: транспорты канала ${now.id} изменены: $described", LogLevel.Info)
        val saved = editingProfile
        if (saved == null) {
            notice = "Транспорты канала ${now.id} изменены: $described. Ключ этого канала хранится на устройстве, " +
                "где он создан: прежняя ссылка продолжает работать, новые документы появятся в ней после обновления профиля там."
            return
        }
        val link = service.channelShareLink(saved.name, saved.secret, host.trim(), now.port, now.transports, now.context)
        shareLink = link
        val candidate = Profile.fromShare(container.shareCodec.decode(link), saved.id, saved.createdAt, saved.source)
            .copy(icon = saved.icon, name = saved.name)
        profile = candidate
        installed = true
        verifyNow(candidate)
    }

    fun removeChannel() {
        if (!canRemoveChannel) return
        val channel = channel ?: return
        launchCall("Удаляю канал с сервера…", onFailure = { e ->
            if (e.sudo) { error = "sudo не принял пароль"; true } else false
        }) {
            onServer { service.remove(channel.id, sudoPassword) }
            installed = false
            notice = "Канал ${channel.id} удалён с сервера"
        }
    }

    // ---- done ----

    /** Saves the profile here; returns it, or null when there is none. */
    fun save(): Profile? {
        val candidate = profile ?: return null
        val named = candidate.copy(name = profileName())
        container.profiles.upsert(named)
        profile = named
        saved = true
        settings.update { it.copy(selectedProfileId = named.id) }
        return named
    }

    fun copyLink() = container.platform.setClipboardText(shareLink)



    fun qr() = container.platform.qrMatrix(shareLink)

    /** Ends SSH and the Yandex window; drops the test connection of an unsaved profile. */
    fun close() {
        job?.cancel()
        service.cancelDocument()
        service.close()
        val state = connection.state.value
        if (!saved && profile != null && state.profile?.id == profile?.id && state.isActive) connection.disconnect()
        target = null
        password = ""
        privateKey = ""
        passphrase = ""
        sudoPassword = ""
        yandexCookies = ""
    }

    /**
     * A call over the wizard's SSH connection. The connection is opened on
     * step 1 and used again only after the user has signed in to Yandex and
     * read the plan, which can take minutes: long enough for a mobile network
     * or the server's sshd to drop it, and then every call fails with "скрипт
     * установки не ответил". So after a pause the wizard connects again (a new
     * SSH session, the pinned installer downloaded and checked anew) before
     * the call, and a read-only call ([retry]) that fails reconnects and is
     * tried once more.
     */
    private suspend fun <T> onServer(retry: Boolean = false, call: suspend () -> T): T {
        val target = target
        if (target != null && container.platform.now() - lastServerReply > SSH_IDLE_MS) reconnect(target)
        val result = try {
            call()
        } catch (e: NodeWizardException) {
            if (!retry || target == null || e.sudo || e.captcha || e.hostKey != null) throw e
            reconnect(target)
            call()
        }
        lastServerReply = container.platform.now()
        return result
    }

    private suspend fun reconnect(target: SshTarget) {
        val previous = busy
        busy = "Подключаюсь к серверу заново…"
        probe = service.connect(target, nodeCore)
        lastServerReply = container.platform.now()
        busy = previous
    }

    /**
     * Runs one wizard call at a time. A [NodeWizardException] becomes [error]
     * unless [onFailure] handled it (returned true).
     */
    private fun launchCall(
        message: String,
        onFailure: (NodeWizardException) -> Boolean = { false },
        block: suspend () -> Unit,
    ) {
        if (busy != null) return
        busy = message
        error = null
        notice = null
        service.note(message)
        job = scope.launch {
            try {
                block()
                service.note("$message — готово", LogLevel.Success)
            } catch (e: CancellationException) {
                service.note("$message — отменено", LogLevel.Warning)
                throw e
            } catch (e: NodeWizardException) {
                service.note(e.message ?: "Ошибка мастера", LogLevel.Error)
                if (!onFailure(e)) error = e.message
            } catch (e: Exception) {
                val text = e.message ?: "Ошибка мастера"
                service.note(text, LogLevel.Error)
                error = text
            } finally {
                busy = null
            }
        }
    }

    companion object {
        private const val VERIFY_TIMEOUT_MS = 150_000L
        private const val POLL_MS = 400L
        private const val RETRY_MS = 4000L
        /** A connection quiet for longer is opened again before the next call. */
        private const val SSH_IDLE_MS = 60_000L
    }
}
